package khi.pentecost.core;

import khi.pentecost.loader.ClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Times every Forge event handler and adds the time up per mod.
 * Pentecost wraps the call that runs a handler (ASMEventHandler.invoke). Timing only runs while the
 * F6 panel is open, so it costs nothing the rest of the time.
 * A handler that posts another event is timed together with the handlers that event runs.
 */
public final class CostMeter {
    private static final String HANDLER = "net.minecraftforge.eventbus.ASMEventHandler";
    private static final String INVOKE_DESC = "(Lnet/minecraftforge/eventbus/api/Event;)V";
    private static final String SUFFIX = "$pentecost";

    private static final class Slot {
        final String mod;
        final String name;
        final LongAdder nanos = new LongAdder();
        final LongAdder calls = new LongAdder();
        long lastNanos;

        Slot(String mod, String name) {
            this.mod = mod;
            this.name = name;
        }
    }

    private static volatile boolean enabled;
    private static final Map<Object, Slot> SLOTS = new ConcurrentHashMap<>();

    private static long windowStart;
    private static volatile List<String[]> top = List.of();

    private CostMeter() {}

    public static void setEnabled(boolean on) {
        if (on != enabled) {
            enabled = on;
            if (on) {
                windowStart = 0;
            }
        }
    }

    /** Called by the rewritten handler call before it runs. Returns 0 while timing is off. */
    public static long begin() {
        return enabled ? System.nanoTime() : 0L;
    }

    /** Called by the rewritten handler call after it runs. */
    public static void end(Object handler, long start) {
        if (start == 0L) {
            return;
        }
        long spent = System.nanoTime() - start;
        Slot slot = SLOTS.get(handler);
        if (slot == null) {
            slot = SLOTS.computeIfAbsent(handler, CostMeter::make);
        }
        slot.nanos.add(spent);
        slot.calls.increment();
    }

    private static Slot make(Object handler) {
        String text = String.valueOf(handler);
        if (text.startsWith("ASM: ")) {
            text = text.substring(5);
        }
        if (text.startsWith("class ")) {
            text = text.substring(6);
        }
        int space = text.indexOf(' ');
        String owner = space < 0 ? text : text.substring(0, space);
        int at = owner.indexOf('@');
        if (at > 0) {
            owner = owner.substring(0, at);
        }
        String method = "";
        if (space >= 0) {
            int paren = text.indexOf('(', space);
            method = paren < 0 ? text.substring(space + 1) : text.substring(space + 1, paren);
        }
        String mod = null;
        var loader = PentecostRuntime.loader;
        if (loader != null) {
            try {
                mod = loader.modOf(owner);
            } catch (RuntimeException ignored) {
                // A class that cannot be placed counts as unknown.
            }
        }
        if (mod == null) {
            mod = owner.startsWith("net.minecraft") ? "minecraft or forge" : "unknown";
        }
        return new Slot(mod, owner.substring(owner.lastIndexOf('.') + 1) + "." + method);
    }

    /**
     * The biggest mods over the last few seconds, as rows for the F6 panel: mod name and milliseconds per tick
     * (at 20 ticks per second). Call from the render thread.
     */
    public static List<String[]> rows(int count) {
        long now = System.nanoTime();
        if (!enabled) {
            return List.of();
        }
        if (windowStart == 0) {
            windowStart = now;
            for (Slot s : SLOTS.values()) {
                s.lastNanos = s.nanos.sum();
            }
        } else if (now - windowStart >= 4_000_000_000L) {
            double seconds = (now - windowStart) / 1e9;
            Map<String, Long> perMod = new HashMap<>();
            Map<String, String> worst = new HashMap<>();
            Map<String, Long> worstCost = new HashMap<>();
            for (Slot s : SLOTS.values()) {
                long total = s.nanos.sum();
                long delta = total - s.lastNanos;
                s.lastNanos = total;
                if (delta <= 0) {
                    continue;
                }
                perMod.merge(s.mod, delta, Long::sum);
                if (delta > worstCost.getOrDefault(s.mod, 0L)) {
                    worstCost.put(s.mod, delta);
                    worst.put(s.mod, s.name);
                }
            }
            List<Map.Entry<String, Long>> sorted = new ArrayList<>(perMod.entrySet());
            sorted.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
            List<String[]> out = new ArrayList<>();
            for (int i = 0; i < sorted.size() && i < count; i++) {
                Map.Entry<String, Long> e = sorted.get(i);
                double perTick = e.getValue() / 1e6 / seconds / 20.0;
                out.add(new String[]{e.getKey(), String.format(java.util.Locale.ROOT, "%.2f ms", perTick)});
                if (perTick >= 1.0) {
                    System.out.println(String.format(java.util.Locale.ROOT,
                            "[Pentecost] Handler cost: %s %.2f ms per tick, mostly %s", e.getKey(), perTick,
                            worst.get(e.getKey())));
                }
            }
            top = out;
            windowStart = now;
        }
        return top;
    }

    /** Wraps ASMEventHandler.invoke so every handler call is timed. */
    public static ClassTransformer transformer() {
        return (name, bytes) -> {
            if (!name.equals(HANDLER)) {
                return bytes;
            }
            ClassReader reader = new ClassReader(bytes);
            ClassWriter writer = new ClassWriter(0);
            final String[] self = new String[1];
            final boolean[] found = new boolean[1];
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                @Override
                public void visit(int version, int access, String n, String sig, String sup, String[] itf) {
                    self[0] = n;
                    super.visit(version, access, n, sig, sup, itf);
                }

                @Override
                public MethodVisitor visitMethod(int access, String n, String d, String s, String[] e) {
                    if (n.equals("invoke") && d.equals(INVOKE_DESC) && (access & Opcodes.ACC_STATIC) == 0) {
                        found[0] = true;
                        return super.visitMethod(access, n + SUFFIX, d, s, e);
                    }
                    return super.visitMethod(access, n, d, s, e);
                }

                @Override
                public void visitEnd() {
                    if (found[0]) {
                        MethodVisitor mv = super.visitMethod(Opcodes.ACC_PUBLIC, "invoke", INVOKE_DESC, null, null);
                        mv.visitCode();
                        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "khi/pentecost/core/CostMeter", "begin", "()J", false);
                        mv.visitVarInsn(Opcodes.LSTORE, 2);
                        mv.visitVarInsn(Opcodes.ALOAD, 0);
                        mv.visitVarInsn(Opcodes.ALOAD, 1);
                        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, self[0], "invoke" + SUFFIX, INVOKE_DESC, false);
                        mv.visitVarInsn(Opcodes.ALOAD, 0);
                        mv.visitVarInsn(Opcodes.LLOAD, 2);
                        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "khi/pentecost/core/CostMeter", "end",
                                "(Ljava/lang/Object;J)V", false);
                        mv.visitInsn(Opcodes.RETURN);
                        mv.visitMaxs(3, 4);
                        mv.visitEnd();
                    }
                    super.visitEnd();
                }
            }, 0);
            return found[0] ? writer.toByteArray() : bytes;
        };
    }
}
