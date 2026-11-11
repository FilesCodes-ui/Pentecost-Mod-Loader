package khi.pentecost.game;

import khi.pentecost.boot.Log;
import khi.pentecost.core.Hooks;
import khi.pentecost.core.PentecostRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.Function;

/**
 * Pentecost owns the Mods button. Every loader's mods screen is one provider here, and the player
 * picks which provider the button opens. Forge's button, Mod Menu's button, and any other mod's
 * button all land in {@link #route}: whatever mods screen they try to open, the chosen one opens.
 *
 * A mod adds its own screen with {@link #register}.
 */
public final class ModsScreens {
    public static final String PENTECOST = "pentecost";

    /** One mods screen a player can choose. */
    public record Provider(String id, String label, Function<Screen, Screen> factory, String screenClass) {}

    private static final Map<String, Provider> PROVIDERS = new LinkedHashMap<>();
    private static boolean routing;
    private static boolean tested;
    private static String chosen;

    private ModsScreens() {}

    /** Called once when the game starts. */
    public static void init() {
        register(PENTECOST, "Pentecost", PentecostModsScreen::new, PentecostModsScreen.class.getName());
        known("forge", "Forge", "net.minecraftforge.client.gui.ModListScreen");
        known("modmenu", "Mod Menu", "com.terraformersmc.modmenu.gui.ModsScreen");
        Hooks.screenRouter = o -> route((Screen) o);
    }

    /**
     * Adds a mods screen. The factory gets the screen to return to.
     * screenClass is the class name of the screen the factory builds.
     */
    public static synchronized void register(String id, String label, Function<Screen, Screen> factory,
                                             String screenClass) {
        PROVIDERS.put(id, new Provider(id, label, factory, screenClass));
    }

    /** Registers a screen from another loader when that loader's class exists. Built by reflection. */
    private static void known(String id, String label, String className) {
        ClassLoader cl = ModsScreens.class.getClassLoader();
        if (cl.getResource(className.replace('.', '/') + ".class") == null) {
            return;
        }
        register(id, label, parent -> {
            try {
                return (Screen) Class.forName(className, true, cl).getConstructor(Screen.class).newInstance(parent);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }, className);
    }

    public static synchronized List<Provider> providers() {
        return new ArrayList<>(PROVIDERS.values());
    }

    public static synchronized Provider chosen() {
        if (chosen == null) {
            chosen = load();
        }
        Provider p = PROVIDERS.get(chosen);
        return p != null ? p : PROVIDERS.get(PENTECOST);
    }

    public static synchronized void choose(String id) {
        chosen = id;
        save(id);
    }

    /** The game calls this for every screen it is about to show. Mods screens get swapped for the chosen one. */
    public static Screen route(Screen incoming) {
        if (incoming == null || routing) {
            return incoming;
        }
        String name = incoming.getClass().getName();
        if (!tested && "mods".equals(System.getenv("PENTECOST_TEST_SCREEN"))
                && name.equals("net.minecraft.client.gui.screens.TitleScreen")) {
            tested = true;
            Provider ask = PROVIDERS.getOrDefault("forge", PROVIDERS.get(PENTECOST));
            Log.info("Test: asking for the " + ask.label() + " mods screen");
            Screen out = route(ask.factory().apply(incoming));
            Log.info("Test: mods screen shown is " + out.getClass().getName());
            return out;
        }
        boolean modsScreen = false;
        for (Provider p : providers()) {
            if (p.screenClass().equals(name)) {
                modsScreen = true;
                break;
            }
        }
        if (!modsScreen) {
            return incoming;
        }
        // Shift always opens the Pentecost list, so a player can get back from any other screen.
        Provider target = Screen.hasShiftDown() ? PROVIDERS.get(PENTECOST) : chosen();
        if (target == null || target.screenClass().equals(name)) {
            return incoming;
        }
        routing = true;
        try {
            return target.factory().apply(Minecraft.getInstance().screen);
        } catch (RuntimeException | LinkageError e) {
            Log.error("The " + target.label() + " mods screen failed to open: " + e + ". Opening the requested one");
            return incoming;
        } finally {
            routing = false;
        }
    }

    /** Opens a provider's screen directly, without routing. */
    static void open(Provider p, Screen parent) {
        routing = true;
        try {
            Minecraft.getInstance().setScreen(p.factory().apply(parent));
        } catch (RuntimeException | LinkageError e) {
            Log.error("The " + p.label() + " mods screen failed to open: " + e);
        } finally {
            routing = false;
        }
    }

    private static Path file() {
        return PentecostRuntime.gameDir.resolve("config").resolve("pentecost.properties");
    }

    private static String load() {
        Properties props = new Properties();
        if (Files.isRegularFile(file())) {
            try (InputStream in = Files.newInputStream(file())) {
                props.load(in);
            } catch (IOException e) {
                Log.error("Cannot read " + file() + ": " + e);
            }
        }
        return props.getProperty("modsScreen", PENTECOST);
    }

    private static void save(String id) {
        Properties props = new Properties();
        try {
            if (Files.isRegularFile(file())) {
                try (InputStream in = Files.newInputStream(file())) {
                    props.load(in);
                }
            }
            props.setProperty("modsScreen", id);
            Files.createDirectories(file().getParent());
            try (OutputStream out = Files.newOutputStream(file())) {
                props.store(out, "Pentecost settings");
            }
        } catch (IOException e) {
            Log.error("Cannot save " + file() + ": " + e);
        }
    }
}
