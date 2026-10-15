package enssp;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;

/**
 * An object-typed argument built by {@link ObjectBuilder} (enabled with {@code --object-args}).
 * A recipe is not a value but a way to make one: every invocation of an event materialises fresh
 * objects, exactly as the generated JUnit code does with an inline {@code new ...} expression, so
 * a mutable argument is never shared between calls or tests.
 */
public final class Recipe {

    enum Kind { NEW, ARRAY, LIST, SET }

    private final Kind kind;
    private final Class<?> type;          // class to instantiate, array component type, or collection type
    private final Constructor<?> ctor;    // NEW only
    private final Object[] parts;         // constructor arguments or elements; may contain recipes
    private final String text;

    private Recipe(Kind kind, Class<?> type, Constructor<?> ctor, Object[] parts) {
        this.kind = kind;
        this.type = type;
        this.ctor = ctor;
        this.parts = parts.clone();
        this.text = render();
    }

    static Recipe newObject(Constructor<?> c, Object[] args) { return new Recipe(Kind.NEW, c.getDeclaringClass(), c, args); }
    static Recipe array(Class<?> component, Object[] elems) { return new Recipe(Kind.ARRAY, component, null, elems); }
    static Recipe list(Object[] elems) { return new Recipe(Kind.LIST, ArrayList.class, null, elems); }
    static Recipe set(Object[] elems) { return new Recipe(Kind.SET, HashSet.class, null, elems); }

    /** Builds a fresh instance; exceptions thrown by a constructor are rethrown unwrapped. */
    public Object materialize() throws Throwable {
        Object[] v = new Object[parts.length];
        for (int i = 0; i < parts.length; i++) v[i] = value(parts[i]);
        switch (kind) {
            case NEW:
                try {
                    return ctor.newInstance(v);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            case ARRAY: {
                Object a = Array.newInstance(type, v.length);
                for (int i = 0; i < v.length; i++) Array.set(a, i, v[i]);
                return a;
            }
            default: {
                Collection<Object> c = kind == Kind.LIST ? new ArrayList<>() : new HashSet<>();
                for (Object o : v) c.add(o);
                return c;
            }
        }
    }

    /** {@code o} itself, or a fresh object when {@code o} is a recipe. */
    static Object value(Object o) throws Throwable {
        return o instanceof Recipe ? ((Recipe) o).materialize() : o;
    }

    /** Java source expression that builds the same object. */
    public String javaExpr() {
        StringBuilder b = new StringBuilder();
        switch (kind) {
            case NEW: {
                b.append("new ").append(type.getCanonicalName()).append('(');
                Class<?>[] p = ctor.getParameterTypes();
                for (int i = 0; i < parts.length; i++) {
                    if (i > 0) b.append(", ");
                    b.append(ArgumentPool.literal(parts[i], p[i]));
                }
                return b.append(')').toString();
            }
            case ARRAY: {
                b.append("new ").append(type.getCanonicalName()).append("[] {");
                for (int i = 0; i < parts.length; i++) {
                    if (i > 0) b.append(", ");
                    b.append(ArgumentPool.literal(parts[i], type));
                }
                return b.append('}').toString();
            }
            default: {
                // new ArrayList<Object>(Arrays.asList(a, b)) compiles for any element type through a raw cast at the call site
                b.append("new java.util.").append(kind == Kind.LIST ? "ArrayList" : "HashSet")
                 .append("<Object>(java.util.Arrays.<Object>asList(");
                for (int i = 0; i < parts.length; i++) {
                    if (i > 0) b.append(", ");
                    b.append(ArgumentPool.literal(parts[i], Object.class));
                }
                return b.append("))").toString();
            }
        }
    }

    /** True if this recipe is a collection literal, which needs a raw cast to the parameter type. */
    boolean isCollection() { return kind == Kind.LIST || kind == Kind.SET; }

    private String render() {
        List<String> s = new ArrayList<>();
        for (Object p : parts) s.add(p instanceof Object[] ? java.util.Arrays.deepToString((Object[]) p) : String.valueOf(p));
        switch (kind) {
            case NEW: return type.getSimpleName() + "(" + String.join(", ", s) + ")";
            case ARRAY: return type.getSimpleName() + "[" + String.join(", ", s) + "]";
            case LIST: return "List[" + String.join(", ", s) + "]";
            default: return "Set[" + String.join(", ", s) + "]";
        }
    }

    @Override public String toString() { return text; }
}
