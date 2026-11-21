package khi.pentecost.loader;

import khi.pentecost.boot.Log;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

final class DefaultConflicts {
    private static final Map<String, Map<String, String>> DEFAULTS = new ConcurrentHashMap<>();
    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();

    private DefaultConflicts() {}

    static byte[] transform(ClassLoader source, String name, byte[] bytes) {
        ClassReader reader = new ClassReader(bytes);
        if ((reader.getAccess() & Opcodes.ACC_INTERFACE) != 0) {
            return bytes;
        }
        String[] itfs = reader.getInterfaces();
        if (itfs.length < 2) {
            return bytes;
        }
        Map<String, List<String>> owners = new LinkedHashMap<>();
        Map<String, Set<String>> declarers = new HashMap<>();
        for (String itf : itfs) {
            for (Map.Entry<String, String> d : defaultsOf(source, itf, new HashSet<>()).entrySet()) {
                owners.computeIfAbsent(d.getKey(), k -> new ArrayList<>()).add(itf);
                declarers.computeIfAbsent(d.getKey(), k -> new HashSet<>()).add(d.getValue());
            }
        }
        Map<String, String> fix = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : owners.entrySet()) {
            if (e.getValue().size() < 2 || !realClash(source, declarers.get(e.getKey()))) {
                continue;
            }
            String winner = e.getValue().get(0);
            for (String itf : e.getValue()) {
                if (itf.startsWith("net/minecraftforge/")) {
                    winner = itf;
                    break;
                }
            }
            fix.put(e.getKey(), winner);
        }
        if (fix.isEmpty()) {
            return bytes;
        }
        Set<String> declared = new HashSet<>();
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String n, String d, String s, String[] ex) {
                declared.add(n + " " + d);
                return null;
            }
        }, ClassReader.SKIP_CODE);
        fix.keySet().removeAll(declared);
        if (fix.isEmpty()) {
            return bytes;
        }
        String internal = name.replace('.', '/');
        ClassWriter writer = new ClassWriter(reader, 0);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public void visitEnd() {
                for (Map.Entry<String, String> e : fix.entrySet()) {
                    int space = e.getKey().indexOf(' ');
                    String mn = e.getKey().substring(0, space);
                    String md = e.getKey().substring(space + 1);
                    MethodVisitor mv = super.visitMethod(Opcodes.ACC_PUBLIC, mn, md, null, null);
                    mv.visitCode();
                    mv.visitVarInsn(Opcodes.ALOAD, 0);
                    int slot = 1;
                    for (Type t : Type.getArgumentTypes(md)) {
                        mv.visitVarInsn(t.getOpcode(Opcodes.ILOAD), slot);
                        slot += t.getSize();
                    }
                    mv.visitMethodInsn(Opcodes.INVOKESPECIAL, e.getValue(), mn, md, true);
                    mv.visitInsn(Type.getReturnType(md).getOpcode(Opcodes.IRETURN));
                    mv.visitMaxs(Math.max(1, slot + 1), slot);
                    mv.visitEnd();
                    if (REPORTED.add(internal + "." + mn)) {
                        Log.info(internal.substring(internal.lastIndexOf('/') + 1) + "." + mn
                                + " came from two loaders. Using " + e.getValue().substring(e.getValue().lastIndexOf('/') + 1));
                    }
                }
                super.visitEnd();
            }
        }, 0);
        return writer.toByteArray();
    }

    private static boolean realClash(ClassLoader source, Set<String> declarers) {
        if (declarers == null || declarers.size() < 2) {
            return false;
        }
        Set<String> top = new HashSet<>(declarers);
        for (String a : declarers) {
            for (String b : declarers) {
                if (!a.equals(b) && supers(source, a).contains(b)) {
                    top.remove(b);
                }
            }
        }
        return top.size() > 1;
    }

    private static final Map<String, Set<String>> SUPERS = new ConcurrentHashMap<>();

    private static Set<String> supers(ClassLoader source, String itf) {
        Set<String> cached = SUPERS.get(itf);
        if (cached != null) {
            return cached;
        }
        Set<String> out = new HashSet<>();
        try (InputStream in = source.getResourceAsStream(itf + ".class")) {
            if (in != null) {
                for (String p : new ClassReader(in.readAllBytes()).getInterfaces()) {
                    out.add(p);
                    out.addAll(supers(source, p));
                }
            }
        } catch (Exception e) {
        }
        SUPERS.put(itf, out);
        return out;
    }

    private static Map<String, String> defaultsOf(ClassLoader source, String itf, Set<String> seen) {
        Map<String, String> cached = DEFAULTS.get(itf);
        if (cached != null) {
            return cached;
        }
        Map<String, String> out = new HashMap<>();
        if (!seen.add(itf)) {
            return out;
        }
        try (InputStream in = source.getResourceAsStream(itf + ".class")) {
            if (in == null) {
                return out;
            }
            ClassReader r = new ClassReader(in.readAllBytes());
            Map<String, Boolean> own = new HashMap<>();
            r.accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String n, String d, String s, String[] ex) {
                    boolean isDefault = (access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_STATIC | Opcodes.ACC_PRIVATE)) == 0
                            && !n.startsWith("<");
                    own.put(n + " " + d, isDefault);
                    return null;
                }
            }, ClassReader.SKIP_CODE);
            for (String parent : r.getInterfaces()) {
                for (Map.Entry<String, String> m : defaultsOf(source, parent, seen).entrySet()) {
                    if (!own.containsKey(m.getKey())) {
                        out.putIfAbsent(m.getKey(), m.getValue());
                    }
                }
            }
            own.forEach((m, isDefault) -> {
                if (isDefault) {
                    out.put(m, itf);
                }
            });
        } catch (Exception e) {
            return out;
        }
        DEFAULTS.put(itf, out);
        return out;
    }
}
