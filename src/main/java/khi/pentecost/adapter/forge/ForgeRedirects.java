package khi.pentecost.adapter.forge;

import khi.pentecost.boot.Log;
import khi.pentecost.core.PentecostRuntime;
import khi.pentecost.loader.ClassTransformer;
import khi.pentecost.remap.Mappings;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * Forge's code expects Forge's own launcher underneath it. Pentecost is underneath instead.
 * This class rewires the few places where Forge reaches down, and holds the code they land in.
 * Everything else in Forge runs unchanged.
 */
public final class ForgeRedirects {
    private static final String SELF = "khi/pentecost/adapter/forge/ForgeRedirects";

    private ForgeRedirects() {}

    // ------------------------------------------------------------------ landing points

    /** Forge looks up a Java module per mod. Pentecost has one class loader, so any module stands in. */
    public static Optional<Module> findModule(ModuleLayer layer, String name) {
        return Optional.of(Object.class.getModule());
    }

    /** Forge loads a mod class from its module. Pentecost loads it from the game class loader. */
    public static Class<?> forName(Module module, String name) {
        try {
            return Class.forName(name, false, PentecostRuntime.loader);
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    /** Forge prints its launcher's name in crash reports. */
    public static String getLauncherInfo() {
        return "Pentecost";
    }

    /** Forge lists its launcher's plugins in crash reports. Pentecost has none of those. */
    public static java.util.List<Map<String, String>> modLauncherModList() {
        return java.util.List.of();
    }

    /** Forge finds its plug-in services through a Java module layer. Pentecost finds them through the game class loader. */
    public static <T> java.util.ServiceLoader<T> loadServices(ModuleLayer layer, Class<T> type) {
        return java.util.ServiceLoader.load(type, PentecostRuntime.loader);
    }

    private static volatile Mappings.NameTable table;

    /**
     * Forge mods look up game members by SRG name through reflection.
     * The game runs in readable names here, so this converts the name first.
     */
    public static Optional<BiFunction<Object, String, String>> nameFunction(String naming) {
        return Optional.of((domain, name) -> {
            Mappings.NameTable t = table;
            if (t == null) {
                try {
                    t = table = PentecostRuntime.remap.modTable();
                } catch (Exception e) {
                    Log.error("Name tables failed to load: " + e);
                    return name;
                }
            }
            String hit = t.methods().get(name);
            if (hit == null) {
                hit = t.fields().get(name);
            }
            return hit == null ? name : hit;
        });
    }

    // ------------------------------------------------------------------ rewiring

    /** Method bodies replaced outright. Key: class name, then method name plus descriptor. */
    private static final Map<String, Map<String, String>> BODIES = new HashMap<>();
    /** Call sites replaced inside chosen classes. Key: owner.name+descriptor of the call. */
    private static final Map<String, String[]> CALLS = new HashMap<>();
    private static final Set<String> CALL_CLASSES = new HashSet<>();

    static {
        // The event bus asks Forge's launcher to build listener classes. The plain class loader path works here.
        body("net.minecraftforge.eventbus.BusBuilderImpl", "useModLauncher()Lnet/minecraftforge/eventbus/api/BusBuilder;", "this");
        body("net.minecraftforge.fml.loading.FMLLoader",
                "getNameFunction(Ljava/lang/String;)Ljava/util/Optional;", "nameFunction");
        body("net.minecraftforge.fml.loading.FMLLoader", "isSecureJarEnabled()Z", "false");
        body("net.minecraftforge.fml.ModList", "isLoaded(Ljava/lang/String;)Z", "isModLoaded");
        body("net.minecraftforge.coremod.api.ASMAPI", "mapMethod(Ljava/lang/String;)Ljava/lang/String;", "mapSrgName");
        body("net.minecraftforge.coremod.api.ASMAPI", "mapField(Ljava/lang/String;)Ljava/lang/String;", "mapSrgName");
        body("net.minecraftforge.fml.loading.FMLLoader", "getLauncherInfo()Ljava/lang/String;", "getLauncherInfo");
        body("net.minecraftforge.fml.loading.FMLLoader", "modLauncherModList()Ljava/util/List;", "modLauncherModList");

        call("java/lang/ModuleLayer.findModule(Ljava/lang/String;)Ljava/util/Optional;",
                "findModule", "(Ljava/lang/ModuleLayer;Ljava/lang/String;)Ljava/util/Optional;");
        call("java/lang/Class.forName(Ljava/lang/Module;Ljava/lang/String;)Ljava/lang/Class;",
                "forName", "(Ljava/lang/Module;Ljava/lang/String;)Ljava/lang/Class;");
        call("java/util/ServiceLoader.load(Ljava/lang/ModuleLayer;Ljava/lang/Class;)Ljava/util/ServiceLoader;",
                "loadServices", "(Ljava/lang/ModuleLayer;Ljava/lang/Class;)Ljava/util/ServiceLoader;");
        // Forge code reads shared settings from its launcher's board. There is no Forge launcher here.
        callTo("cpw/mods/modlauncher/Launcher.blackboard()Lcpw/mods/modlauncher/api/TypesafeMap;",
                "khi/pentecost/forgeglue/CoreMods", "blackboard",
                "(Lcpw/mods/modlauncher/Launcher;)Lcpw/mods/modlauncher/api/TypesafeMap;");
        CALL_CLASSES.add("net.minecraftforge.fml.javafmlmod.FMLModContainer");
        CALL_CLASSES.add("net.minecraftforge.fml.lowcodemod.LowCodeModContainer");
    }

    private static final String DELEGATE_CALL = "net/minecraftforge/registries/IForgeRegistry.getDelegateOrThrow"
            + "(Ljava/lang/Object;)Lnet/minecraft/core/Holder$Reference;";

    /**
     * Forge's versions of some game objects (flowers, music discs) look up the registry entry
     * of what they hold as they are made, and keep it as a supplier. Fabric mods make them before
     * that entry is registered. Where the result goes straight into a supplier field,
     * the lookup is swapped for one that falls back to the plain value.
     */
    private static byte[] looseDelegates(byte[] bytes) {
        org.objectweb.asm.tree.ClassNode node = new org.objectweb.asm.tree.ClassNode();
        new ClassReader(bytes).accept(node, 0);
        boolean changed = false;
        for (org.objectweb.asm.tree.MethodNode m : node.methods) {
            for (org.objectweb.asm.tree.AbstractInsnNode insn : m.instructions.toArray()) {
                if (!(insn instanceof org.objectweb.asm.tree.MethodInsnNode call)
                        || !(call.owner + "." + call.name + call.desc).equals(DELEGATE_CALL)) {
                    continue;
                }
                org.objectweb.asm.tree.AbstractInsnNode next = call.getNext();
                while (next != null && next.getOpcode() < 0) {
                    next = next.getNext();
                }
                if (next instanceof org.objectweb.asm.tree.FieldInsnNode f && f.getOpcode() == Opcodes.PUTFIELD
                        && f.desc.equals("Ljava/util/function/Supplier;")) {
                    m.instructions.set(call, new org.objectweb.asm.tree.MethodInsnNode(Opcodes.INVOKESTATIC, SELF,
                            "delegateOrValue", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/util/function/Supplier;", false));
                } else {
                    // Items and blocks carry their own registry handle from birth. Until they are registered,
                    // that handle stands in (item and block colors are set up this early by Fabric mods).
                    m.instructions.set(call, new org.objectweb.asm.tree.MethodInsnNode(Opcodes.INVOKESTATIC,
                            "khi/pentecost/forgeglue/LateRegistry", "delegateOrOwnHolder",
                            "(Ljava/lang/Object;Ljava/lang/Object;)Lnet/minecraft/core/Holder$Reference;", false));
                }
                changed = true;
            }
        }
        if (!changed) {
            return bytes;
        }
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    /**
     * Minecraft builds its crash report text in getFriendlyReport. Every return of that method goes through
     * CrashDetails.decorate, which puts Pentecost's summary above the game's text and its details after it.
     * The game blames the wrong mod often (it named Fabric Biome API for a Curios crash), so the summary reads the trace itself.
     */
    private static byte[] decorateCrashReport(byte[] bytes) {
        org.objectweb.asm.tree.ClassNode node = new org.objectweb.asm.tree.ClassNode();
        new ClassReader(bytes).accept(node, 0);
        boolean changed = false;
        for (org.objectweb.asm.tree.MethodNode m : node.methods) {
            if (!m.name.equals("getFriendlyReport") || !m.desc.equals("()Ljava/lang/String;")) {
                continue;
            }
            for (org.objectweb.asm.tree.AbstractInsnNode insn : m.instructions.toArray()) {
                if (insn.getOpcode() != Opcodes.ARETURN) {
                    continue;
                }
                // Stack before: the text. After ALOAD 0 and SWAP: the report, then the text. decorate returns the new text.
                org.objectweb.asm.tree.InsnList pre = new org.objectweb.asm.tree.InsnList();
                pre.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, 0));
                pre.add(new org.objectweb.asm.tree.InsnNode(Opcodes.SWAP));
                pre.add(new org.objectweb.asm.tree.MethodInsnNode(Opcodes.INVOKESTATIC, "khi/pentecost/crash/CrashDetails",
                        "decorate", "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/String;", false));
                m.instructions.insertBefore(insn, pre);
                changed = true;
            }
        }
        if (!changed) {
            Log.warn("CrashReport.getFriendlyReport was not found, so the game's crash reports get no Pentecost summary");
            return bytes;
        }
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }
    private static boolean mentions(byte[] bytes, String text) {
        byte[] t = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        outer:
        for (int i = 0; i + t.length <= bytes.length; i++) {
            for (int j = 0; j < t.length; j++) {
                if (bytes[i + j] != t[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    /** Landing point in Forge's flower blocks: the registry entry if it exists, else the value itself. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static java.util.function.Supplier<Object> delegateOrValue(Object registry, Object value) {
        try {
            Object found = registry.getClass().getMethod("getDelegate", Object.class).invoke(registry, value);
            if (found instanceof Optional<?> o && o.isPresent() && o.get() instanceof java.util.function.Supplier sup) {
                return sup;
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            // fall through to the plain value
        }
        return () -> value;
    }

    /**
     * Landing point of Forge's "is this mod loaded". Pentecost's mod list answers, so a Forge mod
     * finds a Fabric or Quilt mod the same way it finds a Forge one.
     */
    public static boolean isModLoaded(String id) {
        if ("minecraft".equals(id) || "forge".equals(id)) {
            return true;
        }
        for (khi.pentecost.mods.ModInfo m : khi.pentecost.core.PentecostRuntime.mods) {
            if (m.loader() == khi.pentecost.mods.LoaderKind.LIBRARY) {
                continue;
            }
            if (m.id().equals(id)) {
                return true;
            }
            if (m.raw() != null && m.raw().has("provides") && m.raw().get("provides").isJsonArray()) {
                for (com.google.gson.JsonElement e : m.raw().getAsJsonArray("provides")) {
                    if (e.isJsonPrimitive() && e.getAsString().equals(id)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Landing point of the coremod name lookups: a member name from Forge's game build to the readable name. */
    public static String mapSrgName(String name) {
        return nameFunction("srg").map(f -> f.apply(null, name)).orElse(name);
    }

    private static void body(String cls, String method, String kind) {
        BODIES.computeIfAbsent(cls, k -> new HashMap<>()).put(method, kind);
    }

    private static void call(String site, String name, String desc) {
        CALLS.put(site, new String[]{name, desc});
    }

    private static void callTo(String site, String owner, String name, String desc) {
        CALLS.put(site, new String[]{name, desc, owner});
    }

    public static ClassTransformer transformer() {
        return (name, bytes) -> {
            if (name.equals("net.minecraft.CrashReport")) {
                bytes = decorateCrashReport(bytes);
            }
            if (name.startsWith("net.minecraft.") && mentions(bytes, "getDelegateOrThrow")) {
                bytes = looseDelegates(bytes);
            }
            Map<String, String> bodies = BODIES.get(name);
            boolean calls = CALL_CLASSES.contains(name)
                    || (name.startsWith("net.minecraftforge.") && !name.startsWith("net.minecraftforge.eventbus."))
                    || name.startsWith("thedarkcolour.kotlinforforge.");
            if (bodies == null && !calls) {
                return bytes;
            }
            ClassReader reader = new ClassReader(bytes);
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                @Override
                public MethodVisitor visitMethod(int access, String n, String d, String s, String[] e) {
                    String kind = bodies == null ? null : bodies.get(n + d);
                    MethodVisitor mv = super.visitMethod(access, n, d, s, e);
                    if (kind != null) {
                        writeBody(mv, access, d, kind);
                        return null;
                    }
                    if (!calls) {
                        return mv;
                    }
                    boolean registryDoor = name.equals("net.minecraftforge.registries.NamespacedWrapper")
                            && ((n.equals("registerMapping")
                            && d.startsWith("(ILnet/minecraft/resources/ResourceKey;Ljava/lang/Object;"))
                            || n.equals("validateWrite"));
                    boolean freezeDoor = name.equals("net.minecraftforge.registries.NamespacedWrapper")
                            && n.equals("freeze") && d.equals("()Lnet/minecraft/core/Registry;");
                    boolean queueDoor = name.equals("net.minecraftforge.fml.DeferredWorkQueue")
                            && n.equals("runTasks") && d.equals("()V");
                    boolean stepDoor = name.equals("net.minecraftforge.fml.ModLoader")
                            && n.equals("dispatchAndHandleError");
                    boolean clientHooksDoor = name.equals("net.minecraftforge.client.ForgeHooksClient")
                            && n.equals("initClientHooks");
                    // The end of Forge's first loading phase on the client: registries are filled, and the game
                    // has not yet built its item models, colors and renderers. Fabric mods start here.
                    boolean beginDoor = name.equals("net.minecraftforge.client.loading.ClientModLoader")
                            && n.equals("begin");
                    return new MethodVisitor(Opcodes.ASM9, mv) {
                        @Override
                        public void visitInsn(int op) {
                            if (beginDoor && op == Opcodes.RETURN) {
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, "khi/pentecost/core/Hooks",
                                        "forgeClientHooks", "()V", false);
                            }
                            super.visitInsn(op);
                        }

                        @Override
                        public void visitCode() {
                            super.visitCode();
                            if (queueDoor) {
                                super.visitVarInsn(Opcodes.ALOAD, 0);
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, "khi/pentecost/core/Hooks",
                                        "forgeDeferred", "(Ljava/lang/Object;)V", false);
                            }
                            if (clientHooksDoor) {
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, "khi/pentecost/core/Hooks",
                                        "forgeClientHooks", "()V", false);
                            }
                            if (stepDoor) {
                                super.visitVarInsn(Opcodes.ALOAD, 1);
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, "khi/pentecost/core/Hooks",
                                        "forgeState", "(Ljava/lang/Object;)V", false);
                            }
                            if (registryDoor) {
                                super.visitVarInsn(Opcodes.ALOAD, 0);
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, "khi/pentecost/forgeglue/LateRegistry",
                                        "open", "(Ljava/lang/Object;)V", false);
                            }
                            if (freezeDoor) {
                                super.visitVarInsn(Opcodes.ALOAD, 0);
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, "khi/pentecost/forgeglue/LateRegistry",
                                        "beforeFreeze", "(Ljava/lang/Object;)V", false);
                            }
                        }

                        @Override
                        public void visitMethodInsn(int op, String owner, String mn, String md, boolean itf) {
                            String[] to = CALLS.get(owner + "." + mn + md);
                            if (to != null) {
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, to.length > 2 ? to[2] : SELF, to[0], to[1], false);
                            } else {
                                super.visitMethodInsn(op, owner, mn, md, itf);
                            }
                        }
                    };
                }
            }, 0);
            return writer.toByteArray();
        };
    }

    private static void writeBody(MethodVisitor mv, int access, String desc, String kind) {
        mv.visitCode();
        switch (kind) {
            case "this" -> {
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitInsn(Opcodes.ARETURN);
            }
            case "false" -> {
                mv.visitInsn(Opcodes.ICONST_0);
                mv.visitInsn(Opcodes.IRETURN);
            }
            case "true" -> {
                mv.visitInsn(Opcodes.ICONST_1);
                mv.visitInsn(Opcodes.IRETURN);
            }
            case "void" -> mv.visitInsn(Opcodes.RETURN);
            default -> {
                // Pass the arguments on to the landing point of the same name and return its result.
                int slot = (access & Opcodes.ACC_STATIC) != 0 ? 0 : 1;
                for (Type t : Type.getArgumentTypes(desc)) {
                    mv.visitVarInsn(t.getOpcode(Opcodes.ILOAD), slot);
                    slot += t.getSize();
                }
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, SELF, kind, desc, false);
                mv.visitInsn(Type.getReturnType(desc).getOpcode(Opcodes.IRETURN));
            }
        }
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }
}
