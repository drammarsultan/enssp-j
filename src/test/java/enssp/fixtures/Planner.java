package enssp.fixtures;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Fixture for the object-argument builder: access programs that take arrays, objects and collections. */
public class Planner {
    public static class Task {
        private final double[] weights;
        private final int priority;
        public Task(double[] weights, int priority) {
            if (priority < 0) throw new IllegalArgumentException("priority");
            this.weights = weights.clone();
            this.priority = priority;
        }
        public int priority() { return priority; }
        public int size() { return weights.length; }
    }

    private final List<Task> tasks = new ArrayList<>();

    public void add(Task t) { tasks.add(t); }
    public void addAll(Collection<Task> ts) { tasks.addAll(ts); }
    public int schedule(Task first, Collection<Task> rest, boolean strict, int limit) {
        int n = first.priority();
        for (Task t : rest) if (!strict || t.size() <= limit) n += t.priority();
        return n;
    }
    public int count() { return tasks.size(); }
}
