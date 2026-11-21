package khi.pentecost.core;

import khi.pentecost.loader.ClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

public final class ChatHooks {
    public interface Processor {
        Object process(Object component);
    }

    public interface Painter {
        boolean fill(Object graphics, int x1, int y1, int x2, int y2, int color);

        default void begin(Object graphics) {}

        default void end(Object graphics) {}

        default void screen(Object graphics, int mouseX, int mouseY) {}

        default boolean click(double x, double y, int button) {
            return false;
        }

        default boolean show(Object message) {
            return true;
        }

        default int chatHeight(int height) {
            return height;
        }

        default void screenInit(Object screen) {}

        default boolean suggestFill(Object graphics, int x1, int y1, int x2, int y2, int color) {
            return false;
        }

        default int suggestText(Object graphics, Object font, String text, int x, int y, int color) {
            return Integer.MIN_VALUE;
        }
    }

    public static volatile Processor processor;
    public static volatile Painter painter;

    private static java.lang.reflect.Method plainFill;

    private ChatHooks() {}

    public static Object process(Object component) {
        Processor p = processor;
        if (p == null) {
            return component;
        }
        try {
            Object r = p.process(component);
            return r != null ? r : component;
        } catch (RuntimeException | LinkageError e) {
            return component;
        }
    }

