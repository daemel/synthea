package org.mitre.synthea.engine;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FilenameFilter;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.lang.reflect.Type;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.apache.commons.io.IOCase;
import org.apache.commons.io.filefilter.WildcardFileFilter;
import org.apache.commons.lang3.StringUtils;

import org.mitre.synthea.editors.GrowthDataErrorsEditor;
import org.mitre.synthea.export.CDWExporter;
import org.mitre.synthea.export.Exporter;
import org.mitre.synthea.helpers.Config;
import org.mitre.synthea.helpers.RandomNumberGenerator;
import org.mitre.synthea.helpers.TransitionMetrics;
import org.mitre.synthea.helpers.Utilities;
import org.mitre.synthea.input.FixedRecord;
import org.mitre.synthea.input.FixedRecordGroup;
import org.mitre.synthea.modules.DeathModule;
import org.mitre.synthea.modules.EncounterModule;
import org.mitre.synthea.modules.HealthInsuranceModule;
import org.mitre.synthea.modules.LifecycleModule;
import org.mitre.synthea.world.agents.Payer;
import org.mitre.synthea.world.agents.Person;
import org.mitre.synthea.world.agents.Provider;
import org.mitre.synthea.world.concepts.Costs;
import org.mitre.synthea.world.concepts.VitalSign;
import org.mitre.synthea.world.geography.Demographics;
import org.mitre.synthea.world.geography.Location;

/**
 * Generator creates a population by running the generic modules each timestep per Person.
 */
public class Generator implements RandomNumberGenerator {

  public GeneratorOptions options;
  private Random random;
  public long timestep;
  public long stop;
  public long referenceTime;
  public Map<String, AtomicInteger> stats;
  public Location location;
  private AtomicInteger totalGeneratedPopulation;
  private String logLevel;
  private boolean onlyAlivePatients;
  private boolean onlyDeadPatients;
  private boolean onlyVeterans;
  private Module keepPatientsModule;
  private Long maxAttemptsToKeepPatient;
  public TransitionMetrics metrics;
  public static String DEFAULT_STATE = "Massachusetts";
  private Exporter.ExporterRuntimeOptions exporterRuntimeOptions;
  private List<FixedRecordGroup> recordGroups;

  /**
   * Used only for testing and debugging. Populate this field to keep track of all patients
   * generated, living or dead, during a simulation. Note that this may result in significantly
   * increased memory usage as patients cannot be GC'ed.
   */
  List<Person> internalStore;

  /**
   * A filename predicate used to filter a subset of modules. Helpful when testing a particular
   * module. Use "-m filename" on the command line to filter which modules get loaded.
   */
  Predicate<String> modulePredicate;
  
  private static final String TARGET_AGE = "target_age";

  /**
   * Helper class following the "Parameter Object" pattern.
   * This class provides the default values for Generator, or alternatives may be set.
   */
  public static class GeneratorOptions {
    public int population = Integer.parseInt(Config.get("generate.default_population", "1"));
    public long seed = System.currentTimeMillis();
    public long clinicianSeed = seed;
    /** Population as exclusively live persons or including deceased.
     * True for live, false includes deceased */
    public boolean overflow = true;
    /** Gender to be generated. M for Male, F for Female, null for any. */
    public String gender;
    /** Age range applies. */
    public boolean ageSpecified = false;
    /** Minimum age of people to be generated. Defaults to zero. */
    public int minAge = 0;
    /** Maximum age of people to be generated. Defaults to 140. */
    public int maxAge = 140;
    public String city;
    public String state;
    /** When Synthea is used as a standalone library, this directory holds
     * any locally created modules. */
    public File localModuleDir;
    public File fixedRecordPath;
    public List<String> enabledModules;
    /** File used to initialize a population. */
    public File initialPopulationSnapshotPath;
    /** File used to store a population snapshot. */
    public File updatedPopulationSnapshotPath;
    /** Time period in days to evolve the population loaded from initialPopulationSnapshotPath. A
     *  value of -1 will evolve the population to the current system time.
     */
    public int daysToTravelForward = -1;
    /** Path to a module defining which patients should be kept and exported. */
    public File keepPatientsModulePath;
    /** Reference Time when to start Synthea. By default equal to the current system time. */
    public long referenceTime = seed;
  }
  
