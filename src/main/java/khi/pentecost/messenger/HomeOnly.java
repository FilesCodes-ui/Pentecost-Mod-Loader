package khi.pentecost.messenger;

import khi.pentecost.loader.ClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.List;
import java.util.Map;

public final class HomeOnly {
    public static final class SendHome extends RuntimeException {
        public final String what;

        SendHome(String what) {
            super("only on the server thread: " + what, null, false, false);
            this.what = what;
        }
    }

    private HomeOnly() {}

    public static void check(String what) {
        if (MobHooks.deferring()) {
            throw new SendHome(what);
        }
    }

    public static boolean isSendHome(Throwable t) {
        for (int i = 0; t != null && i < 8; i++, t = t.getCause()) {
            if (t instanceof SendHome) {
                return true;
            }
        }
        return false;
    }

    private record Guard(String method, String descStart) {}

    private static final Map<String, List<Guard>> GUARDS = Map.of(
            "net.minecraft.world.entity.Entity", List.of(
                    new Guard("changeDimension", "(Lnet/minecraft/server/level/ServerLevel;")),
            "net.minecraft.server.level.ServerPlayer", List.of(
                    new Guard("changeDimension", "(Lnet/minecraft/server/level/ServerLevel;"),
                    new Guard("teleportTo", "(Lnet/minecraft/server/level/ServerLevel;")),
            "net.minecraft.server.level.ServerLevel", List.of(
                    new Guard("addDuringTeleport", null),
                    new Guard("addDuringCommandTeleport", null),
                    new Guard("addDuringPortalTeleport", null),
                    new Guard("addNewPlayer", null),
                    new Guard("addRespawnedPlayer", null),
                    new Guard("removePlayerImmediately", null)),
            "net.minecraft.server.players.PlayerList", List.of(
                    new Guard("respawn", null)));

    public static ClassTransformer transformer() {
        return (name, bytes) -> {
            List<Guard> guards = GUARDS.get(name);
            if (guards == null || System.getenv("PENTECOST_NO_HOME_ONLY") != null) {
                return bytes;
            }
            try {
                ClassReader reader = new ClassReader(bytes);
                ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
                int[] done = {0};
                reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                    @Override
                    public MethodVisitor visitMethod(int access, String n, String d, String s, String[] e) {
                        MethodVisitor mv = super.visitMethod(access, n, d, s, e);
                        if ((access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_STATIC)) != 0) {
                            return mv;
                        }
                        for (Guard g : guards) {
                            if (g.method().equals(n) && (g.descStart() == null || d.startsWith(g.descStart()))) {
                                done[0]++;
                                String what = name.substring(name.lastIndexOf('.') + 1) + "." + n;
                                return new MethodVisitor(Opcodes.ASM9, mv) {
                                    @Override
                                    public void visitCode() {
                                        super.visitCode();
                                        super.visitLdcInsn(what);
                                        super.visitMethodInsn(Opcodes.INVOKESTATIC, "khi/pentecost/messenger/HomeOnly",
                                                "check", "(Ljava/lang/String;)V", false);
                                    }
                                };
                            }
                        }
                        return mv;
                    }
                }, 0);
                return done[0] > 0 ? writer.toByteArray() : bytes;
            } catch (RuntimeException e) {
                khi.pentecost.boot.Log.warn("Server-thread-only guard skipped " + name + ": " + e);
                return bytes;
            }
        };
    }
}
