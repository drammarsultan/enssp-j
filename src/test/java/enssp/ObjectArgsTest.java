package enssp;

import static org.junit.Assert.*;

import enssp.fixtures.BoundedStack;
import enssp.fixtures.Planner;
import java.util.Collection;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import org.junit.Test;

public class ObjectArgsTest {

    private static Set<String> names(EventAlphabet a) {
        Set<String> s = new HashSet<>();
        for (Event e : a.events) s.add(e.signature());
        return s;
    }

    @Test
    public void offByDefaultObjectParametersAreSkippedAsIn100() {
        EventAlphabet a = EventAlphabet.build(Planner.class, ArgumentPool.defaults(), 12, new Random(1));
        assertEquals(Set.of("count()"), names(a));
    }

    @Test
    public void builderReachesObjectArrayAndCollectionParameters() throws Throwable {
        ArgumentPool pool = ArgumentPool.defaults();
        pool.enableObjects(new ObjectBuilder(pool, 1));
        EventAlphabet a = EventAlphabet.build(Planner.class, pool, 12, 5, new Random(1));
        assertTrue(names(a).containsAll(Set.of("add(Task)", "addAll(Collection)", "schedule(Task,Collection,boolean,int)", "count()")));
        for (Event e : a.events) {
            Planner p = new Planner();
            try { e.invoke(p); } catch (IllegalArgumentException ok) { /* module may reject */ }
            if (e.signature().equals("addAll(Collection)")) {
                assertTrue(e.javaCall("cut").contains("(java.util.Collection) new java.util.ArrayList<Object>("));
            }
        }
    }

    @Test
    public void recipesMaterialiseFreshObjects() throws Throwable {
        ArgumentPool pool = ArgumentPool.defaults();
        ObjectBuilder b = new ObjectBuilder(pool, 1);
        for (Object v : b.values(Collection.class, 0)) {
            Recipe r = (Recipe) v;
            assertNotSame(r.materialize(), r.materialize());
        }
        for (Object v : b.values(Planner.Task.class, 0)) {
            assertTrue(((Planner.Task) ((Recipe) v).materialize()).priority() >= 0);  // rejected candidates are dropped
        }
    }

    @Test
    public void builtInTypesKeepTheir100Values() {
        ArgumentPool pool = ArgumentPool.defaults();
        pool.enableObjects(new ObjectBuilder(pool, 1));
        assertEquals(ArgumentPool.defaults().valuesFor(int.class), pool.valuesFor(int.class, int.class));
        EventAlphabet off = EventAlphabet.build(BoundedStack.class, ArgumentPool.defaults(), 12, new Random(4));
        EventAlphabet on = EventAlphabet.build(BoundedStack.class, pool, 12, 5, new Random(4));
        assertEquals(off.events.toString(), on.events.toString());
    }
}
