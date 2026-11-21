package khi.pentecost.core;

import khi.pentecost.boot.Log;
import khi.pentecost.loader.ClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class InstanceofGate {
    private InstanceofGate() {}

    private static final String EVENT = "net/minecraftforge/event/entity/living/LivingEvent$LivingTickEvent";
    private static final String EVENT_DESC = "(L" + EVENT + ";)V";
    private static final String SELF = "khi/pentecost/core/InstanceofGate";
    private static final int MIN_CHECKS = 25;

    private static final List<ClassValue<Boolean>> SITES = new ArrayList<>();

    static int register(Set<String> names) {
        ClassValue<Boolean> cv = new ClassValue<>() {
            @Override
            protected Boolean computeValue(Class<?> type) {
                return !fits(type, names);
            }
        };
        synchronized (SITES) {
            SITES.add(cv);
            return SITES.size() - 1;
        }
    }

    public static boolean skip(Object entity, int site) {
        if (entity == null) return false;
        try {
            ClassValue<Boolean> cv;
            synchronized (SITES) {
                cv = SITES.get(site);
            }
            return cv.get(entity.getClass());
        } catch (Throwable t) {
            return false;
        }
    }

    static boolean fits(Class<?> type, Set<String> names) {
        ArrayDeque<Class<?>> todo = new ArrayDeque<>();
        Set<Class<?>> seen = new HashSet<>();
        todo.add(type);
        while (!todo.isEmpty()) {
            Class<?> c = todo.poll();
            if (!seen.add(c)) continue;
            if (names.contains(c.getName())) return true;
            Class<?> sup = c.getSuperclass();
            if (sup != null) todo.add(sup);
            for (Class<?> i : c.getInterfaces()) todo.add(i);
        }
        return false;
    }

    public static ClassTransformer transformer() {
        return (name, bytes) -> {
            if (bytes.length < 1500 || name.startsWith("org.lwjgl.") || name.startsWith("khi.pentecost.")
                    || name.startsWith("net.minecraft.") || name.startsWith("net.minecraftforge.") || name.startsWith("java.")
                    || System.getenv("PENTECOST_NO_INSTANCEOF_GATE") != null) {
                return bytes;
            }
            if (!contains(bytes, EVENT)) return bytes;
            try {
                return rewrite(name, bytes);
            } catch (RuntimeException e) {
                return bytes;
            }
        };
    }

    static byte[] rewrite(String className, byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        boolean changed = false;
        for (MethodNode m : node.methods) {
            if (!m.desc.equals(EVENT_DESC) || (m.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) continue;
            if (m.tryCatchBlocks != null && !m.tryCatchBlocks.isEmpty()) continue;
            int argLocal = (m.access & Opcodes.ACC_STATIC) != 0 ? 0 : 1;
            Set<String> targets = analyze(m, argLocal);
            if (targets == null || targets.size() < MIN_CHECKS) continue;
            int site = register(targets);
            LabelNode run = new LabelNode();
            InsnList pre = new InsnList();
            pre.add(new VarInsnNode(Opcodes.ALOAD, argLocal));
            pre.add(new JumpInsnNode(Opcodes.IFNULL, run));
            pre.add(new VarInsnNode(Opcodes.ALOAD, argLocal));
            pre.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, EVENT, "getEntity", "()Lnet/minecraft/world/entity/LivingEntity;", false));
            pre.add(new LdcInsnNode(site));
            pre.add(new MethodInsnNode(Opcodes.INVOKESTATIC, SELF, "skip", "(Ljava/lang/Object;I)Z", false));
            pre.add(new JumpInsnNode(Opcodes.IFEQ, run));
            pre.add(new InsnNode(Opcodes.RETURN));
            pre.add(run);
            pre.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
            m.instructions.insert(pre);
            changed = true;
            Log.info("Mob tick gate: " + className + "." + m.name + " now skips " + targets.size()
                    + " type checks for mobs of other types");
        }
        if (!changed) return bytes;
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(cw);
        return cw.toByteArray();
    }

    static Set<String> analyze(MethodNode m, int argLocal) {
        Set<String> targets = new HashSet<>();
        Set<LabelNode> open = new HashSet<>();
        int entityLocal = -1;
        AbstractInsnNode prev = null;
        for (AbstractInsnNode in : m.instructions) {
            if (in instanceof LabelNode l) {
                open.remove(l);
                continue;
            }
            if (in instanceof LineNumberNode || in instanceof FrameNode) continue;
            if (!open.isEmpty()) {
                prev = in;
                continue;
            }
            switch (in.getOpcode()) {
                case Opcodes.ALOAD -> {
                    int v = ((VarInsnNode) in).var;
                    if (v != argLocal && v != entityLocal) return null;
                }
                case Opcodes.ASTORE -> {
                    if (!(prev instanceof MethodInsnNode mi && isGetEntity(mi))) return null;
                    entityLocal = ((VarInsnNode) in).var;
                }
                case Opcodes.INVOKEVIRTUAL -> {
                    if (!isGetEntity((MethodInsnNode) in)) return null;
                    if (!(prev instanceof VarInsnNode v && v.getOpcode() == Opcodes.ALOAD && v.var == argLocal)) return null;
                }
                case Opcodes.INSTANCEOF -> {
                    if (!(prev instanceof VarInsnNode v && v.getOpcode() == Opcodes.ALOAD && v.var == entityLocal)) return null;
                    targets.add(((TypeInsnNode) in).desc.replace('/', '.'));
                }
                case Opcodes.IFEQ -> {
                    if (!(prev instanceof TypeInsnNode t && t.getOpcode() == Opcodes.INSTANCEOF)) return null;
                    open.add(((JumpInsnNode) in).label);
                }
                case Opcodes.IFNULL, Opcodes.RETURN -> {
                }
                default -> {
                    return null;
                }
            }
            prev = in;
        }
        return open.isEmpty() ? targets : null;
    }

    private static boolean isGetEntity(MethodInsnNode mi) {
        return mi.name.equals("getEntity") && mi.owner.equals(EVENT);
    }

    private static boolean contains(byte[] b, String s) {
        byte[] p = s.getBytes(StandardCharsets.ISO_8859_1);
        outer:
        for (int i = 0; i <= b.length - p.length; i++) {
            for (int j = 0; j < p.length; j++) {
                if (b[i + j] != p[j]) continue outer;
            }
            return true;
        }
        return false;
    }
}
