package khi.pentecost.game;

import khi.pentecost.boot.Dirs;
import khi.pentecost.boot.Log;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    /** Oculus and Iris share this screen. The shader key opens it. Pentecost shows its Shaders page instead. */
    static final String SHADER_SCREEN = "net.irisshaders.iris.gui.screen.ShaderPackScreen";

    /** One Embeddium option, reduced to what Pentecost's rows need. */
    record VideoOption(String name, String tooltip, String about, String kind, boolean restart, Object option, Object control) {}

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
                    String about = tip;
                    int dot = tip.indexOf(". ");
                    if (dot > 0) {
                        tip = tip.substring(0, dot + 1);
                    }
                    opts.add(new VideoOption(((Component) call(o, "getName")).getString(), tip, about, k, restart, o, control));
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

    /** Sets a value without applying it yet (sliders while dragging). */
    static void setOnly(VideoOption v, Object value) {
        try {
            for (Method m : v.option().getClass().getMethods()) {
                if (m.getName().equals("setValue") && m.getParameterCount() == 1) {
                    m.setAccessible(true);
                    m.invoke(v.option(), value);
                    return;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.error("Cannot change " + v.name() + ": " + e);
        }
    }

    /** Runs Embeddium's apply step for every changed option. */
    static void apply(Object gui) {
        if (gui == null) {
            return;
        }
        try {
            Method apply = gui.getClass().getDeclaredMethod("applyChanges");
            apply.setAccessible(true);
            apply.invoke(gui);
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.error("Cannot apply video settings: " + e);
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

    /** The screen the shader pack screen would return to. */
    static Screen shaderParentOf(Screen shaderScreen) {
        try {
            return (Screen) field(shaderScreen, "parent");
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
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

    /** The shader pack that ships with Pentecost: its folder name in the shaderpacks folder and in Pentecost's own folder. */
    static final String BUNDLED_PACK = "Pentecost";

    /**
     * Copies Pentecost's own shader pack (shaderpack/Pentecost in the Pentecost folder) into the shaderpacks folder.
     * Only files that are missing or different are written, so a repeat call costs a few reads.
     * Returns false when there is no pack to copy or the copy failed.
     */
    static boolean installBundledPack() {
        Path dir = packFolder();
        Path src = Dirs.home().resolve("shaderpack").resolve(BUNDLED_PACK);
        if (dir == null || !Files.isDirectory(src)) {
            return false;
        }
        Path dst = dir.resolve(BUNDLED_PACK);
        try (Stream<Path> walk = Files.walk(src)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                Path t = dst.resolve(src.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(t);
                } else if (!Files.exists(t) || !Arrays.equals(Files.readAllBytes(p), Files.readAllBytes(t))) {
                    Files.createDirectories(t.getParent());
                    Files.copy(p, t, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            return true;
        } catch (IOException | RuntimeException e) {
            Log.error("Cannot install Pentecost's shader pack: " + e);
            return false;
        }
    }

    /** Installs Pentecost's shader pack if needed, then switches to it. */
    static void usePentecostPack() {
        if (installBundledPack()) {
            usePack(BUNDLED_PACK);
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
    // ---------------------------------------------------------------- shader pack options

    /** One entry of a shader pack's option menu. kind: bool, string, link, profile. */
    record ShaderEntry(String kind, String id, String label, String about, List<String> values) {}

    /** One screen of a shader pack's option menu: the main screen or a group it links to. */
    record ShaderScreen(String id, String title, List<ShaderEntry> entries) {}

    /** Option name to its menu element, for the values the pack runs with now. */
    private static final Map<String, Object> ELEMENTS = new HashMap<>();
    private static Map<String, String> words = Map.of();
    private static Object profileSet;

    /** The current pack's option menu, as Pentecost rows. Empty when no pack is in use. */
    static List<ShaderScreen> shaderMenu() {
        List<ShaderScreen> out = new ArrayList<>();
        ELEMENTS.clear();
        profileSet = null;
        try {
            Class<?> iris = find(IRIS);
            if (iris == null) {
                return out;
            }
            Optional<?> pack = (Optional<?>) iris.getMethod("getCurrentPack").invoke(null);
            if (pack.isEmpty()) {
                return out;
            }
            Object sp = pack.get();
            words = translations(sp);
            Object container = sp.getClass().getMethod("getMenuContainer").invoke(sp);
            Object main = container.getClass().getField("mainScreen").get(container);
            Map<?, ?> subs = (Map<?, ?>) container.getClass().getField("subScreens").get(container);
            out.add(screen("", "General", main));
            for (Map.Entry<?, ?> e : subs.entrySet()) {
                String id = String.valueOf(e.getKey());
                out.add(screen(id, word("screen." + id, id), e.getValue()));
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            Log.error("Cannot read the shader pack's options: " + e);
        }
        return out;
    }

    private static ShaderScreen screen(String id, String title, Object screen) throws ReflectiveOperationException {
        List<ShaderEntry> entries = new ArrayList<>();
        List<?> elements = (List<?>) screen.getClass().getField("elements").get(screen);
        for (Object el : elements) {
            String type = el.getClass().getSimpleName();
            switch (type) {
                case "OptionMenuBooleanOptionElement", "OptionMenuStringOptionElement" -> {
                    String name = (String) el.getClass().getField("optionId").get(el);
                    Object option = el.getClass().getField("option").get(el);
                    ELEMENTS.put(name, el);
                    String about = word("option." + name + ".comment", "");
                    Optional<?> comment = (Optional<?>) option.getClass().getMethod("getComment").invoke(option);
                    if (about.isEmpty() && comment.isPresent()) {
                        about = clean(String.valueOf(comment.get()));
                    }
                    if (type.startsWith("OptionMenuBoolean")) {
                        entries.add(new ShaderEntry("bool", name, word("option." + name, name), about, List.of()));
                    } else {
                        List<String> values = new ArrayList<>();
                        for (Object v : (List<?>) option.getClass().getMethod("getAllowedValues").invoke(option)) {
                            values.add(String.valueOf(v));
                        }
                        entries.add(new ShaderEntry("string", name, word("option." + name, name), about, values));
                    }
                }
                case "OptionMenuLinkElement" -> {
                    String target = (String) el.getClass().getField("targetScreenId").get(el);
                    entries.add(new ShaderEntry("link", target, word("screen." + target, target),
                            word("screen." + target + ".comment", ""), List.of()));
                }
                case "OptionMenuProfileElement" -> {
                    profileSet = el.getClass().getField("profiles").get(el);
                    List<String> names = new ArrayList<>();
                    profileSet.getClass().getMethod("forEach", java.util.function.BiConsumer.class)
                            .invoke(profileSet, (java.util.function.BiConsumer<Object, Object>) (n, prof) -> names.add(String.valueOf(n)));
                    if (!names.isEmpty()) {
                        entries.add(new ShaderEntry("profile", "", "Profile",
                                "A set of values chosen by the pack's author, from light to heavy.", names));
                    }
                }
                default -> { }
            }
        }
        return new ShaderScreen(id, clean(title), entries);
    }

    private static Map<String, String> translations(Object pack) {
        try {
            Object map = pack.getClass().getMethod("getLanguageMap").invoke(pack);
            String code = Minecraft.getInstance().options.languageCode;
            Map<String, String> t = (Map<String, String>) map.getClass().getMethod("getTranslations", String.class).invoke(map, code);
            if (t == null || t.isEmpty()) {
                t = (Map<String, String>) map.getClass().getMethod("getTranslations", String.class).invoke(map, "en_us");
            }
            return t == null ? Map.of() : t;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return Map.of();
        }
    }

    private static String word(String key, String fallback) {
        String w = words.get(key);
        return w == null ? fallback : clean(w);
    }

    /** Shader packs color their text with section signs. Pentecost's screens are black and white. */
    private static String clean(String s) {
        return s.replaceAll("\u00a7.", "").trim();
    }

    private static Map<String, String> queue() throws ReflectiveOperationException {
        return (Map<String, String>) find(IRIS).getMethod("getShaderPackOptionQueue").invoke(null);
    }

    /** The value an option will have after Apply: a change waiting in line, or the value in use. */
    static String shaderValue(String id, boolean bool) {
        try {
            String queued = queue().get(id);
            if (queued != null) {
                return queued;
            }
            Object el = ELEMENTS.get(id);
            if (el == null) {
                return "";
            }
            Object values = el.getClass().getMethod("getAppliedOptionValues").invoke(el);
            return bool ? String.valueOf(values.getClass().getMethod("getBooleanValueOrDefault", String.class).invoke(values, id))
                    : String.valueOf(values.getClass().getMethod("getStringValueOrDefault", String.class).invoke(values, id));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return "";
        }
    }

    static void setShaderValue(String id, String value) {
        try {
            queue().put(id, value);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            Log.error("Cannot change the shader option " + id + ": " + e);
        }
    }

    /** How the pack names a value of an option, or the value itself. */
    static String shaderValueLabel(String id, String value) {
        return word("value." + id + "." + value, value);
    }

    /** Puts a profile's values in line. They take effect on Apply. */
    static void useShaderProfile(String name) {
        try {
            if (profileSet == null) {
                return;
            }
            Object[] found = new Object[1];
            profileSet.getClass().getMethod("forEach", java.util.function.BiConsumer.class)
                    .invoke(profileSet, (java.util.function.BiConsumer<Object, Object>) (n, prof) -> {
                        if (name.equals(String.valueOf(n))) {
                            found[0] = prof;
                        }
                    });
            if (found[0] != null) {
                Class<?> profile = find("net.irisshaders.iris.shaderpack.option.Profile");
                find(IRIS).getMethod("queueShaderPackOptionsFromProfile", profile).invoke(null, found[0]);
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            Log.error("Cannot use the shader profile " + name + ": " + e);
        }
    }

    static int pendingShaderChanges() {
        try {
            return queue().size();
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return 0;
        }
    }

    /** Reloads the pack with the changes in line. Oculus saves them to the pack's settings file. */
    static void applyShaderChanges() {
        try {
            find(IRIS).getMethod("reload").invoke(null);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            Log.error("Cannot apply the shader changes: " + e);
        }
    }

    static void discardShaderChanges() {
        try {
            find(IRIS).getMethod("clearShaderPackOptionQueue").invoke(null);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            Log.error("Cannot discard the shader changes: " + e);
        }
    }

    /** Every option back to the pack's own default, applied at once. */
    static void resetShaderOptions() {
        try {
            Class<?> iris = find(IRIS);
            iris.getMethod("clearShaderPackOptionQueue").invoke(null);
            iris.getMethod("resetShaderPackOptionsOnNextReload").invoke(null);
            iris.getMethod("reload").invoke(null);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            Log.error("Cannot reset the shader options: " + e);
        }
    }

    static void openPackSettings(Screen parent) {
        try {
            Class<?> c = find(SHADER_SCREEN);
            bypass = true;
            try {
                Minecraft.getInstance().setScreen((Screen) c.getConstructor(Screen.class).newInstance(parent));
            } finally {
                bypass = false;
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            Log.error("Cannot open the shader pack screen: " + e);
        }
    }
}
