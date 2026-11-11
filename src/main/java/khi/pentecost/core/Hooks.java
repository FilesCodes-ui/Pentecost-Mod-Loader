package khi.pentecost.core;

import khi.pentecost.adapter.fabric.FabricLoaderImpl;
import khi.pentecost.boot.Log;
import khi.pentecost.loader.ClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Points inside the game where Pentecost takes control.
 * Mods start inside the Minecraft constructor, right before the game records its main thread.
 * At that point the game instance, the folders, and the user exist, and the options do not yet.
 * Fabric starts mods at the same point, so mods see the state they expect.
 */
public final class Hooks {
    private static final String MINECRAFT = "net.minecraft.client.Minecraft";
    private static final AtomicBoolean STARTED = new AtomicBoolean();
    private static final String SCREEN = "net/minecraft/client/gui/screens/Screen";

    /** Set by the game glue. Gets each screen the game is about to show and returns the one to show. */
    public static volatile java.util.function.UnaryOperator<Object> screenRouter;

    /** Called by the game at the top of Minecraft.setScreen. */
    public static Object routeScreen(Object screen) {
        java.util.function.UnaryOperator<Object> r = screenRouter;
        if (r == null || screen == null) {
            return screen;
        }
        try {
            return r.apply(screen);
        } catch (RuntimeException | LinkageError e) {
            Log.error("Screen routing failed: " + e);
            return screen;
        }
    }

    private Hooks() {}

    /** Called by the game from the Minecraft constructor. */
    public static void clientStart(Object game) {
        if (!STARTED.compareAndSet(false, true)) {
            return;
        }
        FabricLoaderImpl.INSTANCE.setGameInstance(game);
        if (System.getProperty("pentecost.gameglue") != null && PentecostRuntime.loader != null) {
            try {
                Class.forName("khi.pentecost.game.ModsScreens", true, PentecostRuntime.loader)
                        .getMethod("init").invoke(null);
            } catch (ReflectiveOperationException | LinkageError e) {
                Log.error("Pentecost's in-game screens did not start: " + e);
            }
        }
        if (PentecostRuntime.runner != null) {
            Log.info("Game reached mod start. Starting mods");
            PentecostRuntime.runner.runAll(true);
        }
    }

    /** Inserts the call to clientStart before the constructor's first Thread.currentThread() call. */
    public static ClassTransformer transformer() {
        return (name, bytes) -> {
            if (!MINECRAFT.equals(name)) {
                return bytes;
            }
            ClassReader reader = new ClassReader(bytes);
            ClassWriter writer = new ClassWriter(0);
            boolean[] hooked = new boolean[1];
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                @Override
                public MethodVisitor visitMethod(int access, String n, String d, String s, String[] e) {
                    MethodVisitor mv = super.visitMethod(access, n, d, s, e);
                    if (n.equals("setScreen") && d.equals("(L" + SCREEN + ";)V")) {
                        return new MethodVisitor(Opcodes.ASM9, mv) {
                            @Override
                            public void visitCode() {
                                super.visitCode();
                                super.visitVarInsn(Opcodes.ALOAD, 1);
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, "khi/pentecost/core/Hooks",
                                        "routeScreen", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
                                super.visitTypeInsn(Opcodes.CHECKCAST, SCREEN);
                                super.visitVarInsn(Opcodes.ASTORE, 1);
                            }

                            @Override
                            public void visitMaxs(int maxStack, int maxLocals) {
                                super.visitMaxs(Math.max(maxStack, 2), maxLocals);
                            }
                        };
                    }
                    if (!n.equals("<init>")) {
                        return mv;
                    }
                    return new MethodVisitor(Opcodes.ASM9, mv) {
                        @Override
                        public void visitMethodInsn(int op, String owner, String mn, String md, boolean itf) {
                            if (!hooked[0] && op == Opcodes.INVOKESTATIC && owner.equals("java/lang/Thread")
                                    && mn.equals("currentThread")) {
                                hooked[0] = true;
                                super.visitVarInsn(Opcodes.ALOAD, 0);
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, "khi/pentecost/core/Hooks",
                                        "clientStart", "(Ljava/lang/Object;)V", false);
                            }
                            super.visitMethodInsn(op, owner, mn, md, itf);
                        }

                        @Override
                        public void visitMaxs(int maxStack, int maxLocals) {
                            super.visitMaxs(Math.max(maxStack, 3), maxLocals);
                        }
                    };
                }
            }, 0);
            if (!hooked[0]) {
                Log.error("The mod start point was not found in the game. Mods will not start.");
            }
            return writer.toByteArray();
        };
    }
}
