package khi.pentecost.core;

import khi.pentecost.loader.ClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

public final class OggSeekGuard {
    private OggSeekGuard() {}

    public static final String CLASS = "com.mojang.blaze3d.audio.OggAudioStream";

    public static ClassTransformer transformer() {
        return (name, bytes) -> {
            if (!name.equals(CLASS) || System.getenv("PENTECOST_NO_OGG_GUARD") != null) {
                return bytes;
            }
            return patch(bytes);
        };
    }

    public static byte[] patch(byte[] bytes) {
        try {
            ClassReader reader = new ClassReader(bytes);
            ClassWriter writer = new ClassWriter(0);
            boolean[] done = {false};
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                @Override
                public MethodVisitor visitMethod(int access, String n, String d, String s, String[] e) {
                    MethodVisitor mv = super.visitMethod(access, n, d, s, e);
                    if (!n.equals("setPositionRandomly") || !d.equals("(J)V") || (access & Opcodes.ACC_ABSTRACT) != 0) {
                        return mv;
                    }
                    done[0] = true;
                    mv.visitCode();
                    mv.visitInsn(Opcodes.RETURN);
                    mv.visitMaxs(0, (access & Opcodes.ACC_STATIC) != 0 ? 2 : 3);
                    mv.visitEnd();
                    return null;
                }
            }, 0);
            if (!done[0]) {
                return bytes;
            }
            khi.pentecost.boot.Log.info("AmbientSounds: random sound start turned off, it locked up the game's worker threads");
            return writer.toByteArray();
        } catch (RuntimeException e) {
            khi.pentecost.boot.Log.warn("Ogg seek guard skipped: " + e);
            return bytes;
        }
    }
}
