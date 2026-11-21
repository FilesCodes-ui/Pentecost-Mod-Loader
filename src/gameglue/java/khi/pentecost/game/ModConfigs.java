package khi.pentecost.game;

import khi.pentecost.control.Choices;
import khi.pentecost.core.PentecostRuntime;
import khi.pentecost.core.SettingItem;
import khi.pentecost.mods.LoaderKind;
import khi.pentecost.mods.ModInfo;
import khi.pentecost.adapter.fabric.FabricLoaderImpl;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Function;

final class ModConfigs {
    private static final Map<String, Optional<Function<Screen, Screen>>> SCREENS = new ConcurrentHashMap<>();

    private ModConfigs() {}

    static List<ModInfo> withSettings() {
        List<ModInfo> out = new ArrayList<>();
        for (ModInfo m : PentecostRuntime.mods) {
            if (m.loader() == LoaderKind.LIBRARY || m.id().startsWith("fabric-") || m.id().equals("minecraft")
                    || m.id().equals("forge") || Choices.isModOff(m.id())) {
                continue;
            }
            if (screenFor(m).isPresent() || hasFiles(m.id())) {
                out.add(m);
            }
        }
        out.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        return out;
    }

    static ModInfo byId(String id) {
        for (ModInfo m : PentecostRuntime.mods) {
            if (m.id().equals(id)) {
                return m;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    static List<SettingItem> fileItems(String modId) {
        try {
            Class<?> c = Class.forName("khi.pentecost.forgeglue.ForgeConfigs", true, loader());
            return (List<SettingItem>) c.getMethod("items", String.class).invoke(null, modId);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return List.of();
        }
    }

    static boolean hasFiles(String modId) {
        if (!PentecostRuntime.forgeLayer) {
            return false;
        }
        try {
            Class<?> c = Class.forName("khi.pentecost.forgeglue.ForgeConfigs", true, loader());
            return (Boolean) c.getMethod("has", String.class).invoke(null, modId);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return false;
        }
    }

    private static ClassLoader loader() {
        return PentecostRuntime.loader != null ? PentecostRuntime.loader : ModConfigs.class.getClassLoader();
    }

    static Optional<Function<Screen, Screen>> screenFor(ModInfo m) {
        return SCREENS.computeIfAbsent(m.id(), id -> {
            try {
                return m.loader() == LoaderKind.FORGE ? forgeScreen(m) : fabricScreen(m);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                return Optional.empty();
            }
        });
    }

    @SuppressWarnings("unchecked")
    private static Optional<Function<Screen, Screen>> forgeScreen(ModInfo m) throws ReflectiveOperationException {
        if (!PentecostRuntime.forgeLayer) {
            return Optional.empty();
        }
        ClassLoader cl = ModConfigs.class.getClassLoader();
        Class<?> modList = Class.forName("net.minecraftforge.fml.ModList", true, cl);
        Object mods = modList.getMethod("get").invoke(null);
        Optional<?> container = (Optional<?>) modList.getMethod("getModContainerById", String.class).invoke(mods, m.id());
        if (container.isEmpty()) {
            return Optional.empty();
        }
        Object info = container.get().getClass().getMethod("getModInfo").invoke(container.get());
        Class<?> handler = Class.forName("net.minecraftforge.client.ConfigScreenHandler", true, cl);
        for (Method method : handler.getMethods()) {
            if (method.getName().equals("getScreenFactoryFor") && method.getParameterCount() == 1) {
                Optional<BiFunction<Minecraft, Screen, Screen>> factory =
                        (Optional<BiFunction<Minecraft, Screen, Screen>>) method.invoke(null, info);
                return factory.map(f -> back -> f.apply(Minecraft.getInstance(), back));
            }
        }
        return Optional.empty();
    }

    private static Optional<Function<Screen, Screen>> fabricScreen(ModInfo m) throws ReflectiveOperationException {
        ClassLoader cl = ModConfigs.class.getClassLoader();
        for (String value : m.entrypoints("modmenu")) {
            Object api;
            try {
                api = FabricLoaderImpl.INSTANCE.create(value, Object.class);
            } catch (Exception e) {
                continue;
            }
            if (api == null) {
                continue;
            }
            Class<?> apiType = Class.forName("com.terraformersmc.modmenu.api.ModMenuApi", true, cl);
            Object factory = apiType.getMethod("getModConfigScreenFactory").invoke(api);
            if (factory == null) {
                continue;
            }
            Method create = Class.forName("com.terraformersmc.modmenu.api.ConfigScreenFactory", true, cl)
                    .getMethod("create", Screen.class);
            return Optional.of(back -> {
                try {
                    Screen s = (Screen) create.invoke(factory, back);
                    return s == null ? back : s;
                } catch (ReflectiveOperationException e) {
                    return back;
                }
            });
        }
        return Optional.empty();
    }
}
