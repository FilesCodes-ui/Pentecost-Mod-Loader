package khi.pentecost.mixin;

import khi.pentecost.boot.Dirs;
import khi.pentecost.boot.Log;
import khi.pentecost.loader.GameClassLoader;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class MixinReport {
    public static final boolean ON = System.getenv("PENTECOST_REPORT") != null;

    private static final int MAX = 1 << 17;
    private static final boolean[] HIT = new boolean[ON ? MAX : 0];
    private static final AtomicInteger NEXT = new AtomicInteger();
    private static final String[][] INFO = new String[ON ? MAX : 0][];
    private static final String MERGED = "Lorg/spongepowered/asm/mixin/transformer/meta/MixinMerged;";

    static {
        if (ON) {
            Runtime.getRuntime().addShutdownHook(new Thread(MixinReport::write, "pentecost-mixin-report"));
            Log.info("Mixin report on: run/logs/pentecost-mixins.log is written when the game closes");
        }
    }

    private MixinReport() {}

    public static void hit(int id) {
        HIT[id] = true;
    }

    public static byte[] instrument(GameClassLoader loader, String className, byte[] bytes) {
        if (!ON || bytes == null) {
            return bytes;
        }
        try {
            ClassNode node = new ClassNode();
            new ClassReader(bytes).accept(node, 0);
            boolean changed = false;
            for (MethodNode m : node.methods) {
                if ((m.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0 || m.instructions.size() == 0) {
                    continue;
                }
                String mixin = mixinOf(m);
                if (mixin == null) {
                    continue;
                }
                int id = NEXT.getAndIncrement();
                if (id >= MAX) {
                    break;
                }
                String mod = loader.modOf(mixin);
                INFO[id] = new String[]{mod == null ? "?" : mod, mixin, className, m.name};
                InsnList mark = new InsnList();
                mark.add(new LdcInsnNode(id));
                mark.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "khi/pentecost/mixin/MixinReport", "hit", "(I)V", false));
                m.instructions.insert(mark);
                changed = true;
            }
            if (!changed) {
                return bytes;
            }
            ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            node.accept(w);
            return w.toByteArray();
        } catch (RuntimeException e) {
            return bytes;
        }
    }

    private static String mixinOf(MethodNode m) {
        for (List<AnnotationNode> list : java.util.Arrays.asList(m.visibleAnnotations, m.invisibleAnnotations)) {
            if (list == null) {
                continue;
            }
            for (AnnotationNode a : list) {
                if (MERGED.equals(a.desc) && a.values != null) {
                    for (int i = 0; i + 1 < a.values.size(); i += 2) {
                        if ("mixin".equals(a.values.get(i))) {
                            return String.valueOf(a.values.get(i + 1));
                        }
                    }
                }
            }
        }
        return null;
    }

    private static void write() {
        int n = Math.min(NEXT.get(), MAX);
        Map<String, List<Integer>> byMod = new TreeMap<>();
        for (int i = 0; i < n; i++) {
            if (INFO[i] != null) {
                byMod.computeIfAbsent(INFO[i][0], k -> new ArrayList<>()).add(i);
            }
        }
        StringBuilder out = new StringBuilder();
        out.append("Pentecost mixin report. A hook that never ran is either unused in this session\n")
           .append("or sits in game code this loader layer skips. Mods with 0 hooks run are the first to check.\n\n");
        StringBuilder detail = new StringBuilder();
        for (Map.Entry<String, List<Integer>> e : byMod.entrySet()) {
            int ran = 0;
            List<Integer> never = new ArrayList<>();
            for (int id : e.getValue()) {
                if (HIT[id]) {
                    ran++;
                } else {
                    never.add(id);
                }
            }
            out.append(String.format("%-36s ran %4d of %4d%n", e.getKey(), ran, e.getValue().size()));
            if (!never.isEmpty()) {
                detail.append("\n== ").append(e.getKey()).append(": never ran\n");
                for (int id : never) {
                    String[] f = INFO[id];
                    detail.append("  ").append(f[2]).append('.').append(f[3])
                          .append("   from ").append(f[1].substring(f[1].lastIndexOf('.') + 1)).append('\n');
                }
            }
        }
        out.append(detail);
        try {
            Path file = Dirs.run().resolve("logs").resolve("pentecost-mixins.log");
            Files.createDirectories(file.getParent());
            Files.writeString(file, out, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
        }
    }
}
