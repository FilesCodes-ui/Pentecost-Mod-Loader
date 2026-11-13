package khi.pentecost.game;

import khi.pentecost.boot.Log;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Settings that live in other mods, shown inside Pentecost's settings.
 * Video: Embeddium's (or Sodium's) option pages, the very same option objects, applied through
 * Embeddium's own apply step. Mods that add pages to Embeddium show up too.
 * Shaders: Oculus's (or Iris's) shader switch and pack list, through its public calls.
 * Nothing here links to those mods directly, so Pentecost runs fine without them.
 */
final class ExternalOptions {
    static final String EMBEDDIUM_SCREEN = "me.jellysquid.mods.sodium.client.gui.SodiumOptionsGUI";

    /** One Embeddium option, reduced to what Pentecost's rows need. */
    record VideoOption(String name, String tooltip, String kind, boolean restart, Object option, Object control) {}

    record VideoPage(String name, List<VideoOption> options) {}

    /** Set while Pentecost opens Embeddium's own screen on request, so routing lets it through. */
    static boolean bypass;

    private ExternalOptions() {}

    private static Class<?> find(String name) {
        try {
            return Class.forName(name, false, Minecraft.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- video

    static boolean hasEmbeddium() {
        return find(EMBEDDIUM_SCREEN) != null;
    }

    /** Embeddium's options screen, built but not shown. Holds the pages and the apply step. */
    static Object embeddiumScreen(Screen parent) {
        Class<?> c = find(EMBEDDIUM_SCREEN);
        if (c == null) {
            return null;
        }
        try {
            return c.getConstructor(Screen.class).newInstance(parent);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            Log.error("Cannot read Embeddium's video settings: " + e);
            return null;
        }
    }

    static List<VideoPage> pages(Object gui) {
        List<VideoPage> out = new ArrayList<>();
        if (gui == null) {
            return out;
        }
        try {
            Field f = gui.getClass().getDeclaredField("pages");
            f.setAccessible(true);
            for (Object page : (List<?>) f.get(gui)) {
                String name = ((Component) page.getClass().getMethod("getName").invoke(page)).getString();
                List<VideoOption> opts = new ArrayList<>();
                for (Object o : (Collection<?>) page.getClass().getMethod("getOptions").invoke(page)) {
                    Object control = call(o, "getControl");
                    String kind = control.getClass().getSimpleName();
                    String k = kind.contains("TickBox") ? "switch" : kind.contains("Cycling") ? "choice"
                            : kind.contains("Slider") ? "slider" : "other";
                    boolean restart = false;
                    for (Object flag : (Collection<?>) call(o, "getFlags")) {
                        if (flag.toString().contains("RESTART")) {
                            restart = true;
                        }
                    }
                    String tip = ((Component) call(o, "getTooltip")).getString();
                    int dot = tip.indexOf(". ");
                    if (dot > 0) {
                        tip = tip.substring(0, dot + 1);
                    }
                    opts.add(new VideoOption(((Component) call(o, "getName")).getString(), tip, k, restart, o, control));
                }
                out.add(new VideoPage(name, opts));
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.error("Cannot read Embeddium's option pages: " + e);
        }
        return out;
    }

    static Object call(Object target, String method) throws ReflectiveOperationException {
        Method m = target.getClass().getMethod(method);
        m.setAccessible(true);
        return m.invoke(target);
    }

    static boolean available(VideoOption v) {
        try {
            return (boolean) call(v.option(), "isAvailable");
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    static Object value(VideoOption v) {
        try {
            return call(v.option(), "getValue");
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /** Sets a value and applies it at once through Embeddium's own apply step. */
    static void set(Object gui, VideoOption v, Object value) {
        try {
            for (Method m : v.option().getClass().getMethods()) {
                if (m.getName().equals("setValue") && m.getParameterCount() == 1) {
                    m.setAccessible(true);
                    m.invoke(v.option(), value);
                    break;
                }
            }
            Method apply = gui.getClass().getDeclaredMethod("applyChanges");
            apply.setAccessible(true);
            apply.invoke(gui);
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.error("Cannot change " + v.name() + ": " + e);
        }
    }

    private static Object field(Object target, String name) throws ReflectiveOperationException {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }

    /** Text for a choice's current value. */
    static String choiceText(VideoOption v) {
        try {
            Object[] values = (Object[]) field(v.control(), "allowedValues");
            Component[] names = (Component[]) field(v.control(), "names");
            Object now = value(v);
            for (int i = 0; i < values.length; i++) {
                if (values[i].equals(now)) {
                    return names[i].getString();
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            // Fall through to the plain value.
        }
        return String.valueOf(value(v));
    }

    static void nextChoice(Object gui, VideoOption v, boolean back) {
        try {
            Object[] values = (Object[]) field(v.control(), "allowedValues");
            Object now = value(v);
            int i = 0;
            for (int k = 0; k < values.length; k++) {
                if (values[k].equals(now)) {
                    i = k;
                }
            }
            set(gui, v, values[(i + (back ? values.length - 1 : 1)) % values.length]);
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.error("Cannot change " + v.name() + ": " + e);
        }
    }

    /** min, max, interval of a slider. */
    static int[] sliderRange(VideoOption v) {
        try {
            return new int[]{(int) field(v.control(), "min"), (int) field(v.control(), "max"), (int) field(v.control(), "interval")};
        } catch (ReflectiveOperationException | RuntimeException e) {
            return new int[]{0, 1, 1};
        }
    }

    static String sliderText(VideoOption v) {
        try {
            Object mode = field(v.control(), "mode");
            Method format = mode.getClass().getMethod("format", int.class);
            format.setAccessible(true);
            return ((Component) format.invoke(mode, (int) value(v))).getString();
        } catch (ReflectiveOperationException | RuntimeException e) {
            return String.valueOf(value(v));
        }
    }

    /** Opens Embeddium's own screen, past Pentecost's routing. */
    static void openEmbeddium(Screen parent) {
        Object gui = embeddiumScreen(parent);
        if (gui instanceof Screen s) {
            bypass = true;
            try {
                Minecraft.getInstance().setScreen(s);
            } finally {
                bypass = false;
            }
        }
    }

    /** The screen Embeddium's options would return to. */
    static Screen parentOf(Screen embeddium) {
        try {
            return (Screen) field(embeddium, "prevScreen");
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- shaders

    private static final String IRIS = "net.irisshaders.iris.Iris";

    static boolean hasShaders() {
        return find(IRIS) != null;
    }

    static boolean shadersOn() {
        try {
            Object config = find(IRIS).getMethod("getIrisConfig").invoke(null);
            return (boolean) config.getClass().getMethod("areShadersEnabled").invoke(config);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return false;
        }
    }

    static void setShaders(boolean on) {
        try {
            Class<?> api = find("net.irisshaders.iris.api.v0.IrisApi");
            Object inst = api.getMethod("getInstance").invoke(null);
            Object config = inst.getClass().getMethod("getConfig").invoke(inst);
            Method m = config.getClass().getMethod("setShadersEnabledAndApply", boolean.class);
            m.setAccessible(true);
            m.invoke(config, on);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            Log.error("Cannot switch shaders: " + e);
        }
    }

    static Path packFolder() {
        try {
            return (Path) find(IRIS).getMethod("getShaderpacksDirectory").invoke(null);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return null;
        }
    }

    static List<String> packs() {
        List<String> out = new ArrayList<>();
        Path dir = packFolder();
        if (dir == null || !Files.isDirectory(dir)) {
            return out;
        }
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(p -> Files.isDirectory(p) || p.getFileName().toString().endsWith(".zip"))
                    .map(p -> p.getFileName().toString()).sorted().forEach(out::add);
        } catch (Exception e) {
            Log.error("Cannot list shader packs: " + e);
        }
        return out;
    }

    static String currentPack() {
        try {
            Object config = find(IRIS).getMethod("getIrisConfig").invoke(null);
            Object name = config.getClass().getMethod("getShaderPackName").invoke(config);
            return name instanceof Optional<?> o && o.isPresent() ? String.valueOf(o.get()) : "";
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return "";
        }
    }

    /** Picks a pack, turns shaders on, saves Oculus's settings and reloads shaders. */
    static void usePack(String name) {
        try {
            Class<?> iris = find(IRIS);
            Object config = iris.getMethod("getIrisConfig").invoke(null);
            config.getClass().getMethod("setShaderPackName", String.class).invoke(config, name);
            config.getClass().getMethod("setShadersEnabled", boolean.class).invoke(config, true);
            config.getClass().getMethod("save").invoke(config);
            iris.getMethod("reload").invoke(null);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            Log.error("Cannot switch to shader pack " + name + ": " + e);
        }
    }

    /** Oculus's own screen, for the chosen pack's settings. */
    static void openPackSettings(Screen parent) {
        try {
            Class<?> c = find("net.irisshaders.iris.gui.screen.ShaderPackScreen");
            Minecraft.getInstance().setScreen((Screen) c.getConstructor(Screen.class).newInstance(parent));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            Log.error("Cannot open the shader pack screen: " + e);
        }
    }
}
