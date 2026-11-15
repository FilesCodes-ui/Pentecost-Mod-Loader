package khi.pentecost.core;

import khi.pentecost.loader.ClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * What the player and tools see as the name of the game. Forge's own files stay as they are (and so does its licence);
 * only the text shown for the window title and for spark's platform name says Pentecost.
 * Protocol values, like the brand a server is told, are left alone.
 */
public final class Branding {
    private Branding() {}

    /** The window title: "Minecraft* Forge 1.20.1 - Singleplayer" becomes "Pentecost 1.20.1 - Singleplayer". */
    public static String title(String title) {
        if (title == null || !title.startsWith("Minecraft")) {
            return title;
        }
        String s = title.replaceFirst("^Minecraft\\*?", "Pentecost");
        return s.replace(" Forge ", " ");
    }

    /** Puts {@link #title} in front of the title that Window.setTitle receives. */
    public static ClassTransformer titleTransformer() {
        return (name, bytes) -> {
            if (!name.equals("com.mojang.blaze3d.platform.Window")) {
                return bytes;
            }
            ClassReader reader = new ClassReader(bytes);
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                @Override
                public MethodVisitor visitMethod(int access, String n, String d, String s, String[] e) {
                    MethodVisitor mv = super.visitMethod(access, n, d, s, e);
                    if (!n.equals("setTitle") || !d.equals("(Ljava/lang/String;)V") || (access & Opcodes.ACC_STATIC) != 0) {
                        return mv;
                    }
                    return new MethodVisitor(Opcodes.ASM9, mv) {
                        @Override
                        public void visitCode() {
                            super.visitCode();
                            super.visitVarInsn(Opcodes.ALOAD, 1);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, "khi/pentecost/core/Branding", "title",
                                    "(Ljava/lang/String;)Ljava/lang/String;", false);
                            super.visitVarInsn(Opcodes.ASTORE, 1);
                        }
                    };
                }
            }, 0);
            return writer.toByteArray();
        };
    }

    /** spark names its platform "Forge" in the Forge build. Inside spark's own Forge classes the name becomes Pentecost. */
    public static ClassTransformer sparkTransformer() {
        return (name, bytes) -> {
            if (!name.startsWith("me.lucko.spark.forge.")) {
                return bytes;
            }
            ClassReader reader = new ClassReader(bytes);
            ClassWriter writer = new ClassWriter(0);
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                @Override
                public MethodVisitor visitMethod(int access, String n, String d, String s, String[] e) {
                    return new MethodVisitor(Opcodes.ASM9, super.visitMethod(access, n, d, s, e)) {
                        @Override
                        public void visitLdcInsn(Object value) {
                            super.visitLdcInsn("Forge".equals(value) ? "Pentecost" : value);
                        }
                    };
                }
            }, 0);
            return writer.toByteArray();
        };
    }
}
