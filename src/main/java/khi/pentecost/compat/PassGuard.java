package khi.pentecost.compat;

import khi.pentecost.boot.Dirs;
import khi.pentecost.boot.Log;
import khi.pentecost.loader.ClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Portal-pass guards. A rule names a method in any mod class; while the game is drawing a portal view,
 * that method returns its default value immediately and its body does not run.
 *
 * Rule format, one per line (built in below, or in run/config/pentecost-guards.txt):
 *   package.Class#method=value          any method with that name
 *   package.Class#method(desc)=value    one overload, e.g. #check()Z=false
 * value is optional: false / true / a number. Omitted means false, 0 or null by return type.
 * Lines starting with # are comments.
 *
 * Works on classes from any loader kind (Fabric, Forge, plain jars) because it runs at class-load time.
 * Never touches constructors, abstract or native methods.
 */
public final class PassGuard implements ClassTransformer {
    public record Rule(int id, String cls, String method, String desc, String value, String origin) {}

    /** Rules that fix a known cost, each with the evidence behind it. */
    private static final String[] BUILT_IN = {
            // Astrocraft 1.5.1: both "is a reload due?" checks read Minecraft.level. Immersive Portals swaps it
            // for the portal pass, so Astrocraft saw a new dimension/seed/time twice a frame and rebuilt every
            // planet, star and GPU buffer each time (62% of render-thread samples, 29 fps at a portal).
            "mod.lwhrvw.astrocraft.Astrocraft#shouldReloadConfig()Z=false",
            "mod.lwhrvw.astrocraft.Astrocraft#shouldReloadDynamics()Z=false",
    };

    private static final List<Rule> RULES = new ArrayList<>();
    private static final Map<String, List<Rule>> BY_CLASS = new HashMap<>();
    private static final Set<Integer> APPLIED = ConcurrentHashMap.newKeySet();
    private static final Map<Integer, String> PROBLEMS = new ConcurrentHashMap<>();

    static {
        int id = 0;
        for (String s : BUILT_IN) id = add(s, "built in", id);
        try {
            Path f = Dirs.config().resolve("pentecost-guards.txt");
            if (Files.exists(f)) {
                for (String line : Files.readAllLines(f)) id = add(line, "pentecost-guards.txt", id);
            }
        } catch (Throwable t) {
            Log.warn("Guard: could not read pentecost-guards.txt: " + t);
        }
    }

    private static int add(String line, String origin, int id) {
        String s = line.trim();
        if (s.isEmpty() || s.startsWith("#")) return id;
        try {
            String value = "";
            int eq = s.lastIndexOf('=');
            if (eq > 0) { value = s.substring(eq + 1).trim(); s = s.substring(0, eq).trim(); }
            int hash = s.indexOf('#');
            if (hash <= 0) throw new IllegalArgumentException("missing #method");
            String cls = s.substring(0, hash).trim();
            String rest = s.substring(hash + 1).trim();
            int paren = rest.indexOf('(');
            String method = paren < 0 ? rest : rest.substring(0, paren);
            String desc = paren < 0 ? null : rest.substring(paren);
            if (cls.isEmpty() || method.isEmpty()) throw new IllegalArgumentException("empty class or method");
            Rule r = new Rule(id, cls, method, desc, value, origin);
            RULES.add(r);
            BY_CLASS.computeIfAbsent(cls, k -> new ArrayList<>()).add(r);
            return id + 1;
        } catch (Throwable t) {
            Log.warn("Guard: ignoring bad rule '" + line.trim() + "' (" + origin + "): " + t.getMessage());
            return id;
        }
    }

    /** Adds a rule at runtime. Must happen before the target class is loaded. */
    public static synchronized void addRule(String line) {
        add(line, "api", RULES.size());
    }

    public static ClassTransformer transformer() {
        return new PassGuard();
    }

