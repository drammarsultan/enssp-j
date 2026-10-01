package enssp;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * State-sensitivity partitioning.
 *
 * <p>Every discovered data state (system ID) is probed with every event of the alphabet, and each
 * response is classified (void, value, null, true, false or exception type, plus whether the
 * data state changed). For every access program <i>m</i>, states whose responses to all events
 * of <i>m</i> coincide are <em>equally sensitive</em> to <i>m</i> and fall into the same
 * sensitivity class of <i>m</i>. States that are equally sensitive to every access program form
 * a global SSP partition.</p>
 *
 * <p>A <em>sensitivity target</em> is a triple (access program, sensitivity class of the state
 * it is applied in, response class). Test generation aims to exercise every target once.</p>
 */
public final class SensitivityModel {

    private final SequenceExecutor exec;
    private final List<Event> events;
    private final int maxStates;
    private final Map<Integer, List<Event>> witness = new HashMap<>();
    private final Map<Integer, Integer> partitionOf = new HashMap<>();
    private final Map<String, Integer> partitionIds = new HashMap<>();
    private final Map<Integer, Integer> partitionRepresentative = new HashMap<>();
    private final Map<Integer, Map<String, Integer>> sensitivityClass = new HashMap<>();
    private final Map<String, Integer> classIds = new HashMap<>();
    private final Set<Integer> unprobeable = new HashSet<>();
    private final Set<String> targets = new LinkedHashSet<>();
    private final Map<String, List<Event>> firstWitness = new java.util.LinkedHashMap<>();
    private int probed;

    public SensitivityModel(SequenceExecutor exec, List<Event> events, int maxStates) {
        this.exec = exec;
        this.events = events;
        this.maxStates = maxStates;
    }

    /** Bounded breadth-first exploration of the abstract state space from every constructor. */
    public void explore(List<Event> constructors, int maxDepth) {
        Deque<Integer> queue = new ArrayDeque<>();
        for (Event c : constructors) {
            List<Event> seq = new ArrayList<>();
            seq.add(c);
            SequenceExecutor.Trace t = exec.execute(seq);
            if (!t.valid) continue;
            if (!witness.containsKey(t.initialState)) {
                witness.put(t.initialState, seq);
                queue.add(t.initialState);
            }
        }
        while (!queue.isEmpty() && probed < maxStates) {
            int s = queue.poll();
            List<Integer> discovered = probe(s);
            if (witness.get(s).size() - 1 < maxDepth) queue.addAll(discovered);
        }
    }

    /** Probes state {@code s}; returns newly discovered successor states. */
    private List<Integer> probe(int s) {
        List<Integer> discovered = new ArrayList<>();
        if (partitionOf.containsKey(s) || unprobeable.contains(s)) return discovered;
        List<Event> w = witness.get(s);
        if (w == null || probed >= maxStates) { unprobeable.add(s); return discovered; }
        TreeMap<String, String> signature = new TreeMap<>();
        Map<String, TreeMap<String, String>> perProgram = new TreeMap<>();
        List<SequenceExecutor.Step> responses = new ArrayList<>();
        for (Event e : events) {
            List<Event> seq = new ArrayList<>(w);
            seq.add(e);
            SequenceExecutor.Trace t = exec.execute(seq);
            TreeMap<String, String> mine = perProgram.computeIfAbsent(e.signature(), k -> new TreeMap<>());
            if (!t.valid || t.finalState() == -1) { signature.put(e.key(), "invalid"); mine.put(e.key(), "invalid"); continue; }
            SequenceExecutor.Step last = t.steps.get(t.steps.size() - 1);
            if (last.pre != s) { signature.put(e.key(), "nondeterministic"); mine.put(e.key(), "nondeterministic"); continue; }
            signature.put(e.key(), last.responseClass());
            mine.put(e.key(), last.responseClass());
            responses.add(last);
            if (!witness.containsKey(last.post)) {
                witness.put(last.post, seq);
                discovered.add(last.post);
            }
        }
        probed++;
        String sig = signature.toString();
        Integer pid = partitionIds.get(sig);
        if (pid == null) {
            pid = partitionIds.size();
            partitionIds.put(sig, pid);
            partitionRepresentative.put(pid, s);
        }
        partitionOf.put(s, pid);
        Map<String, Integer> classes = new HashMap<>();
        for (Map.Entry<String, TreeMap<String, String>> m : perProgram.entrySet()) {
            String k = m.getKey() + m.getValue();
            Integer id = classIds.get(k);
            if (id == null) { id = classIds.size(); classIds.put(k, id); }
            classes.put(m.getKey(), id);
        }
        sensitivityClass.put(s, classes);
        for (SequenceExecutor.Step r : responses) {
            String tg = target(classes.get(r.event.signature()), r);
            targets.add(tg);
            if (!firstWitness.containsKey(tg)) {
                List<Event> seq = new ArrayList<>(w);
                seq.add(r.event);
                firstWitness.put(tg, seq);
            }
        }
        return discovered;
    }

    static String target(int sensitivityClass, SequenceExecutor.Step step) {
        return step.event.signature() + " | S" + sensitivityClass + " | " + step.responseClass();
    }

    /** Sensitivity class of {@code state} with respect to the access program of {@code e}, or -1. */
    public int sensitivityClass(int state, Event e, List<Event> prefixReaching) {
        if (partition(state, prefixReaching) < 0) return -1;
        Integer c = sensitivityClass.get(state).get(e.signature());
        return c == null ? -1 : c;
    }

    /** Target exercised by a step, or null if the pre-state could not be classified. */
    public String targetOf(SequenceExecutor.Step st, List<Event> prefixReaching) {
        int c = sensitivityClass(st.pre, st.event, prefixReaching);
        return c < 0 ? null : target(c, st);
    }

    /**
     * Partition of a state. States first reached during search are probed on demand (online
     * partition refinement) while the state budget allows; otherwise -1 is returned.
     */
    public int partition(int state, List<Event> prefixReaching) {
        Integer p = partitionOf.get(state);
        if (p != null) return p;
        if (unprobeable.contains(state) || probed >= maxStates) return -1;
        if (!witness.containsKey(state)) witness.put(state, new ArrayList<>(prefixReaching));
        probe(state);
        Integer q = partitionOf.get(state);
        return q == null ? -1 : q;
    }

    /** Sensitivity targets exercised by a trace. Targets not seen during exploration are added to the universe. */
    public Set<String> targetsOf(SequenceExecutor.Trace t) {
        Set<String> out = new LinkedHashSet<>();
        if (!t.valid) return out;
        for (int i = 0; i < t.steps.size(); i++) {
            SequenceExecutor.Step st = t.steps.get(i);
            String tg = targetOf(st, t.sequence.subList(0, i + 1));
            if (tg == null) continue;
            out.add(tg);
            targets.add(tg);
        }
        return out;
    }

    public Set<String> targets() { return targets; }

    public int partitions() { return partitionIds.size(); }

    public int sensitivityClasses() { return classIds.size(); }

    public int probedStates() { return probed; }

    public List<Event> witness(int state) { return witness.get(state); }

    /** For every target found during probing, the first sequence (witness + event) that exercised it. */
    public Map<String, List<Event>> firstWitnesses() { return firstWitness; }

    /** Representative state (first probed) of every partition. */
    public Map<Integer, Integer> representatives() { return partitionRepresentative; }
}
