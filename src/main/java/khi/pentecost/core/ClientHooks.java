package khi.pentecost.core;

import khi.pentecost.control.Settings;
import khi.pentecost.loader.ClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Two client hooks.
 * Sounds: every sound the game is asked to play passes a gate first, so Pentecost can mute or limit it.
 * Block outline: the box drawn around the block you look at takes its color and strength from Settings.
 */
public final class ClientHooks {
    public interface Gate {
        boolean allow(Object soundInstance);
    }

    public static volatile Gate gate;

    private static final String HOOKS = "khi/pentecost/core/ClientHooks";
    private static volatile float red, green, blue, alpha = 0.4f;
    private static long refreshed;

    private ClientHooks() {}

    /** Outline colors by name: the default is black at 40 percent, the game's own. */
    public static final String[] COLORS = {"Black", "White", "Red", "Green", "Blue", "Yellow", "Orange", "Cyan"};
    private static final int[] RGB = {0x000000, 0xFFFFFF, 0xFF4040, 0x40FF40, 0x4080FF, 0xFFE040, 0xFF9020, 0x40E0FF};

    private static void refresh() {
        long now = System.currentTimeMillis();
        if (now - refreshed < 500) {
            return;
        }
        refreshed = now;
        String name = Settings.get(Settings.OUTLINE_COLOR);
        int rgb = 0;
        for (int i = 0; i < COLORS.length; i++) {
            if (COLORS[i].equalsIgnoreCase(name)) {
                rgb = RGB[i];
            }
        }
        red = ((rgb >> 16) & 255) / 255f;
        green = ((rgb >> 8) & 255) / 255f;
        blue = (rgb & 255) / 255f;
        alpha = Math.max(0, Math.min(100, Settings.num(Settings.OUTLINE_ALPHA, 40))) / 100f;
    }

    public static float r() { refresh(); return red; }
    public static float g() { return green; }
    public static float b() { return blue; }
    public static float a() { return alpha; }

    private static long limitStarted;
    private static long slept;

    public static void limitStart() {
        limitStarted = System.nanoTime();
    }

    public static void limitEnd() {
        slept += System.nanoTime() - limitStarted;
    }

    /** Nanoseconds the frame cap made the game wait since the last call. */
    public static long takeSleep() {
        long s = slept;
        slept = 0;
        return s;
    }

    /** Called by the game before a sound plays. False drops the sound. */
    public static boolean sound(Object instance) {
        Gate g = gate;
        return g == null || g.allow(instance);
    }

    /** Runs before Mixin. Edits a method body in place, so mod hooks still see the real body. */
    public static ClassTransformer transformer() {
        return build(false);
    }

    /** Runs after Mixin. Wraps SoundEngine.play and RenderSystem.limitDisplayFPS, which hides their bodies. */
    public static ClassTransformer lateTransformer() {
        return build(true);
    }

    private static ClassTransformer build(boolean late) {
        return (name, bytes) -> {
            boolean sounds = late && name.equals("net.minecraft.client.sounds.SoundEngine");
            boolean levels = !late && name.equals("net.minecraft.client.renderer.LevelRenderer");
            boolean render = late && name.equals("com.mojang.blaze3d.systems.RenderSystem");
            if (!sounds && !levels && !render) {
                return bytes;
            }
            final String internal = name.replace('.', '/');
            ClassReader reader = new ClassReader(bytes);
            ClassWriter writer = new ClassWriter(0);
            final boolean[] changed = new boolean[1];
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                @Override
                public MethodVisitor visitMethod(int access, String n, String d, String s, String[] e) {
                    if (render && n.equals("limitDisplayFPS") && d.equals("(I)V") && (access & Opcodes.ACC_STATIC) != 0) {
                        changed[0] = true;
                        String kept = n + "$pentecost";
                        MethodVisitor w = super.visitMethod(access, n, d, s, e);
                        w.visitCode();
                        w.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, "limitStart", "()V", false);
                        w.visitVarInsn(Opcodes.ILOAD, 0);
                        w.visitMethodInsn(Opcodes.INVOKESTATIC, internal, kept, d, false);
                        w.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, "limitEnd", "()V", false);
                        w.visitInsn(Opcodes.RETURN);
                        w.visitMaxs(1, 1);
                        w.visitEnd();
                        return super.visitMethod(access, kept, d, s, e);
                    }
                    if ((access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT)) != 0) {
                        return super.visitMethod(access, n, d, s, e);
                    }
                    if (sounds && n.equals("play") && d.equals("(Lnet/minecraft/client/resources/sounds/SoundInstance;)V")) {
                        changed[0] = true;
                        String kept = n + "$pentecost";
                        MethodVisitor w = super.visitMethod(access, n, d, s, e);
                        Label go = new Label();
                        w.visitCode();
                        w.visitVarInsn(Opcodes.ALOAD, 1);
                        w.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, "sound", "(Ljava/lang/Object;)Z", false);
                        w.visitJumpInsn(Opcodes.IFNE, go);
                        w.visitInsn(Opcodes.RETURN);
                        w.visitLabel(go);
                        w.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
                        w.visitVarInsn(Opcodes.ALOAD, 0);
                        w.visitVarInsn(Opcodes.ALOAD, 1);
                        w.visitMethodInsn(Opcodes.INVOKEVIRTUAL, internal, kept, d, false);
                        w.visitInsn(Opcodes.RETURN);
                        w.visitMaxs(2, 2);
                        w.visitEnd();
                        return super.visitMethod(access, kept, d, s, e);
                    }
                    MethodVisitor out = super.visitMethod(access, n, d, s, e);
                    if (levels && n.equals("renderHitOutline")) {
                        changed[0] = true;
                        return new MethodVisitor(Opcodes.ASM9, out) {
                            private int zeros;

                            @Override
                            public void visitInsn(int opcode) {
                                if (opcode == Opcodes.FCONST_0 && zeros < 3) {
                                    String fn = zeros == 0 ? "r" : zeros == 1 ? "g" : "b";
                                    zeros++;
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, fn, "()F", false);
                                } else {
                                    super.visitInsn(opcode);
                                }
                            }

                            @Override
                            public void visitLdcInsn(Object value) {
                                if (value instanceof Float f && f == 0.4f) {
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, "a", "()F", false);
                                } else {
                                    super.visitLdcInsn(value);
                                }
                            }
                        };
                    }
                    return out;
                }
            }, 0);
            return changed[0] ? writer.toByteArray() : bytes;
        };
    }
}
