package enssp;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps a concrete object of the module under test to an abstract data state.
 *
 * <p>The state variables are (i) every instance field of the module, (ii) the result of every
 * pure observer (zero-argument access program that does not modify the object), (iii) the
 * number of module-internal objects reachable from the module (e.g. tree or list nodes), and
 * (iv) optional shape variables: for every reference or boolean field of those internal objects,
 * how many of them hold {@code null} (respectively {@code true}) in it, e.g. the number of tree
 * nodes without a left child or the number of red nodes. Numeric values are abstracted to the
 * buckets {@code <0, 0, 1, ..., bound-1, >=bound}. Each distinct abstract state is later given a
 * unique system ID.</p>
 */
public final class StateAbstraction {

    private static final int MAX_NODES = 20_000;
    private final int bound;
    private final List<Field> fields;
    private final List<Event> observers;
    private final boolean shape;

    public StateAbstraction(Class<?> cut, List<Event> observers, int bound) {
        this(cut, observers, bound, true);
    }

    public StateAbstraction(Class<?> cut, List<Event> observers, int bound, boolean shape) {
        this.bound = bound;
        this.shape = shape;
        this.observers = new ArrayList<>(observers);
        this.fields = instanceFields(cut);
    }

    public List<Event> observers() { return observers; }

    public String abstractState(Object obj) {
        StringBuilder b = new StringBuilder();
        for (Field f : fields) {
            Object v;
            try { v = f.get(obj); } catch (Exception e) { continue; }
            b.append(f.getName()).append('=').append(abstractValue(v, bound)).append(';');
        }
        for (Event o : observers) {
            String r;
            try { r = abstractValue(o.invoke(obj), bound); }
            catch (Throwable t) { r = "ex:" + t.getClass().getSimpleName(); }
            b.append(o.signature()).append('=').append(r).append(';');
        }
        java.util.TreeMap<String, Integer> shapeCounts = shape ? new java.util.TreeMap<>() : null;
        b.append("#nodes=").append(bucket(internalNodes(obj, shapeCounts), bound));
        if (shapeCounts != null) {
            for (Map.Entry<String, Integer> e : shapeCounts.entrySet()) {
                b.append(';').append(e.getKey()).append('=').append(bucket(e.getValue(), bound));
            }
        }
        return b.toString();
    }

    // ---------------------------------------------------------------- value abstraction

    public static String bucket(long v, int bound) {
        if (v < 0) return "<0";
        if (v >= bound) return ">=" + bound;
        return Long.toString(v);
    }

    public static String abstractValue(Object v, int bound) {
        if (v == null) return "null";
        if (v instanceof Integer || v instanceof Long || v instanceof Short || v instanceof Byte) {
            return bucket(((Number) v).longValue(), bound);
        }
        if (v instanceof Boolean) return v.toString();
        if (v instanceof Character) return ((Character) v) == 0 ? "c0" : "c";
        if (v instanceof Double || v instanceof Float) {
            double d = ((Number) v).doubleValue();
            if (Double.isNaN(d)) return "NaN";
            return d < 0 ? "<0" : d == 0 ? "0" : ">0";
        }
        if (v instanceof CharSequence) return "len" + bucket(((CharSequence) v).length(), bound);
        if (v instanceof Enum) return ((Enum<?>) v).name();
        if (v.getClass().isArray()) return "arr" + bucket(Array.getLength(v), bound);
        if (v instanceof Collection) return "size" + bucket(safeSize((Collection<?>) v), bound);
        if (v instanceof Map) return "size" + bucket(safeSize((Map<?, ?>) v), bound);
        return "obj";
    }

    private static int safeSize(Collection<?> c) {
        try { return c.size(); } catch (RuntimeException e) { return -1; }
    }

    private static int safeSize(Map<?, ?> m) {
        try { return m.size(); } catch (RuntimeException e) { return -1; }
    }

    // ---------------------------------------------------------------- reflection helpers

    static boolean isJdk(Class<?> c) {
        String n = c.getName();
        return n.startsWith("java.") || n.startsWith("javax.") || n.startsWith("jdk.") || n.startsWith("sun.")
            || n.startsWith("com.sun.");
    }

