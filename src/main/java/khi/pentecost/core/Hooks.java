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
            openRegistries(() -> PentecostRuntime.runner.runAll(true));
            Timeline.mark("Fabric and Quilt mods started");
        }
    }

    private static volatile boolean registriesOpen;
    private static final java.util.List<Object> REOPENED = new java.util.ArrayList<>();
    private static final java.util.Set<String> REOPEN_REPORTED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Runs Fabric or Quilt mod start code with the game's frozen registries open, then freezes them again. */
    private static void openRegistries(Runnable work) {
        registriesOpen = true;
        try {
            work.run();
        } finally {
            registriesOpen = false;
            synchronized (REOPENED) {
                for (Object registry : REOPENED) {
                    try {
                        registry.getClass().getMethod("freeze").invoke(registry);
                    } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                        Log.warn("Could not freeze " + registryName(registry) + " again: " + e);
                    }
                }
                REOPENED.clear();
            }
        }
    }

    private static String registryName(Object registry) {
        try {
            return String.valueOf(registry.getClass().getMethod("key").invoke(registry));
        } catch (ReflectiveOperationException | RuntimeException e) {
            return registry.getClass().getSimpleName();
        }
    }

    private static java.lang.reflect.Field frozenField;

    /** Called by the game at the top of MappedRegistry.validateWrite, before it refuses a frozen registry. */
    public static void registryWrite(Object registry) {
        if (!registriesOpen) {
            return;
        }
        try {
            if (frozenField == null) {
                Class<?> c = registry.getClass();
                while (c != null && !c.getName().equals("net.minecraft.core.MappedRegistry")) {
                    c = c.getSuperclass();
                }
                if (c == null) {
                    return;
                }
                java.lang.reflect.Field f = c.getDeclaredField("frozen");
                f.setAccessible(true);
                frozenField = f;
            }
            if (!frozenField.getBoolean(registry)) {
                return;
            }
            registry.getClass().getMethod("unfreeze").invoke(registry);
            synchronized (REOPENED) {
                REOPENED.add(registry);
            }
            String name = registryName(registry);
            if (REOPEN_REPORTED.add(name)) {
                Log.info("Registry " + name + " reopened while Fabric and Quilt mods start");
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            // The game reports the frozen registry as it normally would.
        }
    }

    /** Hooks MappedRegistry.validateWrite, so Pentecost can reopen it for late Fabric registration. */
    public static ClassTransformer registryTransformer() {
        return (name, bytes) -> {
            if (!name.equals("net.minecraft.core.MappedRegistry")) {
                return bytes;
            }
            ClassReader reader = new ClassReader(bytes);
            ClassWriter writer = new ClassWriter(0);
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                @Override
                public MethodVisitor visitMethod(int access, String n, String d, String s, String[] e) {
                    MethodVisitor mv = super.visitMethod(access, n, d, s, e);
                    if (!n.equals("validateWrite")) {
                        return mv;
                    }
                    return new MethodVisitor(Opcodes.ASM9, mv) {
                        @Override
                        public void visitCode() {
                            super.visitCode();
                            super.visitVarInsn(Opcodes.ALOAD, 0);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, "khi/pentecost/core/Hooks", "registryWrite",
                                    "(Ljava/lang/Object;)V", false);
                        }

                        @Override
                        public void visitMaxs(int maxStack, int maxLocals) {
                            super.visitMaxs(Math.max(maxStack, 1), maxLocals);
                        }
                    };
                }
            }, 0);
            return writer.toByteArray();
        };
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
                openRegistries(PentecostRuntime.runner::runWaitingClient);
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

    /** Pentecost's look for the game's standard widgets. Set by the game glue. */
    public interface Style {
        /** Draws a widget. True when drawn, so the game skips its own drawing. */
        boolean widget(Object widget, Object graphics, int mouseX, int mouseY, float partialTick);

        /** Draws a screen background. True when drawn. */
        boolean background(Object screen, Object graphics);

        /** Runs after a screen built its widgets. */
        void inited(Object screen);

        /** Runs after a screen drew itself, for transitions. */
        void drawn(Object screen, Object graphics);

        /** Draws a tooltip's box. True when drawn. */
        boolean tooltip(Object graphics, int x, int y, int width, int height, int z);

        /** Draws the F3 screen. source: the game's debug overlay, or Forge's HUD. True when drawn. */
        boolean debug(Object source, Object graphics);
    }

    private static boolean debugSeen;

    public static boolean styleDebug(Object source, Object graphics) {
        if (!debugSeen) {
            debugSeen = true;
            Log.info("F3 hook reached through " + source.getClass().getSimpleName());
        }
        Style s = style;
        try {
            return s != null && s.debug(source, graphics);
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    public static boolean styleDebugForge(Object gui, int width, int height, Object graphics) {
        return styleDebug(gui, graphics);
    }

    public static boolean styleTooltip(Object graphics, int x, int y, int width, int height, int z) {
        Style s = style;
        return s != null && s.tooltip(graphics, x, y, width, height, z);
    }

    private static final byte[] DIRT = "renderDirtBackground".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    private static boolean mentions(byte[] bytes, byte[] text) {
        outer:
        for (int i = 0; i + text.length <= bytes.length; i++) {
            for (int j = 0; j < text.length; j++) {
                if (bytes[i + j] != text[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    public static void screenDrawn(Object screen, Object graphics) {
        Style s = style;
        if (s != null) {
            try {
                s.drawn(screen, graphics);
            } catch (RuntimeException | LinkageError e) {
                // A missed fade is harmless.
            }
        }
    }

    public static volatile Style style;

    public static boolean styleWidget(Object widget, Object graphics, int mouseX, int mouseY, float partialTick) {
        Style s = style;
        return s != null && s.widget(widget, graphics, mouseX, mouseY, partialTick);
    }

    public static boolean styleBackground(Object screen, Object graphics) {
        Style s = style;
        return s != null && s.background(screen, graphics);
    }

    public static void screenInited(Object screen) {
        Style s = style;
        if (s != null) {
            try {
                s.inited(screen);
            } catch (RuntimeException | LinkageError e) {
                // A screen's own state is left as it is.
            }
        }
    }

    private static final java.util.Set<String> STYLED = java.util.Set.of(
            "net.minecraft.client.gui.components.AbstractButton",
            "net.minecraft.client.gui.components.AbstractSliderButton",
            "net.minecraft.client.gui.components.Checkbox",
            "net.minecraftforge.client.gui.widget.ExtendedButton",
            "net.minecraft.client.gui.components.TabButton",
            "net.minecraft.client.gui.components.EditBox");
    private static final String GG = "Lnet/minecraft/client/gui/GuiGraphics;";

    /** Lets Pentecost draw the game's standard widgets and screen backgrounds. */
    public static ClassTransformer styleTransformer() {
        return (name, bytes) -> {
            boolean widget = STYLED.contains(name);
            boolean screen = name.equals("net.minecraft.client.gui.screens.Screen");
            boolean tip = name.equals("net.minecraft.client.gui.screens.inventory.tooltip.TooltipRenderUtil");
            boolean debugVanilla = name.equals("net.minecraft.client.gui.components.DebugScreenOverlay");
            boolean debugForge = name.equals("net.minecraftforge.client.gui.overlay.ForgeGui");
            // Any screen that draws its own background (Create World does) gets the hook too.
            boolean ownDirt = !screen && bytes != null && mentions(bytes, DIRT);
            if (!widget && !screen && !tip && !ownDirt && !debugVanilla && !debugForge) {
                return bytes;
            }
            ClassReader reader = new ClassReader(bytes);
            ClassWriter writer = new ClassWriter(0);
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                @Override
                public MethodVisitor visitMethod(int access, String n, String d, String s, String[] e) {
                    MethodVisitor mv = super.visitMethod(access, n, d, s, e);
                    boolean draw = widget && n.equals("renderWidget") && d.equals("(" + GG + "IIF)V");
                    boolean dirt = (screen || ownDirt) && n.equals("renderDirtBackground") && d.equals("(" + GG + ")V")
                            && (access & Opcodes.ACC_ABSTRACT) == 0;
                    boolean tooltip = tip && n.equals("renderTooltipBackground") && (access & Opcodes.ACC_STATIC) != 0
                            && d.startsWith("(" + GG + "IIIII");
                    boolean init = screen && n.equals("init") && d.equals("(Lnet/minecraft/client/Minecraft;II)V");
                    boolean shown = screen && n.equals("renderWithTooltip") && d.equals("(" + GG + "IIF)V");
                    boolean dbg = debugVanilla && n.equals("render") && d.equals("(" + GG + ")V");
                    boolean dbgForge = debugForge && n.equals("renderHUDText") && d.equals("(II" + GG + ")V");
                    if (!draw && !dirt && !init && !shown && !tooltip && !dbg && !dbgForge) {
                        return mv;
                    }
                    return new MethodVisitor(Opcodes.ASM9, mv) {
                        @Override
                        public void visitCode() {
                            super.visitCode();
                            if (init || shown) {
                                return;
                            }
                            org.objectweb.asm.Label go = new org.objectweb.asm.Label();
                            if (dbg || dbgForge) {
                                super.visitVarInsn(Opcodes.ALOAD, 0);
                                if (dbg) {
                                    super.visitVarInsn(Opcodes.ALOAD, 1);
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC, "khi/pentecost/core/Hooks", "styleDebug",
                                            "(Ljava/lang/Object;Ljava/lang/Object;)Z", false);
                                } else {
                                    super.visitVarInsn(Opcodes.ILOAD, 1);
                                    super.visitVarInsn(Opcodes.ILOAD, 2);
                                    super.visitVarInsn(Opcodes.ALOAD, 3);
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC, "khi/pentecost/core/Hooks", "styleDebugForge",
                                            "(Ljava/lang/Object;IILjava/lang/Object;)Z", false);
                                }
                                super.visitJumpInsn(Opcodes.IFEQ, go);
                                super.visitInsn(Opcodes.RETURN);
                                super.visitLabel(go);
                                super.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
                                return;
                            }
                            if (tooltip) {
                                super.visitVarInsn(Opcodes.ALOAD, 0);
                                for (int i = 1; i <= 5; i++) {
                                    super.visitVarInsn(Opcodes.ILOAD, i);
                                }
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, "khi/pentecost/core/Hooks", "styleTooltip",
                                        "(Ljava/lang/Object;IIIII)Z", false);
                                super.visitJumpInsn(Opcodes.IFEQ, go);
                                super.visitInsn(Opcodes.RETURN);
                                super.visitLabel(go);
                                super.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
                                return;
                            }
                            super.visitVarInsn(Opcodes.ALOAD, 0);
                            super.visitVarInsn(Opcodes.ALOAD, 1);
                            if (draw) {
                                super.visitVarInsn(Opcodes.ILOAD, 2);
                                super.visitVarInsn(Opcodes.ILOAD, 3);
                                super.visitVarInsn(Opcodes.FLOAD, 4);
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, "khi/pentecost/core/Hooks", "styleWidget",
                                        "(Ljava/lang/Object;Ljava/lang/Object;IIF)Z", false);
                            } else {
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, "khi/pentecost/core/Hooks", "styleBackground",
                                        "(Ljava/lang/Object;Ljava/lang/Object;)Z", false);
                            }
                            super.visitJumpInsn(Opcodes.IFEQ, go);
                            super.visitInsn(Opcodes.RETURN);
                            super.visitLabel(go);
                            super.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
                        }

                        @Override
                        public void visitInsn(int opcode) {
                            if (init && opcode == Opcodes.RETURN) {
                                super.visitVarInsn(Opcodes.ALOAD, 0);
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, "khi/pentecost/core/Hooks", "screenInited",
                                        "(Ljava/lang/Object;)V", false);
                            }
                            if (shown && opcode == Opcodes.RETURN) {
                                super.visitVarInsn(Opcodes.ALOAD, 0);
                                super.visitVarInsn(Opcodes.ALOAD, 1);
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, "khi/pentecost/core/Hooks", "screenDrawn",
                                        "(Ljava/lang/Object;Ljava/lang/Object;)V", false);
                            }
                            super.visitInsn(opcode);
                        }

                        @Override
                        public void visitMaxs(int maxStack, int maxLocals) {
                            super.visitMaxs(Math.max(maxStack, 6), maxLocals);
                        }
                    };
                }
            }, 0);
            return writer.toByteArray();
        };
    }

    /** Set by the game glue. Picks the loading screen to show. */
    public static volatile java.util.function.UnaryOperator<Object> overlayRouter;

    /** Called by the game at the top of Minecraft.setOverlay. */
    public static Object routeOverlay(Object overlay) {
        java.util.function.UnaryOperator<Object> r = overlayRouter;
        if (r == null || overlay == null) {
            return overlay;
        }
        try {
            Object out = r.apply(overlay);
            return out != null ? out : overlay;
        } catch (RuntimeException | LinkageError e) {
            Log.error("Loading screen routing failed: " + e);
            return overlay;
        }
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
                    if (n.equals("setOverlay") && d.equals("(Lnet/minecraft/client/gui/screens/Overlay;)V")) {
                        return new MethodVisitor(Opcodes.ASM9, mv) {
                            @Override
                            public void visitCode() {
                                super.visitCode();
                                super.visitVarInsn(Opcodes.ALOAD, 1);
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, "khi/pentecost/core/Hooks",
                                        "routeOverlay", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
                                super.visitTypeInsn(Opcodes.CHECKCAST, "net/minecraft/client/gui/screens/Overlay");
                                super.visitVarInsn(Opcodes.ASTORE, 1);
                            }
                        };
                    }
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
