package khi.pentecost.core;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.*;

class GlFilterTest {
    private static final Path GAME = Path.of("cache", "remapped", "client-1.20.1-47.4.10-forge-named.jar");

    private static List<String> calls(byte[] bytes) {
        List<String> out = new ArrayList<>();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int a, String n, String d, String s, String[] e) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int op, String owner, String m, String md, boolean itf) {
                        out.add(owner + "." + m + md);
                    }
                };
            }
        }, 0);
        return out;
    }

    @Test
    void glStateManagerBindsGoThroughTheFilter() throws Exception {
        Assumptions.assumeTrue(Files.isRegularFile(GAME), "No remapped game jar");
        byte[] original;
        try (JarFile jf = new JarFile(GAME.toFile());
             InputStream in = jf.getInputStream(jf.getEntry("com/mojang/blaze3d/platform/GlStateManager.class"))) {
            original = in.readAllBytes();
        }
        byte[] out = GlFilter.transformer().transform("com.mojang.blaze3d.platform.GlStateManager", original);
        List<String> after = calls(out);
        assertTrue(after.contains("khi/pentecost/core/GlFilter.glUseProgram(I)V"), "glUseProgram not routed");
        assertTrue(after.contains("khi/pentecost/core/GlFilter.glBindFramebuffer(II)V"), "glBindFramebuffer not routed");
        assertTrue(after.contains("khi/pentecost/core/GlFilter.flush()V"), "no flush before a draw or uniform");
        assertFalse(after.stream().anyMatch(c -> c.startsWith("org/lwjgl/opengl/") && c.contains(".glUseProgram(")), "a direct glUseProgram is left");
        assertTrue(after.size() >= calls(original).size());
    }

    @Test
    void drawsAndUniformsBindTheWaitingProgramFirst() {
        assertTrue(GlFilter.usesProgram("glDrawElements"));
        assertTrue(GlFilter.usesProgram("nglDrawElements"));
        assertTrue(GlFilter.usesProgram("glMultiDrawElementsIndirect"));
        assertTrue(GlFilter.usesProgram("glUniform3f"));
        assertTrue(GlFilter.usesProgram("glGetInteger"));
        assertFalse(GlFilter.usesProgram("glBindBuffer"));
        assertFalse(GlFilter.usesProgram("glEnable"));
    }

    @Test
    void lwjglAndPentecostAreLeftAlone() {
        byte[] b = {1, 2, 3};
        assertSame(b, GlFilter.transformer().transform("org.lwjgl.opengl.GL20", b));
        assertSame(b, GlFilter.transformer().transform("khi.pentecost.game.PerfHud", b));
    }
}
