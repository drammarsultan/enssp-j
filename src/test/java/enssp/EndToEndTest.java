package enssp;

import static org.junit.Assert.*;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.Test;
import org.junit.runner.JUnitCore;
import org.junit.runner.Result;

/** Runs every generation mode end to end on the fixture, compiles the emitted suite and runs it. */
public class EndToEndTest {

    @Test public void enssp() throws Exception { check("enssp", true); }
    @Test public void ensspWithoutReduction() throws Exception { check("enssp", false); }
    @Test public void random() throws Exception { check("random", true); }
    @Test public void ssp() throws Exception { check("ssp", true); }

    private void check(String mode, boolean reduce) throws Exception {
        Path out = Files.createTempDirectory("enssp-e2e");
        Config c = new Config();
        c.className = "enssp.fixtures.BoundedStack";
        c.classpath = "";
        c.outDir = out.toString();
        c.mode = mode;
        c.reduce = reduce;
        c.budgetSeconds = 5;
        c.seed = 7;
        Map<String, Object> r = Main.run(c);

        assertEquals(r.get("targets"), r.get("targetsCovered"));
        assertTrue((Integer) r.get("tests") > 0);
        Path test = Path.of((String) r.get("testFile"));
        assertTrue(Files.exists(test));

        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        String cp = System.getProperty("java.class.path");
        Path classes = Files.createTempDirectory("enssp-e2e-classes");
        int rc = javac.run(null, null, null, "-nowarn", "-cp", cp, "-d", classes.toString(), test.toString());
        assertEquals("generated suite must compile", 0, rc);

        try (URLClassLoader l = new URLClassLoader(new URL[] {classes.toUri().toURL()}, getClass().getClassLoader())) {
            Class<?> tc = l.loadClass("enssp.fixtures.BoundedStack_ESSP_Test");
            Result res = new JUnitCore().run(tc);
            assertEquals("generated tests must pass on the class they were generated from: " + res.getFailures(), 0, res.getFailureCount());
            assertEquals(r.get("tests"), res.getRunCount());
        }
    }

    @Test
    public void reductionNeverLosesTargetsAndShrinksTheSuite() throws Exception {
        Config c = new Config();
        c.className = "enssp.fixtures.BoundedStack";
        c.outDir = Files.createTempDirectory("enssp-red").toString();
        c.budgetSeconds = 5;
        c.seed = 3;
        Map<String, Object> r = Main.run(c);
        assertEquals(r.get("targets"), r.get("targetsCovered"));
        assertTrue((Integer) r.get("eventsInSuite") <= (Integer) r.get("reductionEventsBefore"));
        assertTrue((Integer) r.get("tests") <= (Integer) r.get("reductionSequencesBefore"));
    }

    @Test
    public void rejectsClassesWithoutUsableConstructors() {
        Config c = new Config();
        c.className = "java.lang.Math";
        c.outDir = new File(System.getProperty("java.io.tmpdir"), "enssp-none").toString();
        try {
            Main.run(c);
            fail("expected IllegalArgumentException");
        } catch (Exception e) {
            assertTrue(e instanceof IllegalArgumentException);
        }
    }
}
