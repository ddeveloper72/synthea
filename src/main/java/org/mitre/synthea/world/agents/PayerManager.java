package org.mitre.synthea.world.agents;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.mitre.synthea.helpers.Config;
import org.mitre.synthea.helpers.SimpleCSV;
import org.mitre.synthea.helpers.Utilities;
import org.mitre.synthea.world.agents.behaviors.payeradjustment.IPayerAdjustment;
import org.mitre.synthea.world.agents.behaviors.payeradjustment.PayerAdjustmentFixed;
import org.mitre.synthea.world.agents.behaviors.payeradjustment.PayerAdjustmentNone;
import org.mitre.synthea.world.agents.behaviors.payeradjustment.PayerAdjustmentRandom;
import org.mitre.synthea.world.agents.behaviors.planeligibility.PlanEligibilityFinder;
import org.mitre.synthea.world.agents.behaviors.planfinder.IPlanFinder;
import org.mitre.synthea.world.agents.behaviors.planfinder.PlanFinderBestRates;
import org.mitre.synthea.world.agents.behaviors.planfinder.PlanFinderPriority;
import org.mitre.synthea.world.agents.behaviors.planfinder.PlanFinderRandom;
import org.mitre.synthea.world.concepts.HealthRecord.EncounterType;
import org.mitre.synthea.world.concepts.healthinsurance.InsurancePlan;
import org.mitre.synthea.world.geography.Location;

/**
 * A class that maintains and manages Payers.
 */
public class PayerManager {

  // Payer adjustment algorithm choices:
  private static final String NONE = "none";
  private static final String FIXED = "fixed";
  // Payer selection algorithm choices:
  private static final String RANDOM = "random";
  private static final String BEST_RATE = "best_rate";
  private static final String PRIORITY = "priority";

  // Payer CSV headers.
  private static final String NAME = "Name";
  private static final String ID = "Id";
  private static final String PRIORITY_LEVEL = "Priority Level";
  private static final String STATES_COVERED = "States Covered";
  private static final String OWNERSHIP = "Ownership";
  // Plan CSV Headers.
  private static final String PAYER_ID = "Payer Id";
  private static final String PLAN_ID = "Plan Id";
  private static final String SERVICES_COVERED = "Services Covered";
  private static final String DEDUCTIBLE = "Deductible";
  private static final String COINSURANCE = "Default Coinsurance";
  private static final String COPAY = "Default Copay";
  private static final String MONTHLY_PREMIUM = "Monthly Premium";
  private static final String MAX_OOP = "Max Out of Pocket";
  private static final String MEDICARE_SUPPLEMENT = "Medicare Supplement";
  private static final String ACA = "Is ACA Plan";
  private static final String INCOME_BASED_PREMIUM = "Income Based Premium";
  private static final String START_YEAR = "Start Year";
  private static final String END_YEAR = "End Year";
  private static final String ELIGIBILITY_POLICY = "Eligibility Policy";

  public static final String GOV_OWNERSHIP = "GOVERNMENT";
  public static final String PRIVATE_OWNERSHIP = "PRIVATE";
  public static final String NO_INSURANCE = "NO_INSURANCE";

  public static final String MEDICARE =
      Config.get("generate.payers.insurance_companies.medicare", "Medicare");
  public static final String MEDICAID =
      Config.get("generate.payers.insurance_companies.medicaid", "Medicaid");
  public static final String DUAL_ELIGIBLE =
      Config.get("generate.payers.insurance_companies.dual_eligible", "Dual Eligible");

  /* Map of all loaded Payers. */
  private static final Map<Integer, Payer> payers = new LinkedHashMap<Integer, Payer>();

  /* No Insurance Payer. */
  private static Payer noInsurance;

  /* U.S. States loaded. */
  private static Set<String> statesLoaded = new HashSet<String>();

  // Payer Finder.
  private static IPlanFinder planFinder;

