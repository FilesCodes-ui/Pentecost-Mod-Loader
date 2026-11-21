package khi.pentecost.loader;

import khi.pentecost.boot.Log;
import khi.pentecost.mods.ModInfo;
import khi.pentecost.remap.TextRemapper;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class AccessWidener implements ClassTransformer {
    private static final int ACCESSIBLE = 1;
    private static final int EXTENDABLE = 2;
    private static final int MUTABLE = 4;
    private static final int PROTECTED = 8;
    private static final int KEEP_OPEN = 16;

    private static final class Rules {
        int classFlags;
        int allMethods;
        int allFields;
        final Map<String, Integer> methods = new HashMap<>();
        final Map<String, Integer> fields = new HashMap<>();
    }

    private final Map<String, Rules> rules = new HashMap<>();
    private final Set<String> touched = new HashSet<>();

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
                        aw.readWidener(new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)));
                        files++;
                    }
                } catch (Exception ex) {
                    Log.error("Cannot read access widener " + path + " of " + m.name() + ": " + ex);
                }
            }
        }
        int mutable = 0;
        for (ModInfo m : mods) {
            mutable += aw.readMutableShadows(m);
        }
        if (files > 0) {
            Log.info("Access wideners: " + files + " file(s) open members in " + aw.rules.size() + " game classes");
        }
        if (mutable > 0) {
            Log.info("Mixins: " + mutable + " game field(s) opened for writing, as Fabric's Mixin does");
        }
        return aw;
    }

    private static final String MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;";
    private static final String SHADOW = "Lorg/spongepowered/asm/mixin/Shadow;";
    private static final String MUTABLE_ANN = "Lorg/spongepowered/asm/mixin/Mutable;";

    private int readMutableShadows(ModInfo m) {
        if (m.mixins() == null || m.mixins().isEmpty()) {
            return 0;
        }
        int count = 0;
        try (ZipFile z = new ZipFile(m.jar().toFile())) {
            for (String config : m.mixins()) {
                ZipEntry ce = z.getEntry(config);
                if (ce == null) {
                    continue;
                }
                com.google.gson.JsonObject cfg;
                try (InputStream in = z.getInputStream(ce)) {
                    cfg = com.google.gson.JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                            .getAsJsonObject();
                }
                String pkg = cfg.has("package") ? cfg.get("package").getAsString() : "";
                for (String list : new String[]{"mixins", "client", "server"}) {
                    if (!cfg.has(list) || !cfg.get(list).isJsonArray()) {
                        continue;
                    }
                    for (com.google.gson.JsonElement e : cfg.getAsJsonArray(list)) {
                        String cls = (pkg.isEmpty() ? "" : pkg + ".") + e.getAsString();
                        ZipEntry entry = z.getEntry(cls.replace('.', '/') + ".class");
                        if (entry == null) {
                            continue;
                        }
                        try (InputStream in = z.getInputStream(entry)) {
                            count += mutableShadows(in.readAllBytes());
                        }
                    }
                }
            }
        } catch (Exception ex) {
            Log.warn("Cannot read the mixins of " + m.name() + " for writable fields: " + ex);
        }
        return count;
    }

    private int mutableShadows(byte[] bytes) {
        List<String> targets = new java.util.ArrayList<>();
        List<String[]> fields = new java.util.ArrayList<>();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public org.objectweb.asm.AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                if (!MIXIN.equals(desc)) {
                    return null;
                }
                return new org.objectweb.asm.AnnotationVisitor(Opcodes.ASM9) {
                    @Override
                    public org.objectweb.asm.AnnotationVisitor visitArray(String name) {
                        return new org.objectweb.asm.AnnotationVisitor(Opcodes.ASM9) {
                            @Override
                            public void visit(String n, Object value) {
                                if (value instanceof org.objectweb.asm.Type t) {
                                    targets.add(t.getInternalName());
                                } else if (value instanceof String str && "targets".equals(name)) {
                                    targets.add(str.replace('.', '/'));
                                }
                            }
                        };
                    }
                };
            }

            @Override
            public FieldVisitor visitField(int access, String name, String desc, String sig, Object value) {
                boolean[] flags = new boolean[2];
                String[] prefix = {"shadow$"};
                fields.add(new String[]{name, null});
                int index = fields.size() - 1;
                return new FieldVisitor(Opcodes.ASM9) {
                    @Override
                    public org.objectweb.asm.AnnotationVisitor visitAnnotation(String d, boolean v) {
                        if (MUTABLE_ANN.equals(d)) {
                            flags[1] = true;
                        } else if (SHADOW.equals(d)) {
                            flags[0] = true;
                            return new org.objectweb.asm.AnnotationVisitor(Opcodes.ASM9) {
                                @Override
                                public void visit(String n, Object val) {
                                    if ("prefix".equals(n) && val instanceof String p) {
                                        prefix[0] = p;
                                    }
                                }
                            };
                        }
                        return null;
                    }

                    @Override
                    public void visitEnd() {
                        if (flags[0] && flags[1]) {
                            String n = name.startsWith(prefix[0]) ? name.substring(prefix[0].length()) : name;
                            fields.set(index, new String[]{name, n});
                        }
                    }
                };
            }
        }, ClassReader.SKIP_CODE);
        int count = 0;
        for (String[] f : fields) {
            if (f[1] == null) {
                continue;
            }
            for (String target : targets) {
                rulesFor(target).fields.merge(f[1], MUTABLE, (a, b) -> a | b);
                count++;
            }
        }
        return count;
    }

    public void addAccessTransformers(List<Path> jars, TextRemapper text) {
        int files = 0;
        for (Path jar : jars) {
            try (ZipFile z = new ZipFile(jar.toFile())) {
                ZipEntry e = z.getEntry("META-INF/accesstransformer.cfg");
                if (e == null) {
                    continue;
                }
                try (InputStream in = z.getInputStream(e)) {
                    readTransformer(new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)), text);
                    files++;
                }
            } catch (Exception ex) {
                Log.error("Cannot read the access transformer of " + jar.getFileName() + ": " + ex);
            }
        }
        if (files > 0) {
            Log.info("Access transformers: " + files + " file(s), " + rules.size() + " game classes opened in total");
        }
    }

    private Rules rulesFor(String cls) {
        touched.add(cls);
        for (int cut = cls.indexOf('$'); cut >= 0; cut = cls.indexOf('$', cut + 1)) {
            touched.add(cls.substring(0, cut));
        }
        return rules.computeIfAbsent(cls, k -> new Rules());
    }

    private void readWidener(BufferedReader r) throws IOException {
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
            Rules cr = rulesFor(p[2]);
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

    private void readTransformer(BufferedReader r, TextRemapper text) throws IOException {
        String line;
        while ((line = r.readLine()) != null) {
            int hash = line.indexOf('#');
            if (hash >= 0) {
                line = line.substring(0, hash);
            }
            line = text.remap(line.trim());
            if (line.isEmpty()) {
                continue;
            }
            String[] p = line.split("\\s+");
            if (p.length < 2) {
                continue;
            }
            String access = p[0];
            int flag = KEEP_OPEN;
            if (access.startsWith("public")) {
                flag |= ACCESSIBLE;
            } else if (access.startsWith("protected")) {
                flag |= PROTECTED;
            }
            boolean definal = access.endsWith("-f");
            Rules cr = rulesFor(p[1].replace('.', '/'));
            if (p.length == 2) {
                cr.classFlags |= flag | (definal ? EXTENDABLE : 0);
            } else if (p[2].equals("*")) {
                cr.allFields |= flag | (definal ? MUTABLE : 0);
            } else if (p[2].startsWith("*(")) {
                cr.allMethods |= flag | (definal ? EXTENDABLE : 0);
            } else if (p[2].indexOf('(') >= 0) {
                cr.methods.merge(p[2], flag | (definal ? EXTENDABLE : 0), (a, b) -> a | b);
            } else {
                cr.fields.merge(p[2], flag | (definal ? MUTABLE : 0), (a, b) -> a | b);
            }
        }
    }

    public void addBuiltIn() {
        Rules cr = rulesFor("net/minecraft/client/multiplayer/ClientLevel");
        cr.methods.merge("getEntities()Lnet/minecraft/world/level/entity/LevelEntityGetter;", ACCESSIBLE, (a, b) -> a | b);
    }

    public boolean isEmpty() {
        return rules.isEmpty();
    }

    @Override
    public byte[] transform(String className, byte[] bytes) {
        String internal = className.replace('.', '/');
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
                int f = cr == null ? 0 : cr.allMethods | cr.methods.getOrDefault(name + desc, 0);
                if (f != 0 && !name.equals("<clinit>")) {
                    boolean wasPrivate = (access & Opcodes.ACC_PRIVATE) != 0;
                    if ((f & ACCESSIBLE) != 0) {
                        access = makePublic(access);
                        if (wasPrivate && (f & KEEP_OPEN) == 0 && (access & Opcodes.ACC_STATIC) == 0
                                && !name.equals("<init>")) {
                            access |= Opcodes.ACC_FINAL;
                        }
                    } else if ((f & PROTECTED) != 0) {
                        access = atLeastProtected(access);
                    }
                    if ((f & EXTENDABLE) != 0) {
                        access = atLeastProtected(access);
                        access &= ~Opcodes.ACC_FINAL;
                    }
                }
                return super.visitMethod(access, name, desc, sig, ex);
            }

            @Override
            public FieldVisitor visitField(int access, String name, String desc, String sig, Object value) {
                int f = cr == null ? 0 : cr.allFields | cr.fields.getOrDefault(name, 0);
                if (f != 0) {
                    if ((f & ACCESSIBLE) != 0) {
                        access = makePublic(access);
                    } else if ((f & PROTECTED) != 0) {
                        access = atLeastProtected(access);
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
        if ((flags & (ACCESSIBLE | EXTENDABLE | PROTECTED)) != 0) {
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

    private static int atLeastProtected(int access) {
        if ((access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED)) != 0) {
            return access;
        }
        return (access & ~Opcodes.ACC_PRIVATE) | Opcodes.ACC_PROTECTED;
    }
}
