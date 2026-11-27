package khi.pentecost.compat;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.BooleanSupplier;

/**
 * Tells guarded mod methods whether the game is currently drawing a second world (a portal view).
 * Some mods keep per-world state and assume Minecraft.level never changes during a frame. When a
 * portal swaps the level for its own pass, those mods think the world changed and rebuild everything,
 * twice every frame. Guard rules (see PassGuard) make such methods do nothing during that pass.
 *
 * The detector is installed by the game glue because it needs Minecraft classes.
 */
public final class PortalPass {
    public static volatile BooleanSupplier detector;
    private static final Map<Integer, LongAdder> SKIPS = new ConcurrentHashMap<>();

    private PortalPass() {}

    public static boolean active() {
        BooleanSupplier d = detector;
        if (d == null) return false;
        try {
            return d.getAsBoolean();
        } catch (Throwable t) {
            return false;
        }
    }

    /** Called from the injected code at the top of a guarded method. True means "return the default now". */
    public static boolean skip(int ruleId) {
        if (!active()) return false;
        SKIPS.computeIfAbsent(ruleId, k -> new LongAdder()).increment();
        return true;
    }

    public static long skips(int ruleId) {
        LongAdder a = SKIPS.get(ruleId);
        return a == null ? 0 : a.sum();
    }
}
