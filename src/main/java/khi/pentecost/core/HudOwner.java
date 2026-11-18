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

/**
 * Pentecost owns the player status bars (health, armor, food, air).
 * Forge splits them into four overlays and never runs the vanilla Gui.renderPlayerHealth,
 * so Fabric mods that hook that method (AppleSkin) go silent on the Forge layer.
 * When a mod hooks it, Pentecost draws the bars through the vanilla method, in Forge's health slot,
 * and Forge's own armor, food and air bars step aside. Forge's bar spacing for other overlays is kept: each bar's slot
 * still moves leftHeight or rightHeight at the moment Forge would, so a mod overlay that reads them just before or
 * after a bar (AppleSkin reads rightHeight right before food) finds the bar where it really is.
 * When no mod hooks it, Forge draws its bars as always.
 */
public final class HudOwner {
    private HudOwner() {}

    /** True when a loaded mod hooks the vanilla status bars. */
    public static volatile boolean vanillaBars;

    private static volatile Method renderPlayerHealth;

    private static final String GUI = "net/minecraft/client/gui/Gui";
    private static final String FORGE_GUI = "net/minecraftforge/client/gui/overlay/ForgeGui";
    private static final String GG = "Lnet/minecraft/client/gui/GuiGraphics;";

    /** Runs after Mixin on every class. Looks for mod hooks inside the vanilla status bar method. */
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

    /** Forge's health slot. Returns true when Pentecost drew the bars. */
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
            m.invoke(gui, graphics);
            add(gui, "leftHeight", 10);
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            vanillaBars = false;
            Throwable why = e.getCause() != null ? e.getCause() : e;
            Log.error("Pentecost's status bars failed, Forge draws them again: " + why);
            return false;
        }
    }

    /**
     * Forge's armor, food and air slots. True means Pentecost already drew the bar, so the slot only moves the
     * bar spacing the way Forge's own method would have, in the same order.
     */
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
            // Spacing is a nicety. The bars are drawn either way.
        }
        return true;
    }

    private static void add(Object gui, String field, int by) throws ReflectiveOperationException {
        Field f = gui.getClass().getField(field);
        f.setInt(gui, f.getInt(gui) + by);
    }

    /** A whole number from the local player, or -1 when there is no player. */
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

    /** Forge's bar methods, and the index of their GuiGraphics argument. */
    private static final Map<String, Integer> BARS = Map.of(
            "renderHealth(II" + GG + ")V", 3,
            "renderArmor(" + GG + "II)V", 1,
            "renderFood(II" + GG + ")V", 3,
            "renderAir(II" + GG + ")V", 3);

    /** Puts a Pentecost check in front of Forge's four status bar methods. */
    public static ClassTransformer forgeTransformer() {
        return (name, bytes) -> {
            if (bytes == null || !"net.minecraftforge.client.gui.overlay.ForgeGui".equals(name)) {
                return bytes;
            }
            ClassReader reader = new ClassReader(bytes);
            ClassWriter writer = new ClassWriter(0);
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                private final java.util.List<String[]> wrap = new java.util.ArrayList<>();

                @Override
                public MethodVisitor visitMethod(int access, String n, String d, String s, String[] e) {
                    if (BARS.containsKey(n + d)) {
                        wrap.add(new String[]{n, d, Integer.toString(access)});
                        return super.visitMethod(access, "pentecost$" + n, d, s, e);
                    }
                    return super.visitMethod(access, n, d, s, e);
                }

                @Override
                public void visitEnd() {
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
