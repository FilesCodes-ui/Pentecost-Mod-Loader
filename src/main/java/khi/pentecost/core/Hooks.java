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

public final class Hooks {
    private static final java.util.Set<String> FAILED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public static void markFailed(String modId) {
        FAILED.add(modId);
    }

    public static boolean modFailed(String modId) {
        return !FAILED.isEmpty() && FAILED.contains(modId);
    }

    private static final String MINECRAFT = "net.minecraft.client.Minecraft";
    private static final AtomicBoolean STARTED = new AtomicBoolean();
    private static final String SCREEN = "net/minecraft/client/gui/screens/Screen";

    public static volatile java.util.function.UnaryOperator<Object> screenRouter;

    public static volatile java.util.function.BinaryOperator<Object> screenFinalizer;

    private static Object asked;

    public static volatile java.util.function.UnaryOperator<Object> packSources;

    private static final AtomicBoolean FABRIC_SIDE = new AtomicBoolean();
    private static String forgeStep = "";

    public static void forgeState(Object state) {
        String name;
        try {
            name = String.valueOf(state.getClass().getMethod("name").invoke(state));
        } catch (ReflectiveOperationException | RuntimeException e) {
            return;
        }
        boolean early = "1".equals(System.getenv("PENTECOST_FABRIC_EARLY"));
        boolean ready = early ? "CONSTRUCT".equals(forgeStep) : "LOAD_REGISTRIES".equals(forgeStep);
        forgeStep = name;
        if (ready) {
            startFabricSide();
        }
    }

    public static void forgeClientHooks() {
        if (!"1".equals(System.getenv("PENTECOST_FABRIC_EARLY"))) {
            startFabricSide();
        }
    }

    private static void startFabricSide() {
        if (PentecostRuntime.runner == null || !FABRIC_SIDE.compareAndSet(false, true)) {
            return;
        }
        Timeline.mark("Forge mods built");
        Log.info("Forge built its mods and registries. Starting Fabric and Quilt mods");
        holdTicks = true;
        try {
            openRegistries(() -> {
                PentecostRuntime.runner.runAll(true);
                replayBridgeRegistration();
            });
            loadPortingLibConfigs();
            addFabricMobStats();
        } finally {
            holdTicks = false;
        }
        Timeline.mark("Fabric and Quilt mods started");
    }

    private static void replayBridgeRegistration() {
        if (!PentecostRuntime.forgeLayer || "1".equals(System.getenv("PENTECOST_FABRIC_EARLY"))) {
            return;
        }
        try {
            Object n = Class.forName("khi.pentecost.forgeglue.LateRegistry", true, PentecostRuntime.loader)
                    .getMethod("replayBridgeRegistration").invoke(null);
            Log.info("Handed Fabric mods' bridge registrations to Forge (" + n + " registries)");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            Throwable why = e instanceof java.lang.reflect.InvocationTargetException && e.getCause() != null ? e.getCause() : e;
            Log.warn("Could not hand Fabric mods' bridge registrations to Forge: " + why);
        }
    }

