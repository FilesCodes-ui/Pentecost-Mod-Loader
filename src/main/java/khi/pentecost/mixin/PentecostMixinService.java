package khi.pentecost.mixin;

import khi.pentecost.loader.GameClassLoader;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.launch.platform.container.ContainerHandleVirtual;
import org.spongepowered.asm.launch.platform.container.IContainerHandle;
import org.spongepowered.asm.logging.ILogger;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.transformer.IMixinTransformerFactory;
import org.spongepowered.asm.service.IClassBytecodeProvider;
import org.spongepowered.asm.service.IClassProvider;
import org.spongepowered.asm.service.IClassTracker;
import org.spongepowered.asm.service.IMixinAuditTrail;
import org.spongepowered.asm.service.IMixinInternal;
import org.spongepowered.asm.service.ITransformerProvider;
import org.spongepowered.asm.service.MixinServiceAbstract;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Collection;
import java.util.List;

/** Connects the Mixin library to the Pentecost class loader. Pentecost is the host. No other loader runs. */
public class PentecostMixinService extends MixinServiceAbstract implements IClassProvider, IClassBytecodeProvider {
    static volatile GameClassLoader loader;
    static volatile IMixinTransformerFactory factory;

    public String getName() {
        return "Pentecost";
    }

    public boolean isValid() {
        return true;
    }

    public MixinEnvironment.Phase getInitialPhase() {
        return MixinEnvironment.Phase.PREINIT;
    }

    public MixinEnvironment.CompatibilityLevel getMinCompatibilityLevel() {
        return MixinEnvironment.CompatibilityLevel.JAVA_8;
    }

    public void offer(IMixinInternal internal) {
        if (internal instanceof IMixinTransformerFactory f) {
            factory = f;
        }
        super.offer(internal);
    }

    protected ILogger createLogger(String name) {
        return new MixinLog(name);
    }

    public IClassProvider getClassProvider() {
        return this;
    }

    public IClassBytecodeProvider getBytecodeProvider() {
        return this;
    }

    public ITransformerProvider getTransformerProvider() {
        return null;
    }

    public IClassTracker getClassTracker() {
        return null;
    }

    public IMixinAuditTrail getAuditTrail() {
        return null;
    }

    public Collection<String> getPlatformAgents() {
        return List.of("org.spongepowered.asm.launch.platform.MixinPlatformAgentDefault");
    }

    public IContainerHandle getPrimaryContainer() {
        return new ContainerHandleVirtual(getName());
    }

    /**
     * Mods whose hooks may miss their target without stopping the game. On the Forge layer these are
     * the Fabric and Quilt mods: they were written for the unpatched game. Forge mods stay strict.
     */
    public static volatile java.util.Set<String> softMods = java.util.Set.of();

    /** Hooks the player turned off. Hook list file name to the entries to leave out. */
    public static volatile java.util.Map<String, java.util.Set<String>> droppedMixins = java.util.Map.of();

    private static void drop(com.google.gson.JsonObject cfg, java.util.Set<String> entries) {
        if (entries == null) {
            return;
        }
        for (String list : new String[]{"mixins", "client", "server"}) {
            if (cfg.has(list) && cfg.get(list).isJsonArray()) {
                com.google.gson.JsonArray kept = new com.google.gson.JsonArray();
                for (com.google.gson.JsonElement e : cfg.getAsJsonArray(list)) {
                    if (!entries.contains(e.getAsString())) {
                        kept.add(e);
                    }
                }
                cfg.add(list, kept);
            }
        }
    }

    private static boolean soft(String modId) {
        return modId != null && softMods.contains(modId);
    }

    public InputStream getResourceAsStream(String name) {
        java.util.Set<String> dropped = droppedMixins.get(name);
        boolean softCfg = name.endsWith(".json") && soft(loader.modOfResource(name));
        if (softCfg || dropped != null) {
            try (InputStream in = loader.getResourceAsStream(name)) {
                if (in == null) {
                    return null;
                }
                byte[] raw = in.readAllBytes();
                try {
                    com.google.gson.JsonElement e = com.google.gson.JsonParser.parseString(
                            new String(raw, java.nio.charset.StandardCharsets.UTF_8));
                    if (e.isJsonObject() && e.getAsJsonObject().has("package")) {
                        com.google.gson.JsonObject cfg = e.getAsJsonObject();
                        com.google.gson.JsonObject inj = cfg.has("injectors") && cfg.get("injectors").isJsonObject()
                                ? cfg.getAsJsonObject("injectors") : new com.google.gson.JsonObject();
                        if (softCfg) {
                            inj.addProperty("defaultRequire", 0);
                            cfg.add("injectors", inj);
                            cfg.addProperty("required", false);
                        }
                        drop(cfg, dropped);
                        raw = cfg.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    }
                } catch (RuntimeException notJson) {
                    // Not a mixin config. Hand it over unchanged.
                }
                return new java.io.ByteArrayInputStream(raw);
            } catch (IOException e) {
                return null;
            }
        }
        return loader.getResourceAsStream(name);
    }