  /**
   * Create a Generator, using all default settings.
   */
  public Generator() {
    this(new GeneratorOptions(), new Exporter.ExporterRuntimeOptions());
  }

  /**
   * Create a Generator, with the given population size.
   * All other settings are left as defaults.
   * 
   * @param population Target population size
   */
  public Generator(int population) {
    this(new GeneratorOptions(), new Exporter.ExporterRuntimeOptions());
    options.population = population;
    init();
  }
  
  /**
   * Create a Generator, with the given population size and seed.
   * All other settings are left as defaults.
   * 
   * @param population Target population size
   * @param seed Seed used for randomness
   */
  public Generator(int population, long seed, long clinicianSeed) {
    this(new GeneratorOptions(), new Exporter.ExporterRuntimeOptions());
    options.population = population;
    options.seed = seed;
    options.clinicianSeed = clinicianSeed;
    init();
  }

  /**
   * Create a Generator, with the given options.
   * @param o Desired configuration options
   */
  public Generator(GeneratorOptions o) {
    this(o, new Exporter.ExporterRuntimeOptions());
  }
  
  /**
   * Create a Generator, with the given options.
   * @param o Desired configuration options
   * @param ero Desired exporter options
   */
  public Generator(GeneratorOptions o, Exporter.ExporterRuntimeOptions ero) {
    options = o;
    exporterRuntimeOptions = ero;
    if (options.updatedPopulationSnapshotPath != null) {
      exporterRuntimeOptions.deferExports = true;
      internalStore = Collections.synchronizedList(new LinkedList<>());
    }
    init();
  }

  private void init() {
    if (options.state == null) {
      options.state = DEFAULT_STATE;
    }
    int stateIndex = Location.getIndex(options.state);
    if (Config.getAsBoolean("exporter.cdw.export")) {
      CDWExporter.getInstance().setKeyStart((stateIndex * 1_000_000) + 1);
    }

    this.random = new Random(options.seed);
    this.timestep = Long.parseLong(Config.get("generate.timestep"));
    this.stop = System.currentTimeMillis();
    this.referenceTime = options.referenceTime;

    this.location = new Location(options.state, options.city);

    this.logLevel = Config.get("generate.log_patients.detail", "simple");

    this.onlyDeadPatients = Config.getAsBoolean("generate.only_dead_patients");
    this.onlyAlivePatients = Config.getAsBoolean("generate.only_alive_patients");
    //If both values are set to true, then they are both set back to the default
    if (this.onlyDeadPatients && this.onlyAlivePatients) {
      Config.set("generate.only_dead_patients", "false");
      Config.set("generate.only_alive_patients", "false");
      this.onlyDeadPatients = false;
      this.onlyAlivePatients = false;
    }

    try {
      this.maxAttemptsToKeepPatient = Long.parseLong(
        Config.get("generate.max_attempts_to_keep_patient", "1000"));

      if (this.maxAttemptsToKeepPatient == 0) {
        // set it to null to make the check more clear
        this.maxAttemptsToKeepPatient = null;
      }
    } catch (Exception e) {
      this.maxAttemptsToKeepPatient = null;
    }

    this.onlyVeterans = Config.getAsBoolean("generate.veteran_population_override");
    this.totalGeneratedPopulation = new AtomicInteger(0);
    this.stats = Collections.synchronizedMap(new HashMap<String, AtomicInteger>());
    this.modulePredicate = getModulePredicate();

    stats.put("alive", new AtomicInteger(0));
    stats.put("dead", new AtomicInteger(0));

    if (Config.getAsBoolean("generate.track_detailed_transition_metrics", false)) {
      this.metrics = new TransitionMetrics();
    }

    // initialize hospitals
    Provider.loadProviders(location, options.clinicianSeed);
    // Initialize Payers
    Payer.loadPayers(location);
    // ensure modules load early
    if (options.localModuleDir != null) {
      Module.addModules(options.localModuleDir);
    }
    List<String> coreModuleNames = getModuleNames(Module.getModules(path -> false));
    List<String> moduleNames = getModuleNames(Module.getModules(modulePredicate)); 

    if (options.keepPatientsModulePath != null) {
      try {
        Path path = options.keepPatientsModulePath.toPath().toAbsolutePath();
        this.keepPatientsModule = Module.loadFile(path, false, null, true);
      } catch (Exception e) {
        throw new ExceptionInInitializerError(e);
      }
    }

    Costs.loadCostData(); // ensure cost data loads early
    
    String locationName;
    if (options.city == null) {
      locationName = options.state;
    } else {
      locationName = options.city + ", " + options.state;
    }
    System.out.println("Running with options:");
    System.out.println(String.format(
        "Population: %d\nSeed: %d\nProvider Seed:%d\nReference Time: %d\nLocation: %s",
        options.population, options.seed, options.clinicianSeed, options.referenceTime,
        locationName));
    System.out.println(String.format("Min Age: %d\nMax Age: %d",
        options.minAge, options.maxAge));
    if (options.gender != null) {
      System.out.println(String.format("Gender: %s", options.gender));
    }
    if (options.enabledModules != null) {
      moduleNames.removeAll(coreModuleNames);
      moduleNames.sort(String::compareToIgnoreCase);
      System.out.println("Modules: " + String.join("\n       & ", moduleNames));
      System.out.println(String.format("       > [%d loaded]", moduleNames.size()));
    }

    if (Config.getAsBoolean("growtherrors", false)) {
      HealthRecordEditors hrm = HealthRecordEditors.getInstance();
      hrm.registerEditor(new GrowthDataErrorsEditor());
    }
  }