    /** Goes into the class-cache key so changing the rules re-transforms cached classes. */
    public static String fingerprint() {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-1");
            for (Rule r : RULES) sha.update((r.cls + "#" + r.method + r.desc + "=" + r.value + "\n").getBytes());
            sha.update("guard-v1".getBytes());
            return HexFormat.of().formatHex(sha.digest());
        } catch (Exception e) {
            return "guard-error";
        }
    }

    /** For the live probe: every rule, whether it matched a method, and how often it has skipped a call. */
    public static List<Map<String, Object>> describe() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Rule r : RULES) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("rule", r.cls + "#" + r.method + (r.desc == null ? "" : r.desc) + (r.value.isEmpty() ? "" : "=" + r.value));
            m.put("origin", r.origin);
            m.put("applied", APPLIED.contains(r.id));
            if (PROBLEMS.containsKey(r.id)) m.put("problem", PROBLEMS.get(r.id));
            m.put("callsSkipped", PortalPass.skips(r.id));
            out.add(m);
        }
        return out;
    }

    @Override
    public byte[] transform(String className, byte[] bytes) {
        List<Rule> rules = BY_CLASS.get(className);
        if (rules == null || bytes == null) return bytes;
        try {
            ClassReader cr = new ClassReader(bytes);
            if (cr.readShort(6) < 50) {
                for (Rule r : rules) PROBLEMS.put(r.id, "class file too old for guards");
                return bytes;
            }
            ClassWriter cw = new ClassWriter(0);
            Set<Integer> hit = new java.util.HashSet<>();
            cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                    MethodVisitor mv = super.visitMethod(access, name, desc, sig, ex);
                    if (mv == null || (access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0 || name.startsWith("<")) {
                        return mv;
                    }
                    for (Rule r : rules) {
                        if (r.method.equals(name) && (r.desc == null || r.desc.equals(desc))) {
                            hit.add(r.id);
                            return new Guard(mv, r, desc);
                        }
                    }
                    return mv;
                }
            }, 0);
            for (Rule r : rules) {
                if (hit.contains(r.id)) {
                    APPLIED.add(r.id);
                    PROBLEMS.remove(r.id);
                    Log.info("Guard: " + r.cls + "#" + r.method + " now skips during portal passes");
                } else {
                    PROBLEMS.put(r.id, "no method matched (mod updated or renamed?)");
                    Log.warn("Guard: rule " + r.cls + "#" + r.method + " matched no method");
                }
            }
            return hit.isEmpty() ? bytes : cw.toByteArray();
        } catch (Throwable t) {
            for (Rule r : rules) PROBLEMS.put(r.id, "transform failed: " + t);
            Log.warn("Guard: could not patch " + className + ", left unchanged: " + t);
            return bytes;
        }
    }

    /** Inserts "if (PortalPass.skip(id)) return <default>;" at the top of one method. */
    private static final class Guard extends MethodVisitor {
        private final Rule rule;
        private final String desc;
        private boolean pendingFrame;

        Guard(MethodVisitor mv, Rule rule, String desc) {
            super(Opcodes.ASM9, mv);
            this.rule = rule;
            this.desc = desc;
        }

        @Override
        public void visitCode() {
            super.visitCode();
            Label go = new Label();
            super.visitLdcInsn(rule.id);
            super.visitMethodInsn(Opcodes.INVOKESTATIC, "khi/pentecost/compat/PortalPass", "skip", "(I)Z", false);
            super.visitJumpInsn(Opcodes.IFEQ, go);
            Type ret = Type.getReturnType(desc);
            switch (ret.getSort()) {
                case Type.VOID -> super.visitInsn(Opcodes.RETURN);
                case Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT, Type.INT -> {
                    super.visitLdcInsn(intValue(rule.value));
                    super.visitInsn(Opcodes.IRETURN);
                }
                case Type.LONG -> { super.visitInsn(Opcodes.LCONST_0); super.visitInsn(Opcodes.LRETURN); }
                case Type.FLOAT -> { super.visitInsn(Opcodes.FCONST_0); super.visitInsn(Opcodes.FRETURN); }
                case Type.DOUBLE -> { super.visitInsn(Opcodes.DCONST_0); super.visitInsn(Opcodes.DRETURN); }
                default -> { super.visitInsn(Opcodes.ACONST_NULL); super.visitInsn(Opcodes.ARETURN); }
            }
            super.visitLabel(go);
            pendingFrame = true;
        }

        private static int intValue(String v) {
            if (v.equalsIgnoreCase("true")) return 1;
            try { return Integer.parseInt(v); } catch (NumberFormatException e) { return 0; }
        }

        /** The jump target needs a stack-map frame. If the method brings its own at this spot, use that one. */
        private void flush() {
            if (pendingFrame) {
                pendingFrame = false;
                super.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
            }
        }

        @Override
        public void visitFrame(int type, int numLocal, Object[] local, int numStack, Object[] stack) {
            pendingFrame = false;
            super.visitFrame(type, numLocal, local, numStack, stack);
        }

        @Override public void visitInsn(int op) { flush(); super.visitInsn(op); }
        @Override public void visitIntInsn(int op, int operand) { flush(); super.visitIntInsn(op, operand); }
        @Override public void visitVarInsn(int op, int v) { flush(); super.visitVarInsn(op, v); }
        @Override public void visitTypeInsn(int op, String t) { flush(); super.visitTypeInsn(op, t); }
        @Override public void visitFieldInsn(int op, String o, String n, String d) { flush(); super.visitFieldInsn(op, o, n, d); }
        @Override public void visitMethodInsn(int op, String o, String n, String d, boolean i) {
            flush();
            super.visitMethodInsn(op, o, n, d, i);
        }
        @Override public void visitInvokeDynamicInsn(String n, String d, Handle h, Object... a) { flush(); super.visitInvokeDynamicInsn(n, d, h, a); }
        @Override public void visitJumpInsn(int op, Label l) { flush(); super.visitJumpInsn(op, l); }
        @Override public void visitLdcInsn(Object v) { flush(); super.visitLdcInsn(v); }
        @Override public void visitIincInsn(int v, int inc) { flush(); super.visitIincInsn(v, inc); }
        @Override public void visitTableSwitchInsn(int min, int max, Label d, Label... l) { flush(); super.visitTableSwitchInsn(min, max, d, l); }
        @Override public void visitLookupSwitchInsn(Label d, int[] k, Label[] l) { flush(); super.visitLookupSwitchInsn(d, k, l); }
        @Override public void visitMultiANewArrayInsn(String d, int n) { flush(); super.visitMultiANewArrayInsn(d, n); }

        @Override
        public void visitMaxs(int maxStack, int maxLocals) {
            super.visitMaxs(Math.max(maxStack, 2), maxLocals);
        }
    }
}
