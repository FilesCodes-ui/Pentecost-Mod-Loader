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

public final class ForgeRedirects {
    private static final String SELF = "khi/pentecost/adapter/forge/ForgeRedirects";

    private ForgeRedirects() {}

    public static Optional<Module> findModule(ModuleLayer layer, String name) {
        return Optional.of(Object.class.getModule());
    }

    public static Class<?> forName(Module module, String name) {
        try {
            return Class.forName(name, false, PentecostRuntime.loader);
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    public static String getLauncherInfo() {
        return "Pentecost";
    }

    public static java.util.List<Map<String, String>> modLauncherModList() {
        return java.util.List.of();
    }

    public static <T> java.util.ServiceLoader<T> loadServices(ModuleLayer layer, Class<T> type) {
        return java.util.ServiceLoader.load(type, PentecostRuntime.loader);
    }

    private static volatile Mappings.NameTable table;

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

    private static final Map<String, Map<String, String>> BODIES = new HashMap<>();
    private static final Map<String, String[]> CALLS = new HashMap<>();
    private static final Set<String> CALL_CLASSES = new HashSet<>();

    static {
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
        callTo("cpw/mods/modlauncher/Launcher.blackboard()Lcpw/mods/modlauncher/api/TypesafeMap;",
                "khi/pentecost/forgeglue/CoreMods", "blackboard",
                "(Lcpw/mods/modlauncher/Launcher;)Lcpw/mods/modlauncher/api/TypesafeMap;");
        CALL_CLASSES.add("net.minecraftforge.fml.javafmlmod.FMLModContainer");
        CALL_CLASSES.add("net.minecraftforge.fml.lowcodemod.LowCodeModContainer");
    }

    private static final String DELEGATE_CALL = "net/minecraftforge/registries/IForgeRegistry.getDelegateOrThrow"
            + "(Ljava/lang/Object;)Lnet/minecraft/core/Holder$Reference;";

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

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static java.util.function.Supplier<Object> delegateOrValue(Object registry, Object value) {
        try {
            Object found = registry.getClass().getMethod("getDelegate", Object.class).invoke(registry, value);
            if (found instanceof Optional<?> o && o.isPresent() && o.get() instanceof java.util.function.Supplier sup) {
                return sup;
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
        }
        return () -> value;
    }

    public static boolean isModLoaded(String id) {
        if ("minecraft".equals(id) || "forge".equals(id)) {
            return true;
        }
        return khi.pentecost.mods.ModFinder.find(khi.pentecost.core.PentecostRuntime.mods, id, true).isPresent();
    }

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
                public void visitEnd() {
                    if (name.equals("net.minecraftforge.fml.config.ModConfig")) {
                        addModConfigConstructors(cv);
                    }
                    super.visitEnd();
                }

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

    private static void addModConfigConstructors(ClassVisitor cv) {
        String type = "Lnet/minecraftforge/fml/config/ModConfig$Type;";
        String spec = "Lnet/minecraftforge/fml/config/IConfigSpec;";
        String box = "Lnet/minecraftforge/fml/ModContainer;";
        String shim = "khi/pentecost/forgeglue/ConfigShim";
        String owner = "net/minecraftforge/fml/config/ModConfig";
        MethodVisitor three = cv.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(" + type + spec + "Ljava/lang/String;)V", null, null);
        three.visitCode();
        three.visitVarInsn(Opcodes.ALOAD, 0);
        three.visitVarInsn(Opcodes.ALOAD, 1);
        three.visitVarInsn(Opcodes.ALOAD, 2);
        three.visitVarInsn(Opcodes.ALOAD, 3);
        three.visitMethodInsn(Opcodes.INVOKESTATIC, shim, "container", "(Ljava/lang/String;)" + box, false);
        three.visitMethodInsn(Opcodes.INVOKESPECIAL, owner, "<init>", "(" + type + spec + box + ")V", false);
        three.visitInsn(Opcodes.RETURN);
        three.visitMaxs(0, 0);
        three.visitEnd();
        MethodVisitor four = cv.visitMethod(Opcodes.ACC_PUBLIC, "<init>",
                "(" + type + spec + "Ljava/lang/String;Ljava/lang/String;)V", null, null);
        four.visitCode();
        four.visitVarInsn(Opcodes.ALOAD, 0);
        four.visitVarInsn(Opcodes.ALOAD, 1);
        four.visitVarInsn(Opcodes.ALOAD, 2);
        four.visitVarInsn(Opcodes.ALOAD, 3);
        four.visitMethodInsn(Opcodes.INVOKESTATIC, shim, "container", "(Ljava/lang/String;)" + box, false);
        four.visitVarInsn(Opcodes.ALOAD, 4);
        four.visitMethodInsn(Opcodes.INVOKESPECIAL, owner, "<init>", "(" + type + spec + box + "Ljava/lang/String;)V", false);
        four.visitInsn(Opcodes.RETURN);
        four.visitMaxs(0, 0);
        four.visitEnd();
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