  /**
   * Extracts a list of names from the supplied list of modules.
   * @param modules A collection of modules
   * @return A list of module names.
   */
  private List<String> getModuleNames(List<Module> modules) {
    return modules.stream()
            .map(m -> m.name)
            .collect(Collectors.toList());
  }
  
  /**
   * Generate the population, using the currently set configuration settings.
   */
  public void run() {

    // Import the fixed patient demographics records file, if a file path is given.
    if (this.options.fixedRecordPath != null) {
      importFixedPatientDemographicsFile();
      // Since we're using FixedRecords, split records must be true.
      Config.set("exporter.split_records", "true");
      // We'll be using the FixedRecord names, so no numbers should be appended to them.
      Config.set("generate.append_numbers_to_person_names", "false");
    }

    ExecutorService threadPool = Executors.newFixedThreadPool(8);

    if (options.initialPopulationSnapshotPath != null) {
      FileInputStream fis = null;
      List<Person> initialPopulation = null;
      try {
        fis = new FileInputStream(options.initialPopulationSnapshotPath);
        ObjectInputStream ois = new ObjectInputStream(fis);
        initialPopulation = (List<Person>) ois.readObject();
        ois.close();
      } catch (Exception ex) {
        System.out.printf("Unable to load population snapshot, error: %s", ex.getMessage());
      }
      if (initialPopulation != null && initialPopulation.size() > 0) {
        // default is to run until current system time.
        if (options.daysToTravelForward > 0) {
          stop = initialPopulation.get(0).lastUpdated 
                  + Utilities.convertTime("days", options.daysToTravelForward);
        }
        for (int i = 0; i < initialPopulation.size(); i++) {
          final int index = i;
          final Person p = initialPopulation.get(i);        
          threadPool.submit(() -> updateRecordExportPerson(p, index));
        }
      }
    } else {
      // Generate patients up to the specified population size.
      for (int i = 0; i < this.options.population; i++) {
        final int index = i;
        final long seed = this.random.nextLong();
        threadPool.submit(() -> generatePerson(index, seed));
      }
    }

    try {
      threadPool.shutdown();
      while (!threadPool.awaitTermination(30, TimeUnit.SECONDS)) {
        System.out.println("Waiting for threads to finish... " + threadPool);
      }
    } catch (InterruptedException e) {
      System.out.println("Generator interrupted. Attempting to shut down associated thread pool.");
      threadPool.shutdownNow();
    }

    // Save a snapshot of the generated population using Java Serialization
    if (options.updatedPopulationSnapshotPath != null) {
      FileOutputStream fos = null;
      try {
        fos = new FileOutputStream(options.updatedPopulationSnapshotPath);
        ObjectOutputStream oos = new ObjectOutputStream(fos);
        oos.writeObject(internalStore);
        oos.close();
        fos.close();
      } catch (Exception ex) {
        System.out.printf("Unable to save population snapshot, error: %s", ex.getMessage());
      }
    }
    Exporter.runPostCompletionExports(this, exporterRuntimeOptions);

    System.out.printf("Records: total=%d, alive=%d, dead=%d\n", totalGeneratedPopulation.get(),
            stats.get("alive").get(), stats.get("dead").get());

    if (this.metrics != null) {
      metrics.printStats(totalGeneratedPopulation.get(), Module.getModules(getModulePredicate()));
    }
  }

