package khi.pentecost.loader;

import khi.pentecost.boot.Log;
import khi.pentecost.mods.ModInfo;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Applies Fabric and Quilt access widener files. A mod uses one to open private game
 * members it needs. Without this the mod dies with an access error.
 */
public final class AccessWidener implements ClassTransformer {
    private static final int ACCESSIBLE = 1;
    private static final int EXTENDABLE = 2;
    private static final int MUTABLE = 4;

    private static final class Rules {
        int classFlags;
        final Map<String, Integer> methods = new HashMap<>();
        final Map<String, Integer> fields = new HashMap<>();
    }

    private final Map<String, Rules> rules = new HashMap<>();
    private final java.util.Set<String> touched = new java.util.HashSet<>();

    public static AccessWidener load(List<ModInfo> mods) {
        AccessWidener aw = new AccessWidener();
        int files = 0;
        for (ModInfo m : mods) {
            for (String path : m.accessWideners()) {
                try (ZipFile z = new ZipFile(m.jar().toFile())) {
                    ZipEntry e = z.getEntry(path);
                    if (e == null) {
                        Log.warn(m.name() + " names the access widener " + path + ", and the jar lacks it.");
                        continue;
                    }
                    try (InputStream in = z.getInputStream(e)) {
                        aw.read(new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)));
                        files++;
                    }
                } catch (Exception ex) {
                    Log.error("Cannot read access widener " + path + " of " + m.name() + ": " + ex);
                }
            }
        }
        if (files > 0) {
            Log.info("Access wideners: " + files + " file(s) open members in " + aw.rules.size() + " game classes");
        }
        return aw;
    }

    private void read(BufferedReader r) throws java.io.IOException {
        String line;
        boolean header = true;
        while ((line = r.readLine()) != null) {
            int hash = line.indexOf('#');
            if (hash >= 0) {
                line = line.substring(0, hash);
            }
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (header) {
                header = false;
                if (line.startsWith("accessWidener")) {
                    continue;
                }
            }
            String[] p = line.split("\\s+");
            if (p.length < 3) {
                continue;
            }
            String access = p[0].startsWith("transitive-") ? p[0].substring(11) : p[0];
            int flag = switch (access) {
                case "accessible" -> ACCESSIBLE;
                case "extendable" -> EXTENDABLE;
                case "mutable" -> MUTABLE;
                default -> 0;
            };
            if (flag == 0) {
                continue;
            }
            Rules cr = rules.computeIfAbsent(p[2], k -> new Rules());
            touched.add(p[2]);
            for (int cut = p[2].indexOf('$'); cut >= 0; cut = p[2].indexOf('$', cut + 1)) {
                touched.add(p[2].substring(0, cut));
            }
            switch (p[1]) {
                case "class" -> cr.classFlags |= flag;
                case "method" -> {
                    if (p.length >= 5) {
                        cr.methods.merge(p[3] + p[4], flag, (a, b) -> a | b);
                        if (flag == EXTENDABLE) {
                            cr.classFlags |= EXTENDABLE;
                        }
                    }
                }
                case "field" -> {
                    if (p.length >= 5) {
                        cr.fields.merge(p[3], flag, (a, b) -> a | b);
                    }
                }
                default -> { }
            }
        }
    }

    public boolean isEmpty() {
        return rules.isEmpty();
    }

    @Override
    public byte[] transform(String className, byte[] bytes) {
        String internal = className.replace('.', '/');
        // A class is touched when it has rules itself, or when it lists an inner class that has rules.
        if (!touched.contains(internal)) {
            return bytes;
        }
        Rules cr = rules.get(internal);
        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(0);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public void visit(int version, int access, String name, String sig, String sup, String[] itf) {
                super.visit(version, cr == null ? access : classAccess(access, cr.classFlags), name, sig, sup, itf);
            }

            @Override
            public void visitInnerClass(String name, String outer, String inner, int access) {
                Rules ir = rules.get(name);
                super.visitInnerClass(name, outer, inner, ir == null ? access : classAccess(access, ir.classFlags));
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                Integer f = cr == null ? null : cr.methods.get(name + desc);
                if (f != null) {
                    boolean wasPrivate = (access & Opcodes.ACC_PRIVATE) != 0;
                    if ((f & ACCESSIBLE) != 0) {
                        access = makePublic(access);
                        if (wasPrivate && (access & Opcodes.ACC_STATIC) == 0 && !name.equals("<init>")) {
                            access |= Opcodes.ACC_FINAL;
                        }
                    }
                    if ((f & EXTENDABLE) != 0) {
                        if ((access & Opcodes.ACC_PUBLIC) == 0) {
                            access = (access & ~Opcodes.ACC_PRIVATE) | Opcodes.ACC_PROTECTED;
                        }
                        access &= ~Opcodes.ACC_FINAL;
                    }
                }
                return super.visitMethod(access, name, desc, sig, ex);
            }

            @Override
            public FieldVisitor visitField(int access, String name, String desc, String sig, Object value) {
                Integer f = cr == null ? null : cr.fields.get(name);
                if (f != null) {
                    if ((f & ACCESSIBLE) != 0) {
                        access = makePublic(access);
                    }
                    if ((f & MUTABLE) != 0) {
                        access &= ~Opcodes.ACC_FINAL;
                    }
                }
                return super.visitField(access, name, desc, sig, value);
            }
        }, 0);
        return writer.toByteArray();
    }

    private static int classAccess(int access, int flags) {
        if ((flags & (ACCESSIBLE | EXTENDABLE)) != 0) {
            access = makePublic(access);
        }
        if ((flags & EXTENDABLE) != 0) {
            access &= ~Opcodes.ACC_FINAL;
        }
        return access;
    }

    private static int makePublic(int access) {
        return (access & ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED)) | Opcodes.ACC_PUBLIC;
    }
}
