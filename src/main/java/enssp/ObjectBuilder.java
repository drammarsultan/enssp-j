package enssp;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Object-argument builder (enSSP-J 1.1.0, off by default, enabled with {@code --object-args}).
 *
 * enSSP-J 1.0.0 can only pass primitives, boxed values, strings and enum constants, so an access
 * program that takes an object (for example {@code SimplexSolver.optimize(LinearObjectiveFunction,
 * Collection<LinearConstraint>, GoalType, boolean)}) is never called. The builder supplies such
 * arguments as {@link Recipe}s:
 * <ul>
 *   <li>arrays of any buildable component type: lengths 0, 1 and 2;</li>
 *   <li>{@code Collection}, {@code List}, {@code Iterable}, {@code Set} (and {@code ArrayList},
 *       {@code HashSet}) of a buildable element type taken from the generic signature: sizes 0, 1, 2;</li>
 *   <li>a concrete public class through any of its public constructors whose parameters are
 *       themselves buildable, up to {@link #MAX_DEPTH} levels of nesting. Candidates whose
 *       constructor throws are discarded.</li>
 * </ul>
 * Interfaces and abstract classes other than the collection types above are not instantiated.
 * Choices are drawn from a generator seeded by the run's seed, so a run is reproducible.
 */
public final class ObjectBuilder {

    static final int MAX_DEPTH = 2;          // nesting of constructed objects inside an argument
    static final int PER_TYPE = 6;           // candidate values kept per type

    private final ArgumentPool pool;
    private final Random rng;
    private final Map<String, List<Object>> cache = new HashMap<>();

    public ObjectBuilder(ArgumentPool pool, long seed) {
        this.pool = pool;
        this.rng = new Random(seed ^ 0x0B1EC7A5L);  // own stream: does not disturb the search's generator
    }

    public boolean supports(Type t) { return !values(t, 0).isEmpty(); }

    /** Candidate argument values (plain values or recipes) for a parameter of generic type {@code t}. */
    public List<Object> values(Type t, int depth) {
        String key = t.getTypeName() + "@" + depth;
        List<Object> v = cache.get(key);
        if (v == null) {
            cache.put(key, new ArrayList<>());  // guards against recursive types while computing
            v = compute(t, depth);
            cache.put(key, v);
        }
        return v;
    }

    private List<Object> compute(Type t, int depth) {
        Class<?> raw = raw(t);
        List<Object> out = new ArrayList<>();
        if (raw == null) return out;
        if (pool.supports(raw)) return pool.valuesFor(raw);
        if (raw.isArray()) {
            List<Object> elems = values(raw.getComponentType(), depth);
            if (elems.isEmpty()) return out;
            out.add(Recipe.array(raw.getComponentType(), new Object[0]));
            for (int i = 0; i < 2 && out.size() < PER_TYPE; i++) {
                out.add(Recipe.array(raw.getComponentType(), new Object[] {pick(elems)}));
                out.add(Recipe.array(raw.getComponentType(), new Object[] {pick(elems), pick(elems)}));
            }
            return dedupe(out);
        }
        if (raw == Collection.class || raw == List.class || raw == Iterable.class || raw == ArrayList.class
                || raw == Set.class || raw == HashSet.class) {
            if (depth >= MAX_DEPTH) return out;
            List<Object> elems = values(elementType(t), depth + 1);
            if (elems.isEmpty()) return out;
            boolean set = raw == Set.class || raw == HashSet.class;
            out.add(set ? Recipe.set(new Object[0]) : Recipe.list(new Object[0]));
            for (int i = 0; i < 2 && out.size() < PER_TYPE; i++) {
                Object[] one = {pick(elems)};
                Object[] two = {pick(elems), pick(elems)};
                out.add(set ? Recipe.set(one) : Recipe.list(one));
                out.add(set ? Recipe.set(two) : Recipe.list(two));
            }
            return dedupe(out);
        }
        if (depth >= MAX_DEPTH || raw.isInterface() || Modifier.isAbstract(raw.getModifiers())
                || !Modifier.isPublic(raw.getModifiers()) || raw.getCanonicalName() == null
                || (raw.getEnclosingClass() != null && !Modifier.isStatic(raw.getModifiers()))) {
            return out;
        }
        List<Constructor<?>> ctors = new ArrayList<>(Arrays.asList(raw.getConstructors()));
        ctors.sort(Comparator.comparing(Constructor::toGenericString));
        List<Object> candidates = new ArrayList<>();
        for (Constructor<?> c : ctors) {
            Type[] p = c.getGenericParameterTypes();
            if (p.length != c.getParameterCount()) continue;  // inner-class or enum synthetic parameters
            List<List<Object>> domains = new ArrayList<>();
            boolean ok = true;
            for (Type pt : p) {
                List<Object> d = values(pt, depth + 1);
                if (d.isEmpty()) { ok = false; break; }
                domains.add(d);
            }
            if (!ok) continue;
            for (int k = 0; k < 4; k++) {
                Object[] args = new Object[p.length];
                for (int i = 0; i < args.length; i++) args[i] = pick(domains.get(i));
                candidates.add(Recipe.newObject(c, args));
            }
        }
        for (Object r : dedupe(candidates)) {
            if (out.size() >= PER_TYPE) break;
            try {
                ((Recipe) r).materialize();
                out.add(r);
            } catch (Throwable rejected) { /* constructor rejects these arguments */ }
        }
        return out;
    }

    private Object pick(List<Object> l) { return l.get(rng.nextInt(l.size())); }

    private static List<Object> dedupe(List<Object> l) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        for (Object o : l) m.putIfAbsent(String.valueOf(o) + "|" + (o == null ? "" : o.getClass().getName()), o);
        return new ArrayList<>(m.values());
    }

    static Class<?> raw(Type t) {
        if (t instanceof Class) return (Class<?>) t;
        if (t instanceof ParameterizedType) return raw(((ParameterizedType) t).getRawType());
        if (t instanceof WildcardType) {
            Type[] up = ((WildcardType) t).getUpperBounds();
            return up.length == 1 ? raw(up[0]) : null;
        }
        if (t instanceof java.lang.reflect.GenericArrayType) {
            Class<?> c = raw(((java.lang.reflect.GenericArrayType) t).getGenericComponentType());
            return c == null ? null : java.lang.reflect.Array.newInstance(c, 0).getClass();
        }
        if (t instanceof java.lang.reflect.TypeVariable) {
            Type[] b = ((java.lang.reflect.TypeVariable<?>) t).getBounds();
            return b.length == 1 ? raw(b[0]) : null;
        }
        return null;
    }

    private static Type elementType(Type t) {
        if (t instanceof ParameterizedType) {
            Type[] a = ((ParameterizedType) t).getActualTypeArguments();
            if (a.length == 1) return a[0];
        }
        return Object.class;
    }

    /** Types the builder used, for the run report. */
    public Set<String> builtTypes() {
        Set<String> s = new LinkedHashSet<>();
        for (Map.Entry<String, List<Object>> e : cache.entrySet()) {
            for (Object o : e.getValue()) if (o instanceof Recipe) { s.add(e.getKey().replaceAll("@\\d+$", "")); break; }
        }
        return s;
    }
}