  /**
   * Imports the fixed demographics records file when using fixed patient
   * demographics.
   * 
   * @return A list of the groups of records imported.
   */
  public List<FixedRecordGroup> importFixedPatientDemographicsFile() {
    Gson gson = new Gson();
    Type listType = new TypeToken<List<FixedRecordGroup>>() {}.getType();
    try {
      System.out.println("Loading fixed patient demographic records file: "
          + this.options.fixedRecordPath);
      this.recordGroups = gson.fromJson(new FileReader(this.options.fixedRecordPath), listType);
      int linkIdStart = 100000;
      for (int i = 0; i < this.recordGroups.size(); i++) {
        this.recordGroups.get(i).linkId = linkIdStart + i;
      }
    } catch (FileNotFoundException e) {
      throw new RuntimeException("Couldn't open the fixed patient demographics records file", e);
    }
    // Update the population size to reflect the number of patients in the fixed records file.
    this.options.population = this.recordGroups.size();
    // Return the record groups.
    return recordGroups;
  }
  
  /**
   * Generate a completely random Person. The returned person will be alive at the end of the
   * simulation. This means that if in the course of the simulation the person dies, a new person
   * will be started to replace them. 
   * The seed used to generate the person is randomized as well.
   * Note that this method is only used by unit tests.
   * 
   * @param index Target index in the whole set of people to generate
   * @return generated Person
   */
  public Person generatePerson(int index) {
    // System.currentTimeMillis is not unique enough
    long personSeed = UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE;
    return generatePerson(index, personSeed);
  }

