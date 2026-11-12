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
    /** Mods that failed to start. Their event hooks return at once. */
    private static final java.util.Set<String> FAILED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public static void markFailed(String modId) {
        FAILED.add(modId);
    }

    /** Called at the top of every mod event hook. True when that mod failed to start. */
    public static boolean modFailed(String modId) {
        return !FAILED.isEmpty() && FAILED.contains(modId);
    }

    private static final String MINECRAFT = "net.minecraft.client.Minecraft";
    private static final AtomicBoolean STARTED = new AtomicBoolean();
    private static final String SCREEN = "net/minecraft/client/gui/screens/Screen";

    /** Set by the game glue. Gets each screen the game is about to show and returns the one to show. */
    public static volatile java.util.function.UnaryOperator<Object> screenRouter;

    /** Set by the game glue. Gets the screen asked for and the screen about to show, returns the one to show. */
    public static volatile java.util.function.BinaryOperator<Object> screenFinalizer;

    private static Object asked;

    /** Set by the game glue. Gets the pack sources of a new pack list and returns the ones to use. */
    public static volatile java.util.function.UnaryOperator<Object> packSources;

    private static final AtomicBoolean FABRIC_SIDE = new AtomicBoolean();
    private static String forgeStep = "";

    /**
     * Called by Forge's mod loader at the start of each of its loading steps.
     * The step after CONSTRUCT is where Pentecost starts the Fabric and Quilt mods:
     * every Forge mod object exists, and the registries have not been filled yet.
     */
    public static void forgeState(Object state) {
        String name;
        try {
            name = String.valueOf(state.getClass().getMethod("name").invoke(state));
        } catch (ReflectiveOperationException | RuntimeException e) {
            return;
        }
        boolean constructed = "CONSTRUCT".equals(forgeStep);
        forgeStep = name;
        if (constructed && PentecostRuntime.runner != null && FABRIC_SIDE.compareAndSet(false, true)) {
            Timeline.mark("Forge mods built");
            Log.info("Forge built its mods. Starting Fabric and Quilt mods");
            PentecostRuntime.runner.runAll(true);
            Timeline.mark("Fabric and Quilt mods started");
        }
    }

    /** Called by Forge at the top of a step's queued-task run. */
    public static void forgeDeferred(Object queue) {
        if (PentecostRuntime.runner == null) {
            return;
        }
        try {
            java.lang.reflect.Field f = queue.getClass().getDeclaredField("modLoadingStage");
            f.setAccessible(true);
            if ("SIDED_SETUP".equals(String.valueOf(f.get(queue)))) {
                PentecostRuntime.runner.runWaitingClient();
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.error("Could not start the waiting mods: " + e);
        }
    }

    /** Set by the game glue. Runs once per frame, after the game drew its HUD. */
    public static volatile Runnable frame;

    /** Called by the game right after it drew its HUD. */
    public static void hud() {
        Runnable r = frame;
        if (r != null) {
            try {
                r.run();
            } catch (RuntimeException | LinkageError e) {
                frame = null;
                Log.error("Pentecost's HUD failed and was turned off: " + e);
            }
        }
    }

    /** Adds the per-frame call behind the game's own HUD draw. */
    public static ClassTransformer hudTransformer() {
        return (name, bytes) -> {
            if (!"net.minecraft.client.renderer.GameRenderer".equals(name)) {
                return bytes;
            }
            ClassReader reader = new ClassReader(bytes);
            ClassWriter writer = new ClassWriter(0);
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                @Override
                public MethodVisitor visitMethod(int access, String n, String d, String s, String[] e) {
                    return new MethodVisitor(Opcodes.ASM9, super.visitMethod(access, n, d, s, e)) {
                        @Override
                        public void visitMethodInsn(int op, String owner, String mn, String md, boolean itf) {
                            super.visitMethodInsn(op, owner, mn, md, itf);
                            if (owner.equals("net/minecraft/client/gui/Gui") && mn.equals("render")
                                    && md.equals("(Lnet/minecraft/client/gui/GuiGraphics;F)V")) {
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, "khi/pentecost/core/Hooks",
                                        "hud", "()V", false);
                            }
                        }
                    };
                }
            }, 0);
            return writer.toByteArray();
        };
    }

    private static final AtomicBoolean GLUE = new AtomicBoolean();

    /** Starts Pentecost's in-game code once: screens, resource packs. */
    private static void startGlue() {
        if (System.getProperty("pentecost.gameglue") == null || PentecostRuntime.loader == null
                || !GLUE.compareAndSet(false, true)) {
            return;
        }
        try {
            Class.forName("khi.pentecost.game.ModsScreens", true, PentecostRuntime.loader)
                    .getMethod("init").invoke(null);
        } catch (ReflectiveOperationException | LinkageError e) {
            Log.error("Pentecost's in-game code did not start: " + e);
        }
    }

    /** Called by the game at the top of the pack list constructor. */
    public static Object packSources(Object sources) {
        startGlue();
        java.util.function.UnaryOperator<Object> p = packSources;
        if (p == null) {
            return sources;
        }
        try {
            return p.apply(sources);
        } catch (RuntimeException | LinkageError e) {
            Log.error("Adding mod resource packs failed: " + e);
            return sources;
        }
    }

    private static final String PACKS = "net.minecraft.server.packs.repository.PackRepository";
    private static final String SOURCES = "[Lnet/minecraft/server/packs/repository/RepositorySource;";

    /** Routes the pack list constructor's sources through packSources. */
    public static ClassTransformer packTransformer() {
        return (name, bytes) -> {
            if (!PACKS.equals(name)) {
                return bytes;
            }
            ClassReader reader = new ClassReader(bytes);
            ClassWriter writer = new ClassWriter(0);
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                @Override
                public MethodVisitor visitMethod(int access, String n, String d, String s, String[] e) {
                    MethodVisitor mv = super.visitMethod(access, n, d, s, e);
                    if (!n.equals("<init>") || !d.equals("(" + SOURCES + ")V")) {
                        return mv;
                    }
                    return new MethodVisitor(Opcodes.ASM9, mv) {
                        @Override
                        public void visitCode() {
                            super.visitCode();
                            super.visitVarInsn(Opcodes.ALOAD, 1);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, "khi/pentecost/core/Hooks",
                                    "packSources", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
                            super.visitTypeInsn(Opcodes.CHECKCAST, SOURCES);
                            super.visitVarInsn(Opcodes.ASTORE, 1);
                        }

                        @Override
                        public void visitMaxs(int maxStack, int maxLocals) {
                            super.visitMaxs(Math.max(maxStack, 2), maxLocals);
                        }
                    };
                }
            }, 0);
            return writer.toByteArray();
        };
    }

    /** Called by the game at the top of Minecraft.setScreen. */
    public static Object routeScreen(Object screen) {
        Object out = screen;
        java.util.function.UnaryOperator<Object> r = screenRouter;
        if (r != null && screen != null) {
            try {
                out = r.apply(screen);
            } catch (RuntimeException | LinkageError e) {
                Log.error("Screen routing failed: " + e);
            }
        }
        asked = out;
        return out;
    }

    /** Called by the game in Minecraft.setScreen, right before the new screen becomes the current one. */
    public static Object finalScreen(Object shown) {
        java.util.function.BinaryOperator<Object> f = screenFinalizer;
        if (f == null) {
            return shown;
        }
        try {
            return f.apply(asked, shown);
        } catch (RuntimeException | LinkageError e) {
            Log.error("Screen check failed: " + e);
            return shown;
        }
    }

    private Hooks() {}

    /** Called by the game from the Minecraft constructor. */
    public static void clientStart(Object game) {
        if (!STARTED.compareAndSet(false, true)) {
            return;
        }
        FabricLoaderImpl.INSTANCE.setGameInstance(game);
        Timeline.mark("Game object created");
        startGlue();
        if (PentecostRuntime.runner != null && !PentecostRuntime.forgeLayer) {
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
                            public void visitFieldInsn(int op, String owner, String fn, String fd) {
                                if (op == Opcodes.PUTFIELD && owner.equals("net/minecraft/client/Minecraft")
                                        && fd.equals("L" + SCREEN + ";")) {
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC, "khi/pentecost/core/Hooks",
                                            "finalScreen", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
                                    super.visitTypeInsn(Opcodes.CHECKCAST, SCREEN);
                                    super.visitInsn(Opcodes.DUP);
                                    super.visitVarInsn(Opcodes.ASTORE, 1);
                                }
                                super.visitFieldInsn(op, owner, fn, fd);
                            }

                            @Override
                            public void visitMaxs(int maxStack, int maxLocals) {
                                super.visitMaxs(Math.max(maxStack, 4), maxLocals);
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
