package khi.pentecost.core;

import khi.pentecost.boot.Log;
import khi.pentecost.loader.ClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

public final class GlFilter {
    private GlFilter() {}

    private static final int GL_FRAMEBUFFER = 0x8D40, GL_READ_FRAMEBUFFER = 0x8CA8, GL_DRAW_FRAMEBUFFER = 0x8CA9;

    private static volatile Thread render;
    private static int program = -1, readFb = -1, drawFb = -1, vao = -1;
    private static final int NONE = Integer.MIN_VALUE;
    private static int pending = NONE;
    private static long passed, skipped, lastReport;

    private static MethodHandle useProgram, bindFramebuffer, bindVertexArray;

    private static MethodHandle handle(String cls, String name, MethodType type) {
        try {
            ClassLoader l = Thread.currentThread().getContextClassLoader();
            Class<?> c = Class.forName(cls, true, l != null ? l : GlFilter.class.getClassLoader());
            return MethodHandles.publicLookup().findStatic(c, name, type);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("OpenGL call not found: " + cls + "." + name, e);
        }
    }

    private static boolean onRender() {
        Thread t = Thread.currentThread();
        Thread r = render;
        if (r == null && t.getName().equals("Render thread")) {
            render = t;
            return true;
        }
        return t == r;
    }

    private static void count(boolean skip) {
        if (skip) skipped++;
        else passed++;
        long now = System.currentTimeMillis();
        if (now - lastReport > 60_000) {
            if (lastReport != 0 && passed + skipped > 0) {
                Log.info(String.format("GL filter: %d of %d bind calls in the last minute changed nothing and were skipped (%d%%)",
                        skipped, passed + skipped, 100 * skipped / (passed + skipped)));
            }
            lastReport = now;
            passed = 0;
            skipped = 0;
        }
    }

    public static void glUseProgram(int p) throws Throwable {
        if (onRender()) {
            pending = p;
            return;
        }
        bindProgram(p);
    }

    private static void bindProgram(int p) throws Throwable {
        MethodHandle h = useProgram;
        if (h == null) useProgram = h = handle("org.lwjgl.opengl.GL20C", "glUseProgram", MethodType.methodType(void.class, int.class));
        h.invokeExact(p);
    }

    public static void flush() throws Throwable {
        int p = pending;
        if (p == NONE || !onRender()) return;
        pending = NONE;
        boolean same = p == program;
        count(same);
        if (same) return;
        program = p;
        bindProgram(p);
    }

    public static void glBindFramebuffer(int target, int fb) throws Throwable {
        if (onRender()) {
            boolean same;
            if (target == GL_FRAMEBUFFER) {
                same = readFb == fb && drawFb == fb;
                readFb = fb;
                drawFb = fb;
            } else if (target == GL_READ_FRAMEBUFFER) {
                same = readFb == fb;
                readFb = fb;
            } else if (target == GL_DRAW_FRAMEBUFFER) {
                same = drawFb == fb;
                drawFb = fb;
            } else {
                same = false;
                readFb = -1;
                drawFb = -1;
            }
            count(same);
            if (same) return;
        }
        MethodHandle h = bindFramebuffer;
        if (h == null) bindFramebuffer = h = handle("org.lwjgl.opengl.GL30C", "glBindFramebuffer", MethodType.methodType(void.class, int.class, int.class));
        h.invokeExact(target, fb);
    }

    public static void glBindVertexArray(int a) throws Throwable {
        if (onRender()) {
            boolean same = a == vao;
            count(same);
            if (same) return;
            vao = a;
        }
        MethodHandle h = bindVertexArray;
        if (h == null) bindVertexArray = h = handle("org.lwjgl.opengl.GL30C", "glBindVertexArray", MethodType.methodType(void.class, int.class));
        h.invokeExact(a);
    }

    public static void forgetProgram() throws Throwable {
        if (onRender()) {
            flush();
            program = -1;
        }
    }

    public static void forgetFramebuffers() {
        if (onRender()) {
            readFb = -1;
            drawFb = -1;
        }
    }

