package enssp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Test-suite reduction for enSSP suites. It preserves the set of covered sensitivity targets and
 * removes, in order:
 * <ol>
 *   <li>redundant data states: a cycle of events that leaves the start system ID, passes through
 *       other data states and returns to it is cut out when this loses no target (single events
 *       that leave the state unchanged are kept, since they test the event in that state);</li>
 *   <li>whole sequences, by greedy set cover over targets.</li>
 * </ol>
 * Every candidate is re-executed, so the reduced suite's coverage is measured, not assumed.
 */
public final class Reducer {

    public static final class Stats {
        public int sequencesBefore, sequencesAfter;
        public int eventsBefore, eventsAfter;
        public int redundantStatesBefore, redundantStatesAfter;
        public int loopsRemoved;
    }

    private final SequenceExecutor exec;
    private final SensitivityModel model;
    public final Stats stats = new Stats();

    public Reducer(SequenceExecutor exec, SensitivityModel model) {
        this.exec = exec;
        this.model = model;
    }

    public List<List<Event>> reduce(List<List<Event>> suite) {
        List<List<Event>> seqs = new ArrayList<>();
        List<Set<String>> cov = new ArrayList<>();
        Map<String, Integer> count = new HashMap<>();
        for (List<Event> s : suite) {
            SequenceExecutor.Trace t = exec.execute(s);
            if (!t.valid) continue;
            Set<String> tg = model.targetsOf(t);
            seqs.add(new ArrayList<>(s));
            cov.add(tg);
            for (String x : tg) count.merge(x, 1, Integer::sum);
            stats.eventsBefore += s.size() - 1;
            stats.redundantStatesBefore += redundantStates(t);
        }
        stats.sequencesBefore = seqs.size();

        for (int k = 0; k < seqs.size(); k++) {
            boolean changed = true;
            while (changed) {
                changed = false;
                int[] ids = exec.execute(seqs.get(k)).stateIds();
                outer:
                for (int i = 0; i < ids.length; i++) {
                    for (int j = ids.length - 1; j > i + 1; j--) {
                        if (ids[i] != ids[j] || !leavesState(ids, i, j)) continue;
                        List<Event> cand = new ArrayList<>(seqs.get(k).subList(0, i + 1));
                        cand.addAll(seqs.get(k).subList(j + 1, seqs.get(k).size()));
                        if (tryReplace(k, cand, seqs, cov, count)) { stats.loopsRemoved++; changed = true; break outer; }
                    }
                }
            }
        }

        // Greedy set cover over sensitivity targets.
        Set<String> universe = new HashSet<>(count.keySet());
        List<List<Event>> out = new ArrayList<>();
        Set<Integer> used = new HashSet<>();
        while (!universe.isEmpty()) {
            int best = -1, bestGain = 0;
            for (int k = 0; k < seqs.size(); k++) {
                if (used.contains(k)) continue;
                int gain = 0;
                for (String x : cov.get(k)) if (universe.contains(x)) gain++;
                if (gain > bestGain || (gain == bestGain && gain > 0 && seqs.get(k).size() < seqs.get(best).size())) {
                    best = k; bestGain = gain;
                }
            }
            if (best < 0) break;
            used.add(best);
            out.add(seqs.get(best));
            universe.removeAll(cov.get(best));
        }
        stats.sequencesAfter = out.size();
        for (List<Event> s : out) {
            stats.eventsAfter += s.size() - 1;
            stats.redundantStatesAfter += redundantStates(exec.execute(s));
        }
        return out;
    }

    /** True if the segment between positions i and j passes through a system ID other than ids[i]. */
    private static boolean leavesState(int[] ids, int i, int j) {
        for (int k = i + 1; k < j; k++) if (ids[k] != ids[i]) return true;
        return false;
    }

    /** Replaces sequence k by cand if the suite keeps every covered target. */
    private boolean tryReplace(int k, List<Event> cand, List<List<Event>> seqs, List<Set<String>> cov, Map<String, Integer> count) {
        SequenceExecutor.Trace t = exec.execute(cand);
        if (!t.valid) return false;
        Set<String> nc = model.targetsOf(t);
        for (String x : cov.get(k)) {
            if (!nc.contains(x) && count.getOrDefault(x, 0) <= 1) return false;
        }
        for (String x : cov.get(k)) count.merge(x, -1, Integer::sum);
        for (String x : nc) count.merge(x, 1, Integer::sum);
        count.values().removeIf(v -> v <= 0);
        seqs.set(k, cand);
        cov.set(k, nc);
        return true;
    }

    /** Number of events after which the sequence revisits a system ID it has already been in. */
    public static int redundantStates(SequenceExecutor.Trace t) {
        if (!t.valid) return 0;
        Set<Integer> seen = new HashSet<>();
        int r = 0;
        for (int id : t.stateIds()) if (!seen.add(id)) r++;
        return r;
    }
}