  /**
   * Load into cache the list of payers for a state.
   *
   * @param location the state being loaded.
   */
  public static void loadPayers(Location location) {
    // Load the plan eligibility algorithms.
    String eligibilitiesFile = Config.get("generate.payers.insurance_plans.eligibilities_file");
    PlanEligibilityFinder.buildPlanEligibilities(location.state, eligibilitiesFile);

    // Build the Plan Finder.
    planFinder = buildPlanFinder();
    if (!statesLoaded.contains(location.state)
        || !statesLoaded.contains(Location.getAbbreviation(location.state))
        || !statesLoaded.contains(Location.getStateName(location.state))) {
      try {
        String payerFile = Config.get("generate.payers.insurance_companies.default_file");
        loadPayers(location, payerFile);

        statesLoaded.add(location.state);
        statesLoaded.add(Location.getAbbreviation(location.state));
        statesLoaded.add(Location.getStateName(location.state));
      } catch (IOException e) {
        System.err.println("ERROR: unable to load payers for state: " + location.state);
        e.printStackTrace();
      }
    }
  }

  /**
   * Read the payers from the given resource file, only importing the ones for the
   * given state.
   *
   * @param location the state being loaded
   * @param fileName Location of the file, relative to src/main/resources

   * @throws IOException if the file cannot be read
   */
  private static void loadPayers(Location location, String fileName) throws IOException {
    PayerManager.loadNoInsurance();

    String resource = Utilities.readResource(fileName, true, true);
    Iterator<? extends Map<String, String>> csv = SimpleCSV.parseLineByLine(resource);

    while (csv.hasNext()) {
      Map<String, String> row = csv.next();
      String payerStates = row.get(STATES_COVERED).toUpperCase();
      String abbreviation = Location.getAbbreviation(location.state).toUpperCase();

      // For now, only allow one U.S. state at a time.
      if (payerStates.contains(abbreviation) || payerStates.contains("*")) {

        Payer parsedPayer = csvLineToPayer(row);
        parsedPayer.setPayerAdjustment(buildPayerAdjustment());
        PayerManager.payers.put(parsedPayer.getPlanLinkId(), parsedPayer);
      }
    }

    PayerManager.loadPlans();
  }

  private static void loadPlans() {
    String fileName = Config.get("generate.payers.insurance_plans.default_file");
    Iterator<? extends Map<String, String>> csv = null;
    try {
      String resource = Utilities.readResource(fileName, true, true);
      csv = SimpleCSV.parseLineByLine(resource);
    } catch (IOException e) {
      e.printStackTrace();
    }

    while (csv.hasNext()) {
      Map<String, String> row = csv.next();
      csvLineToPlan(row);
    }
  }

  /**
   * Determines the algorithm to use for patients to find a Payer.
   */
  private static IPlanFinder buildPlanFinder() {
    IPlanFinder finder;
    String behavior = Config.get("generate.payers.selection_behavior").toLowerCase();
    switch (behavior) {
      case BEST_RATE:
        finder = new PlanFinderBestRates();
        break;
      case RANDOM:
        finder = new PlanFinderRandom();
        break;
      case PRIORITY:
        finder = new PlanFinderPriority();
        break;
      default:
        throw new RuntimeException("Not a valid Payer Selection Algorithm: " + behavior);
    }
    return finder;
  }

  private static IPayerAdjustment buildPayerAdjustment() {
    IPayerAdjustment adjustment;
    String behavior = Config.get("generate.payers.adjustment_behavior", "none").toLowerCase();
    String rateString = Config.get("generate.payers.adjustment_rate", "0.05");
    double rate = Double.parseDouble(rateString);
    switch (behavior) {
      case NONE:
        adjustment = new PayerAdjustmentNone();
        break;
      case FIXED:
        adjustment = new PayerAdjustmentFixed(rate);
        break;
      case RANDOM:
        adjustment = new PayerAdjustmentRandom(rate);
        break;
      default:
        adjustment = new PayerAdjustmentNone();
    }
    return adjustment;
  }

  /**
   * Given a line of parsed CSV input, convert the data into a Payer.
   *
   * @param line read a csv line to a payer's attributes
   * @return the new payer.
   */
  private static Payer csvLineToPayer(Map<String, String> line) {

    // Uses .remove() instead of .get() so we can iterate over remaining keys later.
    String payerName = line.remove(NAME).trim();
    int payerId = Integer.parseInt(line.remove(ID).trim());
    if (PayerManager.payers.containsKey(payerId)) {
      throw new RuntimeException("The given payer id '" + payerId + "' already exists.");
    }
    if (payerId < 0) {
      throw new RuntimeException("Payer IDs must be non-negative. Given Id " + payerId + ".");
    }
    Set<String> statesCovered = commaSeparatedStringToHashSet(line.remove(STATES_COVERED).trim());
    String ownership = line.remove(OWNERSHIP).trim().toUpperCase();
    if (!(ownership.equals(GOV_OWNERSHIP) || ownership.equals(PRIVATE_OWNERSHIP))) {
      throw new RuntimeException("A Payer's ownership must be tagged as either "
          + GOV_OWNERSHIP + " or " + PRIVATE_OWNERSHIP + ". Payer " + payerName
          + " " + payerId + " has ownership of " + ownership + ".");
    }

    Payer newPayer = new Payer(payerName, payerId, statesCovered, ownership);

    // Add remaining columns we didn't map to first-class fields.
    for (Map.Entry<String, String> e : line.entrySet()) {
      newPayer.addAttribute(e.getKey(), e.getValue());
    }

    return newPayer;
  }

