package khi.pentecost.remap;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.Remapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Renames the vanilla jar from scrambled names to readable names.
 * Scrambled names repeat across classes, so each lookup walks the class hierarchy.
 */
public final class GameRemapper extends Remapper {
    private final Mappings m;
    private final Map<String, String[]> parents;
    private final Map<String, Optional<String>> cache = new ConcurrentHashMap<>();

    public GameRemapper(Mappings m, Map<String, String[]> parents) {
        this.m = m;
        this.parents = parents;
    }

    /** Reads the parents (superclass and interfaces) of every class in a jar. */
    public static Map<String, String[]> readHierarchy(Path jar) throws IOException {
        Map<String, String[]> out = new HashMap<>();
        try (ZipFile z = new ZipFile(jar.toFile())) {
            Enumeration<? extends ZipEntry> en = z.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (!e.getName().endsWith(".class")) {
                    continue;
                }
                try (InputStream in = z.getInputStream(e)) {
                    ClassReader cr = new ClassReader(in);
                    String[] itf = cr.getInterfaces();
                    String sup = cr.getSuperName();
                    String[] all = new String[itf.length + (sup == null ? 0 : 1)];
                    int i = 0;
                    if (sup != null) {
                        all[i++] = sup;
                    }
                    System.arraycopy(itf, 0, all, i, itf.length);
                    out.put(cr.getClassName(), all);
                }
            }
        }
        return out;
    }

    @Override
    public String map(String internalName) {
        return m.classes.getOrDefault(internalName, internalName);
    }

    @Override
    public String mapMethodName(String owner, String name, String descriptor) {
        if (name.charAt(0) == '<') {
            return name;
        }
        return lookup(m.methods, owner, name + descriptor).orElse(name);
    }

    @Override
    public String mapFieldName(String owner, String name, String descriptor) {
        return lookup(m.fields, owner, name + ":" + descriptor).orElse(name);
    }

    @Override
    public String mapRecordComponentName(String owner, String name, String descriptor) {
        return mapFieldName(owner, name, descriptor);
    }

    @Override
    public String mapInvokeDynamicMethodName(String name, String descriptor) {
        Type ret = Type.getReturnType(descriptor);
        if (ret.getSort() != Type.OBJECT) {
            return name;
        }
        return lookup(m.methodsByName, ret.getInternalName(), name).orElse(name);
    }

    private Optional<String> lookup(Map<String, Map<String, String>> table, String owner, String key) {
        String cacheKey = System.identityHashCode(table) + "|" + owner + "|" + key;
        Optional<String> hit = cache.get(cacheKey);
        if (hit != null) {
            return hit;
        }
        Optional<String> r = Optional.ofNullable(walk(table, owner, key, 0));
        cache.put(cacheKey, r);
        return r;
    }

    private String walk(Map<String, Map<String, String>> table, String owner, String key, int depth) {
        if (depth > 64) {
            return null;
        }
        Map<String, String> own = table.get(owner);
        if (own != null) {
            String v = own.get(key);
            if (v != null) {
                return v;
            }
        }
        String[] ps = parents.get(owner);
        if (ps != null) {
            for (String p : ps) {
                String v = walk(table, p, key, depth + 1);
                if (v != null) {
                    return v;
                }
            }
        }
        return null;
    }
}
