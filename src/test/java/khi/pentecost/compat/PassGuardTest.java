package khi.pentecost.compat;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PassGuardTest {
    private static final AtomicBoolean PORTAL = new AtomicBoolean();
    private static byte[] original;
    private static byte[] patched;

    private static final class Loader extends ClassLoader {
        Class<?> define(String name, byte[] b) {
            return defineClass(name, b, 0, b.length); // runs the bytecode verifier, so bad frames fail here
        }
    }

    @BeforeAll
    static void build(@TempDir Path dir) throws Exception {
        Path src = dir.resolve("guardtest").resolve("Target.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, """
                package guardtest;
                public class Target {
                    public static int calls;
                    public static boolean flag() { calls++; return true; }
                    public static void bump() { calls++; }
                    public static long big(long a) { calls++; return a + 1; }
                    public static String name() { calls++; return "real"; }
                    public static double dbl() { calls++; return 2.5; }
                    public static int spin(int n) { while (true) { if (n > 0) n--; else return n + 7; } }
                    public static int branchy(int n) { if (n > 1) { return n * 2; } else { return -n; } }
                    public static boolean untouched() { return true; }
                    public Target() { calls += 100; }
                }
                """);
        int rc = ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", dir.toString(), src.toString());
        assertEquals(0, rc, "test class must compile");
        original = Files.readAllBytes(dir.resolve("guardtest").resolve("Target.class"));

        PortalPass.detector = PORTAL::get;
        PassGuard.addRule("guardtest.Target#flag()Z=false");
        PassGuard.addRule("guardtest.Target#bump");
        PassGuard.addRule("guardtest.Target#big(J)J");
        PassGuard.addRule("guardtest.Target#name");
        PassGuard.addRule("guardtest.Target#dbl");
        PassGuard.addRule("guardtest.Target#spin(I)I=5");
        PassGuard.addRule("guardtest.Target#branchy=9");
        PassGuard.addRule("guardtest.Target#<init>");          // constructors must be ignored
        PassGuard.addRule("guardtest.Target#doesNotExist");    // must be reported, not crash
        PassGuard.addRule("garbage line without a hash");      // must be ignored
        patched = PassGuard.transformer().transform("guardtest.Target", original);
    }

    private static Object call(Class<?> c, String name, Class<?>[] types, Object... args) throws Exception {
        Method m = c.getMethod(name, types);
        return m.invoke(null, args);
    }

    @Test
    void normalFrameBehavesExactlyLikeTheOriginal() throws Exception {
        PORTAL.set(false);
        Class<?> c = new Loader().define("guardtest.Target", patched);
        c.getField("calls").setInt(null, 0);
        assertEquals(true, call(c, "flag", new Class<?>[0]));
        call(c, "bump", new Class<?>[0]);
        assertEquals(8L, call(c, "big", new Class<?>[]{long.class}, 7L));
        assertEquals("real", call(c, "name", new Class<?>[0]));
        assertEquals(2.5, call(c, "dbl", new Class<?>[0]));
        assertEquals(7, call(c, "spin", new Class<?>[]{int.class}, 3));
        assertEquals(10, call(c, "branchy", new Class<?>[]{int.class}, 5));
        assertEquals(5, c.getField("calls").getInt(null)); // flag, bump, big, name, dbl
    }

    @Test
    void duringAPortalPassGuardedMethodsReturnDefaultsAndDoNotRun() throws Exception {
        Class<?> c = new Loader().define("guardtest.Target", patched);
        c.getField("calls").setInt(null, 0);
        PORTAL.set(true);
        try {
            assertEquals(false, call(c, "flag", new Class<?>[0]));
            call(c, "bump", new Class<?>[0]);
            assertEquals(0L, call(c, "big", new Class<?>[]{long.class}, 7L));
            assertNull(call(c, "name", new Class<?>[0]));
            assertEquals(0.0, call(c, "dbl", new Class<?>[0]));
            assertEquals(5, call(c, "spin", new Class<?>[]{int.class}, 3));
            assertEquals(9, call(c, "branchy", new Class<?>[]{int.class}, 5));
            assertEquals(0, c.getField("calls").getInt(null), "no guarded body may run");
            assertEquals(true, call(c, "untouched", new Class<?>[0]), "methods without a rule are untouched");
        } finally {
            PORTAL.set(false);
        }
    }

    @Test
    void constructorsAndUnmatchedRulesAreHandledSafely() throws Exception {
        PORTAL.set(true);
        try {
            Class<?> c = new Loader().define("guardtest.Target", patched);
            c.getField("calls").setInt(null, 0);
            c.getConstructor().newInstance();
            assertEquals(100, c.getField("calls").getInt(null), "constructor still runs in a portal pass");
        } finally {
            PORTAL.set(false);
        }
        String report = PassGuard.describe().toString();
        assertTrue(report.contains("no method matched"), report);
    }

    @Test
    void classesWithoutRulesAreReturnedAsTheSameBytes() {
        byte[] other = new byte[]{1, 2, 3};
        assertArrayEquals(other, PassGuard.transformer().transform("some.Other", other));
        assertFalse(patched.length == original.length);
    }

    @Test
    void builtInAstrocraftRulesAreRegistered() {
        String report = PassGuard.describe().toString();
        assertTrue(report.contains("Astrocraft#shouldReloadConfig()Z=false"), report);
        assertTrue(report.contains("Astrocraft#shouldReloadDynamics()Z=false"), report);
    }
}
