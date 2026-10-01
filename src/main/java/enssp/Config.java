package enssp;

/** Tool configuration; defaults are those documented in the README. */
public final class Config {
    public String className;
    public String classpath = "";
    public String outDir = "enssp-tests";
    public String mode = "enssp";           // enssp | random | ssp
    public boolean reduce = true;
    public long budgetSeconds = 30;
    public long seed = 1;
    public int bound = 3;                   // numeric abstraction buckets <0,0,..,bound-1,>=bound
    public int maxStates = 150;             // states probed for sensitivity
    public int maxDepth = 5;                // BFS depth of the exploration phase
    public int maxArgCombos = 12;           // argument tuples per access program
    public int maxLength = 20;              // events per sequence
    public long timeoutMillis = 1000;       // per sequence execution
    public int populationSize = 50;
    public int elitism = 2;
    public int tournamentSize = 3;
    public double crossoverRate = 0.75;
    public double mutationRate = 0.33;
    public double immigrantRate = 0.2;     // fraction of each generation replaced by random sequences
    public long stagnation = Long.MAX_VALUE; // evaluations without a new target (disabled)
    public double wNovelState = 0.5;
    public double wDistinct = 0.1;
    public double wRedundant = 0.2;
    public double wLength = 0.05;
    public String reportFile;
    public String testSuffix = "_ESSP_Test";
}
