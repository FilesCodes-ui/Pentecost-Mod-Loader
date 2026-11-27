package khi.pentecost.forgeglue;

import net.minecraftforge.fml.ModContainer;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.forgespi.language.IConfigurable;
import net.minecraftforge.forgespi.language.IModInfo;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class ConfigShim {
    private static final Map<String, ModContainer> STAND_INS = new ConcurrentHashMap<>();

    private ConfigShim() {}

    public static ModContainer container(String modId) {
        try {
            ModList list = ModList.get();
            if (list != null) {
                Optional<? extends ModContainer> real = list.getModContainerById(modId);
                if (real.isPresent()) {
                    return real.get();
                }
            }
        } catch (RuntimeException | LinkageError ignored) {
        }
        return STAND_INS.computeIfAbsent(modId, StandIn::new);
    }

    private static final class StandIn extends ModContainer {
        private final String id;

        StandIn(String id) {
            super(info(id));
            this.id = id;
        }

        @Override
        public boolean matches(Object mod) {
            return mod == this;
        }

        @Override
        public Object getMod() {
            return id;
        }
    }

    private static IModInfo info(String id) {
        IConfigurable none = (IConfigurable) proxy(IConfigurable.class, (p, m, a) -> {
            if (m.getName().equals("getConfigElement")) {
                if (a != null && a.length > 0 && a[0] instanceof String[] ks && ks.length == 1 && ks[0].equals("displayTest")) {
                    return Optional.of("IGNORE_ALL_VERSION");
                }
                return Optional.empty();
            }
            if (m.getName().equals("getConfigList")) {
                return java.util.List.of();
            }
            return fallback(m.getReturnType());
        });
        return (IModInfo) proxy(IModInfo.class, (p, m, a) -> switch (m.getName()) {
            case "getModId", "getNamespace", "getDisplayName" -> id;
            case "getConfig" -> none;
            case "getDependencies" -> java.util.List.of();
            case "getOwningFile" -> ownerFile(none);
            case "toString" -> "StandInModInfo[" + id + "]";
            case "hashCode" -> id.hashCode();
            case "equals" -> p == a[0];
            default -> fallback(m.getReturnType());
        });
    }

    private static Object ownerFile(IConfigurable none) {
        return proxy(net.minecraftforge.forgespi.language.IModFileInfo.class, (p, m, a) -> switch (m.getName()) {
            case "getFileProperties" -> java.util.Collections.emptyMap();
            case "getConfig" -> none;
            case "getMods", "requiredLanguageLoaders" -> java.util.List.of();
            case "toString" -> "StandInModFileInfo";
            case "hashCode" -> 0;
            case "equals" -> p == a[0];
            default -> fallback(m.getReturnType());
        });
    }

    private static Object proxy(Class<?> type, InvocationHandler handler) {
        return Proxy.newProxyInstance(ConfigShim.class.getClassLoader(), new Class<?>[]{type}, handler);
    }

    private static Object fallback(Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == double.class) {
            return 0.0;
        }
        if (type == float.class) {
            return 0f;
        }
        if (type == Optional.class) {
            return Optional.empty();
        }
        if (type == java.util.List.class) {
            return java.util.List.of();
        }
        return null;
    }
}
