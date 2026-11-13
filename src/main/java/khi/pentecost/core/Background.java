package khi.pentecost.core;

import khi.pentecost.loader.ClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.lang.reflect.Method;

/**
 * Moves slow network work that mods do on the main thread to a background thread.
 * Today that is the update check of Xaero's Minimap and Xaero's World Map, which took about 2.7 s at startup.
 * The mod jars stay as they are. Only the loaded copy of the class changes.
 * Set PENTECOST_XAERO_CHECK=1 to run the check the old way.
 */
public final class Background {
    private static final String SUFFIX = "$pentecost";

    private Background() {}

    /** Called by the rewritten method. Runs the original method on its own thread. */
    public static void run(Class<?> owner, String method, Object argument) {
        Runnable job = () -> {
            try {
                for (Method m : owner.getDeclaredMethods()) {
                    if (m.getName().equals(method)) {
                        m.setAccessible(true);
                        if (m.getParameterCount() == 0) {
                            m.invoke(null);
                        } else {
                            m.invoke(null, argument);
                        }
                        return;
                    }
                }
            } catch (Throwable e) {
                System.out.println("[Pentecost] Background update check failed in " + owner.getName() + ": " + e);
            }
        };
        if (System.getenv("PENTECOST_XAERO_CHECK") != null) {
            job.run();
            return;
        }
        Thread t = new Thread(job, "pentecost-update-check");
        t.setDaemon(true);
        t.start();
    }

    private static boolean wanted(String className) {
        return className.equals("xaero.common.misc.Internet") || className.equals("xaero.map.misc.Internet");
    }

    public static ClassTransformer transformer() {
        return (name, bytes) -> {
            if (!wanted(name)) {
                return bytes;
            }
            ClassReader reader = new ClassReader(bytes);
            ClassWriter writer = new ClassWriter(0);
            final String[] self = new String[1];
            final String[] desc = new String[1];
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                @Override
                public void visit(int version, int access, String n, String sig, String sup, String[] itf) {
                    self[0] = n;
                    super.visit(version, access, n, sig, sup, itf);
                }

                @Override
                public MethodVisitor visitMethod(int access, String n, String d, String s, String[] e) {
                    if (n.equals("checkModVersion") && (access & Opcodes.ACC_STATIC) != 0 && d.endsWith(")V")) {
                        desc[0] = d;
                        return super.visitMethod(access, n + SUFFIX, d, s, e);
                    }
                    return super.visitMethod(access, n, d, s, e);
                }

                @Override
                public void visitEnd() {
                    if (desc[0] != null) {
                        MethodVisitor mv = super.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                                "checkModVersion", desc[0], null, null);
                        mv.visitCode();
                        mv.visitLdcInsn(Type.getObjectType(self[0]));
                        mv.visitLdcInsn("checkModVersion" + SUFFIX);
                        if (Type.getArgumentTypes(desc[0]).length == 0) {
                            mv.visitInsn(Opcodes.ACONST_NULL);
                        } else {
                            mv.visitVarInsn(Opcodes.ALOAD, 0);
                        }
                        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "khi/pentecost/core/Background", "run",
                                "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/Object;)V", false);
                        mv.visitInsn(Opcodes.RETURN);
                        mv.visitMaxs(3, 1);
                        mv.visitEnd();
                    }
                    super.visitEnd();
                }
            }, 0);
            return desc[0] == null ? bytes : writer.toByteArray();
        };
    }
}