    public static void forgetVertexArrays() {
        if (onRender()) vao = -1;
    }

    public static void forgetAll() {
        pending = NONE;
        program = -1;
        readFb = -1;
        drawFb = -1;
        vao = -1;
    }

    private static boolean glClass(String owner) {
        return owner.startsWith("org/lwjgl/opengl/") && !owner.equals("org/lwjgl/opengl/GL");
    }

    public static ClassTransformer transformer() {
        return (name, bytes) -> {
            if (name.startsWith("org.lwjgl.") || name.startsWith("khi.pentecost.") || System.getenv("PENTECOST_NO_GL_FILTER") != null) {
                return bytes;
            }
            return rewrite(bytes);
        };
    }

    static byte[] rewrite(byte[] bytes) {
        if (!contains(bytes, "org/lwjgl/opengl/")) return bytes;
        try {
            ClassReader reader = new ClassReader(bytes);
            ClassWriter writer = new ClassWriter(0);
            int[] n = {0};
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                @Override
                public MethodVisitor visitMethod(int access, String mn, String md, String s, String[] e) {
                    return new MethodVisitor(Opcodes.ASM9, super.visitMethod(access, mn, md, s, e)) {
                        @Override
                        public void visitMethodInsn(int op, String owner, String m, String d, boolean itf) {
                            if (op == Opcodes.INVOKESTATIC && glClass(owner)) {
                                String self = "khi/pentecost/core/GlFilter";
                                if (m.equals("glUseProgram") && d.equals("(I)V")) {
                                    n[0]++;
                                    super.visitMethodInsn(op, self, "glUseProgram", d, false);
                                    return;
                                }
                                if (m.equals("glBindFramebuffer") && d.equals("(II)V")) {
                                    n[0]++;
                                    super.visitMethodInsn(op, self, "glBindFramebuffer", d, false);
                                    return;
                                }
                                if (m.equals("glBindVertexArray") && d.equals("(I)V")) {
                                    n[0]++;
                                    super.visitMethodInsn(op, self, "glBindVertexArray", d, false);
                                    return;
                                }
                                if (usesProgram(m)) {
                                    n[0]++;
                                    super.visitMethodInsn(op, self, "flush", "()V", false);
                                    super.visitMethodInsn(op, owner, m, d, itf);
                                    return;
                                }
                                String forget = m.equals("glDeleteProgram") ? "forgetProgram"
                                        : m.equals("glDeleteFramebuffers") ? "forgetFramebuffers"
                                        : m.equals("glDeleteVertexArrays") ? "forgetVertexArrays" : null;
                                if (forget != null) {
                                    n[0]++;
                                    super.visitMethodInsn(op, self, forget, "()V", false);
                                }
                            }
                            super.visitMethodInsn(op, owner, m, d, itf);
                        }
                    };
                }
            }, 0);
            return n[0] == 0 ? bytes : writer.toByteArray();
        } catch (RuntimeException e) {
            return bytes;
        }
    }

    static boolean usesProgram(String m) {
        String n = m.startsWith("ngl") ? m.substring(1) : m;
        return n.startsWith("glDraw") || n.startsWith("glMultiDraw") || n.startsWith("glUniform") || n.startsWith("glGet")
                || n.startsWith("glDispatchCompute") || n.startsWith("glBeginTransformFeedback") || n.startsWith("glValidateProgram")
                || n.startsWith("glProgramUniform") || n.startsWith("glReadPixels") || n.startsWith("glFinish") || n.startsWith("glFlush")
                || n.startsWith("glFenceSync") || n.startsWith("glMemoryBarrier") || n.startsWith("glClear")
                || n.startsWith("glBlitFramebuffer") || n.startsWith("glCopyTex");
    }

    private static boolean contains(byte[] b, String s) {
        byte[] p = s.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        outer:
        for (int i = 0; i <= b.length - p.length; i++) {
            for (int j = 0; j < p.length; j++) {
                if (b[i + j] != p[j]) continue outer;
            }
            return true;
        }
        return false;
    }
}