    public static void fill(Object graphics, int x1, int y1, int x2, int y2, int color) {
        Painter p = painter;
        if (p != null) {
            try {
                if (p.fill(graphics, x1, y1, x2, y2, color)) {
                    return;
                }
            } catch (RuntimeException | LinkageError e) {
            }
        }
        try {
            java.lang.reflect.Method m = plainFill;
            if (m == null) {
                for (java.lang.reflect.Method c : graphics.getClass().getMethods()) {
                    if (c.getName().equals("fill") && c.getParameterCount() == 5 && c.getParameterTypes()[0] == int.class
                            && c.getParameterTypes()[4] == int.class) {
                        m = c;
                        plainFill = c;
                        break;
                    }
                }
            }
            if (m != null) {
                m.invoke(graphics, x1, y1, x2, y2, color);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
        }
    }

    public static void begin(Object graphics) {
        Painter p = painter;
        if (p != null) {
            try {
                p.begin(graphics);
            } catch (RuntimeException | LinkageError e) {
            }
        }
    }

    public static void end(Object graphics) {
        Painter p = painter;
        if (p != null) {
            try {
                p.end(graphics);
            } catch (RuntimeException | LinkageError e) {
            }
        }
    }

    public static void screen(Object graphics, int mouseX, int mouseY) {
        Painter p = painter;
        if (p != null) {
            try {
                p.screen(graphics, mouseX, mouseY);
            } catch (RuntimeException | LinkageError e) {
            }
        }
    }

    public static boolean click(double x, double y, int button) {
        Painter p = painter;
        if (p == null) {
            return false;
        }
        try {
            return p.click(x, y, button);
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    public static boolean show(Object message) {
        Painter p = painter;
        if (p == null) {
            return true;
        }
        try {
            return p.show(message);
        } catch (RuntimeException | LinkageError e) {
            return true;
        }
    }

    public static int chatHeight(int height) {
        Painter p = painter;
        if (p == null) {
            return height;
        }
        try {
            return p.chatHeight(height);
        } catch (RuntimeException | LinkageError e) {
            return height;
        }
    }

    public static void screenInit(Object screen) {
        Painter p = painter;
        if (p != null) {
            try {
                p.screenInit(screen);
            } catch (RuntimeException | LinkageError e) {
            }
        }
    }

    public static void suggestFill(Object graphics, int x1, int y1, int x2, int y2, int color) {
        Painter p = painter;
        if (p != null) {
            try {
                if (p.suggestFill(graphics, x1, y1, x2, y2, color)) {
                    return;
                }
            } catch (RuntimeException | LinkageError e) {
            }
        }
        try {
            java.lang.reflect.Method m = plainFill;
            if (m == null) {
                for (java.lang.reflect.Method c : graphics.getClass().getMethods()) {
                    if (c.getName().equals("fill") && c.getParameterCount() == 5 && c.getParameterTypes()[0] == int.class
                            && c.getParameterTypes()[4] == int.class) {
                        m = c;
                        plainFill = c;
                        break;
                    }
                }
            }
            if (m != null) {
                m.invoke(graphics, x1, y1, x2, y2, color);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
        }
    }

    private static java.lang.reflect.Method plainText;

    public static int suggestText(Object graphics, Object font, String text, int x, int y, int color) {
        Painter p = painter;
        if (p != null) {
            try {
                int r = p.suggestText(graphics, font, text, x, y, color);
                if (r != Integer.MIN_VALUE) {
                    return r;
                }
            } catch (RuntimeException | LinkageError e) {
            }
        }
        try {
            java.lang.reflect.Method m = plainText;
            if (m == null) {
                for (java.lang.reflect.Method c : graphics.getClass().getMethods()) {
                    if (c.getName().equals("drawString") && c.getParameterCount() == 5
                            && c.getParameterTypes()[1] == String.class && c.getParameterTypes()[2] == int.class) {
                        m = c;
                        plainText = c;
                        break;
                    }
                }
            }
            if (m != null) {
                return (Integer) m.invoke(graphics, font, text, x, y, color);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
        }
        return x;
    }

    private static final String HOOKS = "khi/pentecost/core/ChatHooks";
    private static final String GRAPHICS = "net/minecraft/client/gui/GuiGraphics";
    private static final String OBJ = "Ljava/lang/Object;";

    public static ClassTransformer transformer() {
        return (name, bytes) -> {
            final boolean component = name.equals("net.minecraft.client.gui.components.ChatComponent");
            final boolean screen = name.equals("net.minecraft.client.gui.screens.ChatScreen");
            final boolean sugg = name.equals("net.minecraft.client.gui.components.CommandSuggestions")
                    || name.equals("net.minecraft.client.gui.components.CommandSuggestions$SuggestionsList");
            if (!component && !screen && !sugg) {
                return bytes;
            }
            ClassReader reader = new ClassReader(bytes);
            ClassWriter writer = new ClassWriter(0);
            final boolean[] changed = new boolean[1];
            final ClassVisitor rewrite = new ClassVisitor(Opcodes.ASM9, writer) {
                @Override
                public MethodVisitor visitMethod(int access, String n, String d, String s, String[] e) {
                    MethodVisitor mv = super.visitMethod(access, n, d, s, e);
                    if (!component && !sugg) {
                        return mv;
                    }
                    return new MethodVisitor(Opcodes.ASM9, mv) {
                        @Override
                        public void visitMethodInsn(int op, String owner, String mname, String desc, boolean itf) {
                            if (component && op == Opcodes.INVOKEVIRTUAL && owner.equals("com/mojang/blaze3d/platform/Window")
                                    && mname.equals("getGuiScaledHeight") && desc.equals("()I")) {
                                super.visitMethodInsn(op, owner, mname, desc, itf);
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, "chatHeight", "(I)I", false);
                                changed[0] = true;
                                return;
                            }
                            if (component && op == Opcodes.INVOKEVIRTUAL && owner.equals(GRAPHICS) && mname.equals("guiHeight")
                                    && desc.equals("()I")) {
                                super.visitMethodInsn(op, owner, mname, desc, itf);
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, "chatHeight", "(I)I", false);
                                changed[0] = true;
                                return;
                            }
                            if (sugg && op == Opcodes.INVOKEVIRTUAL && owner.equals(GRAPHICS) && mname.equals("fill")
                                    && desc.equals("(IIIII)V")) {
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, "suggestFill", "(" + OBJ + "IIIII)V", false);
                                changed[0] = true;
                                return;
                            }
                            if (sugg && op == Opcodes.INVOKEVIRTUAL && owner.equals(GRAPHICS) && mname.equals("drawString")
                                    && desc.equals("(Lnet/minecraft/client/gui/Font;Ljava/lang/String;III)I")) {
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, "suggestText",
                                        "(" + OBJ + OBJ + "Ljava/lang/String;III)I", false);
                                changed[0] = true;
                                return;
                            }
                            super.visitMethodInsn(op, owner, mname, desc, itf);
                        }
                    };
                }
            };
            reader.accept(new ClassVisitor(Opcodes.ASM9, rewrite) {
                @Override
                public MethodVisitor visitMethod(int access, String n, String d, String s, String[] e) {
                    MethodVisitor mv = super.visitMethod(access, n, d, s, e);
                    if ((access & Opcodes.ACC_STATIC) != 0) {
                        return mv;
                    }
                    if (component && n.equals("addMessage") && d.equals("(Lnet/minecraft/network/chat/Component;"
                            + "Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/GuiMessageTag;)V")) {
                        changed[0] = true;
                        return new MethodVisitor(Opcodes.ASM9, mv) {
                            @Override
                            public void visitCode() {
                                super.visitCode();
                                super.visitVarInsn(Opcodes.ALOAD, 1);
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, "process", "(" + OBJ + ")" + OBJ, false);
                                super.visitTypeInsn(Opcodes.CHECKCAST, "net/minecraft/network/chat/Component");
                                super.visitVarInsn(Opcodes.ASTORE, 1);
                            }

                            @Override
                            public void visitMaxs(int maxStack, int maxLocals) {
                                super.visitMaxs(Math.max(maxStack, 1), maxLocals);
                            }
                        };
                    }
                    if (component && n.equals("addMessageToDisplayQueue") && d.equals("(Lnet/minecraft/client/GuiMessage;)V")) {
                        changed[0] = true;
                        return new MethodVisitor(Opcodes.ASM9, mv) {
                            @Override
                            public void visitCode() {
                                super.visitCode();
                                Label go = new Label();
                                super.visitVarInsn(Opcodes.ALOAD, 1);
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, "show", "(" + OBJ + ")Z", false);
                                super.visitJumpInsn(Opcodes.IFNE, go);
                                super.visitInsn(Opcodes.RETURN);
                                super.visitLabel(go);
                                super.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
                            }

                            @Override
                            public void visitMaxs(int maxStack, int maxLocals) {
                                super.visitMaxs(Math.max(maxStack, 1), maxLocals);
                            }
                        };
                    }
                    if (component && n.equals("render") && d.equals("(Lnet/minecraft/client/gui/GuiGraphics;III)V")) {
                        changed[0] = true;
                        return new MethodVisitor(Opcodes.ASM9, mv) {
                            @Override
                            public void visitCode() {
                                super.visitCode();
                                super.visitVarInsn(Opcodes.ALOAD, 1);
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, "begin", "(" + OBJ + ")V", false);
                            }

                            @Override
                            public void visitInsn(int op) {
                                if (op == Opcodes.RETURN) {
                                    super.visitVarInsn(Opcodes.ALOAD, 1);
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, "end", "(" + OBJ + ")V", false);
                                }
                                super.visitInsn(op);
                            }

                            @Override
                            public void visitMethodInsn(int op, String owner, String mname, String desc, boolean itf) {
                                if (op == Opcodes.INVOKEVIRTUAL && owner.equals(GRAPHICS) && mname.equals("fill")
                                        && desc.equals("(IIIII)V")) {
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, "fill", "(" + OBJ + "IIIII)V", false);
                                    return;
                                }
                                super.visitMethodInsn(op, owner, mname, desc, itf);
                            }

                            @Override
                            public void visitMaxs(int maxStack, int maxLocals) {
                                super.visitMaxs(Math.max(maxStack, 1), maxLocals);
                            }
                        };
                    }
                    if (screen && n.equals("init") && d.equals("()V")) {
                        changed[0] = true;
                        return new MethodVisitor(Opcodes.ASM9, mv) {
                            @Override
                            public void visitCode() {
                                super.visitCode();
                                super.visitVarInsn(Opcodes.ALOAD, 0);
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, "screenInit", "(" + OBJ + ")V", false);
                            }

                            @Override
                            public void visitMaxs(int maxStack, int maxLocals) {
                                super.visitMaxs(Math.max(maxStack, 1), maxLocals);
                            }
                        };
                    }
                    if (screen && n.equals("render") && d.equals("(Lnet/minecraft/client/gui/GuiGraphics;IIF)V")) {
                        changed[0] = true;
                        return new MethodVisitor(Opcodes.ASM9, mv) {
                            @Override
                            public void visitInsn(int op) {
                                if (op == Opcodes.RETURN) {
                                    super.visitVarInsn(Opcodes.ALOAD, 1);
                                    super.visitVarInsn(Opcodes.ILOAD, 2);
                                    super.visitVarInsn(Opcodes.ILOAD, 3);
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, "screen", "(" + OBJ + "II)V", false);
                                }
                                super.visitInsn(op);
                            }

                            @Override
                            public void visitMethodInsn(int op, String owner, String mname, String desc, boolean itf) {
                                if (op == Opcodes.INVOKEVIRTUAL && owner.equals(GRAPHICS) && mname.equals("fill")
                                        && desc.equals("(IIIII)V")) {
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, "fill", "(" + OBJ + "IIIII)V", false);
                                    return;
                                }
                                super.visitMethodInsn(op, owner, mname, desc, itf);
                            }

                            @Override
                            public void visitMaxs(int maxStack, int maxLocals) {
                                super.visitMaxs(Math.max(maxStack, 3), maxLocals);
                            }
                        };
                    }
                    if (screen && n.equals("mouseClicked") && d.equals("(DDI)Z")) {
                        changed[0] = true;
                        return new MethodVisitor(Opcodes.ASM9, mv) {
                            @Override
                            public void visitCode() {
                                super.visitCode();
                                Label go = new Label();
                                super.visitVarInsn(Opcodes.DLOAD, 1);
                                super.visitVarInsn(Opcodes.DLOAD, 3);
                                super.visitVarInsn(Opcodes.ILOAD, 5);
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, "click", "(DDI)Z", false);
                                super.visitJumpInsn(Opcodes.IFEQ, go);
                                super.visitInsn(Opcodes.ICONST_1);
                                super.visitInsn(Opcodes.IRETURN);
                                super.visitLabel(go);
                                super.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
                            }

                            @Override
                            public void visitMaxs(int maxStack, int maxLocals) {
                                super.visitMaxs(Math.max(maxStack, 5), maxLocals);
                            }
                        };
                    }
                    return mv;
                }
            }, 0);
            return changed[0] ? writer.toByteArray() : bytes;
        };
    }
}
