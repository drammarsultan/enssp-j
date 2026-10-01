package enssp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Supplies concrete argument values for access programs (methods) and constructors.
 * Reference parameters typed as {@code Object}, {@code Comparable}, {@code Number} or
 * {@code Serializable} receive boxed integers, which makes generic containers testable.
 */
public final class ArgumentPool {

    private final int[] ints;
    private final String[] strings;

    public ArgumentPool(int[] ints, String[] strings) {
        this.ints = ints.clone();
        this.strings = strings.clone();
    }

    public static ArgumentPool defaults() {
        return new ArgumentPool(new int[] {-1, 0, 1, 2, 3, 5}, new String[] {"", "a", "b"});
    }

    public boolean supports(Class<?> t) {
        if (t == void.class) return false;
        if (t.isPrimitive()) return true;
        if (t.isEnum()) return t.getEnumConstants() != null && t.getEnumConstants().length > 0;
        return t == Integer.class || t == Long.class || t == Short.class || t == Byte.class
            || t == Boolean.class || t == Character.class || t == Double.class || t == Float.class
            || t == String.class || t == CharSequence.class || t == Object.class
            || t == Comparable.class || t == Number.class || t == java.io.Serializable.class;
    }

    public List<Object> valuesFor(Class<?> t) {
        List<Object> v = new ArrayList<>();
        if (t == int.class || t == Integer.class || t == Object.class || t == Comparable.class
                || t == Number.class || t == java.io.Serializable.class) {
            for (int i : ints) v.add(i);
        } else if (t == long.class || t == Long.class) {
            for (int i : ints) v.add((long) i);
        } else if (t == short.class || t == Short.class) {
            for (int i : ints) v.add((short) i);
        } else if (t == byte.class || t == Byte.class) {
            for (int i : ints) v.add((byte) i);
        } else if (t == boolean.class || t == Boolean.class) {
            v.add(false); v.add(true);
        } else if (t == char.class || t == Character.class) {
            v.add('a'); v.add('b');
        } else if (t == double.class || t == Double.class) {
            v.add(-1.0); v.add(0.0); v.add(1.5);
        } else if (t == float.class || t == Float.class) {
            v.add(-1.0f); v.add(0.0f); v.add(1.5f);
        } else if (t == String.class || t == CharSequence.class) {
            Collections.addAll(v, (Object[]) strings);
        } else if (t.isEnum()) {
            Collections.addAll(v, t.getEnumConstants());
        }
        return v;
    }

    /** Java source literal for {@code value} passed to a parameter declared as {@code declared}. */
    public static String literal(Object value, Class<?> declared) {
        String raw = rawLiteral(value);
        if (declared.isPrimitive() || value == null) return raw;
        if (declared == String.class && value instanceof String) return raw;
        if (value instanceof Enum) return raw;
        // Explicit cast selects the intended overload (e.g. remove(Object) vs remove(int)).
        return "(" + declared.getCanonicalName() + ") " + raw;
    }

    public static String rawLiteral(Object v) {
        if (v == null) return "null";
        if (v instanceof Integer) {
            int i = (Integer) v;
            return i < 0 ? "(" + i + ")" : Integer.toString(i);
        }
        if (v instanceof Long) return "(" + v + "L)";
        if (v instanceof Short) return "((short) " + v + ")";
        if (v instanceof Byte) return "((byte) " + v + ")";
        if (v instanceof Boolean) return v.toString();
        if (v instanceof Character) return charLiteral((Character) v);
        if (v instanceof Double) return doubleLiteral((Double) v);
        if (v instanceof Float) return floatLiteral((Float) v);
        if (v instanceof String) return stringLiteral((String) v);
        if (v instanceof Enum) return ((Enum<?>) v).getDeclaringClass().getCanonicalName() + "." + ((Enum<?>) v).name();
        throw new IllegalArgumentException("No literal for " + v.getClass());
    }

    static String doubleLiteral(double d) {
        if (Double.isNaN(d)) return "Double.NaN";
        if (Double.isInfinite(d)) return d > 0 ? "Double.POSITIVE_INFINITY" : "Double.NEGATIVE_INFINITY";
        return "(" + d + ")";
    }

    static String floatLiteral(float f) {
        if (Float.isNaN(f)) return "Float.NaN";
        if (Float.isInfinite(f)) return f > 0 ? "Float.POSITIVE_INFINITY" : "Float.NEGATIVE_INFINITY";
        return "(" + f + "f)";
    }

    static String charLiteral(char c) {
        switch (c) {
            case '\'': return "'\\''";
            case '\\': return "'\\\\'";
            case '\n': return "'\\n'";
            case '\r': return "'\\r'";
            case '\t': return "'\\t'";
            default:
                if (c < 32 || c > 126) return String.format("'\\u%04x'", (int) c);
                return "'" + c + "'";
        }
    }

    static String stringLiteral(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 32 || c > 126) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        return b.append('"').toString();
    }
}