  /**
   * Generate a random Person, from the given seed. The returned person will be alive at the end of
   * the simulation. This means that if in the course of the simulation the person dies, a new
   * person will be started to replace them. Note also that if the person dies, the seed to produce
   * them can't be re-used (otherwise the new person would die as well) so a new seed is picked,
   * based on the given seed.
   * 
   * @param index
   *          Target index in the whole set of people to generate
   * @param personSeed
   *          Seed for the random person
   * @return generated Person
   */
  public Person generatePerson(int index, long personSeed) {

    Person person = null;
    
    try {
      int tryNumber = 0; // Number of tries to create these demographics
      Random randomForDemographics = new Random(personSeed);

      Map<String, Object> demoAttributes = randomDemographics(randomForDemographics);
      if (this.recordGroups != null) {
        // Pick fixed demographics if a fixed demographics record file is used.
        demoAttributes = pickFixedDemographics(index, random);
      }

      boolean patientMeetsCriteria;

      do {
        tryNumber++;
        person = createPerson(personSeed, demoAttributes);
        long finishTime = person.lastUpdated + timestep;

        boolean isAlive = person.alive(finishTime);

        CriteriaCheck check = checkCriteria(person, finishTime, index, isAlive);
        patientMeetsCriteria = check.meetsCriteria();

        if (!patientMeetsCriteria) {
          if (this.maxAttemptsToKeepPatient != null
              && tryNumber >= this.maxAttemptsToKeepPatient) {
            // we've tried and failed to produce a patient that meets the criteria
            // throw an exception to halt processing in this slot
            String msg = "Failed to produce a matching patient after " 
                + tryNumber + " attempts. "
                + "Ensure that it is possible for all "
                + "requested demographics to meet the criteria. "
                + "(e.g., make sure there is no age restriction "
                + "that conflicts with a requested condition, "
                + "such as limiting age to 0-18 and requiring "
                + "all patients have a condition that only onsets after 55.) "
                + "If you are confident that the constraints"
                + " are possible to satisfy but rare, "
                + "consider increasing the value in config setting "
                + "`generate.max_attempts_to_keep_patient`";
            throw new RuntimeException(msg);
          }

          // this should be false for any clauses in checkCriteria below
          // when we want to export this patient, but keep trying to produce one meeting criteria
          if (!check.exportAnyway()) {
            // rotate the seed so the next attempt gets a consistent but different one
            personSeed = randomForDemographics.nextLong();
            continue;
            // skip the other stuff if the patient doesn't meet our goals
            // note that this skips ahead to the while check
            // also note, this may run forever if the requested criteria are impossible to meet
          }
        }

        recordPerson(person, index);

        if (!isAlive) {
          // rotate the seed so the next attempt gets a consistent but different one
          personSeed = randomForDemographics.nextLong();

          // if we've tried and failed > 10 times to generate someone over age 90
          // and the options allow for ages as low as 85
          // reduce the age to increase the likelihood of success
          if (tryNumber > 10 && (int)person.attributes.get(TARGET_AGE) > 90
              && (!options.ageSpecified || options.minAge <= 85)) {
            // pick a new target age between 85 and 90
            int newTargetAge = randomForDemographics.nextInt(5) + 85;
            // the final age bracket is 85-110, but our patients rarely break 100
            // so reducing a target age to 85-90 shouldn't affect numbers too much
            demoAttributes.put(TARGET_AGE, newTargetAge);
            long birthdate = birthdateFromTargetAge(newTargetAge, randomForDemographics);
            demoAttributes.put(Person.BIRTHDATE, birthdate);
          }
        }

        // TODO - export is DESTRUCTIVE when it filters out data
        // this means export must be the LAST THING done with the person
        Exporter.export(person, finishTime, exporterRuntimeOptions);
      } while (!patientMeetsCriteria);
      //repeat while patient doesn't meet criteria
      // if the patient is alive and we want only dead ones => loop & try again
      //  (and dont even export, see above)
      // if the patient is dead and we only want dead ones => done
      // if the patient is dead and we want live ones => loop & try again
      //  (but do export the record anyway)
      // if the patient is alive and we want live ones => done
    } catch (Throwable e) {
      // lots of fhir things throw errors for some reason
      e.printStackTrace();
      throw e;
    }
    return person;
  }
  
  /**
   * Helper class to keep track of patient criteria.
   * Caches results in booleans so different combinations are quick to check
   */
  private static class CriteriaCheck {
    // see checkCriteria below for notes on these flags
    // reminder that java booleans default to false if unset
    private boolean rejectDeadButOverflow;
    private boolean isAliveButDeadRequired;
    private boolean isDeadButAliveRequired;
    private boolean insufficientProviders;
    private boolean failedKeepModule;

    private boolean meetsCriteria() {
      // if any of the flags are true, the patient does not meet criteria
      return !(rejectDeadButOverflow
        || isAliveButDeadRequired
        || isDeadButAliveRequired
        || insufficientProviders
        || failedKeepModule);
    }

    private boolean exportAnyway() {
      // export anyway if rejectDeadButOverflow is the only one that is true
      // (ie. if all the other flags are false)
      return !isAliveButDeadRequired
        && !isDeadButAliveRequired
        && !insufficientProviders
        && !failedKeepModule;
    }
  }

