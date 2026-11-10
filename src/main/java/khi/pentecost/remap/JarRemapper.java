package khi.pentecost.remap;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** Rewrites every class in a jar through a remapper, on all CPU threads. */
public final class JarRemapper {
    private JarRemapper() {}

    /** @param text when set, also converts annotation strings, refmap files, and access widener files */
    public static void remap(Path in, Path out, Remapper remapper, TextRemapper text) throws IOException {
        List<Map.Entry<String, byte[]>> entries = new ArrayList<>();
        try (ZipFile z = new ZipFile(in.toFile())) {
            Enumeration<? extends ZipEntry> en = z.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory() || isSignature(e.getName())) {
                    continue;
                }
                try (InputStream is = z.getInputStream(e)) {
                    entries.add(Map.entry(e.getName(), is.readAllBytes()));
                }
            }
        }
        List<Map.Entry<String, byte[]>> done = entries.parallelStream().map(e -> {
            String name = e.getKey();
            if (!name.endsWith(".class")) {
                return text == null ? e : Map.entry(name, textEntry(name, e.getValue(), text));
            }
            try {
                ClassReader cr = new ClassReader(e.getValue());
                ClassWriter cw = new ClassWriter(0);
                ClassVisitor chain = text == null ? cw : new StringFixer(cw, text);
                cr.accept(new ClassRemapper(chain, remapper), 0);
                String prefix = "";
                // Multi-release jars keep classes under META-INF/versions/N/.
                int cut = name.indexOf(cr.getClassName() + ".class");
                if (cut > 0) {
                    prefix = name.substring(0, cut);
                }
                return Map.entry(prefix + remapper.map(cr.getClassName()) + ".class", cw.toByteArray());
            } catch (RuntimeException ex) {
                return e;
            }
        }).toList();

        Files.createDirectories(out.getParent());
        Path tmp = out.resolveSibling(out.getFileName() + ".part");
        Set<String> seen = new HashSet<>();
        try (OutputStream os = Files.newOutputStream(tmp); ZipOutputStream zos = new ZipOutputStream(os)) {
            zos.setLevel(1);
            for (Map.Entry<String, byte[]> e : done) {
                if (!seen.add(e.getKey())) {
                    continue;
                }
                zos.putNextEntry(new ZipEntry(e.getKey()));
                zos.write(e.getValue());
                zos.closeEntry();
            }
        }
        Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING);
    }

    private static byte[] textEntry(String name, byte[] data, TextRemapper text) {
        boolean widener = name.endsWith(".accesswidener") || name.endsWith(".aw");
        boolean json = name.endsWith(".json") && !name.startsWith("assets/") && !name.startsWith("data/");
        if (!widener && !json) {
            return data;
        }
        String s = new String(data, StandardCharsets.UTF_8);
        if (widener) {
            return text.remap(s).getBytes(StandardCharsets.UTF_8);
        }
        if (!(s.contains("\"mappings\"") && s.contains("\"data\""))) {
            return data;
        }
        try {
            com.google.gson.JsonObject root = com.google.gson.JsonParser.parseString(s).getAsJsonObject();
            remapValues(root.get("mappings"), text);
            remapValues(root.get("data"), text);
            return root.toString().getBytes(StandardCharsets.UTF_8);
        } catch (RuntimeException notARefmap) {
            return data;
        }
    }

    private static void remapValues(com.google.gson.JsonElement e, TextRemapper text) {
        if (e == null || !e.isJsonObject()) {
            return;
        }
        com.google.gson.JsonObject o = e.getAsJsonObject();
        for (String key : new ArrayList<>(o.keySet())) {
            com.google.gson.JsonElement v = o.remove(key);
            if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isString()) {
                o.addProperty(text.remap(key), text.remap(v.getAsString()));
            } else {
                remapValues(v, text);
                o.add(key, v);
            }
        }
    }

    private static boolean isSignature(String name) {
        if (!name.startsWith("META-INF/")) {
            return false;
        }
        return name.endsWith(".SF") || name.endsWith(".RSA") || name.endsWith(".DSA") || name.endsWith(".EC");
    }

    /** Converts name tokens inside annotation strings, such as Mixin targets. */
    private static final class StringFixer extends ClassVisitor {
        private static final java.util.regex.Pattern INFERRED =
                java.util.regex.Pattern.compile("^(get|set|is|call|invoke|create|new)([A-Za-z].*)$");
        private final TextRemapper text;

        StringFixer(ClassVisitor next, TextRemapper text) {
            super(Opcodes.ASM9, next);
            this.text = text;
        }

        private AnnotationVisitor fix(AnnotationVisitor av) {
            if (av == null) {
                return null;
            }
            return new AnnotationVisitor(Opcodes.ASM9, av) {
                @Override
                public void visit(String name, Object value) {
                    super.visit(name, value instanceof String s ? text.remap(s) : value);
                }

                @Override
                public AnnotationVisitor visitAnnotation(String name, String descriptor) {
                    return fix(super.visitAnnotation(name, descriptor));
                }

                @Override
                public AnnotationVisitor visitArray(String name) {
                    return fix(super.visitArray(name));
                }
            };
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            return fix(super.visitAnnotation(descriptor, visible));
        }

        @Override
        public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
            FieldVisitor fv = super.visitField(access, name, descriptor, signature, value);
            return fv == null ? null : new FieldVisitor(Opcodes.ASM9, fv) {
                @Override
                public AnnotationVisitor visitAnnotation(String d, boolean visible) {
                    return fix(super.visitAnnotation(d, visible));
                }
            };
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] ex) {
            MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, ex);
            return mv == null ? null : new MethodVisitor(Opcodes.ASM9, mv) {
                @Override
                public AnnotationVisitor visitAnnotation(String d, boolean visible) {
                    AnnotationVisitor fixed = fix(super.visitAnnotation(d, visible));
                    boolean accessor = d.equals("Lorg/spongepowered/asm/mixin/gen/Accessor;")
                            || d.equals("Lorg/spongepowered/asm/mixin/gen/Invoker;");
                    if (!accessor || fixed == null) {
                        return fixed;
                    }
                    return new AnnotationVisitor(Opcodes.ASM9, fixed) {
                        private boolean hasValue;

                        @Override
                        public void visit(String n, Object value) {
                            if ("value".equals(n)) {
                                hasValue = true;
                            }
                            super.visit(n, value);
                        }

                        @Override
                        public void visitEnd() {
                            if (!hasValue) {
                                java.util.regex.Matcher m = INFERRED.matcher(name);
                                if (m.matches()) {
                                    String target = Character.toLowerCase(m.group(2).charAt(0)) + m.group(2).substring(1);
                                    String mapped = text.remap(target);
                                    if (!mapped.equals(target)) {
                                        super.visit("value", mapped);
                                    }
                                }
                            }
                            super.visitEnd();
                        }
                    };
                }

                @Override
                public AnnotationVisitor visitParameterAnnotation(int parameter, String d, boolean visible) {
                    return fix(super.visitParameterAnnotation(parameter, d, visible));
                }
            };
        }
    }
}
