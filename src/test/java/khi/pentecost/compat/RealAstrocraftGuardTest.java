package khi.pentecost.compat;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs the built-in Astrocraft rules against the real class from the installed jar, if there is one. */
class RealAstrocraftGuardTest {
    @Test
    void realAstrocraftClassIsPatched() throws Exception {
        Path cache = Path.of("cache", "mods-v10");
        Assumptions.assumeTrue(Files.isDirectory(cache));
        Path jar;
        try (var s = Files.list(cache)) {
            jar = s.filter(f -> f.getFileName().toString().contains("astrocraft") && f.toString().endsWith(".jar"))
                    .findFirst().orElse(null);
        }
        Assumptions.assumeTrue(jar != null);
        byte[] bytes;
        try (var zf = new java.util.zip.ZipFile(jar.toFile())) {
            bytes = zf.getInputStream(zf.getEntry("mod/lwhrvw/astrocraft/Astrocraft.class")).readAllBytes();
        }
        byte[] out = PassGuard.transformer().transform("mod.lwhrvw.astrocraft.Astrocraft", bytes);
        assertFalse(Arrays.equals(bytes, out), "Astrocraft.class must be changed");
        String report = PassGuard.describe().toString();
        assertTrue(report.contains("rule=mod.lwhrvw.astrocraft.Astrocraft#shouldReloadConfig()Z=false, origin=built in, applied=true"), report);
        assertTrue(report.contains("rule=mod.lwhrvw.astrocraft.Astrocraft#shouldReloadDynamics()Z=false, origin=built in, applied=true"), report);
        // the patched class must still be valid bytecode
        new org.objectweb.asm.ClassReader(out).accept(new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9) {}, 0);
    }
}
