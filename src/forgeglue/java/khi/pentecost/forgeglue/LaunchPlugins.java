package khi.pentecost.forgeglue;

import cpw.mods.modlauncher.serviceapi.ILaunchPluginService;
import cpw.mods.modlauncher.serviceapi.ILaunchPluginService.Phase;
import khi.pentecost.loader.ClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.ServiceLoader;
import java.util.Set;

public final class LaunchPlugins implements ClassTransformer {
    private static final Set<String> SKIPPED = Set.of("runtimedistcleaner", "mixin", "accesstransformer", "eventbus");

    private net.minecraftforge.eventbus.IEventBusEngine events;

    private final ClassLoader loader;
    private volatile List<ILaunchPluginService> plugins;
    private volatile boolean reported;
    private final ThreadLocal<Boolean> busy = ThreadLocal.withInitial(() -> false);
    private final ThreadLocal<Boolean> makingEngine = ThreadLocal.withInitial(() -> false);

    public LaunchPlugins(ClassLoader loader) {
        this.loader = loader;
    }

    private List<ILaunchPluginService> plugins() {
        List<ILaunchPluginService> p = plugins;
        if (p == null) {
            synchronized (this) {
                p = plugins;
                if (p == null) {
                    p = new ArrayList<>();
                    busy.set(true);
                    try {
                        java.util.Iterator<ILaunchPluginService> it =
                                ServiceLoader.load(ILaunchPluginService.class, loader).iterator();
                        for (int guard = 0; guard < 64; guard++) {
                            try {
                                if (!it.hasNext()) {
                                    break;
                                }
                                ILaunchPluginService s = it.next();
                                if (s.getClass().getClassLoader() == loader && !SKIPPED.contains(s.name())) {
                                    p.add(s);
                                }
                            } catch (Throwable notForUs) {
                            }
                        }
                    } finally {
                        busy.set(false);
                    }
                    StringBuilder names = new StringBuilder();
                    for (ILaunchPluginService s : p) {
                        names.append(names.length() == 0 ? "" : ", ").append(s.name());
                    }
                    System.out.println("[Pentecost] Forge class plug-ins: " + names);
                    plugins = p;
                }
            }
        }
        return p;
    }

    @Override
    public byte[] transform(String name, byte[] bytes) {
        if (busy.get() || name.startsWith("khi.pentecost.") || name.startsWith("cpw.mods.") || name.startsWith("org.objectweb.")
                || name.startsWith("net.minecraftforge.eventbus.") || name.startsWith("org.apache.")
                || name.startsWith("org.slf4j.") || name.startsWith("net.minecraftforge.accesstransformer.")) {
            return bytes;
        }
        try {
            Type type = Type.getObjectType(name.replace('.', '/'));
            ClassNode node = null;
            int flags = 0;
            if (CoreMods.has(name)) {
                node = new ClassNode();
                new ClassReader(bytes).accept(node, 0);
                node = CoreMods.apply(name, node);
                flags |= ILaunchPluginService.ComputeFlags.COMPUTE_FRAMES;
            }
            if (events == null && !makingEngine.get()) {
                makingEngine.set(true);
                try {
                    events = new net.minecraftforge.eventbus.EventBusEngine();
                } finally {
                    makingEngine.set(false);
                }
            }
            if (events != null && !khi.pentecost.loader.GameClassLoader.PEEK.get()
                    && !name.startsWith("com.mojang.")
                    && (name.startsWith("net.minecraftforge.") || !name.startsWith("net.minecraft."))
                    && events.handlesClass(type)) {
                if (node == null) {
                    node = new ClassNode();
                    new ClassReader(bytes).accept(node, 0);
                }
                if (events.processClass(node, type) != 0) {
                    flags |= ILaunchPluginService.ComputeFlags.SIMPLE_REWRITE;
                }
            }
            for (Phase phase : Phase.values()) {
                for (ILaunchPluginService plugin : plugins()) {
                    EnumSet<Phase> wanted = plugin.handlesClass(type, false);
                    if (wanted == null || !wanted.contains(phase)) {
                        continue;
                    }
                    if (node == null) {
                        node = new ClassNode();
                        new ClassReader(bytes).accept(node, 0);
                    }
                    flags |= plugin.processClassWithFlags(phase, node, type, "classloading");
                }
            }
            if (node == null || flags == 0) {
                return bytes;
            }
            boolean frames = (flags & ILaunchPluginService.ComputeFlags.COMPUTE_FRAMES) != 0;
            ClassWriter writer = new SafeWriter(frames ? ClassWriter.COMPUTE_FRAMES : ClassWriter.COMPUTE_MAXS);
            node.accept(writer);
            return writer.toByteArray();
        } catch (RuntimeException | LinkageError e) {
            if (!reported) {
                reported = true;
                System.out.println("[Pentecost] A Forge class plug-in failed on " + name + ": " + e);
                e.printStackTrace(System.out);
            }
            return bytes;
        }
    }

    private final class SafeWriter extends ClassWriter {
        SafeWriter(int flags) {
            super(flags);
        }

        @Override
        protected String getCommonSuperClass(String a, String b) {
            List<String> chainA = chain(a);
            List<String> chainB = chain(b);
            for (String x : chainA) {
                if (chainB.contains(x)) {
                    return x;
                }
            }
            return "java/lang/Object";
        }

        private List<String> chain(String internal) {
            List<String> out = new ArrayList<>();
            String cur = internal;
            while (cur != null && out.size() < 64) {
                out.add(cur);
                try (InputStream in = loader.getResourceAsStream(cur + ".class")) {
                    if (in == null) {
                        break;
                    }
                    ClassReader r = new ClassReader(in);
                    if ((r.getAccess() & org.objectweb.asm.Opcodes.ACC_INTERFACE) != 0) {
                        break;
                    }
                    cur = r.getSuperName();
                } catch (java.io.IOException e) {
                    break;
                }
            }
            if (!out.contains("java/lang/Object")) {
                out.add("java/lang/Object");
            }
            return out;
        }
    }
}
