package khi.pentecost.core;

import khi.pentecost.loader.ClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Hooks for the chat. The game glue sets the two fields below (see khi.pentecost.game.ChatStyle):
 * - the processor sees every line on its way into the chat window and can rewrite it (links, coordinates, mentions);
 * - the painter draws the chat in Pentecost's look: backgrounds, motion, tabs, and which lines the window shows.
 * If a field is empty or its code fails, the game does what it always did.
 */
public final class ChatHooks {
    public interface Processor {
        /** Gets the line the game was about to add, returns the line to add instead. */
        Object process(Object component);
    }

    public interface Painter {
        /** Draws this rectangle instead of the game. Returns false to let the game draw it as usual. */
        boolean fill(Object graphics, int x1, int y1, int x2, int y2, int color);

        /** Start of the chat window's drawing, every frame. Whatever is changed here is undone in end. */
        default void begin(Object graphics) {}

        /** End of the chat window's drawing, every frame. */
        default void end(Object graphics) {}

        /** End of the chat input screen's drawing, with the pointer position. */
        default void screen(Object graphics, int mouseX, int mouseY) {}

        /** A click on the chat input screen. Returns true when it was used. */
        default boolean click(double x, double y, int button) {
            return false;
        }

        /** True when the chat window shows this line (a GuiMessage). Used to filter by tab. */
        default boolean show(Object message) {
            return true;
        }

        /** The height the chat window draws its lines up from. The game's is the window's height. */
        default int chatHeight(int height) {
            return height;
        }

        /** The chat input screen is about to build itself. Its height may be changed here, so it sits higher. */
        default void screenInit(Object screen) {}

        /** Draws a rectangle of the command suggestions instead of the game. Returns false to let the game draw it. */
        default boolean suggestFill(Object graphics, int x1, int y1, int x2, int y2, int color) {
            return false;
        }

        /** Draws a line of the command suggestions. Returns the x it ended at, or Integer.MIN_VALUE for the game to draw it. */
        default int suggestText(Object graphics, Object font, String text, int x, int y, int color) {
            return Integer.MIN_VALUE;
        }
    }

    public static volatile Processor processor;
    public static volatile Painter painter;

    private static java.lang.reflect.Method plainFill;

    private ChatHooks() {}

    /** Called by the game for every chat line. */
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

    /** Called by the game where the chat window and the chat input draw a filled rectangle. */
    public static void fill(Object graphics, int x1, int y1, int x2, int y2, int color) {
        Painter p = painter;
        if (p != null) {
            try {
                if (p.fill(graphics, x1, y1, x2, y2, color)) {
                    return;
                }
            } catch (RuntimeException | LinkageError e) {
                // fall through to the game's own drawing
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
            // nothing to draw with
        }
    }

    public static void begin(Object graphics) {
        Painter p = painter;
        if (p != null) {
            try {
                p.begin(graphics);
            } catch (RuntimeException | LinkageError e) {
                // the chat draws as the game does
            }
        }
    }

    public static void end(Object graphics) {
        Painter p = painter;
        if (p != null) {
            try {
                p.end(graphics);
            } catch (RuntimeException | LinkageError e) {
                // the chat draws as the game does
            }
        }
    }

    public static void screen(Object graphics, int mouseX, int mouseY) {
        Painter p = painter;
        if (p != null) {
            try {
                p.screen(graphics, mouseX, mouseY);
            } catch (RuntimeException | LinkageError e) {
                // the chat input draws as the game does
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

    /** Called by the game right after the chat window asks for the window's height. */
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

    /** Called by the game at the start of the chat input screen's init. */
    public static void screenInit(Object screen) {
        Painter p = painter;
        if (p != null) {
            try {
                p.screenInit(screen);
            } catch (RuntimeException | LinkageError e) {
                // the screen builds as the game does
            }
        }
    }

    /** Called by the game where the command suggestions draw a filled rectangle. */
    public static void suggestFill(Object graphics, int x1, int y1, int x2, int y2, int color) {
        Painter p = painter;
        if (p != null) {
            try {
                if (p.suggestFill(graphics, x1, y1, x2, y2, color)) {
                    return;
                }
            } catch (RuntimeException | LinkageError e) {
                // fall through to the game's own drawing
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
            // nothing to draw with
        }
    }

    private static java.lang.reflect.Method plainText;

    /** Called by the game where the command suggestions draw a line of text. */
    public static int suggestText(Object graphics, Object font, String text, int x, int y, int color) {
        Painter p = painter;
        if (p != null) {
            try {
                int r = p.suggestText(graphics, font, text, x, y, color);
                if (r != Integer.MIN_VALUE) {
                    return r;
                }
            } catch (RuntimeException | LinkageError e) {
                // fall through to the game's own drawing
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
            // nothing to draw with
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
            // Applies to every method of the class, and sits behind the specific changes below.
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
                                // The chat window works from a height that can be lifted, so it clears the hotbar.
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