    private static void addFabricMobStats() {
        if (!PentecostRuntime.forgeLayer) {
            return;
        }
        try {
            Object n = Class.forName("khi.pentecost.forgeglue.LateRegistry", true, PentecostRuntime.loader)
                    .getMethod("addFabricAttributes").invoke(null);
            if (n instanceof Integer count && count > 0) {
                Log.info("Gave Forge the default stats of " + count + " mobs from Fabric mods");
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            Log.warn("Could not hand Fabric mob stats to Forge: " + e);
        }
    }

    private static void loadPortingLibConfigs() {
        ClassLoader cl = PentecostRuntime.loader;
        try {
            Class<?> tracker = Class.forName("io.github.fabricators_of_create.porting_lib.config.ConfigTracker", true, cl);
            Class<?> type = Class.forName("io.github.fabricators_of_create.porting_lib.config.ConfigType", true, cl);
            Object instance = tracker.getField("INSTANCE").get(null);
            Class<?> fl = Class.forName("net.fabricmc.loader.api.FabricLoader", true, cl);
            Object dir = fl.getMethod("getConfigDir").invoke(fl.getMethod("getInstance").invoke(null));
            java.lang.reflect.Method load = tracker.getMethod("loadConfigs", type, java.nio.file.Path.class);
            for (String t : new String[]{"CLIENT", "COMMON"}) {
                @SuppressWarnings({"unchecked", "rawtypes"})
                Object value = Enum.valueOf((Class) type, t);
                load.invoke(instance, value, dir);
            }
            Log.info("Loaded the config files of mods that use Porting Lib");
        } catch (ClassNotFoundException e) {
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            Throwable why = e instanceof java.lang.reflect.InvocationTargetException && e.getCause() != null ? e.getCause() : e;
            Log.warn("Could not load the config files of mods that use Porting Lib: " + why);
        }
    }

    private static volatile boolean registriesOpen;
    private static final java.util.List<Object> REOPENED = new java.util.ArrayList<>();
    private static final java.util.Set<String> REOPEN_REPORTED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static void openRegistries(Runnable work) {
        registriesOpen = true;
        try {
            work.run();
        } finally {
            registriesOpen = false;
            synchronized (REOPENED) {
                for (Object registry : REOPENED) {
                    try {
                        dropLeftoverHolders(registry);
                        registry.getClass().getMethod("freeze").invoke(registry);
                    } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                        Log.warn("Could not freeze " + registryName(registry) + " again: " + e);
                    }
                }
                REOPENED.clear();
            }
        }
    }