  /**
   * Converts a key-value CSV line to a plan.
   * @param line The Map with the CSV key-value pairs.
   */
  private static void csvLineToPlan(Map<String, String> line) {
    int payerId = Integer.parseInt(getTrimmedValue(line, PAYER_ID));
    int planId = Integer.parseInt(getTrimmedValue(line, PLAN_ID));
    if (planId < 0) {
      throw new RuntimeException("Plan IDs must be non-negative. Given Id " + planId + ".");
    }
    if (!PayerManager.payers.containsKey(payerId)) {
      // Return without an error, because the given payer might only exist in another state.
      return;
    }
    String planName = getTrimmedValue(line, NAME);
    Set<String> servicesCovered
        = commaSeparatedStringToHashSet(getTrimmedValue(line, SERVICES_COVERED));
    BigDecimal deductible = new BigDecimal(getTrimmedValue(line, DEDUCTIBLE));
    BigDecimal defaultCoinsurance = new BigDecimal(getTrimmedValue(line, COINSURANCE));
    BigDecimal defaultCopay = new BigDecimal(getTrimmedValue(line, COPAY));
    BigDecimal monthlyPremium = new BigDecimal(getTrimmedValue(line, MONTHLY_PREMIUM));
    boolean medicareSupplement = Boolean.parseBoolean(getTrimmedValue(line, MEDICARE_SUPPLEMENT));
    boolean isACA = Boolean.parseBoolean(getTrimmedValue(line, ACA));
    boolean incomeBasedPremium = Boolean.parseBoolean(getTrimmedValue(line, INCOME_BASED_PREMIUM));
    String yearStartStr = getTrimmedValue(line, START_YEAR);
    int yearStart = yearStartStr.equals("") ? 0 : Integer.parseInt(yearStartStr);
    String yearEndStr = getTrimmedValue(line, END_YEAR);
    int yearEnd = StringUtils.isBlank(yearEndStr)
        ? Integer.MAX_VALUE : Integer.parseInt(yearEndStr);
    BigDecimal maxOutOfPocket = new BigDecimal(getTrimmedValue(line, MAX_OOP));
    // If the priority is blank, give it minimum priority (maximum int value).
    String priorityString = getTrimmedValue(line, PRIORITY_LEVEL);
    int priority = StringUtils.isBlank(priorityString)
        ? Integer.MAX_VALUE : Integer.parseInt(priorityString);
    String eligibilityName = getTrimmedValue(line, ELIGIBILITY_POLICY);

    Payer payer = PayerManager.payers.get(payerId);
    InsurancePlan newPlan = new InsurancePlan(planId, payer, servicesCovered, deductible,
        defaultCoinsurance, defaultCopay, monthlyPremium, maxOutOfPocket, medicareSupplement,
        isACA, incomeBasedPremium, yearStart, yearEnd, priority, eligibilityName);
    payer.addPlan(newPlan);
  }

  /**
   * Converts a comma separated string to a Set.
   *
   * @param field the string to extract the Set from.
   * @return the Set of services covered.
   */
  private static Set<String> commaSeparatedStringToHashSet(String field) {
    String[] commaSeparatedField = field.split("\\s*,\\s*");
    return Arrays.stream(commaSeparatedField).collect(Collectors.toSet());
  }

  /**
   * Gets the trimmed value from the map for the given key.
   *
   * @param map the map containing the key-value pairs.
   * @param key the key to look for in the map.
   * @return the trimmed value associated with the key.
   */
  private static String getTrimmedValue(Map<String, String> map, String key) {
    String value = map.remove(key);
    if (value == null) {
      // Provide a default value for missing fields
      switch (key) {
        case ACA:
          value = "false";
          break;
        case INCOME_BASED_PREMIUM:
          value = "false";
          break;
        case MAX_OOP:
          value = "0";
          break;
        case PRIORITY_LEVEL:
          value = String.valueOf(Integer.MAX_VALUE);
          break;
        default:
          throw new RuntimeException("Missing required field: " + key);
      }
    }
    return value.trim();
  }