    // ---- IClassProvider

    public URL[] getClassPath() {
        return new URL[0];
    }

    public Class<?> findClass(String name) throws ClassNotFoundException {
        return loader.loadClass(name);
    }

    public Class<?> findClass(String name, boolean initialize) throws ClassNotFoundException {
        return Class.forName(name, initialize, loader);
    }

    public Class<?> findAgentClass(String name, boolean initialize) throws ClassNotFoundException {
        return Class.forName(name, initialize, PentecostMixinService.class.getClassLoader());
    }

    // ---- IClassBytecodeProvider

    public ClassNode getClassNode(String name) throws ClassNotFoundException, IOException {
        return getClassNode(name, true, 0);
    }

    public ClassNode getClassNode(String name, boolean runTransformers) throws ClassNotFoundException, IOException {
        return getClassNode(name, runTransformers, 0);
    }

    public ClassNode getClassNode(String name, boolean runTransformers, int readerFlags)
            throws ClassNotFoundException, IOException {
        byte[] bytes = loader.preMixinBytes(name);
        if (bytes == null) {
            throw new ClassNotFoundException(name);
        }
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, readerFlags);
        if (isMixin(node)) {
            renameClashingFields(node);
            guardHooks(node, loader.modOf(name.replace('/', '.')));
        }
        if (!softMods.isEmpty() && isMixin(node) && soft(loader.modOf(name.replace('/', '.')))) {
            for (org.objectweb.asm.tree.MethodNode m : node.methods) {
                softRequire(m.visibleAnnotations);
                softRequire(m.invisibleAnnotations);
            }
        }
        return node;
    }

    private static final java.util.Map<String, java.util.Set<String>> TARGET_FIELDS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Set<String> RENAMED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Gives a mixin's own new fields a Pentecost prefix when their name is already a field of the target.
     * Shadow fields mean the game's field on purpose and stay as they are.
     */
    private void renameClashingFields(ClassNode node) {
        if (node.fields == null || node.fields.isEmpty()) {
            return;
        }
        java.util.List<String> targets = mixinTargets(node);
        if (targets.isEmpty()) {
            return;
        }
        java.util.Map<String, String> renames = new java.util.HashMap<>();
        for (org.objectweb.asm.tree.FieldNode f : node.fields) {
            if (has(f.visibleAnnotations, "Lorg/spongepowered/asm/mixin/Shadow;")
                    || has(f.invisibleAnnotations, "Lorg/spongepowered/asm/mixin/Shadow;")) {
                continue;
            }
            for (String target : targets) {
                if (fieldsOf(target).contains(f.name + " " + f.desc) || fieldsOf(target).contains(f.name)) {
                    renames.put(f.name + " " + f.desc, "pentecost$" + f.name);
                    break;
                }
            }
        }
        if (renames.isEmpty()) {
            return;
        }
        for (org.objectweb.asm.tree.FieldNode f : node.fields) {
            String to = renames.get(f.name + " " + f.desc);
            if (to != null) {
                if (RENAMED.add(node.name + "." + f.name)) {
                    khi.pentecost.boot.Log.info("Mixin " + node.name.substring(node.name.lastIndexOf('/') + 1)
                            + " adds a field named like a game field (" + f.name + "). Renamed it so the two stay apart");
                }
                f.name = to;
            }
        }
        for (org.objectweb.asm.tree.MethodNode m : node.methods) {
            if (m.instructions == null) {
                continue;
            }
            for (org.objectweb.asm.tree.AbstractInsnNode insn : m.instructions) {
                if (insn instanceof org.objectweb.asm.tree.FieldInsnNode fi && fi.owner.equals(node.name)) {
                    String to = renames.get(fi.name + " " + fi.desc);
                    if (to != null) {
                        fi.name = to;
                    }
                }
            }
        }
    }

    /**
     * Puts a check at the top of each void @Inject handler: when the mod failed to start, the handler
     * returns before doing anything, and the game goes on as if the hook were not there.
     */
    private static void guardHooks(ClassNode node, String modId) {
        if (modId == null || modId.equals("minecraft") || modId.equals("forge") || node.methods == null) {
            return;
        }
        for (org.objectweb.asm.tree.MethodNode m : node.methods) {
            if (m.instructions == null || m.instructions.size() == 0) {
                continue;
            }
            org.objectweb.asm.tree.InsnList exit = passThrough(m);
            if (exit == null) {
                continue;
            }
            org.objectweb.asm.tree.InsnList head = new org.objectweb.asm.tree.InsnList();
            org.objectweb.asm.tree.LabelNode go = new org.objectweb.asm.tree.LabelNode();
            head.add(new org.objectweb.asm.tree.LdcInsnNode(modId));
            head.add(new org.objectweb.asm.tree.MethodInsnNode(org.objectweb.asm.Opcodes.INVOKESTATIC,
                    "khi/pentecost/core/Hooks", "modFailed", "(Ljava/lang/String;)Z", false));
            head.add(new org.objectweb.asm.tree.JumpInsnNode(org.objectweb.asm.Opcodes.IFEQ, go));
            head.add(exit);
            head.add(go);
            m.instructions.insert(head);
            m.maxStack = Math.max(m.maxStack, 8);
        }
    }

    private static boolean annotated(org.objectweb.asm.tree.MethodNode m, String desc) {
        return has(m.visibleAnnotations, desc) || has(m.invisibleAnnotations, desc);
    }

    private static final String OPERATION = "Lcom/llamalad7/mixinextras/injector/wrapoperation/Operation;";

    /** The code that leaves a hook without changing anything, or null when the hook kind is not covered. */
    private static org.objectweb.asm.tree.InsnList passThrough(org.objectweb.asm.tree.MethodNode m) {
        org.objectweb.asm.Type[] args = org.objectweb.asm.Type.getArgumentTypes(m.desc);
        org.objectweb.asm.Type ret = org.objectweb.asm.Type.getReturnType(m.desc);
        int base = (m.access & org.objectweb.asm.Opcodes.ACC_STATIC) != 0 ? 0 : 1;
        org.objectweb.asm.tree.InsnList out = new org.objectweb.asm.tree.InsnList();
        if (annotated(m, "Lorg/spongepowered/asm/mixin/injection/Inject;") && ret.getSort() == org.objectweb.asm.Type.VOID) {
            out.add(new org.objectweb.asm.tree.InsnNode(org.objectweb.asm.Opcodes.RETURN));
            return out;
        }
        if (annotated(m, "Lcom/llamalad7/mixinextras/injector/v2/WrapWithCondition;")
                || annotated(m, "Lcom/llamalad7/mixinextras/injector/WrapWithCondition;")) {
            if (ret.getSort() != org.objectweb.asm.Type.BOOLEAN) {
                return null;
            }
            out.add(new org.objectweb.asm.tree.InsnNode(org.objectweb.asm.Opcodes.ICONST_1));
            out.add(new org.objectweb.asm.tree.InsnNode(org.objectweb.asm.Opcodes.IRETURN));
            return out;
        }
        if (annotated(m, "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;")) {
            int op = -1;
            for (int i = 0; i < args.length; i++) {
                if (OPERATION.equals(args[i].getDescriptor())) {
                    op = i;
                    break;
                }
            }
            if (op < 0) {
                return null;
            }
            int slot = base;
            int[] slots = new int[args.length];
            for (int i = 0; i < args.length; i++) {
                slots[i] = slot;
                slot += args[i].getSize();
            }
            out.add(new org.objectweb.asm.tree.VarInsnNode(org.objectweb.asm.Opcodes.ALOAD, slots[op]));
            out.add(new org.objectweb.asm.tree.LdcInsnNode(op));
            out.add(new org.objectweb.asm.tree.TypeInsnNode(org.objectweb.asm.Opcodes.ANEWARRAY, "java/lang/Object"));
            for (int i = 0; i < op; i++) {
                out.add(new org.objectweb.asm.tree.InsnNode(org.objectweb.asm.Opcodes.DUP));
                out.add(new org.objectweb.asm.tree.LdcInsnNode(i));
                out.add(new org.objectweb.asm.tree.VarInsnNode(args[i].getOpcode(org.objectweb.asm.Opcodes.ILOAD), slots[i]));
                box(out, args[i]);
                out.add(new org.objectweb.asm.tree.InsnNode(org.objectweb.asm.Opcodes.AASTORE));
            }
            out.add(new org.objectweb.asm.tree.MethodInsnNode(org.objectweb.asm.Opcodes.INVOKEINTERFACE,
                    "com/llamalad7/mixinextras/injector/wrapoperation/Operation", "call", "([Ljava/lang/Object;)Ljava/lang/Object;", true));
            unboxReturn(out, ret);
            return out;
        }
        boolean modify = annotated(m, "Lorg/spongepowered/asm/mixin/injection/ModifyArg;")
                || annotated(m, "Lorg/spongepowered/asm/mixin/injection/ModifyVariable;")
                || annotated(m, "Lorg/spongepowered/asm/mixin/injection/ModifyConstant;")
                || annotated(m, "Lcom/llamalad7/mixinextras/injector/ModifyReturnValue;")
                || annotated(m, "Lcom/llamalad7/mixinextras/injector/ModifyExpressionValue;");
        if (modify && args.length > 0 && args[0].equals(ret)) {
            out.add(new org.objectweb.asm.tree.VarInsnNode(ret.getOpcode(org.objectweb.asm.Opcodes.ILOAD), base));
            out.add(new org.objectweb.asm.tree.InsnNode(ret.getOpcode(org.objectweb.asm.Opcodes.IRETURN)));
            return out;
        }
        return null;
    }

    private static void box(org.objectweb.asm.tree.InsnList out, org.objectweb.asm.Type t) {
        String owner = switch (t.getSort()) {
            case org.objectweb.asm.Type.BOOLEAN -> "java/lang/Boolean";
            case org.objectweb.asm.Type.BYTE -> "java/lang/Byte";
            case org.objectweb.asm.Type.CHAR -> "java/lang/Character";
            case org.objectweb.asm.Type.SHORT -> "java/lang/Short";
            case org.objectweb.asm.Type.INT -> "java/lang/Integer";
            case org.objectweb.asm.Type.LONG -> "java/lang/Long";
            case org.objectweb.asm.Type.FLOAT -> "java/lang/Float";
            case org.objectweb.asm.Type.DOUBLE -> "java/lang/Double";
            default -> null;
        };
        if (owner != null) {
            out.add(new org.objectweb.asm.tree.MethodInsnNode(org.objectweb.asm.Opcodes.INVOKESTATIC, owner, "valueOf",
                    "(" + t.getDescriptor() + ")L" + owner + ";", false));
        }
    }

    private static void unboxReturn(org.objectweb.asm.tree.InsnList out, org.objectweb.asm.Type ret) {
        switch (ret.getSort()) {
            case org.objectweb.asm.Type.VOID -> {
                out.add(new org.objectweb.asm.tree.InsnNode(org.objectweb.asm.Opcodes.POP));
                out.add(new org.objectweb.asm.tree.InsnNode(org.objectweb.asm.Opcodes.RETURN));
                return;
            }
            case org.objectweb.asm.Type.OBJECT, org.objectweb.asm.Type.ARRAY -> {
                out.add(new org.objectweb.asm.tree.TypeInsnNode(org.objectweb.asm.Opcodes.CHECKCAST, ret.getInternalName()));
                out.add(new org.objectweb.asm.tree.InsnNode(org.objectweb.asm.Opcodes.ARETURN));
                return;
            }
            default -> { }
        }
        String owner = switch (ret.getSort()) {
            case org.objectweb.asm.Type.BOOLEAN -> "java/lang/Boolean";
            case org.objectweb.asm.Type.BYTE -> "java/lang/Byte";
            case org.objectweb.asm.Type.CHAR -> "java/lang/Character";
            case org.objectweb.asm.Type.SHORT -> "java/lang/Short";
            case org.objectweb.asm.Type.INT -> "java/lang/Integer";
            case org.objectweb.asm.Type.LONG -> "java/lang/Long";
            case org.objectweb.asm.Type.FLOAT -> "java/lang/Float";
            default -> "java/lang/Double";
        };
        out.add(new org.objectweb.asm.tree.TypeInsnNode(org.objectweb.asm.Opcodes.CHECKCAST, owner));
        out.add(new org.objectweb.asm.tree.MethodInsnNode(org.objectweb.asm.Opcodes.INVOKEVIRTUAL, owner,
                ret.getClassName() + "Value", "()" + ret.getDescriptor(), false));
        out.add(new org.objectweb.asm.tree.InsnNode(ret.getOpcode(org.objectweb.asm.Opcodes.IRETURN)));
    }

    private static boolean has(java.util.List<org.objectweb.asm.tree.AnnotationNode> list, String desc) {
        if (list != null) {
            for (org.objectweb.asm.tree.AnnotationNode a : list) {
                if (desc.equals(a.desc)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static java.util.List<String> mixinTargets(ClassNode node) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (java.util.List<org.objectweb.asm.tree.AnnotationNode> list
                : java.util.Arrays.asList(node.visibleAnnotations, node.invisibleAnnotations)) {
            if (list == null) {
                continue;
            }
            for (org.objectweb.asm.tree.AnnotationNode a : list) {
                if (!"Lorg/spongepowered/asm/mixin/Mixin;".equals(a.desc) || a.values == null) {
                    continue;
                }
                for (int i = 0; i + 1 < a.values.size(); i += 2) {
                    if (a.values.get(i + 1) instanceof java.util.List<?> vals) {
                        for (Object v : vals) {
                            if (v instanceof org.objectweb.asm.Type t) {
                                out.add(t.getInternalName());
                            } else if (v instanceof String str) {
                                out.add(str.replace('.', '/'));
                            }
                        }
                    }
                }
            }
        }
        return out;
    }

    /** Field names of a game or mod class, before any mixin, as "name desc" and plain "name". */
    private java.util.Set<String> fieldsOf(String internal) {
        return TARGET_FIELDS.computeIfAbsent(internal, k -> {
            java.util.Set<String> names = new java.util.HashSet<>();
            try {
                byte[] b = loader.preMixinBytes(k.replace('/', '.'));
                if (b == null) {
                    b = loader.preMixinBytes(k);
                }
                if (b != null) {
                    new ClassReader(b).accept(new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9) {
                        @Override
                        public org.objectweb.asm.FieldVisitor visitField(int access, String name, String desc,
                                                                        String sig, Object value) {
                            names.add(name + " " + desc);
                            names.add(name);
                            return null;
                        }
                    }, ClassReader.SKIP_CODE);
                }
            } catch (Exception e) {
                // Unknown target: nothing to compare against.
            }
            return names;
        });
    }

    private static boolean isMixin(ClassNode node) {
        for (java.util.List<org.objectweb.asm.tree.AnnotationNode> list
                : java.util.Arrays.asList(node.visibleAnnotations, node.invisibleAnnotations)) {
            if (list != null) {
                for (org.objectweb.asm.tree.AnnotationNode a : list) {
                    if ("Lorg/spongepowered/asm/mixin/Mixin;".equals(a.desc)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Sets require = 0 on every injector annotation, so a missed target is a warning. */
    private static void softRequire(java.util.List<org.objectweb.asm.tree.AnnotationNode> list) {
        if (list == null) {
            return;
        }
        for (org.objectweb.asm.tree.AnnotationNode a : list) {
            if (!a.desc.startsWith("Lorg/spongepowered/asm/mixin/injection/")
                    && !a.desc.startsWith("Lcom/llamalad7/mixinextras/")) {
                continue;
            }
            java.util.List<Object> values = a.values == null ? new java.util.ArrayList<>() : new java.util.ArrayList<>(a.values);
            for (int i = 0; i + 1 < values.size(); ) {
                Object key = values.get(i);
                if ("require".equals(key) || "allow".equals(key) || "expect".equals(key)) {
                    values.remove(i + 1);
                    values.remove(i);
                } else {
                    i += 2;
                }
            }
            values.add("require");
            values.add(0);
            for (int i = 0; i + 1 < values.size(); i += 2) {
                if ("locals".equals(values.get(i)) && values.get(i + 1) instanceof String[] en
                        && en.length == 2 && !"NO_CAPTURE".equals(en[1]) && !"PRINT".equals(en[1])) {
                    values.set(i + 1, new String[]{en[0], "CAPTURE_FAILSOFT"});
                }
            }
            a.values = values;
        }
    }
}
