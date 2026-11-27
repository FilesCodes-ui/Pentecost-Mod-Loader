package khi.pentecost.messenger;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MobHooksBroadcastTest {
    private static final String DESC = "(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/network/protocol/Packet;)V";

    /** A stand-in for ServerChunkCache with the two methods that send a packet to the players tracking an entity. */
    private static byte[] chunkCache() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "net/minecraft/server/level/ServerChunkCache", null, "java/lang/Object", null);
        for (String name : new String[]{"broadcast", "broadcastAndSend"}) {
            MethodVisitor m = cw.visitMethod(Opcodes.ACC_PUBLIC, name, DESC, null, null);
            m.visitCode();
            m.visitInsn(Opcodes.RETURN);
            m.visitMaxs(0, 0);
            m.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    @Test
    void bothSendsAreHandedBackToTheServerThread() {
        byte[] out = MobHooks.transformer().transform("net.minecraft.server.level.ServerChunkCache", chunkCache());
        List<String> seen = new ArrayList<>();
        new ClassReader(out).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                return new MethodVisitor(Opcodes.ASM9) {
                    boolean flag;

                    @Override
                    public void visitInsn(int op) {
                        if (op == Opcodes.ICONST_1) {
                            flag = true;
                        }
                    }

                    @Override
                    public void visitMethodInsn(int op, String owner, String n, String d, boolean itf) {
                        if (owner.equals("khi/pentecost/messenger/MobHooks") && n.equals("broadcast")) {
                            seen.add(name + (flag ? ":send" : ":plain"));
                        }
                    }
                };
            }
        }, 0);
        assertEquals(List.of("broadcast:plain", "broadcastAndSend:send"), seen);
    }

    @Test
    void onTheServerThreadNothingIsDeferred() {
        assertFalse(MobHooks.broadcast(null, null, null, true));
        assertFalse(MobHooks.broadcast(null, null, null, false));
    }
}
