package enssp;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** Command-line entry point. Run with {@code --help} for usage. */
public final class Main {

    private static final String USAGE = String.join("\n",
        "enSSP-J 1.0.0 - enhanced state-sensitivity partitioning test generator",
        "",
        "Usage: java -jar enssp-j.jar --class <fully.qualified.Name> [options]",
        "",
        "  --cp <path>            classpath of the class under test (':' or ';' separated)",
        "  --out <dir>            output directory for the JUnit test class (default enssp-tests)",
        "  --mode <m>             enssp (GA + reduction, default) | random | ssp",
        "  --no-reduce            skip test-suite reduction (enssp mode)",
        "  --budget <s>           search budget in seconds (default 30)",
        "  --seed <n>             random seed (default 1)",
        "  --bound <n>            numeric abstraction bound (default 3)",
        "  --max-states <n>       maximum states probed for sensitivity (default 150)",
        "  --max-depth <n>        exploration depth (default 5)",
        "  --max-length <n>       maximum events per sequence (default 20)",
        "  --population <n>       GA population size (default 50)",
        "  --immigrants <r>       fraction of random immigrants per generation (default 0.2)",
        "  --stagnation <n>       stop after n evaluations without a new target (default: off)",
        "  --suffix <s>           test class name suffix (default _ESSP_Test)",
        "  --report <file>        write a JSON run report");

    public static void main(String[] argv) throws Exception {
        Config cfg;
        try {
            cfg = parse(argv);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.err.println();
            System.err.println(USAGE);
            System.exit(2);
            return;
        }
        // The class under test may print; keep enSSP-J's own stdout (the JSON report) clean.
        java.io.PrintStream out = System.out;
        System.setOut(new java.io.PrintStream(java.io.OutputStream.nullOutputStream()));
        Map<String, Object> report;
        try {
            report = run(cfg);
        } catch (IllegalArgumentException | ClassNotFoundException e) {
            System.setOut(out);
            System.err.println("enSSP-J: " + e.getMessage());
            System.exit(2);
            return;
        } finally {
            System.setOut(out);
        }
        out.println(toJson(report));
        System.exit(0);
    }

    static Config parse(String[] a) {
        Config c = new Config();
        for (int i = 0; i < a.length; i++) {
            String k = a[i];
            if (k.equals("--help") || k.equals("-h")) { System.out.println(USAGE); System.exit(0); }
            if (k.equals("--no-reduce")) { c.reduce = false; continue; }
            if (i + 1 >= a.length) throw new IllegalArgumentException("Missing value for " + k);
            String v = a[++i];
            switch (k) {
                case "--class": c.className = v; break;
                case "--cp": c.classpath = v; break;
                case "--out": c.outDir = v; break;
                case "--mode": c.mode = v; break;
                case "--budget": c.budgetSeconds = Long.parseLong(v); break;
                case "--seed": c.seed = Long.parseLong(v); break;
                case "--bound": c.bound = Integer.parseInt(v); break;
                case "--max-states": c.maxStates = Integer.parseInt(v); break;
                case "--max-depth": c.maxDepth = Integer.parseInt(v); break;
                case "--max-length": c.maxLength = Integer.parseInt(v); break;
                case "--population": c.populationSize = Integer.parseInt(v); break;
                case "--immigrants": c.immigrantRate = Double.parseDouble(v); break;
                case "--stagnation": c.stagnation = Long.parseLong(v); break;
                case "--suffix": c.testSuffix = v; break;
                case "--report": c.reportFile = v; break;
                default: throw new IllegalArgumentException("Unknown option " + k);
            }
        }
        if (c.className == null) throw new IllegalArgumentException("--class is required");
        if (!c.mode.equals("enssp") && !c.mode.equals("random") && !c.mode.equals("ssp")) {
            throw new IllegalArgumentException("--mode must be enssp, random or ssp");
        }
        return c;
    }

