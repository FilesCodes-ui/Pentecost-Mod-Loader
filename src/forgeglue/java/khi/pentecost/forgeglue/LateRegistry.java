package khi.pentecost.forgeglue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Forge locks the game's registries outside its own registration step.
 * Fabric keeps them open, and Fabric mods rely on that: they register from their start code,
 * and some register later still. When a registration arrives at a locked registry,
 * Pentecost reopens that registry so the registration goes through, as it would on Fabric.
 */
public final class LateRegistry {
    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();
    private static final Set<Object> REOPENED = Collections.synchronizedSet(
            Collections.newSetFromMap(new WeakHashMap<>()));

    private LateRegistry() {}

    /** Called at the top of Forge's registry wrapper writes, before it checks its locks. */
    public static void open(Object wrapper) {
        try {
            Field locked = field(wrapper.getClass(), "locked");
            Field frozen = field(wrapper.getClass(), "frozen");
            Object registry = field(wrapper.getClass(), "delegate").get(wrapper);
            boolean wasLocked = locked.getBoolean(wrapper);
            boolean wasFrozen = frozen.getBoolean(wrapper);
            boolean innerLocked = (boolean) registry.getClass().getMethod("isLocked").invoke(registry);
            if (!wasLocked && !wasFrozen && !innerLocked) {
                return;
            }
            locked.setBoolean(wrapper, false);
            frozen.setBoolean(wrapper, false);
            if (innerLocked) {
                Method unfreeze = registry.getClass().getMethod("unfreeze");
                unfreeze.invoke(registry);
            }
            REOPENED.add(wrapper);
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

    /**
     * Called at the top of Forge's registry wrapper freeze.
     * A mod that failed halfway through making a block leaves a half-made entry behind.
     * Forge refuses to freeze with that entry in place, so Pentecost drops it first.
     */
    public static void beforeFreeze(Object wrapper) {
        try {
            Field f = field(wrapper.getClass(), "unregisteredIntrusiveHolders");
            Object value = f.get(wrapper);
            if (!(value instanceof Map<?, ?> map) || map.isEmpty()) {
                return;
            }
            int dropped = 0;
            Iterator<?> it = map.values().iterator();
            while (it.hasNext()) {
                Object holder = it.next();
                Method bound = holder.getClass().getMethod("isBound");
                if (!(boolean) bound.invoke(holder)) {
                    it.remove();
                    dropped++;
                }
            }
            if (dropped > 0) {
                Object registry = field(wrapper.getClass(), "delegate").get(wrapper);
                String name = String.valueOf(registry.getClass().getMethod("getRegistryName").invoke(registry));
                System.out.println("[Pentecost] Dropped " + dropped + " half-made entr" + (dropped == 1 ? "y" : "ies")
                        + " from " + name + " left by a mod that failed");
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            if (REPORTED.add("freeze-error")) {
                System.out.println("[Pentecost] Could not clean a registry before freezing: " + e);
            }
        }
    }

    /**
     * Forge's lookup of an entry's registry handle, made tolerant for entries not registered yet.
     * Items and blocks are born with their handle, so that one is used. Anything else fails as before.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static net.minecraft.core.Holder.Reference<?> delegateOrOwnHolder(Object registry, Object value) {
        net.minecraftforge.registries.IForgeRegistry r = (net.minecraftforge.registries.IForgeRegistry) registry;
        java.util.Optional<net.minecraft.core.Holder.Reference<?>> found = r.getDelegate(value);
        if (found.isPresent()) {
            return found.get();
        }
        if (value instanceof net.minecraft.world.item.Item item) {
            return item.builtInRegistryHolder();
        }
        if (value instanceof net.minecraft.world.level.block.Block block) {
            return block.builtInRegistryHolder();
        }
        return r.getDelegateOrThrow(value);
    }

    private static final Map<String, Field> FIELDS = new ConcurrentHashMap<>();

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        String key = type.getName() + "." + name;
        Field cached = FIELDS.get(key);
        if (cached != null) {
            return cached;
        }
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                FIELDS.put(key, f);
                return f;
            } catch (NoSuchFieldException ignored) {
                // look in the parent class
            }
        }
        throw new NoSuchFieldException(name);
    }
}
