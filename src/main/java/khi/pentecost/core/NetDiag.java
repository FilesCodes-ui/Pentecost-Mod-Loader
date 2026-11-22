package khi.pentecost.core;

import khi.pentecost.boot.Log;
import khi.pentecost.loader.ClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.concurrent.atomic.AtomicInteger;

// Minecraft turns a network exception into a short "Internal Exception" line and drops the stack.
// This writes the whole stack into pentecost.log the first few times, so a failed join names its cause.
public final class NetDiag {
    private static final AtomicInteger LOGGED = new AtomicInteger();
    private static final String SELF = "khi/pentecost/core/NetDiag";

    private NetDiag() {}

    public static void caught(Throwable t) {
        try {
            if (t == null || LOGGED.incrementAndGet() > 8) {
                return;
            }
            StringWriter text = new StringWriter();
            t.printStackTrace(new PrintWriter(text));
            Log.warn("Network exception on " + Thread.currentThread().getName() + ": " + text);
        } catch (RuntimeException | LinkageError ignored) {
        }
    }

    public static ClassTransformer transformer() {
        return (name, bytes) -> {
            if (!name.equals("net.minecraft.network.Connection") || System.getenv("PENTECOST_NO_NETDIAG") != null) {
                return bytes;
            }
            int[] done = new int[1];
            ClassReader reader = new ClassReader(bytes);
            ClassWriter writer = new ClassWriter(0);
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                @Override
                public MethodVisitor visitMethod(int access, String n, String d, String s, String[] e) {
                    MethodVisitor mv = super.visitMethod(access, n, d, s, e);
                    if (!n.equals("exceptionCaught") || !d.equals("(Lio/netty/channel/ChannelHandlerContext;Ljava/lang/Throwable;)V")) {
                        return mv;
                    }
                    done[0]++;
                    return new MethodVisitor(Opcodes.ASM9, mv) {
                        @Override
                        public void visitCode() {
                            super.visitCode();
                            super.visitVarInsn(Opcodes.ALOAD, 2);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, SELF, "caught", "(Ljava/lang/Throwable;)V", false);
                        }

                        @Override
                        public void visitMaxs(int maxStack, int maxLocals) {
                            super.visitMaxs(Math.max(maxStack, 1), maxLocals);
                        }
                    };
                }
            }, 0);
            if (done[0] != 1) {
                Log.warn("Pentecost network diagnostics: " + name + " did not match. No stack traces for network errors.");
                return bytes;
            }
            return writer.toByteArray();
        };
    }
}
