package khi.pentecost.core;

import khi.pentecost.boot.Log;
import khi.pentecost.loader.ClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

public final class GeckoMolangGuard {
    static final String TARGET = "software.bernie.geckolib.core.molang.MolangParser";

    private GeckoMolangGuard() {}

    public static ClassTransformer transformer() {
        return (name, bytes) -> {
            if (!TARGET.equals(name) || System.getenv("PENTECOST_NO_MOLANG_GUARD") != null) {
                return bytes;
            }
            ClassReader reader = new ClassReader(bytes);
            ClassWriter writer = new ClassWriter(0);
            boolean[] done = new boolean[1];
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                @Override
                public MethodVisitor visitMethod(int access, String n, String d, String s, String[] e) {
                    MethodVisitor mv = super.visitMethod(access, n, d, s, e);
                    if (!n.equals("<clinit>")) {
                        return mv;
                    }
                    return new MethodVisitor(Opcodes.ASM9, mv) {
                        @Override
                        public void visitFieldInsn(int op, String owner, String field, String desc) {
                            if (op == Opcodes.PUTSTATIC && field.equals("VARIABLES") && desc.equals("Ljava/util/Map;")) {
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Collections", "synchronizedMap",
                                        "(Ljava/util/Map;)Ljava/util/Map;", false);
                                done[0] = true;
                            }
                            super.visitFieldInsn(op, owner, field, desc);
                        }
                    };
                }
            }, 0);
            if (!done[0]) {
                return bytes;
            }
            Log.info("GeckoLib Molang variables made safe to use from several threads");
            return writer.toByteArray();
        };
    }
}
