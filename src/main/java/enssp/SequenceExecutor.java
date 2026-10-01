package enssp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Executes event sequences on fresh instances of the module under test and records, for every
 * event, its abstract response and the system IDs of the data states before and after it.
 */
public final class SequenceExecutor implements AutoCloseable {

    /** Result of one event within a sequence. */
    public static final class Step {
        public final Event event;
        public final int pre;
        public final int post;
        /** Abstract response: {@code void}, {@code ret:<abstract value>} or {@code ex:<exception>}. */
        public final String response;
        public final Object value;
        public final Throwable thrown;

        Step(Event event, int pre, int post, String response, Object value, Throwable thrown) {
            this.event = event; this.pre = pre; this.post = post;
            this.response = response; this.value = value; this.thrown = thrown;
        }

        /**
         * Response class used for sensitivity: the kind of outcome (void, a value, null, true,
         * false, or the exception type) plus whether the data state changed. Concrete returned
         * values are deliberately not part of the class: they are data, not behaviour.
         */
        public String responseClass() {
            String kind;
            if (thrown != null) kind = response;
            else if (response.equals("void")) kind = "void";
            else if (value == null) kind = "null";
            else if (value instanceof Boolean) kind = value.toString();
            else kind = "value";
            return kind + (pre == post ? "|same" : "|changed");
        }
    }

    /** Result of executing a whole sequence. */
    public static final class Trace {
        public final List<Event> sequence;
        public final boolean valid;
        public final int initialState;
        public final List<Step> steps;
        public final Object finalObject;
        public final String failure;

        Trace(List<Event> sequence, boolean valid, int initialState, List<Step> steps, Object finalObject, String failure) {
            this.sequence = sequence; this.valid = valid; this.initialState = initialState;
            this.steps = steps; this.finalObject = finalObject; this.failure = failure;
        }

        /** System IDs visited: index 0 is the state after construction, index i the state after event i. */
        public int[] stateIds() {
            int[] ids = new int[steps.size() + 1];
            ids[0] = initialState;
            for (int i = 0; i < steps.size(); i++) ids[i + 1] = steps.get(i).post;
            return ids;
        }

        public int finalState() { return steps.isEmpty() ? initialState : steps.get(steps.size() - 1).post; }
    }

    private final StateAbstraction abstraction;
    private final int bound;
    private final long timeoutMillis;
    private final Map<String, Integer> systemIds = new HashMap<>();
    private final List<String> states = new ArrayList<>();
    private ExecutorService worker;
    private long executions;
    private long eventsExecuted;

    public SequenceExecutor(StateAbstraction abstraction, int bound, long timeoutMillis) {
        this.abstraction = abstraction;
        this.bound = bound;
        this.timeoutMillis = timeoutMillis;
        this.worker = newWorker();
    }

    private static ExecutorService newWorker() {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "enssp-exec");
            t.setDaemon(true);
            return t;
        });
    }

    /** Unique system ID of an abstract state (enSSP's integer encoding of state-variable combinations). */
    public synchronized int systemId(String abstractState) {
        Integer id = systemIds.get(abstractState);
        if (id == null) {
            id = states.size();
            systemIds.put(abstractState, id);
            states.add(abstractState);
        }
        return id;
    }

    public synchronized String describe(int systemId) { return states.get(systemId); }

    public synchronized int stateCount() { return states.size(); }

    public long executions() { return executions; }

    public long eventsExecuted() { return eventsExecuted; }

    public Trace execute(List<Event> seq) {
        executions++;
        eventsExecuted += seq.size();
        Future<Trace> f = worker.submit(() -> run(seq));
        try {
            return f.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            f.cancel(true);
            worker.shutdownNow();
            worker = newWorker();
            return new Trace(seq, false, -1, Collections.emptyList(), null, "timeout");
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return new Trace(seq, false, -1, Collections.emptyList(), null, "interrupted");
        } catch (ExecutionException ee) {
            return new Trace(seq, false, -1, Collections.emptyList(), null, "error:" + ee.getCause());
        }
    }

    private Trace run(List<Event> seq) {
        if (seq.isEmpty() || !seq.get(0).isConstructor()) throw new IllegalArgumentException("sequence must start with a constructor");
        Object obj;
        try {
            obj = seq.get(0).invoke(null);
        } catch (Throwable t) {
            return new Trace(seq, false, -1, Collections.emptyList(), null, "constructor:" + t);
        }
        int initial = systemId(abstraction.abstractState(obj));
        List<Step> steps = new ArrayList<>(seq.size() - 1);
        int pre = initial;
        for (int i = 1; i < seq.size(); i++) {
            Event e = seq.get(i);
            Object value = null;
            Throwable thrown = null;
            String response;
            try {
                value = e.invoke(obj);
                response = e.returnType() == void.class ? "void" : "ret:" + StateAbstraction.abstractValue(value, bound);
            } catch (VirtualMachineError | ThreadDeath fatal) {
                return new Trace(seq, false, initial, steps, obj, "fatal:" + fatal.getClass().getSimpleName());
            } catch (Throwable t) {
                thrown = t;
                response = "ex:" + t.getClass().getSimpleName();
            }
            if (Thread.currentThread().isInterrupted()) {
                return new Trace(seq, false, initial, steps, obj, "interrupted");
            }
            int post = systemId(abstraction.abstractState(obj));
            steps.add(new Step(e, pre, post, response, value, thrown));
            pre = post;
        }
        return new Trace(seq, true, initial, steps, obj, null);
    }

    @Override
    public void close() { worker.shutdownNow(); }
}