    static List<Field> instanceFields(Class<?> c) {
        List<Field> out = new ArrayList<>();
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            if (isJdk(k)) break;
            for (Field f : k.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || f.isSynthetic()) continue;
                try { f.setAccessible(true); out.add(f); } catch (RuntimeException ignored) { /* inaccessible */ }
            }
        }
        return out;
    }

    /** Number of distinct non-JDK objects and arrays reachable from {@code root}, excluding root itself. */
    static int internalNodes(Object root) { return internalNodes(root, null); }

    /** As {@link #internalNodes(Object)}; if {@code shape} is non-null, also counts null/true fields of internal nodes. */
    static int internalNodes(Object root, Map<String, Integer> shape) {
        IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();
        Deque<Object> work = new ArrayDeque<>();
        work.push(root);
        seen.put(root, Boolean.TRUE);
        int count = 0;
        while (!work.isEmpty() && count < MAX_NODES) {
            Object o = work.pop();
            if (o != root) {
                count++;
                if (shape != null && !o.getClass().isArray()) recordShape(o, shape);
            }
            for (Object child : children(o)) {
                if (child == null || seen.containsKey(child)) continue;
                Class<?> cc = child.getClass();
                if (cc.isArray() || !isJdk(cc)) {
                    seen.put(child, Boolean.TRUE);
                    work.push(child);
                }
            }
        }
        return count;
    }

    private static void recordShape(Object o, Map<String, Integer> shape) {
        String cn = o.getClass().getSimpleName();
        for (Field f : instanceFields(o.getClass())) {
            Class<?> t = f.getType();
            try {
                if (t == boolean.class) {
                    if (f.getBoolean(o)) shape.merge(cn + "." + f.getName() + ":true", 1, Integer::sum);
                    else shape.putIfAbsent(cn + "." + f.getName() + ":true", 0);
                } else if (!t.isPrimitive()) {
                    if (f.get(o) == null) shape.merge(cn + "." + f.getName() + ":null", 1, Integer::sum);
                    else shape.putIfAbsent(cn + "." + f.getName() + ":null", 0);
                }
            } catch (Exception ignored) { /* inaccessible */ }
        }
    }

    private static List<Object> children(Object o) {
        List<Object> out = new ArrayList<>();
        Class<?> c = o.getClass();
        if (c.isArray()) {
            if (!c.getComponentType().isPrimitive()) {
                int n = Math.min(Array.getLength(o), MAX_NODES);
                for (int i = 0; i < n; i++) out.add(Array.get(o, i));
            }
            return out;
        }
        if (isJdk(c)) return out;
        for (Field f : instanceFields(c)) {
            if (f.getType().isPrimitive()) continue;
            try { out.add(f.get(o)); } catch (Exception ignored) { /* skip */ }
        }
        return out;
    }

    /**
     * Deep structural fingerprint of an object graph (field values, array contents, and the
     * contents of JDK collections). Used to decide whether an access program is a pure observer.
     */
    public static long fingerprint(Object root) {
        IdentityHashMap<Object, Integer> ids = new IdentityHashMap<>();
        long[] h = {1125899906842597L};
        fp(root, ids, h, 0);
        return h[0];
    }

    private static void mix(long[] h, long v) { h[0] = 31 * h[0] + v; }

    private static void fp(Object o, IdentityHashMap<Object, Integer> ids, long[] h, int depth) {
        if (o == null) { mix(h, 7); return; }
        Integer seenId = ids.get(o);
        if (seenId != null) { mix(h, 1000 + seenId); return; }
        if (ids.size() > MAX_NODES || depth > 2_000) { mix(h, 3); return; }
        Class<?> c = o.getClass();
        if (o instanceof Number || o instanceof Boolean || o instanceof Character || o instanceof String
                || o instanceof Enum) {
            mix(h, o.hashCode());
            return;
        }
        ids.put(o, ids.size());
        mix(h, c.getName().hashCode());
        if (c.isArray()) {
            int n = Array.getLength(o);
            mix(h, n);
            for (int i = 0; i < n; i++) fp(Array.get(o, i), ids, h, depth + 1);
            return;
        }
        if (isJdk(c)) {
            try {
                if (o instanceof Collection) {
                    for (Object e : (Collection<?>) o) fp(e, ids, h, depth + 1);
                } else if (o instanceof Map) {
                    for (Map.Entry<?, ?> e : ((Map<?, ?>) o).entrySet()) {
                        fp(e.getKey(), ids, h, depth + 1);
                        fp(e.getValue(), ids, h, depth + 1);
                    }
                }
            } catch (RuntimeException ignored) { mix(h, 11); }
            return;
        }
        for (Field f : instanceFields(c)) {
            try {
                Object v = f.get(o);
                if (f.getType().isPrimitive()) mix(h, v.hashCode());
                else fp(v, ids, h, depth + 1);
            } catch (Exception ignored) { mix(h, 13); }
        }
    }
}
