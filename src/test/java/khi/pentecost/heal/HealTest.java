package khi.pentecost.heal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HealTest {
    @org.junit.jupiter.api.BeforeAll
    static void quiet() {
        Heal.quiet = true;
    }

    private static final class Loader extends ClassLoader {
        Class<?> define(String name, byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }

    @Test
    void missingLoaderExceptionGetsAWorkingStandIn() throws Exception {
        String name = "net.fabricmc.loader.api.SomeMissingException";
        byte[] bytes = Heal.stubFor(name);
        assertNotNull(bytes);
        Class<?> c = new Loader().define(name, bytes);
        assertTrue(Exception.class.isAssignableFrom(c));
        Exception e = (Exception) c.getConstructor(String.class, Throwable.class)
                .newInstance("text", new RuntimeException("cause"));
        assertEquals("text", e.getMessage());
        assertEquals("cause", e.getCause().getMessage());
        assertNotNull(c.getConstructor().newInstance());
        assertNotNull(c.getConstructor(String.class).newInstance("x"));
        assertNotNull(c.getConstructor(Throwable.class).newInstance(new RuntimeException()));
    }

    @Test
    void otherMissingClassesAreNotInvented() {
        assertNull(Heal.stubFor("net.fabricmc.loader.api.SomeMissingInterface"));
        assertNull(Heal.stubFor("com.example.Other"));
        assertNull(Heal.stubFor("com.example.OtherException"));
    }

    @Test
    void failuresAreClassified() {
        assertTrue(Heal.kind(new NoClassDefFoundError("net/fabricmc/loader/api/Thing")).contains("loader class"));
        assertTrue(Heal.kind(new RuntimeException("x", new ClassCastException("a cannot be cast to Holder$Reference")))
                .contains("registry"));
        assertTrue(Heal.kind(new NullPointerException("IModFileInfo forgespi")).contains("config"));
        assertTrue(Heal.kind(new IllegalStateException("boom")).startsWith("mod error"));
    }
}
