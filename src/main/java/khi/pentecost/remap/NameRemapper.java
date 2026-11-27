package khi.pentecost.remap;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.Remapper;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class NameRemapper extends Remapper {
    /** Suffix given to a mod's own method when renaming another method of the same class would collide with it. */
    static final String CLASH_SUFFIX = "$pentecost";

    private final Mappings.NameTable t;
    /** Per class: "name+descriptor" of declared methods whose current name is the target of another method's rename. */
    private final Map<String, Set<String>> clashing = new ConcurrentHashMap<>();

    public NameRemapper(Mappings.NameTable t) {
        this.t = t;
    }

    /**
     * Looks at every class before any is renamed. A mod can declare both an intermediary-named method
     * (method_23787) and a helper that already carries the name that method is about to receive
     * (getStarBrightness). Renaming only the first one makes the two identical, so the method ends up calling
     * itself. The helper is the one that gets a new name, so the call keeps reaching it.
     */
    public void scan(Collection<byte[]> classFiles) {
        for (byte[] data : classFiles) {
            try {
                scanOne(data);
            } catch (RuntimeException ignored) {
                // an unreadable class is left alone, the remap pass reports it the way it always did
            }
        }
    }

    private void scanOne(byte[] data) {
        ClassReader cr = new ClassReader(data);
        Set<String> declared = new HashSet<>();
        cr.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                if (name.charAt(0) != '<') {
                    declared.add(name + desc);
                }
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        Set<String> clash = null;
        for (String nd : declared) {
            int paren = nd.indexOf('(');
            String name = nd.substring(0, paren);
            String desc = nd.substring(paren);
            String mapped = t.methods().get(name);
            if (mapped != null && !mapped.equals(name) && declared.contains(mapped + desc)
                    && t.methods().get(mapped) == null) {
                if (clash == null) {
                    clash = new HashSet<>();
                }
                clash.add(mapped + desc);
            }
        }
        if (clash != null) {
            clashing.put(cr.getClassName(), clash);
        }
    }

    @Override
    public String map(String internalName) {
        return t.classes().getOrDefault(internalName, internalName);
    }

    @Override
    public String mapMethodName(String owner, String name, String descriptor) {
        String mapped = t.methods().get(name);
        if (mapped != null) {
            return mapped;
        }
        Set<String> clash = clashing.get(owner);
        if (clash != null && clash.contains(name + descriptor)) {
            return name + CLASH_SUFFIX;
        }
        return name;
    }

    @Override
    public String mapInvokeDynamicMethodName(String name, String descriptor) {
        return t.methods().getOrDefault(name, name);
    }

    @Override
    public String mapFieldName(String owner, String name, String descriptor) {
        return t.fields().getOrDefault(name, name);
    }

    @Override
    public String mapRecordComponentName(String owner, String name, String descriptor) {
        return t.fields().getOrDefault(name, name);
    }
}
