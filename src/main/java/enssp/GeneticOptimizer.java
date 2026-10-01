package enssp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Genetic optimisation of event sequences (the "en" in enSSP).
 *
 * <p>A chromosome is an event sequence: gene 0 is a constructor, the remaining genes are events.
 * Executing a chromosome yields the system IDs it visits. Its fitness rewards sensitivity targets
 * not yet covered by the archive and distinct data states, and penalises redundant data states
 * (revisits of a system ID that contribute no new target) and sequence length:</p>
 *
 * <pre>F(s) = |new targets| + n * novel + a * distinct/len - b * redundant/len - c * len/maxLen</pre>
 *
 * <p>where {@code novel} counts system IDs the sequence visits that no archived sequence has
 * visited, which steers the search towards unexplored data states. Each generation keeps the
 * elite, adds a fraction of random immigrants, and fills the rest by tournament selection,
 * crossover and mutation.</p>
 *
 * <p>Every chromosome that covers a new target is copied into the archive, which becomes the
 * test suite. Search stops when every known target is covered, at the time budget, or (if
 * configured) after {@code stagnation} evaluations without a new target.</p>
 */
public final class GeneticOptimizer {

    public static final class Stats {
        public int generations;
        public long evaluations;
    }

    private final SequenceExecutor exec;
    private final SensitivityModel model;
    private final List<Event> ctors;
    private final List<Event> events;
    private final Random rng;
    private final Config cfg;
    private final Set<String> covered = new HashSet<>();
    private final Set<Integer> archivedStates = new HashSet<>();
    private final List<List<Event>> archive = new ArrayList<>();
    public final Stats stats = new Stats();

    private static final class Individual {
        final List<Event> genes;
        double fitness;
        Set<String> newTargets = Collections.emptySet();
        SequenceExecutor.Trace trace;
        Individual(List<Event> genes) { this.genes = genes; }
    }

    public GeneticOptimizer(SequenceExecutor exec, SensitivityModel model, EventAlphabet alphabet, Random rng, Config cfg) {
        this.exec = exec;
        this.model = model;
        this.ctors = alphabet.constructors;
        this.events = alphabet.events;
        this.rng = rng;
        this.cfg = cfg;
    }

    public List<List<Event>> run(long deadline) {
        List<Individual> pop = new ArrayList<>();
        for (int i = 0; i < cfg.populationSize; i++) pop.add(new Individual(randomSequence(1 + rng.nextInt(cfg.maxLength))));
        long sinceImprovement = 0;
        while (System.currentTimeMillis() < deadline && sinceImprovement < cfg.stagnation && !events.isEmpty()
               && covered.size() < model.targets().size()) {
            stats.generations++;
            boolean improved = false;
            for (Individual ind : pop) {
                if (System.currentTimeMillis() >= deadline) break;
                evaluate(ind);
                stats.evaluations++;
                sinceImprovement++;
                if (!ind.newTargets.isEmpty()) {
                    archive.add(new ArrayList<>(ind.genes));
                    covered.addAll(ind.newTargets);
                    for (int id : ind.trace.stateIds()) archivedStates.add(id);
                    improved = true;
                    sinceImprovement = 0;
                }
            }
            if (improved) for (Individual ind : pop) if (ind.trace != null) score(ind, ind.trace);
            pop.sort((a, b) -> Double.compare(b.fitness, a.fitness));
            List<Individual> next = new ArrayList<>();
            for (int i = 0; i < Math.min(cfg.elitism, pop.size()); i++) next.add(new Individual(new ArrayList<>(pop.get(i).genes)));
            // Random immigrants keep the population diverse once it converges on a few regions.
            int immigrants = (int) Math.round(cfg.immigrantRate * cfg.populationSize);
            for (int i = 0; i < immigrants && next.size() < cfg.populationSize; i++) {
                next.add(new Individual(randomSequence(1 + rng.nextInt(cfg.maxLength))));
            }
            while (next.size() < cfg.populationSize) {
                List<Event> a = tournament(pop).genes;
                List<Event> b = tournament(pop).genes;
                List<Event> child = rng.nextDouble() < cfg.crossoverRate ? crossover(a, b) : new ArrayList<>(a);
                next.add(new Individual(mutate(child)));
            }
            pop = next;
        }
        return archive;
    }

    private void evaluate(Individual ind) {
        ind.trace = exec.execute(ind.genes);
        score(ind, ind.trace);
    }

    private void score(Individual ind, SequenceExecutor.Trace t) {
        if (!t.valid) {
            ind.fitness = -1e9;
            ind.newTargets = Collections.emptySet();
            return;
        }
        Set<String> tg = model.targetsOf(t);
        Set<String> fresh = new LinkedHashSet<>();
        for (String s : tg) if (!covered.contains(s)) fresh.add(s);
        int len = Math.max(1, t.steps.size());
        int[] ids = t.stateIds();
        Set<Integer> seen = new HashSet<>();
        seen.add(ids[0]);
        int redundant = 0;
        for (int i = 1; i < ids.length; i++) {
            boolean revisit = !seen.add(ids[i]);
            if (revisit) {
                String target = model.targetOf(t.steps.get(i - 1), t.sequence.subList(0, i));
                if (target == null || !fresh.contains(target)) redundant++;
            }
        }
        int novel = 0;
        for (int id : seen) if (!archivedStates.contains(id)) novel++;
        ind.newTargets = fresh;
        ind.fitness = fresh.size()
            + cfg.wNovelState * novel
            + cfg.wDistinct * seen.size() / (double) len
            - cfg.wRedundant * redundant / (double) len
            - cfg.wLength * len / (double) cfg.maxLength;
    }

    private Individual tournament(List<Individual> pop) {
        Individual best = null;
        for (int i = 0; i < cfg.tournamentSize; i++) {
            Individual c = pop.get(rng.nextInt(pop.size()));
            if (best == null || c.fitness > best.fitness) best = c;
        }
        return best;
    }

    /** Cut-and-splice crossover; the constructor gene of the first parent is kept. */
    private List<Event> crossover(List<Event> a, List<Event> b) {
        int ca = 1 + rng.nextInt(a.size());
        int cb = 1 + rng.nextInt(b.size());
        List<Event> child = new ArrayList<>(a.subList(0, ca));
        if (cb < b.size()) child.addAll(b.subList(cb, b.size()));
        while (child.size() > cfg.maxLength + 1) child.remove(child.size() - 1);
        return child;
    }

    /** Replace, insert or delete events; occasionally switch constructor. */
    private List<Event> mutate(List<Event> s) {
        List<Event> m = new ArrayList<>(s);
        double pGene = 1.0 / Math.max(1, m.size() - 1);
        for (int i = 1; i < m.size(); i++) if (rng.nextDouble() < pGene) m.set(i, randomEvent());
        if (rng.nextDouble() < cfg.mutationRate && m.size() <= cfg.maxLength) m.add(1 + rng.nextInt(m.size()), randomEvent());
        if (rng.nextDouble() < cfg.mutationRate && m.size() > 2) m.remove(1 + rng.nextInt(m.size() - 1));
        if (ctors.size() > 1 && rng.nextDouble() < 0.1) m.set(0, ctors.get(rng.nextInt(ctors.size())));
        return m;
    }

    private Event randomEvent() { return events.get(rng.nextInt(events.size())); }

    List<Event> randomSequence(int len) {
        List<Event> s = new ArrayList<>(len + 1);
        s.add(ctors.get(rng.nextInt(ctors.size())));
        for (int i = 0; i < len; i++) s.add(randomEvent());
        return s;
    }
}
