package khi.pentecost.forgeglue;

import net.minecraft.core.WritableRegistry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.registries.ForgeRegistry;
import net.minecraftforge.registries.IForgeRegistry;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Lets a Fabric mod write to a registry that a Forge build of another mod owns.
 * Fabric code expects a WritableRegistry. The Forge build exposes a Supplier of an IForgeRegistry.
 */
public final class RegistryAdapter {
    private RegistryAdapter() {}

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static WritableRegistry writable(Supplier<?> holder) {
        InvocationHandler handler = (proxy, method, args) -> {
            Object raw = holder.get();
            if (!(raw instanceof IForgeRegistry<?> reg)) {
                throw new IllegalStateException("The Forge registry is not ready yet");
            }
            switch (method.getName()) {
                case "register":
                    if (args != null && args.length >= 2 && args[0] instanceof ResourceKey<?> key) {
                        add(reg, key.location(), args[1]);
                        return holderFor(reg, key.location(), args[1]);
                    }
                    break;
                case "key":
                    return reg.getRegistryKey();
                case "get":
                    if (args != null && args.length == 1) {
                        if (args[0] instanceof ResourceLocation id) {
                            return reg.getValue(id);
                        }
                        if (args[0] instanceof ResourceKey<?> key) {
                            return reg.getValue(key.location());
                        }
                    }
                    break;
                case "getOptional":
                    if (args != null && args.length == 1) {
                        ResourceLocation id = args[0] instanceof ResourceKey<?> key ? key.location()
                                : args[0] instanceof ResourceLocation r ? r : null;
                        return Optional.ofNullable(id == null ? null : reg.getValue(id));
                    }
                    break;
                case "getKey":
                    return ((IForgeRegistry) reg).getKey(args[0]);
                case "containsKey":
                    if (args != null && args.length == 1) {
                        ResourceLocation id = args[0] instanceof ResourceKey<?> key ? key.location()
                                : args[0] instanceof ResourceLocation r ? r : null;
                        return id != null && reg.containsKey(id);
                    }
                    break;
                case "keySet":
                    return reg.getKeys();
                case "size":
                    return reg.getKeys().size();
                case "toString":
                    return "ForgeRegistryAdapter[" + reg.getRegistryName() + "]";
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                default:
                    break;
            }
            return defaultFor(method.getReturnType());
        };
        return (WritableRegistry) Proxy.newProxyInstance(RegistryAdapter.class.getClassLoader(),
                new Class<?>[]{WritableRegistry.class}, handler);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void add(IForgeRegistry<?> reg, ResourceLocation id, Object value) {
        if (reg.containsKey(id)) {
            return;
        }
        if (reg instanceof ForgeRegistry fr) {
            boolean locked = fr.isLocked();
            if (locked) {
                fr.unfreeze();
            }
            try {
                fr.register(id, value);
            } finally {
                if (locked) {
                    fr.freeze();
                }
            }
            return;
        }
        throw new IllegalStateException("Cannot write to " + reg.getRegistryName());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object holderFor(IForgeRegistry<?> reg, ResourceLocation id, Object value) {
        try {
            return ((IForgeRegistry) reg).getDelegateOrThrow(id);
        } catch (RuntimeException e) {
            return value;
        }
    }

    private static Object defaultFor(Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == Optional.class) {
            return Optional.empty();
        }
        return null;
    }
}
