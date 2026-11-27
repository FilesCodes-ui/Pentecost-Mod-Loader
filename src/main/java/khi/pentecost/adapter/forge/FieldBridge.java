package khi.pentecost.adapter.forge;

import khi.pentecost.boot.Log;
import khi.pentecost.loader.ClassTransformer;
import khi.pentecost.loader.GameClassLoader;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A Fabric mod reads a registry field as a WritableRegistry. When another mod is a Forge build,
 * the same field holds a Supplier of a Forge registry. This rewrites the read so Fabric code still works.
 */
public final class FieldBridge {
    private static final String WRITABLE = "Lnet/minecraft/core/WritableRegistry;";
    private static final String REGISTRY = "Lnet/minecraft/core/Registry;";
    private static final String SUPPLIER = "Ljava/util/function/Supplier;";
    private static final Map<String, String> SEEN = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> REPORTED = new ConcurrentHashMap<>();

    private FieldBridge() {}

    public static ClassTransformer transformer(GameClassLoader loader) {
        return (name, bytes) -> {
            if (name.startsWith("net.minecraft.") || name.startsWith("net.minecraftforge.")
                    || name.startsWith("khi.pentecost.") || name.startsWith("java.")
                    || name.startsWith("org.spongepowered.") || name.startsWith("org.objectweb.")
                    || !mentions(bytes, "net/minecraft/core/WritableRegistry")
                    && !mentions(bytes, "net/minecraft/core/Registry;")) {
                return bytes;
            }
            ClassNode node = new ClassNode();
            new ClassReader(bytes).accept(node, 0);
            boolean changed = false;
            for (MethodNode m : node.methods) {
                for (AbstractInsnNode insn : m.instructions.toArray()) {
                    if (!(insn instanceof FieldInsnNode f) || f.getOpcode() != Opcodes.GETSTATIC
                            || !(f.desc.equals(WRITABLE) || f.desc.equals(REGISTRY))
                            || f.owner.startsWith("net/minecraft/")) {
                        continue;
                    }
                    String actual = declared(loader, f.owner, f.name);
                    if (actual == null || actual.equals(f.desc)) {
                        continue;
                    }
                    if (!actual.equals(SUPPLIER)) {
                        if (REPORTED.putIfAbsent(f.owner + "." + f.name, true) == null) {
                            Log.warn("Field " + f.owner.replace('/', '.') + "." + f.name + " is " + actual
                                    + " here, not " + f.desc + ", and no bridge exists for that pair");
                        }
                        continue;
                    }
                    f.desc = SUPPLIER;
                    m.instructions.insert(f, new MethodInsnNode(Opcodes.INVOKESTATIC,
                            "khi/pentecost/forgeglue/RegistryAdapter", "writable",
                            "(Ljava/util/function/Supplier;)" + WRITABLE, false));
                    changed = true;
                    if (REPORTED.putIfAbsent("ok:" + f.owner + "." + f.name, true) == null) {
                        Log.info("Bridged " + f.owner.replace('/', '.') + "." + f.name
                                + " from a Forge registry for " + name);
                    }
                }
            }
            if (!changed) {
                return bytes;
            }
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            node.accept(writer);
            return writer.toByteArray();
        };
    }

    private static String declared(GameClassLoader loader, String owner, String field) {
        String key = owner + "." + field;
        String hit = SEEN.get(key);
        if (hit != null) {
            return hit.isEmpty() ? null : hit;
        }
        String found = "";
        try (InputStream in = loader.getResourceAsStream(owner + ".class")) {
            if (in != null) {
                ClassNode node = new ClassNode();
                new ClassReader(in.readAllBytes()).accept(node, ClassReader.SKIP_CODE);
                for (FieldNode fn : node.fields) {
                    if (fn.name.equals(field)) {
                        found = fn.desc;
                        break;
                    }
                }
            }
        } catch (Exception | LinkageError e) {
            found = "";
        }
        SEEN.put(key, found);
        return found.isEmpty() ? null : found;
    }

    private static boolean mentions(byte[] bytes, String text) {
        byte[] t = text.getBytes(StandardCharsets.UTF_8);
        outer:
        for (int i = 0; i + t.length <= bytes.length; i++) {
            for (int j = 0; j < t.length; j++) {
                if (bytes[i + j] != t[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }
}