  /**
   * Determines if a patient meets the requested criteria.
   * If a patient does not meet the criteria the process will be repeated so a new one is generated
   * @param person the patient to check if we want to export them
   * @param finishTime the time simulation finished
   * @param index Target index in the whole set of people to generate 
   * @param isAlive Whether the patient is alive at end of simulation.
   * @return CriteriaCheck to determine if the patient should be exported/re-simulated
   */
  public CriteriaCheck checkCriteria(Person person, long finishTime, int index, boolean isAlive) {
    CriteriaCheck check = new CriteriaCheck();

    check.rejectDeadButOverflow = !isAlive && !onlyDeadPatients && this.options.overflow;
    // if patient is not alive and the criteria isn't dead patients new patient is needed
    // however in this one case we still want to export the patient

    check.isAliveButDeadRequired = isAlive && onlyDeadPatients;
    // if patient is alive and the criteria is dead patients new patient is needed

    check.isDeadButAliveRequired = !isAlive && onlyAlivePatients;
    // if patient is not alive and the criteria is alive patients new patient is needed

    int providerCount = person.providerCount();
    int providerMinimum = 1;

    if (this.recordGroups != null) {
      // If fixed records are used, there must be 1 provider for each of this person's records.
      FixedRecordGroup recordGroup = this.recordGroups.get(index);
      providerMinimum = recordGroup.count;
    }

    check.insufficientProviders = providerCount < providerMinimum;
    // if provider count less than provider min new patient is needed

    if (this.keepPatientsModule != null) {
      // this one might be slow to process, so only do it if the other things are true
      if (!check.isAliveButDeadRequired && !check.isDeadButAliveRequired) {
        this.keepPatientsModule.process(person, finishTime, false);
        State terminal = person.history.get(0);
        check.failedKeepModule = !terminal.name.equals("Keep");
      }
    }

    return check;
  }

  /**
   * Update person record to stop time, record the entry and export record.
   */
  public Person updateRecordExportPerson(Person person, int index) {
    updatePerson(person);
    recordPerson(person, index);
    long finishTime = person.lastUpdated + timestep;
    Exporter.export(person, finishTime, exporterRuntimeOptions);
    return person;
  }

  /**
   * Create a new person and update them until until Generator.stop or
   * they die, whichever comes sooner.
   * @param personSeed Seed for the random person
   * @param demoAttributes Demographic attributes for the new person, {@link #randomDemographics}
   * @return the new person
   */
  public Person createPerson(long personSeed, Map<String, Object> demoAttributes) {
    Person person = new Person(personSeed);
    person.populationSeed = this.options.seed;
    person.attributes.putAll(demoAttributes);
    person.attributes.put(Person.LOCATION, location);
    person.lastUpdated = (long) demoAttributes.get(Person.BIRTHDATE);

    LifecycleModule.birth(person, person.lastUpdated);
    person.currentModules = Module.getModules(modulePredicate);

    updatePerson(person);

    return person;
  }

  /**
   * Update a previously created person from the time they were last updated until Generator.stop or
   * they die, whichever comes sooner.
   * @param person the previously created person to update
   */
  public void updatePerson(Person person) {
    HealthInsuranceModule healthInsuranceModule = new HealthInsuranceModule();
    EncounterModule encounterModule = new EncounterModule();

    long time = person.lastUpdated;
    while (person.alive(time) && time < stop) {
      healthInsuranceModule.process(person, time + timestep);
      encounterModule.process(person, time);

      Iterator<Module> iter = person.currentModules.iterator();
      while (iter.hasNext()) {
        Module module = iter.next();

        if (module.process(person, time)) {
          iter.remove(); // this module has completed/terminated.
        }
      }
      encounterModule.endEncounterModuleEncounters(person, time);
      person.lastUpdated = time;
      HealthRecordEditors.getInstance().executeAll(person, person.record, time, timestep);
      time += timestep;
    }

    DeathModule.process(person, time);
  }

  /**
   * Create a set of random demographics.
   * @param random The random number generator to use.
   * @return demographics
   */
  public Map<String, Object> randomDemographics(Random random) {
    Demographics city = location.randomCity(random);
    Map<String, Object> demoAttributes = pickDemographics(random, city);
    return demoAttributes;
  }

