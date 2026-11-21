package khi.pentecost.forgeglue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

public final class LateRegistry {
    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();
    private static final Set<Object> REOPENED = Collections.synchronizedSet(
            Collections.newSetFromMap(new WeakHashMap<>()));

    private LateRegistry() {}

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

    @SuppressWarnings("unchecked")
    public static int addFabricAttributes() {
        try {
            Class<?> impl = Class.forName("net.fabricmc.fabric.impl.object.builder.FabricDefaultAttributeRegistryImpl",
                    true, LateRegistry.class.getClassLoader());
            Field suppliers = impl.getDeclaredField("SUPPLIERS");
            suppliers.setAccessible(true);
            Map<Object, Object> fabric = (Map<Object, Object>) suppliers.get(null);
            Field forgeField = net.minecraftforge.common.ForgeHooks.class.getDeclaredField("FORGE_ATTRIBUTES");
            forgeField.setAccessible(true);
            Map<Object, Object> forge = (Map<Object, Object>) forgeField.get(null);
            int added = 0;
            for (Map.Entry<Object, Object> e : fabric.entrySet()) {
                if (forge.putIfAbsent(e.getKey(), e.getValue()) == null) {
                    added++;
                }
            }
            return added;
        } catch (ClassNotFoundException e) {
            return 0;
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.out.println("[Pentecost] Could not hand Fabric mob stats to Forge: " + e);
            return 0;
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static int replayBridgeRegistration() {
        java.util.List<net.minecraftforge.eventbus.api.IEventBus> buses = new java.util.ArrayList<>();
        net.minecraftforge.fml.ModList.get().forEachModContainer((id, container) -> {
            if ((id.startsWith("fabric_") || id.startsWith("fabric-"))
                    && container instanceof net.minecraftforge.fml.javafmlmod.FMLModContainer fmc) {
                buses.add(fmc.getEventBus());
            }
        });
        if (buses.isEmpty()) {
            return 0;
        }
        java.lang.reflect.Constructor<net.minecraftforge.registries.RegisterEvent> make;
        try {
            make = net.minecraftforge.registries.RegisterEvent.class.getDeclaredConstructor(
                    net.minecraft.resources.ResourceKey.class, net.minecraftforge.registries.ForgeRegistry.class,
                    net.minecraft.core.Registry.class);
            make.setAccessible(true);
        } catch (ReflectiveOperationException e) {
            System.out.println("[Pentecost] Could not replay registration for the Fabric API bridge: " + e);
            return 0;
        }
        int count = 0;
        for (var entry : net.minecraft.core.registries.BuiltInRegistries.REGISTRY.entrySet()) {
            net.minecraft.resources.ResourceKey key = entry.getKey();
            net.minecraft.core.Registry<?> registry = entry.getValue();
            net.minecraftforge.registries.ForgeRegistry<?> forge =
                    net.minecraftforge.registries.RegistryManager.ACTIVE.getRegistry(key.location());
            try {
                if (forge != null && forge.isLocked()) {
                    forge.getClass().getMethod("unfreeze").invoke(forge);
                }
                net.minecraftforge.registries.RegisterEvent event = make.newInstance(key, forge, registry);
                for (net.minecraftforge.eventbus.api.IEventBus bus : buses) {
                    bus.post(event);
                }
                count++;
            } catch (ReflectiveOperationException | RuntimeException e) {
                Throwable why = e instanceof java.lang.reflect.InvocationTargetException && e.getCause() != null ? e.getCause() : e;
                System.out.println("[Pentecost] Replaying registration of " + key.location()
                        + " for the Fabric API bridge failed: " + why);
            }
        }
        return count;
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
            }
        }
        throw new NoSuchFieldException(name);
    }
}
