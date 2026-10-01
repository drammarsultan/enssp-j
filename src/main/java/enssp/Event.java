package enssp;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;

/**
 * An event in the SSP sense: one invocation of an access program (a public method or
 * constructor of the module under test) with fixed concrete arguments.
 */
public final class Event {

    private final Executable exec;
    private final Object[] args;
    private final String signature;
    private final String key;

    public Event(Executable exec, Object[] args) {
        this.exec = exec;
        this.args = args.clone();
        this.signature = signatureOf(exec);
        this.key = signature + Arrays.deepToString(args);
    }

    static String signatureOf(Executable e) {
        StringBuilder b = new StringBuilder(e instanceof Constructor ? "<init>" : e.getName()).append('(');
        Class<?>[] p = e.getParameterTypes();
        for (int i = 0; i < p.length; i++) {
            if (i > 0) b.append(',');
            b.append(p[i].getSimpleName());
        }
        return b.append(')').toString();
    }

    public boolean isConstructor() { return exec instanceof Constructor; }

    /** Access-program signature, e.g. {@code put(Object,Object)}. */
    public String signature() { return signature; }

    /** Signature plus concrete arguments; unique per event. */
    public String key() { return key; }

    public Class<?> returnType() {
        return exec instanceof Method ? ((Method) exec).getReturnType() : exec.getDeclaringClass();
    }

    public Executable executable() { return exec; }

    /** Invokes the event; checked and unchecked exceptions thrown by the module are rethrown unwrapped. */
    public Object invoke(Object target) throws Throwable {
        try {
            if (exec instanceof Constructor) return ((Constructor<?>) exec).newInstance(args);
            return ((Method) exec).invoke(target, args);
        } catch (InvocationTargetException ite) {
            throw ite.getCause();
        }
    }

    /** Java source for this call on variable {@code receiver} (ignored for constructors). */
    public String javaCall(String receiver) {
        StringBuilder b = new StringBuilder();
        if (exec instanceof Constructor) {
            b.append("new ").append(exec.getDeclaringClass().getCanonicalName());
        } else {
            b.append(receiver).append('.').append(exec.getName());
        }
        b.append('(');
        Class<?>[] p = exec.getParameterTypes();
        for (int i = 0; i < args.length; i++) {
            if (i > 0) b.append(", ");
            b.append(ArgumentPool.literal(args[i], p[i]));
        }
        return b.append(')').toString();
    }

    @Override
    public String toString() { return key; }
}
