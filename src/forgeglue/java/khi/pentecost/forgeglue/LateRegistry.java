package khi.pentecost.forgeglue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Forge locks the game's registries outside its own registration step.
 * Fabric keeps them open, and Fabric mods rely on that: they register from their start code,
 * and some register later still. When a registration arrives at a locked registry,
 * Pentecost reopens that registry so the registration goes through, as it would on Fabric.
 */
public final class LateRegistry {
    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();
    private static volatile Field locked;
    private static volatile Field delegate;

    private LateRegistry() {}

    /** Called at the top of Forge's registry wrapper, before it checks its lock. */
    public static void open(Object wrapper) {
        try {
            Field l = locked;
            Field d = delegate;
            if (l == null) {
                l = wrapper.getClass().getDeclaredField("locked");
                l.setAccessible(true);
                d = wrapper.getClass().getDeclaredField("delegate");
                d.setAccessible(true);
                locked = l;
                delegate = d;
            }
            Object registry = d.get(wrapper);
            boolean wasLocked = l.getBoolean(wrapper);
            boolean wasFrozen = (boolean) registry.getClass().getMethod("isLocked").invoke(registry);
            if (!wasLocked && !wasFrozen) {
                return;
            }
            l.setBoolean(wrapper, false);
            if (wasFrozen) {
                Method unfreeze = registry.getClass().getMethod("unfreeze");
                unfreeze.invoke(registry);
            }
            String name = String.valueOf(registry.getClass().getMethod("getRegistryName").invoke(registry));
            if (REPORTED.add(name)) {
                System.out.println("[Pentecost] Registry " + name + " reopened for a mod that registers the Fabric way");
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            if (REPORTED.add("error")) {
                System.out.println("[Pentecost] Could not reopen a registry: " + e);
            }
        }
    }
}
