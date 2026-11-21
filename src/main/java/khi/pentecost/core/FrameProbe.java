package khi.pentecost.core;

import khi.pentecost.loader.ClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

public final class FrameProbe {
    private static long handStart;
    private static long handSum;
    private static long swapStart;
    private static long swapSum;
    private static int frames;
    private static long windowStart;

    private static volatile double handMs;
    private static volatile double gpuWaitMs;

    private FrameProbe() {}

    public static void handStart() {
        handStart = System.nanoTime();
    }

    public static void handEnd() {
        long s = handStart;
        if (s != 0) {
            handSum += System.nanoTime() - s;
            handStart = 0;
        }
    }

    public static void swapStart() {
        swapStart = System.nanoTime();
    }

    public static void swapEnd() {
        long now = System.nanoTime();
        if (swapStart != 0) {
            swapSum += now - swapStart;
            swapStart = 0;
        }
        frames++;
        if (windowStart == 0) {
            windowStart = now;
        } else if (now - windowStart >= 500_000_000L) {
            handMs = handSum / 1e6 / frames;
            gpuWaitMs = swapSum / 1e6 / frames;
            handSum = 0;
            swapSum = 0;
            frames = 0;
            windowStart = now;
        }
    }

    public static double handMs() {
        return handMs;
    }

    public static double gpuWaitMs() {
        return gpuWaitMs;
    }

    public static ClassTransformer transformer() {
        return (name, bytes) -> {
            boolean hands = name.equals("net.minecraft.client.renderer.ItemInHandRenderer");
            boolean window = name.equals("com.mojang.blaze3d.platform.Window");
            if (!hands && !window) {
                return bytes;
            }
            ClassReader reader = new ClassReader(bytes);
            ClassWriter writer = new ClassWriter(0);
            final boolean[] changed = new boolean[1];
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                @Override
                public MethodVisitor visitMethod(int access, String n, String d, String s, String[] e) {
                    MethodVisitor mv = super.visitMethod(access, n, d, s, e);
                    String start;
                    String end;
                    if (hands && n.equals("renderHandsWithItems") && d.startsWith("(FLcom/mojang/blaze3d/vertex/PoseStack;")) {
                        start = "handStart";
                        end = "handEnd";
                    } else if (window && n.equals("updateDisplay") && d.equals("()V")) {
                        start = "swapStart";
                        end = "swapEnd";
                    } else {
                        return mv;
                    }
                    changed[0] = true;
                    return new MethodVisitor(Opcodes.ASM9, mv) {
                        @Override
                        public void visitCode() {
                            super.visitCode();
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, "khi/pentecost/core/FrameProbe", start, "()V", false);
                        }

                        @Override
                        public void visitInsn(int op) {
                            if (op == Opcodes.RETURN || op == Opcodes.ATHROW) {
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, "khi/pentecost/core/FrameProbe", end, "()V", false);
                            }
                            super.visitInsn(op);
                        }
                    };
                }
            }, 0);
            return changed[0] ? writer.toByteArray() : bytes;
        };
    }
}
