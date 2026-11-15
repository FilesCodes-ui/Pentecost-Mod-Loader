package khi.pentecost.core;

import khi.pentecost.loader.ClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * The render distance of the menu world. The game's effective render distance is the smaller of the player's own
 * setting and the one the level asks for, so a player with a low render distance got a short menu world however far
 * the menu world was set to load. While the menu world is showing, the menu world's own distance is used instead,
 * whatever the player's setting is. The player's setting is not changed, and it applies again in a real world.
 */
public final class MenuDistance {
    /** The menu world's view distance in chunks while it is showing, 0 when it is not. Set by the menu host. */
    public static volatile int chunks;

    private MenuDistance() {}

    public static ClassTransformer transformer() {
        return (name, bytes) -> {
            if (!name.equals("net.minecraft.client.Options")) {
                return bytes;
            }
            ClassReader reader = new ClassReader(bytes);
            ClassWriter writer = new ClassWriter(0);
            final boolean[] changed = new boolean[1];
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                @Override
                public MethodVisitor visitMethod(int access, String n, String d, String s, String[] e) {
                    MethodVisitor mv = super.visitMethod(access, n, d, s, e);
                    if ((access & Opcodes.ACC_STATIC) == 0 && n.equals("getEffectiveRenderDistance") && d.equals("()I")) {
                        changed[0] = true;
                        return new MethodVisitor(Opcodes.ASM9, mv) {
                            @Override
                            public void visitCode() {
                                super.visitCode();
                                Label own = new Label();
                                super.visitFieldInsn(Opcodes.GETSTATIC, "khi/pentecost/core/MenuDistance", "chunks", "I");
                                super.visitJumpInsn(Opcodes.IFLE, own);
                                super.visitFieldInsn(Opcodes.GETSTATIC, "khi/pentecost/core/MenuDistance", "chunks", "I");
                                super.visitInsn(Opcodes.IRETURN);
                                super.visitLabel(own);
                                super.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
                            }

                            @Override
                            public void visitMaxs(int maxStack, int maxLocals) {
                                super.visitMaxs(Math.max(maxStack, 1), maxLocals);
                            }
                        };
                    }
                    return mv;
                }
            }, 0);
            return changed[0] ? writer.toByteArray() : bytes;
        };
    }
}