  /**
   * Print out the completed person to the consol.
   * @param person The person to print.
   * @param index The number person simulated.
   * @param time The time at which they died/the simulation ended.
   * @param isAlive Whether the person to print is alive.
   */
  private synchronized void writeToConsole(Person person, int index, long time, boolean isAlive) {
    // this is synchronized to ensure all lines for a single person are always printed 
    // consecutively
    String deceased = isAlive ? "" : "DECEASED";
    System.out.format("%d -- %s (%d y/o %s) %s, %s %s\n", index + 1,
        person.attributes.get(Person.NAME), person.ageInYears(time),
        person.attributes.get(Person.GENDER),
        person.attributes.get(Person.CITY), person.attributes.get(Person.STATE),
        deceased);

    if (this.logLevel.equals("detailed")) {
      System.out.println("ATTRIBUTES");
      for (String attribute : person.attributes.keySet()) {
        System.out.format("  * %s = %s\n", attribute, person.attributes.get(attribute));
      }
      System.out.format("SYMPTOMS: %d\n", person.symptomTotal());
      System.out.println(person.record.textSummary());
      System.out.println("VITAL SIGNS");
      for (VitalSign vitalSign : person.vitalSigns.keySet()) {
        System.out.format("  * %25s = %6.2f\n", vitalSign,
            person.getVitalSign(vitalSign, time).doubleValue());
      }
      System.out.println("-----");
    }
  }

  /**
   * Returns a map of demographics that have been randomly picked based on the given location.
   * @param random The random object to use.
   * @param city The city to base the demographics off of.
   * @return the person's picked demographics.
   */
  private Map<String, Object> pickDemographics(Random random, Demographics city) {
    // Output map of the generated demographc data.
    Map<String, Object> demographicsOutput = new HashMap<>();

    // Pull the person's location data.
    demographicsOutput.put(Person.CITY, city.city);
    demographicsOutput.put(Person.STATE, city.state);
    demographicsOutput.put("county", city.county);

    // Generate the person's race data based on their location.
    String race = city.pickRace(random);
    demographicsOutput.put(Person.RACE, race);
    String ethnicity = city.pickEthnicity(random);
    demographicsOutput.put(Person.ETHNICITY, ethnicity);
    String language = city.languageFromRaceAndEthnicity(race, ethnicity, random);
    demographicsOutput.put(Person.FIRST_LANGUAGE, language);

    // Generate the person's gender based on their location.
    String gender;
    if (options.gender != null) {
      gender = options.gender;
    } else {
      gender = city.pickGender(random);
      if (gender.equalsIgnoreCase("male") || gender.equalsIgnoreCase("M")) {
        gender = "M";
      } else {
        gender = "F";
      }
    }
    demographicsOutput.put(Person.GENDER, gender);

    // Pick the person's socioeconomic variables of education/income/occupation based on location.
    String education = city.pickEducation(random);
    demographicsOutput.put(Person.EDUCATION, education);
    double educationLevel = city.educationLevel(education, random);
    demographicsOutput.put(Person.EDUCATION_LEVEL, educationLevel);

    int income = city.pickIncome(random);
    demographicsOutput.put(Person.INCOME, income);
    double incomeLevel = city.incomeLevel(income);
    demographicsOutput.put(Person.INCOME_LEVEL, incomeLevel);

    double occupation = random.nextDouble();
    demographicsOutput.put(Person.OCCUPATION_LEVEL, occupation);

    double sesScore = city.socioeconomicScore(incomeLevel, educationLevel, occupation);
    demographicsOutput.put(Person.SOCIOECONOMIC_SCORE, sesScore);
    demographicsOutput.put(Person.SOCIOECONOMIC_CATEGORY, city.socioeconomicCategory(sesScore));

    if (this.onlyVeterans) {
      demographicsOutput.put("veteran_population_override", Boolean.TRUE);
    }

    // Generate the person's age data.
    int targetAge;
    if (options.ageSpecified) {
      targetAge = 
          (int) (options.minAge + ((options.maxAge - options.minAge) * random.nextDouble()));
    } else {
      targetAge = city.pickAge(random);
    }
    demographicsOutput.put(TARGET_AGE, targetAge);

    long birthdate = birthdateFromTargetAge(targetAge, random);
    demographicsOutput.put(Person.BIRTHDATE, birthdate);
    
    // Return the generated demographics.
    return demographicsOutput;
  }