    public static Map<String, Object> run(Config cfg) throws Exception {
        long t0 = System.currentTimeMillis();
        List<URL> urls = new ArrayList<>();
        for (String p : cfg.classpath.split(File.pathSeparator)) if (!p.isEmpty()) urls.add(new File(p).toURI().toURL());
        ClassLoader loader = new URLClassLoader(urls.toArray(new URL[0]), Main.class.getClassLoader());
        Class<?> cut = Class.forName(cfg.className, true, loader);
        Random rng = new Random(cfg.seed);

        EventAlphabet alphabet = EventAlphabet.build(cut, ArgumentPool.defaults(), cfg.maxArgCombos, rng);
        StateAbstraction abstraction = new StateAbstraction(cut, alphabet.observers, cfg.bound);
        Map<String, Object> r = new LinkedHashMap<>();
        try (SequenceExecutor exec = new SequenceExecutor(abstraction, cfg.bound, cfg.timeoutMillis)) {
            SensitivityModel model = new SensitivityModel(exec, alphabet.events, cfg.maxStates);
            model.explore(alphabet.constructors, cfg.maxDepth);
            long tExplore = System.currentTimeMillis();
            int targetsAfterExploration = model.targets().size();

            long deadline = t0 + cfg.budgetSeconds * 1000;
            List<List<Event>> suite;
            Reducer.Stats red = null;
            long evaluations;
            int generations = 0;
            if (cfg.mode.equals("ssp")) {
                suite = Baselines.ssp(model, alphabet);
                evaluations = 0;
            } else if (cfg.mode.equals("random")) {
                long[] ev = {0};
                suite = Baselines.randomSearch(exec, model, alphabet, rng, cfg, deadline, ev);
                evaluations = ev[0];
            } else {
                GeneticOptimizer ga = new GeneticOptimizer(exec, model, alphabet, rng, cfg);
                suite = ga.run(deadline);
                evaluations = ga.stats.evaluations;
                generations = ga.stats.generations;
                if (cfg.reduce) {
                    Reducer reducer = new Reducer(exec, model);
                    suite = reducer.reduce(suite);
                    red = reducer.stats;
                }
            }
            long tSearch = System.currentTimeMillis();

            java.util.Set<String> covered = new java.util.LinkedHashSet<>();
            int events = 0, redundant = 0, valid = 0;
            for (List<Event> s : suite) {
                SequenceExecutor.Trace t = exec.execute(s);
                if (!t.valid) continue;
                valid++;
                events += s.size() - 1;
                redundant += Reducer.redundantStates(t);
                covered.addAll(model.targetsOf(t));
            }
            String header = "Class " + cut.getName() + ", mode " + cfg.mode + (cfg.mode.equals("enssp") && !cfg.reduce ? " (no reduction)" : "")
                + ", seed " + cfg.seed + ". " + valid + " tests, " + covered.size() + "/" + model.targets().size()
                + " sensitivity targets over " + model.partitions() + " partitions.";
            Path file = new JUnitWriter(cut, exec, abstraction.observers()).write(suite, Paths.get(cfg.outDir), cfg.testSuffix, header);

            r.put("tool", "enSSP-J 1.0.0");
            r.put("class", cut.getName());
            r.put("mode", cfg.mode + (cfg.mode.equals("enssp") && !cfg.reduce ? "-noreduce" : ""));
            r.put("seed", cfg.seed);
            r.put("budgetSeconds", cfg.budgetSeconds);
            r.put("accessPrograms", alphabet.accessPrograms);
            r.put("constructorEvents", alphabet.constructors.size());
            r.put("events", alphabet.events.size());
            r.put("observers", alphabet.observers.size());
            r.put("systemIds", exec.stateCount());
            r.put("probedStates", model.probedStates());
            r.put("partitions", model.partitions());
            r.put("sensitivityClasses", model.sensitivityClasses());
            r.put("targetsAfterExploration", targetsAfterExploration);
            r.put("targets", model.targets().size());
            r.put("targetsCovered", covered.size());
            r.put("tests", valid);
            r.put("eventsInSuite", events);
            r.put("meanLength", valid == 0 ? 0 : Math.round(100.0 * events / valid) / 100.0);
            r.put("redundantStateVisits", redundant);
            r.put("evaluations", evaluations);
            r.put("generations", generations);
            if (red != null) {
                r.put("reductionSequencesBefore", red.sequencesBefore);
                r.put("reductionEventsBefore", red.eventsBefore);
                r.put("reductionRedundantBefore", red.redundantStatesBefore);
                r.put("reductionLoopsRemoved", red.loopsRemoved);
            }
            r.put("explorationMillis", tExplore - t0);
            r.put("searchMillis", tSearch - tExplore);
            r.put("totalMillis", System.currentTimeMillis() - t0);
            r.put("executions", exec.executions());
            r.put("testFile", file.toString());
        }
        if (cfg.reportFile != null) {
            Path p = Paths.get(cfg.reportFile);
            if (p.getParent() != null) Files.createDirectories(p.getParent());
            Files.write(p, toJson(r).getBytes(StandardCharsets.UTF_8));
        }
        return r;
    }

    static String toJson(Map<String, Object> m) {
        StringBuilder b = new StringBuilder("{\n");
        int i = 0;
        for (Map.Entry<String, Object> e : m.entrySet()) {
            b.append("  \"").append(e.getKey()).append("\": ");
            Object v = e.getValue();
            if (v instanceof Number || v instanceof Boolean) b.append(v);
            else b.append(ArgumentPool.stringLiteral(String.valueOf(v)));
            b.append(++i < m.size() ? ",\n" : "\n");
        }
        return b.append("}").toString();
    }
}