    private static void dropLeftoverHolders(Object registry) {
        try {
            Class<?> c = registry.getClass();
            while (c != null && !c.getName().equals("net.minecraft.core.MappedRegistry")) {
                c = c.getSuperclass();
            }
            if (c == null) {
                return;
            }
            java.lang.reflect.Field f = c.getDeclaredField("unregisteredIntrusiveHolders");
            f.setAccessible(true);
            Object map = f.get(registry);
            if (map instanceof java.util.Map<?, ?> m && !m.isEmpty()) {
                Log.warn("Registry " + registryName(registry) + ": dropping " + m.size()
                        + " half-made entr" + (m.size() == 1 ? "y" : "ies") + " a mod never registered");
                m.clear();
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
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

    public static void registryWrite(Object registry) {
        if (!registriesOpen || registry.getClass().getName().startsWith("net.minecraftforge.")) {
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
        }
    }

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

    public static volatile Runnable windowHints;
    public static volatile java.util.function.Consumer<Object> windowBorn;

    public static void beforeWindow() {
        startGlue();
        Runnable r = windowHints;
        if (r != null) {
            try {
                r.run();
            } catch (RuntimeException | LinkageError e) {
                Log.error("Window setup failed: " + e);
            }
        }
    }

    public static void afterWindow(Object window) {
        java.util.function.Consumer<Object> c = windowBorn;
        if (c != null) {
            try {
                c.accept(window);
            } catch (RuntimeException | LinkageError e) {
                Log.error("Window setup failed: " + e);
            }
        }
    }

    public static volatile java.util.function.Consumer<Object> iconSet;

    public static void afterIcon(Object window) {
        java.util.function.Consumer<Object> c = iconSet;
        if (c != null) {
            try {
                c.accept(window);
            } catch (RuntimeException | LinkageError e) {
                Log.error("Window icon failed: " + e);
            }
        }
    }

    public static ClassTransformer windowTransformer() {
        return (name, bytes) -> {
            if (!name.equals("com.mojang.blaze3d.platform.Window")) {
                return bytes;
            }
            ClassReader reader = new ClassReader(bytes);
            ClassWriter writer = new ClassWriter(0);
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                @Override
                public MethodVisitor visitMethod(int access, String n, String d, String s, String[] e) {
                    MethodVisitor mv = super.visitMethod(access, n, d, s, e);
                    if (n.equals("setIcon") && d.endsWith(")V")) {
                        return new MethodVisitor(Opcodes.ASM9, mv) {
                            @Override
                            public void visitInsn(int op) {
                                if (op == Opcodes.RETURN) {
                                    super.visitVarInsn(Opcodes.ALOAD, 0);
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC, "khi/pentecost/core/Hooks",
                                            "afterIcon", "(Ljava/lang/Object;)V", false);
                                }
                                super.visitInsn(op);
                            }

                            @Override
                            public void visitMaxs(int maxStack, int maxLocals) {
                                super.visitMaxs(Math.max(maxStack, 1) + 1, maxLocals);
                            }
                        };
                    }
                    if (!n.equals("<init>")) {
                        return mv;
                    }
                    return new MethodVisitor(Opcodes.ASM9, mv) {
                        private boolean born;

                        @Override
                        public void visitMethodInsn(int op, String owner, String mn, String md, boolean itf) {
                            if (owner.equals("org/lwjgl/glfw/GLFW") && mn.equals("glfwCreateWindow")) {
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, "khi/pentecost/core/Hooks",
                                        "beforeWindow", "()V", false);
                            }
                            if (!born && owner.equals("com/mojang/blaze3d/platform/Window") && mn.equals("setMode")) {
                                born = true;
                                super.visitVarInsn(Opcodes.ALOAD, 0);
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, "khi/pentecost/core/Hooks",
                                        "afterWindow", "(Ljava/lang/Object;)V", false);
                            }
                            super.visitMethodInsn(op, owner, mn, md, itf);
                        }

                        @Override
                        public void visitMaxs(int maxStack, int maxLocals) {
                            super.visitMaxs(maxStack + 1, maxLocals);
                        }
                    };
                }
            }, 0);
            return writer.toByteArray();
        };
    }

    private static volatile boolean fabricRegistriesSubmitted;

    public static void fabricRegistriesSubmit() {
        fabricRegistriesSubmitted = true;
    }

    public static void fabricRegistryAdded(Object registry) {
        if (registry == null) {
            return;
        }
        try {
            ClassLoader cl = registry.getClass().getClassLoader();
            Object root = Class.forName("net.minecraft.core.registries.BuiltInRegistries", true, cl)
                    .getField("REGISTRY").get(null);
            Object key = registry.getClass().getMethod("key").invoke(registry);
            Object existing = root.getClass().getMethod("get", Class.forName("net.minecraft.resources.ResourceKey", true, cl))
                    .invoke(root, key);
            if (existing != null) {
                if (existing != registry) {
                    Log.warn("Fabric registry " + key + " was made twice. The game keeps the first one,"
                            + " so the mod's own copy stays empty");
                    if (System.getenv("PENTECOST_DEBUG") != null) {
                        new Throwable("second copy made here").printStackTrace(System.out);
                    }
                }
                return;
            }
            Object stable = Class.forName("com.mojang.serialization.Lifecycle", true, cl).getMethod("stable").invoke(null);
            java.lang.reflect.Method register = null;
            for (java.lang.reflect.Method m : root.getClass().getMethods()) {
                if (m.getName().equals("register") && m.getParameterCount() == 3
                        && m.getParameterTypes()[2].getName().equals("com.mojang.serialization.Lifecycle")
                        && m.getParameterTypes()[0].getName().equals("net.minecraft.resources.ResourceKey")) {
                    register = m;
                    break;
                }
            }
            if (register == null) {
                Log.warn("A late Fabric registry could not be handed to the game: no register method");
                return;
            }
            java.lang.reflect.Field frozen = null;
            for (Class<?> c = root.getClass(); c != null && frozen == null; c = c.getSuperclass()) {
                try {
                    frozen = c.getDeclaredField("frozen");
                } catch (NoSuchFieldException ignored) {
                }
            }
            boolean wasFrozen = false;
            if (frozen != null) {
                frozen.setAccessible(true);
                wasFrozen = frozen.getBoolean(root);
                frozen.setBoolean(root, false);
            }
            try {
                register.invoke(root, key, registry, stable);
            } finally {
                if (frozen != null && wasFrozen) {
                    frozen.setBoolean(root, true);
                }
            }
            try {
                java.lang.reflect.Field list = Class.forName("net.fabricmc.fabric.impl.registry.sync.FabricRegistryInit", false, cl)
                        .getDeclaredField("REGISTRIES");
                list.setAccessible(true);
                ((java.util.List<?>) list.get(null)).remove(registry);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
            }
            Log.info("Fabric registry " + key + " added to the game right away, as on Fabric");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            Throwable why = e instanceof java.lang.reflect.InvocationTargetException && e.getCause() != null ? e.getCause() : e;
            Log.warn("A Fabric registry could not be added to the game: " + why);
            if (System.getenv("PENTECOST_DEBUG") != null) {
                why.printStackTrace(System.out);
            }
        }
    }

    public static ClassTransformer fabricRegistryTransformer() {
        return (name, bytes) -> {
            if (!name.equals("net.fabricmc.fabric.impl.registry.sync.FabricRegistryInit")) {
                return bytes;
            }
            ClassReader reader = new ClassReader(bytes);
            ClassWriter writer = new ClassWriter(0);
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                @Override
                public MethodVisitor visitMethod(int access, String n, String d, String s, String[] e) {
                    MethodVisitor mv = super.visitMethod(access, n, d, s, e);
                    boolean submit = n.equals("submitRegistries") && d.equals("()V");
                    boolean add = n.equals("addRegistry");
                    if (!submit && !add) {
                        return mv;
                    }
                    return new MethodVisitor(Opcodes.ASM9, mv) {
                        @Override
                        public void visitCode() {
                            super.visitCode();
                            if (submit) {
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, "khi/pentecost/core/Hooks",
                                        "fabricRegistriesSubmit", "()V", false);
                            }
                        }

                        @Override
                        public void visitInsn(int op) {
                            if (add && op == Opcodes.RETURN) {
                                super.visitVarInsn(Opcodes.ALOAD, 0);
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, "khi/pentecost/core/Hooks",
                                        "fabricRegistryAdded", "(Ljava/lang/Object;)V", false);
                            }
                            super.visitInsn(op);
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

    public static volatile boolean holdTicks;

    private static final AtomicBoolean TICK_HOOK_SEEN = new AtomicBoolean();
    private static final AtomicBoolean TICK_HELD_SEEN = new AtomicBoolean();
    private static final AtomicBoolean TICK_FREE_SEEN = new AtomicBoolean();

    public static boolean ticksHeld() {
        if (TICK_HOOK_SEEN.compareAndSet(false, true)) {
            Log.info("Tick hook reached");
        }
        boolean held = holdTicks;
        if (held && TICK_HELD_SEEN.compareAndSet(false, true)) {
            Log.info("Game ticks wait while Fabric and Quilt mods start, as on Fabric");
        }
        if (!held && TICK_HELD_SEEN.get() && TICK_FREE_SEEN.compareAndSet(false, true)) {
            Log.info("First tick after the wait, on " + Thread.currentThread().getName());
        }
        return held;
    }

    private static volatile java.lang.invoke.MethodHandle tickMethod;

    public static void tickUnlessHeld(Object minecraft) throws Throwable {
        if (ticksHeld()) {
            return;
        }
        java.lang.invoke.MethodHandle h = tickMethod;
        if (h == null) {
            h = java.lang.invoke.MethodHandles.publicLookup()
                    .findVirtual(minecraft.getClass(), "tick", java.lang.invoke.MethodType.methodType(void.class))
                    .asType(java.lang.invoke.MethodType.methodType(void.class, Object.class));
            tickMethod = h;
        }
        h.invokeExact(minecraft);
    }

    private static volatile java.lang.invoke.MethodHandle runTickMethod;
    private static volatile java.lang.invoke.MethodHandle pollEvents;
    private static final AtomicBoolean LOOP_HELD_SEEN = new AtomicBoolean();

    public static void runTickUnlessHeld(Object minecraft, boolean render) throws Throwable {
        if (holdTicks) {
            if (LOOP_HELD_SEEN.compareAndSet(false, true)) {
                Log.info("Game loop waits while Fabric and Quilt mods start, as on Fabric");
            }
            java.lang.invoke.MethodHandle poll = pollEvents;
            if (poll == null) {
                poll = java.lang.invoke.MethodHandles.publicLookup().findStatic(
                        Class.forName("org.lwjgl.glfw.GLFW", true, minecraft.getClass().getClassLoader()),
                        "glfwPollEvents", java.lang.invoke.MethodType.methodType(void.class));
                pollEvents = poll;
            }
            poll.invokeExact();
            Thread.sleep(10);
            return;
        }
        java.lang.invoke.MethodHandle h = runTickMethod;
        if (h == null) {
            java.lang.reflect.Method m = minecraft.getClass().getDeclaredMethod("runTick", boolean.class);
            m.setAccessible(true);
            h = java.lang.invoke.MethodHandles.lookup().unreflect(m)
                    .asType(java.lang.invoke.MethodType.methodType(void.class, Object.class, boolean.class));
            runTickMethod = h;
        }
        h.invokeExact(minecraft, render);
    }

    public static ClassTransformer tickTransformer() {
        return (name, bytes) -> {
            if (!name.equals("net.minecraft.client.Minecraft")) {
                return bytes;
            }
            ClassReader reader = new ClassReader(bytes);
            ClassWriter writer = new ClassWriter(0);
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                @Override
                public MethodVisitor visitMethod(int access, String n, String d, String s, String[] e) {
                    MethodVisitor mv = super.visitMethod(access, n, d, s, e);
                    boolean loop = n.equals("run") && d.equals("()V");
                    if (!n.equals("runTick") && !loop) {
                        return mv;
                    }
                    return new MethodVisitor(Opcodes.ASM9, mv) {
                        @Override
                        public void visitMethodInsn(int op, String owner, String mn, String md, boolean itf) {
                            if (loop && op == Opcodes.INVOKEVIRTUAL && owner.equals("net/minecraft/client/Minecraft")
                                    && mn.equals("runTick") && md.equals("(Z)V")) {
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, "khi/pentecost/core/Hooks",
                                        "runTickUnlessHeld", "(Ljava/lang/Object;Z)V", false);
                                return;
                            }
                            if (!loop && op == Opcodes.INVOKEVIRTUAL && owner.equals("net/minecraft/client/Minecraft")
                                    && mn.equals("tick") && md.equals("()V")) {
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, "khi/pentecost/core/Hooks",
                                        "tickUnlessHeld", "(Ljava/lang/Object;)V", false);
                                return;
                            }
                            super.visitMethodInsn(op, owner, mn, md, itf);
                        }
                    };
                }
            }, 0);
            return writer.toByteArray();
        };
    }

    public static ClassTransformer lootNameTransformer() {
        return (name, bytes) -> {
            if (!name.equals("net.minecraft.world.level.storage.loot.LootTable")) {
                return bytes;
            }
            ClassReader reader = new ClassReader(bytes);
            ClassWriter writer = new ClassWriter(0);
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                @Override
                public MethodVisitor visitMethod(int access, String n, String d, String s, String[] e) {
                    MethodVisitor mv = super.visitMethod(access, n, d, s, e);
                    if (!n.equals("setLootTableId") || !d.equals("(Lnet/minecraft/resources/ResourceLocation;)V")) {
                        return mv;
                    }
                    return new MethodVisitor(Opcodes.ASM9, mv) {
                        @Override
                        public void visitCode() {
                            super.visitCode();
                            org.objectweb.asm.Label go = new org.objectweb.asm.Label();
                            super.visitVarInsn(Opcodes.ALOAD, 0);
                            super.visitFieldInsn(Opcodes.GETFIELD, "net/minecraft/world/level/storage/loot/LootTable",
                                    "lootTableId", "Lnet/minecraft/resources/ResourceLocation;");
                            super.visitVarInsn(Opcodes.ALOAD, 1);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Objects", "equals",
                                    "(Ljava/lang/Object;Ljava/lang/Object;)Z", false);
                            super.visitJumpInsn(Opcodes.IFEQ, go);
                            super.visitInsn(Opcodes.RETURN);
                            super.visitLabel(go);
                            super.visitFrame(Opcodes.F_NEW, 2, new Object[]{
                                    "net/minecraft/world/level/storage/loot/LootTable",
                                    "net/minecraft/resources/ResourceLocation"}, 0, new Object[0]);
                        }

                        @Override
                        public void visitMaxs(int maxStack, int maxLocals) {
                            super.visitMaxs(Math.max(maxStack, 2), maxLocals);
                        }
                    };
                }
            }, ClassReader.EXPAND_FRAMES);
            return writer.toByteArray();
        };
    }

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

    public static volatile Runnable frame;

    public static volatile java.util.function.Consumer<Object> toasts;

    public static void afterToasts(Object graphics) {
        java.util.function.Consumer<Object> t = toasts;
        if (t != null) {
            try {
                t.accept(graphics);
            } catch (RuntimeException | LinkageError e) {
                toasts = null;
                Log.error("Pentecost's notices failed and were turned off: " + e);
            }
        }
    }

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
                            if (owner.equals("net/minecraft/client/gui/components/toasts/ToastComponent") && mn.equals("render")
                                    && md.equals("(Lnet/minecraft/client/gui/GuiGraphics;)V")) {
                                super.visitInsn(Opcodes.DUP_X1);
                                super.visitMethodInsn(op, owner, mn, md, itf);
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, "khi/pentecost/core/Hooks",
                                        "afterToasts", "(Ljava/lang/Object;)V", false);
                                return;
                            }
                            super.visitMethodInsn(op, owner, mn, md, itf);
                            if (owner.equals("net/minecraft/client/gui/Gui") && mn.equals("render")
                                    && md.equals("(Lnet/minecraft/client/gui/GuiGraphics;F)V")) {
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, "khi/pentecost/core/Hooks",
                                        "hud", "()V", false);
                            }
                        }

                        @Override
                        public void visitMaxs(int maxStack, int maxLocals) {
                            super.visitMaxs(maxStack + 1, maxLocals);
                        }
                    };
                }
            }, 0);
            return writer.toByteArray();
        };
    }

    private static volatile Object[] hudCallback;
    private static volatile boolean hudCallbackLooked;

    public static void hudRender(Object graphics, float partialTick) {
        Object[] cb = hudCallback;
        if (cb == null) {
            if (hudCallbackLooked) {
                return;
            }
            hudCallbackLooked = true;
            cb = resolveHudCallback();
            if (cb == null) {
                return;
            }
            hudCallback = cb;
        }
        if (cb.length > 4 && cb[3] instanceof java.lang.invoke.MethodHandle invokerHandle
                && cb[4] instanceof java.lang.invoke.MethodHandle callHandle) {
            try {
                Object invoker = (Object) invokerHandle.invokeExact();
                callHandle.invokeExact(invoker, graphics, partialTick);
                return;
            } catch (Throwable handleFailed) {
                hudCallback = new Object[]{cb[0], cb[1], cb[2]};
            }
        }
        try {
            Object invoker = ((java.lang.reflect.Method) cb[1]).invoke(cb[0]);
            ((java.lang.reflect.Method) cb[2]).invoke(invoker, graphics, partialTick);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            hudCallback = null;
            Throwable why = e.getCause() != null ? e.getCause() : e;
            Log.error("Fabric HUD callbacks failed and were turned off: " + why);
        }
    }

    private static Object[] resolveHudCallback() {
        ClassLoader cl = PentecostRuntime.loader;
        if (cl == null) {
            hudCallbackLooked = false;
            return null;
        }
        try {
            Class<?> cb = Class.forName("net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback", false, cl);
            Object event = cb.getField("EVENT").get(null);
            java.lang.reflect.Method invoker = event.getClass().getMethod("invoker");
            java.lang.reflect.Method call = null;
            for (java.lang.reflect.Method m : cb.getMethods()) {
                if (m.getName().equals("onHudRender")) {
                    call = m;
                }
            }
            if (call == null) {
                return null;
            }
            Log.info("Forge layer: Pentecost fires Fabric HUD callbacks after Forge's HUD");
            try {
                java.lang.invoke.MethodHandles.Lookup lookup = java.lang.invoke.MethodHandles.lookup();
                java.lang.invoke.MethodHandle invokerHandle = lookup.unreflect(invoker).bindTo(event)
                        .asType(java.lang.invoke.MethodType.methodType(Object.class));
                java.lang.invoke.MethodHandle callHandle = lookup.unreflect(call).asType(
                        java.lang.invoke.MethodType.methodType(void.class, Object.class, Object.class, float.class));
                return new Object[]{event, invoker, call, invokerHandle, callHandle};
            } catch (ReflectiveOperationException | RuntimeException | LinkageError handles) {
                return new Object[]{event, invoker, call};
            }
        } catch (ClassNotFoundException noFabricApi) {
            return null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            Log.error("Could not find Fabric's HUD callback: " + e);
            return null;
        }
    }

    public static ClassTransformer forgeHudTransformer() {
        return (name, bytes) -> {
            if (bytes == null || !"net.minecraftforge.client.gui.overlay.ForgeGui".equals(name)) {
                return bytes;
            }
            ClassReader reader = new ClassReader(bytes);
            ClassWriter writer = new ClassWriter(0);
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                @Override
                public MethodVisitor visitMethod(int access, String n, String d, String s, String[] e) {
                    MethodVisitor mv = super.visitMethod(access, n, d, s, e);
                    if (!n.equals("render") || !d.equals("(Lnet/minecraft/client/gui/GuiGraphics;F)V")) {
                        return mv;
                    }
                    return new MethodVisitor(Opcodes.ASM9, mv) {
                        @Override
                        public void visitInsn(int op) {
                            if (op == Opcodes.RETURN) {
                                super.visitVarInsn(Opcodes.ALOAD, 1);
                                super.visitVarInsn(Opcodes.FLOAD, 2);
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, "khi/pentecost/core/Hooks",
                                        "hudRender", "(Ljava/lang/Object;F)V", false);
                            }
                            super.visitInsn(op);
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

    private static final AtomicBoolean GLUE = new AtomicBoolean();

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

    public interface Style {
        boolean widget(Object widget, Object graphics, int mouseX, int mouseY, float partialTick);

        boolean background(Object screen, Object graphics);

        void inited(Object screen);

        void drawn(Object screen, Object graphics);

        boolean tooltip(Object graphics, int x, int y, int width, int height, int z);

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

    public static ClassTransformer styleTransformer() {
        return (name, bytes) -> {
            boolean widget = STYLED.contains(name);
            boolean screen = name.equals("net.minecraft.client.gui.screens.Screen");
            boolean tip = name.equals("net.minecraft.client.gui.screens.inventory.tooltip.TooltipRenderUtil");
            boolean debugVanilla = name.equals("net.minecraft.client.gui.components.DebugScreenOverlay");
            boolean debugForge = name.equals("net.minecraftforge.client.gui.overlay.ForgeGui");
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

    public static volatile java.util.function.UnaryOperator<Object> overlayRouter;

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