  /**
   * Pick a person's demographics based on their FixedRecords.
   * @param index The index to use.
   * @param random Random object.
   */
  private Map<String, Object> pickFixedDemographics(int index, Random random) {

    // Get the first FixedRecord from the current RecordGroup
    FixedRecordGroup recordGroup = this.recordGroups.get(index);
    FixedRecord fr = recordGroup.records.get(0);
    // Get the city from the location in the fixed record.
    this.location = new Location(fr.state, recordGroup.getSafeCity());
    Demographics city = this.location.randomCity(random);
    // Pick the rest of the demographics based on the location of the fixed record.
    Map<String, Object> demoAttributes = pickDemographics(random, city);

    // Overwrite the person's attributes with the FixedRecord.
    demoAttributes.put(Person.BIRTHDATE, recordGroup.getValidBirthdate());
    demoAttributes.put(Person.BIRTH_CITY, city.city);
    String g = fr.gender;
    if (g.equalsIgnoreCase("None") || StringUtils.isBlank(g)) {
      g = "F";
    }
    demoAttributes.put(Person.GENDER, g);

    // Give the person their FixedRecordGroup of FixedRecords.
    demoAttributes.put(Person.RECORD_GROUP, recordGroup);
    demoAttributes.put(Person.LINK_ID, recordGroup.linkId);

    // Return the Demographic Attributes of the current person.
    return demoAttributes;
  }

  /**
   * Get a birthdate from the given target age.
   * @param targetAge The target age.
   * @param random A random object.
   * @return
   */
  private long birthdateFromTargetAge(long targetAge, Random random) {
    long earliestBirthdate = referenceTime - TimeUnit.DAYS.toMillis((targetAge + 1) * 365L + 1);
    long latestBirthdate = referenceTime - TimeUnit.DAYS.toMillis(targetAge * 365L);
    return 
        (long) (earliestBirthdate + ((latestBirthdate - earliestBirthdate) * random.nextDouble()));
  }

  /**
   * Record the person using whatever tracking mechanisms are currently configured.
   * @param person the person to record
   * @param index the index of the person being recorded, e.g. if generating 100 people, the index
   *     would identify which of those 100 is being recorded.
   */
  public void recordPerson(Person person, int index) {
    long finishTime = person.lastUpdated + timestep;
    boolean isAlive = person.alive(finishTime);

    if (internalStore != null) {
      internalStore.add(person);
    }

    if (this.metrics != null) {
      metrics.recordStats(person, finishTime, Module.getModules(modulePredicate));
    }

    if (!this.logLevel.equals("none")) {
      writeToConsole(person, index, finishTime, isAlive);
    }

    String key = isAlive ? "alive" : "dead";

    AtomicInteger count = stats.get(key);
    count.incrementAndGet();

    totalGeneratedPopulation.incrementAndGet();
  }
  
  private Predicate<String> getModulePredicate() {
    if (options.enabledModules == null) {
      return path -> true;
    }
    FilenameFilter filenameFilter = new WildcardFileFilter(options.enabledModules, 
        IOCase.INSENSITIVE);
    return path -> filenameFilter.accept(null, path);
  }

  /**
   * Returns a random double.
   */
  public double rand() {
    return random.nextDouble();
  }

  /**
   * Returns a random boolean.
   */
  public boolean randBoolean() {
    return random.nextBoolean();
  }

  /**
   * Returns a random integer.
   */
  public int randInt() {
    return random.nextInt();
  }

  /**
   * Returns a random integer in the given bound.
   */
  public int randInt(int bound) {
    return random.nextInt(bound);
  }

  /**
   * Returns a double from a normal distribution.
   */
  public double randGaussian() {
    return random.nextGaussian();
  }

  /**
   * Return a random long.
   */
  public long randLong() {
    return random.nextLong();
  }
  
  /**
   * Return a random UUID.
   */
  public UUID randUUID() {
    return new UUID(randLong(), randLong());
  }
  
}
