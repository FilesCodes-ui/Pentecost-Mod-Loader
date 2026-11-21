package khi.pentecost.remap;

import org.objectweb.asm.commons.Remapper;

public final class NameRemapper extends Remapper {
    private final Mappings.NameTable t;

    public NameRemapper(Mappings.NameTable t) {
        this.t = t;
    }

    @Override
    public String map(String internalName) {
        return t.classes().getOrDefault(internalName, internalName);
    }

    @Override
    public String mapMethodName(String owner, String name, String descriptor) {
        return t.methods().getOrDefault(name, name);
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
