package khi.pentecost.core;

import khi.pentecost.mods.LoaderKind;
import khi.pentecost.mods.ModInfo;
import org.objectweb.asm.Type;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class ModOwners implements ModImpact.Owners {
    private static final String MERGED = "org.spongepowered.asm.mixin.transformer.meta.MixinMerged";

    private final Map<String, String> byPackage = new HashMap<>();
    private final Set<String> libraries = new HashSet<>();
    private final ClassLoader loader;
    private final Map<String, Map<String, String>> mixinsByClass = new ConcurrentHashMap<>();

    private ModOwners(ClassLoader loader) {
        this.loader = loader;
    }

    public static ModOwners build(List<ModInfo> mods, ClassLoader loader) {
        ModOwners o = new ModOwners(loader);
        for (ModInfo m : mods) {
            String id = group(m.id());
            if (m.loader() == LoaderKind.LIBRARY) {
                o.libraries.add(id);
            }
            if (m.jar() == null || !Files.isRegularFile(m.jar())) {
                continue;
            }
            try (ZipFile z = new ZipFile(m.jar().toFile())) {
                Enumeration<? extends ZipEntry> e = z.entries();
                while (e.hasMoreElements()) {
                    String n = e.nextElement().getName();
                    if (n.endsWith(".class") && !n.startsWith("META-INF/")) {
                        int slash = n.lastIndexOf('/');
                        if (slash > 0) {
                            o.byPackage.putIfAbsent(n.substring(0, slash), id);
                        }
                    }
                }
            } catch (java.io.IOException | RuntimeException ignored) {
            }
        }
        o.byPackage.put("khi/pentecost", "pentecost");
        return o;
    }

    public static String group(String id) {
        String l = id.toLowerCase(Locale.ROOT);
        return l.startsWith("fabric_") || l.startsWith("fabric-") || l.equals("fabric") ? "fabric_api" : id;
    }

    @Override
    public boolean passThrough(String mod) {
        return libraries.contains(mod);
    }

    @Override
    public String owner(String className, String method, String descriptor) {
        String internal = internal(className);
        String mod = ownerOfInternal(internal);
        if (mod != null) {
            return mod;
        }
        if (internal.startsWith("net/minecraft/") || internal.startsWith("com/mojang/")) {
            return mixinOwner(className, method, descriptor);
        }
        return null;
    }

    private String ownerOfInternal(String internal) {
        int slash = internal.lastIndexOf('/');
        if (slash <= 0) {
            return null;
        }
        String pkg = internal.substring(0, slash);
        String mod = byPackage.get(pkg);
        if (mod == null && pkg.startsWith("khi/pentecost")) {
            return "pentecost";
        }
        return mod;
    }

    static String internal(String className) {
        String n = className;
        int hidden = n.indexOf('/');
        if (hidden > 0) {
            n = n.substring(0, hidden);
        }
        int lambda = n.indexOf("$$Lambda");
        if (lambda > 0) {
            n = n.substring(0, lambda);
        }
        return n.replace('.', '/');
    }

    private String mixinOwner(String className, String method, String descriptor) {
        Map<String, String> merged = mixinsByClass.computeIfAbsent(className, this::readMerged);
        return merged.get(method + descriptor);
    }

    private Map<String, String> readMerged(String className) {
        Map<String, String> out = new HashMap<>();
        try {
            Class<?> c = Class.forName(className, false, loader);
            for (Method m : c.getDeclaredMethods()) {
                for (Annotation a : m.getDeclaredAnnotations()) {
                    if (a.annotationType().getName().equals(MERGED)) {
                        Object mixin = a.annotationType().getMethod("mixin").invoke(a);
                        String mod = mixin == null ? null : ownerOfInternal(internal(mixin.toString()));
                        if (mod != null) {
                            out.put(m.getName() + Type.getMethodDescriptor(m), mod);
                        }
                    }
                }
            }
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ignored) {
        }
        return out;
    }

    public String ownerOfClass(String className) {
        return ownerOfInternal(internal(className));
    }
}
