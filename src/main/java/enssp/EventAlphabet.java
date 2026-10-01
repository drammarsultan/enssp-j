package enssp;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Identifies the access programs of the module under test and instantiates them as events.
 * Also detects pure observers, which become additional state variables.
 */
public final class EventAlphabet {

    private static final Set<String> EXCLUDED = new HashSet<>(Arrays.asList(
        "equals", "hashCode", "toString", "clone", "finalize", "getClass", "notify", "notifyAll", "wait",
        "iterator", "listIterator", "spliterator", "stream", "parallelStream", "forEach", "toArray"));

    public final List<Event> constructors;
    public final List<Event> events;
    public final List<Event> observers;
    public final int accessPrograms;

    private EventAlphabet(List<Event> constructors, List<Event> events, List<Event> observers, int accessPrograms) {
        this.constructors = constructors;
        this.events = events;
        this.observers = observers;
        this.accessPrograms = accessPrograms;
    }

    public static EventAlphabet build(Class<?> cut, ArgumentPool pool, int maxArgCombos, Random rng) {
        if (cut.isInterface() || Modifier.isAbstract(cut.getModifiers())) {
            throw new IllegalArgumentException(cut.getName() + " is abstract; enSSP-J needs a concrete class");
        }
        List<Constructor<?>> ctors = new ArrayList<>(Arrays.asList(cut.getConstructors()));
        ctors.sort(Comparator.comparing(Constructor::toGenericString));
        List<Event> ctorEvents = new ArrayList<>();
        for (Constructor<?> c : ctors) {
            if (!supported(c, pool)) continue;
            for (Event e : instantiate(c, pool, maxArgCombos, rng)) {
                try { e.invoke(null); ctorEvents.add(e); } catch (Throwable ignored) { /* rejects these arguments */ }
            }
        }
        if (ctorEvents.isEmpty()) {
            throw new IllegalArgumentException(cut.getName() + " has no public constructor with supported parameter types");
        }

        List<Method> methods = new ArrayList<>();
        for (Method m : cut.getMethods()) {
            if (Modifier.isStatic(m.getModifiers()) || m.isBridge() || m.isSynthetic()) continue;
            if (m.getDeclaringClass() == Object.class || EXCLUDED.contains(m.getName())) continue;
            if (!supported(m, pool) || m.getParameterCount() > 3) continue;
            methods.add(m);
        }
        methods.sort(Comparator.comparing(Method::toGenericString));

        List<Event> events = new ArrayList<>();
        for (Method m : methods) events.addAll(instantiate(m, pool, maxArgCombos, rng));
        if (events.isEmpty()) {
            throw new IllegalArgumentException(cut.getName() + " has no public instance method with supported parameter types");
        }

        List<Event> observers = detectObservers(ctorEvents, events, methods, rng);
        return new EventAlphabet(ctorEvents, events, observers, methods.size());
    }

    private static boolean supported(Executable e, ArgumentPool pool) {
        for (Class<?> p : e.getParameterTypes()) if (!pool.supports(p)) return false;
        return true;
    }

    /** All argument combinations, or a seeded random sample of {@code max} of them. */
    private static List<Event> instantiate(Executable e, ArgumentPool pool, int max, Random rng) {
        Class<?>[] p = e.getParameterTypes();
        List<List<Object>> domains = new ArrayList<>();
        long total = 1;
        for (Class<?> t : p) {
            List<Object> d = pool.valuesFor(t);
            domains.add(d);
            total *= d.size();
        }
        List<Event> out = new ArrayList<>();
        if (total <= max) {
            int[] idx = new int[p.length];
            for (long n = 0; n < total; n++) {
                Object[] args = new Object[p.length];
                for (int i = 0; i < p.length; i++) args[i] = domains.get(i).get(idx[i]);
                out.add(new Event(e, args));
                for (int i = p.length - 1; i >= 0; i--) {
                    if (++idx[i] < domains.get(i).size()) break;
                    idx[i] = 0;
                }
            }
        } else {
            Set<String> seen = new HashSet<>();
            int attempts = 0;
            while (out.size() < max && attempts++ < max * 20) {
                Object[] args = new Object[p.length];
                for (int i = 0; i < p.length; i++) args[i] = domains.get(i).get(rng.nextInt(domains.get(i).size()));
                if (seen.add(Arrays.deepToString(args))) out.add(new Event(e, args));
            }
        }
        return out;
    }

    /**
     * A zero-argument, non-void access program is an observer if, over a sample of randomly
     * reached states, calling it never changes the deep structure of the object.
     */
    private static List<Event> detectObservers(List<Event> ctors, List<Event> events, List<Method> methods, Random rng) {
        List<Event> candidates = new ArrayList<>();
        for (Event e : events) {
            Method m = (Method) e.executable();
            if (m.getParameterCount() == 0 && m.getReturnType() != void.class) candidates.add(e);
        }
        Set<Event> impure = new HashSet<>();
        for (int sample = 0; sample < 40 && impure.size() < candidates.size(); sample++) {
            Object obj;
            try {
                obj = ctors.get(rng.nextInt(ctors.size())).invoke(null);
                int len = rng.nextInt(8);
                for (int i = 0; i < len; i++) {
                    try { events.get(rng.nextInt(events.size())).invoke(obj); } catch (Throwable ignored) { /* exceptional outcome */ }
                }
            } catch (Throwable t) {
                continue;
            }
            for (Event c : candidates) {
                if (impure.contains(c)) continue;
                long before = StateAbstraction.fingerprint(obj);
                try { c.invoke(obj); } catch (Throwable ignored) { /* exceptional outcome is still observable */ }
                if (StateAbstraction.fingerprint(obj) != before) impure.add(c);
            }
        }
        List<Event> observers = new ArrayList<>();
        for (Event c : candidates) if (!impure.contains(c)) observers.add(c);
        return observers;
    }
}
