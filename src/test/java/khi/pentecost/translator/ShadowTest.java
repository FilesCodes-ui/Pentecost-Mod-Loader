package khi.pentecost.translator;

import khi.pentecost.mods.LoaderKind;
import khi.pentecost.mods.ModInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShadowTest {
    private static byte[] cls(String name, String... calls) {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        w.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "()V", null, null);
        m.visitCode();
        for (String c : calls) {
            m.visitMethodInsn(Opcodes.INVOKESTATIC, c, "go", "()V", false);
        }
        m.visitInsn(Opcodes.RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        w.visitEnd();
        return w.toByteArray();
    }

    private static Path jar(Path dir, String file, Map<String, byte[]> classes) throws IOException {
        Path p = dir.resolve(file);
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(p))) {
            for (var e : classes.entrySet()) {
                z.putNextEntry(new ZipEntry(e.getKey() + ".class"));
                z.write(e.getValue());
                z.closeEntry();
            }
        }
        return p;
    }

    private static ModInfo mod(String id, LoaderKind k, Path jar, List<String> depends) {
        return new ModInfo(id, id, "1", k, jar, Map.of(), depends, List.of(), List.of(), null);
    }

    @Test
    void findsTheParentTheGapAndTheBestStandIn(@TempDir Path dir) throws IOException {
        Path base = jar(dir, "create.jar", Map.of(
                "com/sim/create/Base", cls("com/sim/create/Base"),
                "com/sim/create/foundation/forge/RenderHelper", cls("com/sim/create/foundation/forge/RenderHelper")));
        Path addon = jar(dir, "copycats.jar", Map.of(
                "com/copy/Addon", cls("com/copy/Addon", "com/sim/create/Base", "com/sim/create/foundation/fabric/RenderHelper",
                        "net/minecraft/world/Thing", "com/nowhere/Gone")));
        Shadow.Result r = Shadow.analyze(List.of(
                mod("create", LoaderKind.FORGE, base, List.of()),
                mod("copycats", LoaderKind.FABRIC, addon, List.of())), n -> n.startsWith("net/minecraft/"));

        Shadow.ModReport m = r.mods().stream().filter(x -> x.id().equals("copycats")).findFirst().orElseThrow();
        assertEquals(1, m.parents().size());
        assertEquals("create", m.parents().get(0).id());
        assertFalse(m.parents().get(0).declared(), "copycats never declared create");
        assertEquals(2, m.gaps().size(), m.gaps().toString());
        Shadow.Gap helper = m.gaps().stream().filter(g -> g.missing().endsWith("RenderHelper")).findFirst().orElseThrow();
        assertEquals("com.sim.create.foundation.forge.RenderHelper", helper.candidate());
        assertTrue(helper.score() >= 80, "score " + helper.score());
        Shadow.Gap gone = m.gaps().stream().filter(g -> g.missing().endsWith("Gone")).findFirst().orElseThrow();
        assertEquals("", gone.candidate());
        assertTrue(Shadow.text(r).contains("NOT DECLARED"));
    }

    @Test
    void aDeclaredParentIsMarkedDeclared(@TempDir Path dir) throws IOException {
        Path base = jar(dir, "a.jar", Map.of("a/Base", cls("a/Base")));
        Path addon = jar(dir, "b.jar", Map.of("b/Addon", cls("b/Addon", "a/Base")));
        Shadow.Result r = Shadow.analyze(List.of(
                mod("a", LoaderKind.FORGE, base, List.of()),
                mod("b", LoaderKind.FORGE, addon, List.of("a"))), n -> false);
        Shadow.ModReport m = r.mods().stream().filter(x -> x.id().equals("b")).findFirst().orElseThrow();
        assertTrue(m.parents().get(0).declared());
        assertTrue(m.gaps().isEmpty());
    }

    @Test
    void aBrokenJarIsSkippedNotFatal(@TempDir Path dir) throws IOException {
        Path junk = Files.writeString(dir.resolve("junk.jar"), "not a jar");
        Shadow.Result r = Shadow.analyze(List.of(mod("junk", LoaderKind.FORGE, junk, List.of())), n -> false);
        assertTrue(r.mods().isEmpty());
    }
}
