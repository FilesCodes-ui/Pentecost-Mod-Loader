package khi.pentecost.core;

import khi.pentecost.boot.Log;
import khi.pentecost.loader.ClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

public final class HudOwner {
    private HudOwner() {}

    public static volatile boolean vanillaBars;

    private static volatile Method renderPlayerHealth;

    private static final String GUI = "net/minecraft/client/gui/Gui";
    private static final String FORGE_GUI = "net/minecraftforge/client/gui/overlay/ForgeGui";
    private static final String GG = "Lnet/minecraft/client/gui/GuiGraphics;";

    public static void afterMixin(String name, byte[] bytes) {
        if (bytes == null || !PentecostRuntime.forgeLayer || !"net.minecraft.client.gui.Gui".equals(name)) {
            return;
        }
        boolean[] found = {false};
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String n, String d, String s, String[] e) {
                if (!n.equals("renderPlayerHealth") || !d.equals("(" + GG + ")V")) {
                    return null;
                }
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int op, String owner, String mn, String md, boolean itf) {
                        if (owner.equals(GUI) && mn.indexOf('$') >= 0) {
                            found[0] = true;
                        }
                    }
                };
            }
        }, ClassReader.SKIP_FRAMES);
        vanillaBars = found[0];
        if (found[0]) {
            Log.info("Forge layer: mods hook the vanilla status bars, so Pentecost draws them");
        }
    }

    public static boolean health(Object gui, Object graphics) {
        if (!vanillaBars) {
            return false;
        }
        try {
            Method m = renderPlayerHealth;
            if (m == null) {
                for (Method x : gui.getClass().getSuperclass().getDeclaredMethods()) {
                    if (x.getName().equals("renderPlayerHealth") && x.getParameterCount() == 1) {
                        x.setAccessible(true);
                        m = x;
                    }
                }
                if (m == null) {
                    throw new NoSuchMethodException("Gui.renderPlayerHealth");
                }
                renderPlayerHealth = m;
            }
            drawing = true;
            try {
                m.invoke(gui, graphics);
            } finally {
                drawing = false;
            }
            add(gui, "leftHeight", 10);
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            vanillaBars = false;
            Throwable why = e.getCause() != null ? e.getCause() : e;
            Log.error("Pentecost's status bars failed, Forge draws them again: " + why);
            return false;
        }
    }

    private static volatile boolean drawing;
    private static volatile Object armorOverlay, foodOverlay, airOverlay;
    private static volatile Method firePre, firePost;
    private static volatile boolean eventsBroken;

    public static boolean held(Object overlay) {
        if (!vanillaBars || drawing || eventsBroken || overlay == null) {
            return false;
        }
        findOverlays(overlay.getClass().getClassLoader());
        return overlay == armorOverlay || overlay == foodOverlay || overlay == airOverlay;
    }

    private static void findOverlays(ClassLoader cl) {
        if (foodOverlay != null) {
            return;
        }
        try {
            Class<?> vanilla = Class.forName("net.minecraftforge.client.gui.overlay.VanillaGuiOverlay", false, cl);
            Class<?> manager = Class.forName("net.minecraftforge.client.gui.overlay.GuiOverlayManager", false, cl);
            Method id = vanilla.getMethod("id");
            Method find = manager.getMethod("findOverlay", id.getReturnType());
            armorOverlay = find.invoke(null, id.invoke(vanilla.getField("ARMOR_LEVEL").get(null)));
            airOverlay = find.invoke(null, id.invoke(vanilla.getField("AIR_LEVEL").get(null)));
            foodOverlay = find.invoke(null, id.invoke(vanilla.getField("FOOD_LEVEL").get(null)));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            eventsBroken = true;
            Log.warn("Status bar events stay in Forge's slots: " + e);
        }
    }

    public static void bar(Object gui, Object graphics, String bar, boolean start) {
        if (!vanillaBars || !drawing || eventsBroken) {
            return;
        }
        try {
            findOverlays(gui.getClass().getClassLoader());
            Object overlay = switch (bar) {
                case "armor" -> armorOverlay;
                case "food" -> foodOverlay;
                case "air" -> airOverlay;
                default -> null;
            };
            if (overlay == null) {
                return;
            }
            Method m = start ? firePre : firePost;
            if (m == null) {
                for (Method x : gui.getClass().getDeclaredMethods()) {
                    if (x.getName().equals(start ? "pentecost$pre" : "pentecost$post") && x.getParameterCount() == 2) {
                        x.setAccessible(true);
                        m = x;
                    }
                }
                if (m == null) {
                    throw new NoSuchMethodException("ForgeGui.pre/post");
                }
                if (start) {
                    firePre = m;
                } else {
                    firePost = m;
                }
            }
            String side = bar.equals("armor") ? "leftHeight" : bar.equals("air") ? "rightHeight" : null;
            if (side != null) {
                add(gui, side, 10);
            }
            try {
                m.invoke(gui, overlay, graphics);
            } finally {
                if (side != null) {
                    add(gui, side, -10);
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            eventsBroken = true;
            Throwable why = e.getCause() != null ? e.getCause() : e;
            Log.warn("Status bar events go back to Forge's slots: " + why);
        }
    }

    public static ClassTransformer guiTransformer() {
        return (name, bytes) -> {
            if (bytes == null || !"net.minecraft.client.gui.Gui".equals(name)) {
                return bytes;
            }
            ClassReader reader = new ClassReader(bytes);
            ClassWriter writer = new ClassWriter(0);
            int[] count = {0};
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                @Override
                public MethodVisitor visitMethod(int access, String n, String d, String s, String[] e) {
                    MethodVisitor mv = super.visitMethod(access, n, d, s, e);
                    if (!n.equals("renderPlayerHealth") || !d.equals("(" + GG + ")V")) {
                        return mv;
                    }
                    return new MethodVisitor(Opcodes.ASM9, mv) {
                        private String lastText;
                        private String open;

                        private void call(String bar, boolean start) {
                            super.visitVarInsn(Opcodes.ALOAD, 0);
                            super.visitVarInsn(Opcodes.ALOAD, 1);
                            super.visitLdcInsn(bar);
                            super.visitInsn(start ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, "khi/pentecost/core/HudOwner", "bar",
                                    "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/String;Z)V", false);
                            count[0]++;
                        }

                        @Override
                        public void visitLdcInsn(Object value) {
                            lastText = value instanceof String str ? str : null;
                            super.visitLdcInsn(value);
                        }

                        @Override
                        public void visitMethodInsn(int op, String owner, String mn, String md, boolean itf) {
                            boolean profiler = owner.equals("net/minecraft/util/profiling/ProfilerFiller");
                            boolean section = profiler && (mn.equals("push") || mn.equals("popPush")) && md.equals("(Ljava/lang/String;)V");
                            boolean closes = profiler && (mn.equals("popPush") || (mn.equals("pop") && md.equals("()V")));
                            if (closes && open != null) {
                                call(open, false);
                                open = null;
                            }
                            super.visitMethodInsn(op, owner, mn, md, itf);
                            if (section && lastText != null && (lastText.equals("armor") || lastText.equals("food") || lastText.equals("air"))) {
                                open = lastText;
                                call(open, true);
                            }
                            lastText = null;
                        }

                        @Override
                        public void visitMaxs(int maxStack, int maxLocals) {
                            super.visitMaxs(maxStack + 4, maxLocals);
                        }
                    };
                }
            }, 0);
            if (count[0] < 6) {
                Log.warn("Status bars: the vanilla bar sections were not all found (" + count[0] + " of 6). Bar events stay in Forge's slots.");
                eventsBroken = true;
                return bytes;
            }
            return writer.toByteArray();
        };
    }

    public static boolean skip(Object gui, String bar) {
        if (!vanillaBars) {
            return false;
        }
        try {
            switch (bar) {
                case "renderArmor" -> {
                    if (playerInt(gui, "getArmorValue") > 0) {
                        add(gui, "leftHeight", 10);
                    }
                }
                case "renderFood" -> add(gui, "rightHeight", 10);
                case "renderAir" -> {
                    int air = playerInt(gui, "getAirSupply");
                    int max = playerInt(gui, "getMaxAirSupply");
                    if (air >= 0 && max > 0 && air < max) {
                        add(gui, "rightHeight", 10);
                    }
                }
                default -> {
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
        }
        return true;
    }

    private static void add(Object gui, String field, int by) throws ReflectiveOperationException {
        Field f = gui.getClass().getField(field);
        f.setInt(gui, f.getInt(gui) + by);
    }

    private static int playerInt(Object gui, String method) throws ReflectiveOperationException {
        Object mc = Class.forName("net.minecraft.client.Minecraft", false, gui.getClass().getClassLoader())
                .getMethod("getInstance").invoke(null);
        Object player = mc.getClass().getField("player").get(mc);
        if (player == null) {
            return -1;
        }
        Object v = player.getClass().getMethod(method).invoke(player);
        return v instanceof Integer i ? i : -1;
    }

    private static final Map<String, Integer> BARS = Map.of(
            "renderHealth(II" + GG + ")V", 3,
            "renderArmor(" + GG + "II)V", 1,
            "renderFood(II" + GG + ")V", 3,
            "renderAir(II" + GG + ")V", 3);

    public static ClassTransformer forgeTransformer() {
        return (name, bytes) -> {
            if (bytes == null || !"net.minecraftforge.client.gui.overlay.ForgeGui".equals(name)) {
                return bytes;
            }
            ClassReader reader = new ClassReader(bytes);
            ClassWriter writer = new ClassWriter(0);
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                private final java.util.List<String[]> wrap = new java.util.ArrayList<>();

                private final java.util.List<String[]> events = new java.util.ArrayList<>();

                @Override
                public MethodVisitor visitMethod(int access, String n, String d, String s, String[] e) {
                    if ((n.equals("pre") || n.equals("post")) && d.startsWith("(Lnet/minecraftforge/client/gui/overlay/NamedGuiOverlay;" + GG + ")")) {
                        events.add(new String[]{n, d, Integer.toString(access)});
                        return super.visitMethod(access, "pentecost$" + n, d, s, e);
                    }
                    if (BARS.containsKey(n + d)) {
                        wrap.add(new String[]{n, d, Integer.toString(access)});
                        return super.visitMethod(access, "pentecost$" + n, d, s, e);
                    }
                    return super.visitMethod(access, n, d, s, e);
                }

                @Override
                public void visitEnd() {
                    for (String[] w : events) {
                        String n = w[0];
                        String d = w[1];
                        boolean pre = n.equals("pre");
                        MethodVisitor mv = super.visitMethod(Integer.parseInt(w[2]), n, d, null, null);
                        mv.visitCode();
                        Label go = new Label();
                        mv.visitVarInsn(Opcodes.ALOAD, 1);
                        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "khi/pentecost/core/HudOwner", "held", "(Ljava/lang/Object;)Z", false);
                        mv.visitJumpInsn(Opcodes.IFEQ, go);
                        if (pre) {
                            mv.visitInsn(Opcodes.ICONST_0);
                            mv.visitInsn(Opcodes.IRETURN);
                        } else {
                            mv.visitInsn(Opcodes.RETURN);
                        }
                        mv.visitLabel(go);
                        mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
                        mv.visitVarInsn(Opcodes.ALOAD, 0);
                        mv.visitVarInsn(Opcodes.ALOAD, 1);
                        mv.visitVarInsn(Opcodes.ALOAD, 2);
                        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, FORGE_GUI, "pentecost$" + n, d, false);
                        mv.visitInsn(pre ? Opcodes.IRETURN : Opcodes.RETURN);
                        mv.visitMaxs(3, 3);
                        mv.visitEnd();
                    }
                    for (String[] w : wrap) {
                        String n = w[0];
                        String d = w[1];
                        MethodVisitor mv = super.visitMethod(Integer.parseInt(w[2]), n, d, null, null);
                        mv.visitCode();
                        if (n.equals("renderHealth")) {
                            mv.visitVarInsn(Opcodes.ALOAD, 0);
                            mv.visitVarInsn(Opcodes.ALOAD, BARS.get(n + d));
                            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "khi/pentecost/core/HudOwner", "health",
                                    "(Ljava/lang/Object;Ljava/lang/Object;)Z", false);
                        } else {
                            mv.visitVarInsn(Opcodes.ALOAD, 0);
                            mv.visitLdcInsn(n);
                            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "khi/pentecost/core/HudOwner", "skip",
                                    "(Ljava/lang/Object;Ljava/lang/String;)Z", false);
                        }
                        Label done = new Label();
                        mv.visitJumpInsn(Opcodes.IFNE, done);
                        mv.visitVarInsn(Opcodes.ALOAD, 0);
                        int slot = 1;
                        for (Type t : Type.getArgumentTypes(d)) {
                            mv.visitVarInsn(t.getOpcode(Opcodes.ILOAD), slot);
                            slot += t.getSize();
                        }
                        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, FORGE_GUI, "pentecost$" + n, d, false);
                        mv.visitLabel(done);
                        mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
                        mv.visitInsn(Opcodes.RETURN);
                        mv.visitMaxs(4, slot);
                        mv.visitEnd();
                    }
                    super.visitEnd();
                }
            }, 0);
            return writer.toByteArray();
        };
    }
}
