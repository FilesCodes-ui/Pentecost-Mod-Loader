package khi.pentecost.crash;

import khi.pentecost.adapter.forge.ForgeRedirects;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.util.CheckClassAdapter;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrashDetailsTest {
    @Test
    void startupReportHasEverySection() {
        Throwable t = new IllegalStateException("outer",
                new NullPointerException("Cannot invoke \"java.util.Collection.addAll(java.util.Collection)\" because \"drops\" is null"));
        String text = CrashReport.build(t, List.of(), null);
        for (String part : new String[]{"WHAT BROKE", "MOST LIKELY CAUSE", "WHAT TO TRY", "PENTECOST\n", "MACHINE AND JAVA",
                "MODS (", "GAME SETTINGS", "ERRORS EARLIER IN THIS RUN", "EARLIER CRASH REPORTS", "FULL TRACE"}) {
            assertTrue(text.contains(part), "missing section: " + part);
        }
        assertTrue(text.contains("because \"drops\" is null"));
    }

    @Test
    void sessionSecretsAreHidden() {
        String masked = CrashDetails.maskSecrets("--username Dev --accessToken abc123secret --uuid 1234-5678 --width 800");
        assertFalse(masked.contains("abc123secret"));
        assertFalse(masked.contains("1234-5678"));
        assertTrue(masked.contains("--username Dev"));
        assertTrue(masked.contains("--width 800"));
    }

    @Test
    void decorateNeverThrowsAndKeepsTheOriginalText() {
        String original = "---- Minecraft Crash Report ----\n// comment\n\nTime: now\nDescription: x\n";
        String out = CrashDetails.decorate(new Object(), original);
        assertTrue(out.contains("Time: now"));
        assertTrue(out.contains(CrashDetails.MARK));
        assertTrue(out.indexOf(CrashDetails.MARK) < out.indexOf("Time: now"));
        assertEquals(out, CrashDetails.decorate(new Object(), out), "already decorated text stays as it is");
    }

    @Test
    void patchedGameCrashReportVerifies() throws Exception {
        Path jar = Path.of("cache/remapped/client-1.20.1-47.4.10-forge-named.jar");
        Assumptions.assumeTrue(Files.isRegularFile(jar), "game jar not present");
        byte[] original;
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            original = zip.getInputStream(zip.getEntry("net/minecraft/CrashReport.class")).readAllBytes();
        }
        byte[] patched = ForgeRedirects.transformer().transform("net.minecraft.CrashReport", original);
        assertNotSame(original, patched, "the patch did not apply");
        StringWriter problems = new StringWriter();
        try (URLClassLoader cl = new URLClassLoader(new URL[]{jar.toUri().toURL()}, getClass().getClassLoader())) {
            CheckClassAdapter.verify(new ClassReader(patched), cl, false, new PrintWriter(problems));
        }
        assertEquals("", problems.toString());
    }
}