  /**
   * Loads the noInsurance Payer.
   */
  public static void loadNoInsurance() {
    // noInsurance 'covers' all states.
    Set<String> statesCovered = new HashSet<String>();
    statesCovered.add("*");
    PayerManager.noInsurance = new Payer(NO_INSURANCE, -1, statesCovered, NO_INSURANCE);
    InsurancePlan noInsurancePlan = new InsurancePlan(-1, PayerManager.noInsurance,
        new HashSet<String>(), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
        BigDecimal.valueOf(Integer.MAX_VALUE), false, false, false, 0,
        Integer.MAX_VALUE, 0, PlanEligibilityFinder.GENERIC);
    PayerManager.noInsurance.addPlan(noInsurancePlan);
    PayerManager.noInsurance.setPayerAdjustment(new PayerAdjustmentNone());
  }

  /**
   * Returns the List of all loaded payers.
   */
  public static List<Payer> getAllPayers() {
    return payers.values().stream().collect(Collectors.toList());
  }

  /**
   * Clear the list of loaded and cached Payers.
   * Currently only used in tests.
   */
  public static void clear() {
    payers.clear();
    statesLoaded.clear();
    planFinder = buildPlanFinder();
  }

  /**
   * Returns a Payer that the person can qualify for.
   *
   * @param person  the person who needs to find insurance.
   * @param service the EncounterType the person would like covered.
   * @param time    the time that the person requires insurance.
   * @return a payer who the person can accept and vice versa.
   */
  public static InsurancePlan findPlan(Person person, EncounterType service, long time) {
    List<InsurancePlan> plans = getActivePlans(getAllPayers(), time);
    // Remove medicare supplement plans from this check.
    plans = plans.stream().filter(plan -> !plan.isMedicareSupplementPlan())
        .collect(Collectors.toList());
    InsurancePlan potentialPlan = planFinder.find(plans, person, service, time);
    if (potentialPlan.isGovernmentPlan()) {
      // Person will always choose a government plan.
      return potentialPlan;
    }
    if (!person.coverage.getPlanHistory().isEmpty()) {
      // If the person can't get a government plan, they will try to keep their existing insurance.
      InsurancePlan previousPlan = person.coverage
          .getPlanAtTime(time - Config.getAsLong("generate.timestep"));
      if (!previousPlan.isNoInsurance()
          && previousPlan.accepts(person, time) && previousPlan.isActive(time)
          && IPlanFinder.meetsAffordabilityRequirements(previousPlan, person, null, time)) {
        return previousPlan;
      }
    }
    return potentialPlan;
  }

  /**
   * Returns all active plans in the given payers based on the given time.
   * @param payers  The payers.
   * @param time  The time for the plan to be active in.
   * @return The set of active plans.
   */
  public static List<InsurancePlan> getActivePlans(List<Payer> payers, long time) {
    List<InsurancePlan> activePlans = new ArrayList<>();
    for (Payer payer : payers) {
      activePlans.addAll(payer.getPlans().stream().filter(plan -> plan.isActive(time))
          .collect(Collectors.toList()));
    }
    return activePlans;
  }

  /**
   * Returns the no insurance plan.
   * @return
   */
  public static InsurancePlan getNoInsurancePlan() {
    return noInsurance.getPlans().iterator().next();
  }

  /**
   * Finds an eligible medicare supplement plan for the given person.
   * @param person  The person for whom to find a medicare supplement plan.
   * @param service The service the plan should cover.
   * @param time  The time.
   * @return  A potential Medicare Supplement plan, if eligible and affordable.
   */
  public static InsurancePlan findMedicareSupplement(Person person,
      EncounterType service, long time) {
    List<InsurancePlan> plans = getActivePlans(getAllPayers(), time);
    // Remove non-medicare supplement plans from this check.
    plans = plans.stream().filter(plan -> plan.isMedicareSupplementPlan())
        .collect(Collectors.toList());
    InsurancePlan potentialPlan = planFinder.find(plans, person, service, time);
    return potentialPlan;
  }
}