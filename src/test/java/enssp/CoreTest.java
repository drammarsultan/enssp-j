package enssp;

import static org.junit.Assert.*;

import enssp.fixtures.BoundedStack;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.Test;

public class CoreTest {

    @Test
    public void bucketsAbstractNumbers() {
        assertEquals("<0", StateAbstraction.bucket(-4, 3));
        assertEquals("0", StateAbstraction.bucket(0, 3));
        assertEquals("2", StateAbstraction.bucket(2, 3));
        assertEquals(">=3", StateAbstraction.bucket(3, 3));
        assertEquals(">=3", StateAbstraction.bucket(99, 3));
    }

    @Test
    public void abstractsValuesByKind() {
        assertEquals("null", StateAbstraction.abstractValue(null, 3));
        assertEquals("true", StateAbstraction.abstractValue(true, 3));
        assertEquals("len1", StateAbstraction.abstractValue("a", 3));
        assertEquals("arr2", StateAbstraction.abstractValue(new int[2], 3));
        assertEquals("size>=3", StateAbstraction.abstractValue(Arrays.asList(1, 2, 3, 4), 3));
        assertEquals("obj", StateAbstraction.abstractValue(new Object(), 3));
    }

    @Test
    public void literalsSelectTheDeclaredOverload() {
        assertEquals("(java.lang.Object) 3", ArgumentPool.literal(3, Object.class));
        assertEquals("(-1)", ArgumentPool.literal(-1, int.class));
        assertEquals("(3L)", ArgumentPool.literal(3L, long.class));
        assertEquals("\"a\\\"b\"", ArgumentPool.literal("a\"b", String.class));
        assertEquals("'\\n'", ArgumentPool.rawLiteral('\n'));
    }

    @Test
    public void fingerprintDetectsStructuralChange() {
        BoundedStack s = new BoundedStack();
        long before = StateAbstraction.fingerprint(s);
        s.size();
        assertEquals(before, StateAbstraction.fingerprint(s));
        s.push(1);
        assertNotEquals(before, StateAbstraction.fingerprint(s));
    }

    @Test
    public void alphabetFindsAccessProgramsAndObservers() {
        EventAlphabet a = EventAlphabet.build(BoundedStack.class, ArgumentPool.defaults(), 12, new Random(1));
        assertEquals(6, a.accessPrograms);      // push, pop, peek, isEmpty, isFull, size
        List<String> obs = a.observers.stream().map(Event::signature).sorted().collect(java.util.stream.Collectors.toList());
        assertEquals(Arrays.asList("isEmpty()", "isFull()", "peek()", "size()"), obs);
        // BoundedStack(int) rejects capacities <= 0; those constructor events are dropped.
        assertTrue(a.constructors.stream().noneMatch(e -> e.key().contains("[-1]") || e.key().contains("[0]")));
    }

    @Test
    public void systemIdsAreStableAndRedundantStatesCounted() {
        EventAlphabet a = EventAlphabet.build(BoundedStack.class, ArgumentPool.defaults(), 12, new Random(1));
        StateAbstraction abs = new StateAbstraction(BoundedStack.class, a.observers, 3);
        try (SequenceExecutor ex = new SequenceExecutor(abs, 3, 1000)) {
            Event ctor = a.constructors.stream().filter(e -> e.key().equals("<init>()[]")).findFirst().get();
            Event push1 = a.events.stream().filter(e -> e.key().equals("push(int)[1]")).findFirst().get();
            Event pop = a.events.stream().filter(e -> e.key().equals("pop()[]")).findFirst().get();
            SequenceExecutor.Trace t = ex.execute(Arrays.asList(ctor, push1, pop, push1));
            assertTrue(t.valid);
            int[] ids = t.stateIds();
            assertEquals(ids[0], ids[2]);           // push then pop returns to the empty state
            assertEquals(ids[1], ids[3]);
            assertEquals(2, Reducer.redundantStates(t));
            SequenceExecutor.Trace t2 = ex.execute(Arrays.asList(ctor, pop));
            assertEquals("ex:IllegalStateException|same", t2.steps.get(0).responseClass());
        }
    }
}
