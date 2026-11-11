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

    private static boolean soft(String modId) {
        return modId != null && softMods.contains(modId);
    }

    public InputStream getResourceAsStream(String name) {
        if (name.endsWith(".json") && soft(loader.modOfResource(name))) {
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
                        inj.addProperty("defaultRequire", 0);
                        cfg.add("injectors", inj);
                        cfg.addProperty("required", false);
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
        if (!softMods.isEmpty() && isMixin(node) && soft(loader.modOf(name.replace('/', '.')))) {
            for (org.objectweb.asm.tree.MethodNode m : node.methods) {
                softRequire(m.visibleAnnotations);
                softRequire(m.invisibleAnnotations);
            }
        }
        return node;
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
