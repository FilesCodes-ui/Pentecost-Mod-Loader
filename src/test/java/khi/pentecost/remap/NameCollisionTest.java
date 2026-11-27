package khi.pentecost.remap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class NameCollisionTest {
    /** A mixin-like class: method_23787 forwards to a helper that already has the name method_23787 will receive. */
    private static byte[] mixinClass() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "a/MixinClientWorld", null, "java/lang/Object", null);
        MethodVisitor helper = cw.visitMethod(Opcodes.ACC_PUBLIC, "getStarBrightness", "(F)F", null, null);
        helper.visitCode();
        helper.visitInsn(Opcodes.FCONST_1);
        helper.visitInsn(Opcodes.FRETURN);
        helper.visitMaxs(0, 0);
        helper.visitEnd();
        MethodVisitor bridge = cw.visitMethod(Opcodes.ACC_PUBLIC, "method_23787", "(F)F", null, null);
        bridge.visitCode();
        bridge.visitVarInsn(Opcodes.ALOAD, 0);
        bridge.visitVarInsn(Opcodes.FLOAD, 1);
        bridge.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "a/MixinClientWorld", "getStarBrightness", "(F)F", false);
        bridge.visitInsn(Opcodes.FRETURN);
        bridge.visitMaxs(0, 0);
        bridge.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    @Test
    void renamedMethodDoesNotCallItself(@TempDir Path dir) throws Exception {
        Path in = dir.resolve("in.jar");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(in))) {
            z.putNextEntry(new ZipEntry("a/MixinClientWorld.class"));
            z.write(mixinClass());
            z.closeEntry();
        }
        Mappings.NameTable t = new Mappings.NameTable(Map.of(), Map.of("method_23787", "getStarBrightness"), Map.of(), 0);
        Path out = dir.resolve("out.jar");
        JarRemapper.remap(in, out, new NameRemapper(t), new TextRemapper(t));

        byte[] data;
        try (ZipFile z = new ZipFile(out.toFile())) {
            data = z.getInputStream(z.getEntry("a/MixinClientWorld.class")).readAllBytes();
        }
        List<String> declared = new ArrayList<>();
        List<String> calls = new ArrayList<>();
        new ClassReader(data).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                declared.add(name + desc);
                String owner = name;
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int op, String o, String n, String d, boolean itf) {
                        calls.add(owner + "->" + n);
                    }
                };
            }
        }, 0);

        assertEquals(2, declared.stream().distinct().count(), "no two methods may end up identical: " + declared);
        assertTrue(declared.contains("getStarBrightness(F)F"), declared.toString());
        assertTrue(declared.contains("getStarBrightness$pentecost(F)F"), declared.toString());
        assertTrue(calls.contains("getStarBrightness->getStarBrightness$pentecost"), calls.toString());
        assertFalse(calls.contains("getStarBrightness->getStarBrightness"), "the renamed method must not call itself");
    }

    @Test
    void methodsWithoutAClashAreUntouched(@TempDir Path dir) throws Exception {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "a/Plain", null, "java/lang/Object", null);
        cw.visitMethod(Opcodes.ACC_PUBLIC, "helper", "()V", null, null).visitEnd();
        cw.visitMethod(Opcodes.ACC_PUBLIC, "method_1", "()V", null, null).visitEnd();
        cw.visitEnd();
        Path in = dir.resolve("in.jar");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(in))) {
            z.putNextEntry(new ZipEntry("a/Plain.class"));
            z.write(cw.toByteArray());
            z.closeEntry();
        }
        Mappings.NameTable t = new Mappings.NameTable(Map.of(), Map.of("method_1", "tick"), Map.of(), 0);
        Path out = dir.resolve("out.jar");
        JarRemapper.remap(in, out, new NameRemapper(t), new TextRemapper(t));
        List<String> declared = new ArrayList<>();
        try (ZipFile z = new ZipFile(out.toFile())) {
            new ClassReader(z.getInputStream(z.getEntry("a/Plain.class")).readAllBytes()).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                    declared.add(name);
                    return null;
                }
            }, 0);
        }
        assertTrue(declared.contains("tick") && declared.contains("helper"), declared.toString());
    }
}
