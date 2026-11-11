package khi.pentecost.control;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import khi.pentecost.boot.Dirs;
import khi.pentecost.boot.Log;
import khi.pentecost.core.PentecostRuntime;
import khi.pentecost.install.Http;
import khi.pentecost.mods.LoaderKind;
import khi.pentecost.mods.ModInfo;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Finds what each mod changes outside its own content, in words a player understands,
 * and applies the player's choice to turn single changes off.
 *
 * Three kinds are found:
 *   - vanilla files a mod overwrites (menu textures, the font, sounds),
 *   - code hooks a mod puts into the game, grouped by the part of the game they touch,
 *   - vanilla screens a mod swaps for its own. Those are spotted while the game runs.
 */
public final class Features {
    /**
     * One thing a mod changes.
     * risky: turning it off can break the mod. restart: the choice applies on the next launch.
     */
    public record Feature(String modId, String key, String label, String detail, boolean risky, boolean restart,
                          List<String> items) {}

    private record Group(String key, String label, boolean risky) {}

    /** Vanilla file folders a mod can overwrite. Folder prefix under assets/minecraft/, then the feature. */
    private static final Map<String, Group> ASSETS = new LinkedHashMap<>();
    static {
        ASSETS.put("textures/gui/", new Group("assets:menus", "Menu and button textures", false));
        ASSETS.put("font/", new Group("assets:font", "Game font", false));
        ASSETS.put("texts/", new Group("assets:texts", "Splash texts and credits", false));
        ASSETS.put("lang/", new Group("assets:lang", "Vanilla translations", false));
        ASSETS.put("sounds/", new Group("assets:sounds", "Vanilla sounds", false));
        ASSETS.put("sounds.json", new Group("assets:sounds", "Vanilla sounds", false));
        for (String t : new String[]{"block", "item", "entity", "environment", "particle", "painting", "mob_effect",
                "misc", "colormap", "map", "models", "trims"}) {
            ASSETS.put("textures/" + t + "/", new Group("assets:world", "Vanilla block, item and mob textures", false));
        }
        ASSETS.put("models/", new Group("assets:models", "Vanilla block and item models", false));
        ASSETS.put("blockstates/", new Group("assets:models", "Vanilla block and item models", false));
        ASSETS.put("shaders/", new Group("assets:shaders", "Vanilla shaders", false));
    }

    private Features() {}

    /** Finds every mod's features, then applies the choices that are off. Returns the mods to load. */
    public static List<ModInfo> apply(List<ModInfo> mods) {
        Map<String, List<Feature>> all = new LinkedHashMap<>();
        Map<String, Set<String>> dropped = new HashMap<>();
        Map<Path, Path> swapped = new HashMap<>();
        Set<Path> scanned = new HashSet<>();
        for (ModInfo m : mods) {
            List<Feature> found = new ArrayList<>();
            if (!internal(m)) {
                try {
                    if (scanned.add(m.jar())) {
                        found.addAll(assets(m));
                    }
                    found.addAll(mixins(m));
                } catch (IOException | RuntimeException e) {
                    Log.error("Cannot read what " + m.name() + " changes: " + e);
                }
            }
            all.put(m.id(), List.copyOf(found));
            List<String> strip = new ArrayList<>();
            for (Feature f : found) {
                if (!Choices.isOff(m.id(), f.key())) {
                    continue;
                }
                Log.info("Player choice: " + m.name() + " has \"" + f.label() + "\" turned off");
                if (f.key().startsWith("assets:")) {
                    strip.addAll(f.items());
                } else if (f.key().startsWith("mixin:")) {
                    for (String item : f.items()) {
                        int bar = item.indexOf('|');
                        dropped.computeIfAbsent(item.substring(0, bar), k -> new HashSet<>()).add(item.substring(bar + 1));
                    }
                }
            }
            if (!strip.isEmpty()) {
                try {
                    swapped.put(m.jar(), withoutFiles(m.jar(), strip));
                } catch (IOException e) {
                    Log.error("Cannot hide files of " + m.name() + ": " + e);
                }
            }
        }
        List<ModInfo> out = new ArrayList<>();
        for (ModInfo m : mods) {
            Path jar = swapped.get(m.jar());
            out.add(jar == null ? m : m.withJar(jar));
        }
        PentecostRuntime.features = all;
        khi.pentecost.mixin.PentecostMixinService.droppedMixins = dropped;
        return out;
    }

    /** Everything known about one mod: what the scan found, and the screens it was seen replacing. */
    public static List<Feature> forMod(String modId) {
        List<Feature> out = new ArrayList<>(PentecostRuntime.features.getOrDefault(modId, List.of()));
        for (String vanilla : Choices.seenScreens(modId).keySet()) {
            out.add(0, new Feature(modId, "screen:" + vanilla, "Replaces " + screenName(vanilla),
                    "Shows its own screen in place of the game's. Applies right away.", false, false, List.of()));
        }
        return out;
    }

    private static String screenName(String vanillaClass) {
        String simple = vanillaClass.substring(vanillaClass.lastIndexOf('.') + 1);
        return switch (simple) {
            case "TitleScreen" -> "the main menu";
            case "PauseScreen" -> "the pause menu";
            case "OptionsScreen" -> "the options menu";
            case "InventoryScreen" -> "the inventory";
            case "DeathScreen" -> "the death screen";
            default -> "the " + simple.replaceAll("([a-z])([A-Z])", "$1 $2").toLowerCase();
        };
    }

    /** Libraries and Fabric API modules are plumbing. Players do not pick them apart. */
    private static boolean internal(ModInfo m) {
        String id = m.id();
        return m.loader() == LoaderKind.LIBRARY || id.startsWith("fabric-") || id.startsWith("fabric_")
                || id.equals("mixinextras");
    }

    // ---------------------------------------------------------------- vanilla files

    private static List<Feature> assets(ModInfo m) throws IOException {
        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, Set<String>> prefixes = new HashMap<>();
        try (ZipFile zip = new ZipFile(m.jar().toFile())) {
            Enumeration<? extends ZipEntry> en = zip.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                String n = e.getName();
                if (e.isDirectory() || !n.startsWith("assets/minecraft/")) {
                    continue;
                }
                String rest = n.substring("assets/minecraft/".length());
                for (Map.Entry<String, Group> g : ASSETS.entrySet()) {
                    if (rest.startsWith(g.getKey())) {
                        counts.merge(g.getValue().key(), 1, Integer::sum);
                        prefixes.computeIfAbsent(g.getValue().key(), k -> new HashSet<>())
                                .add("assets/minecraft/" + g.getKey());
                        break;
                    }
                }
            }
        }
        List<Feature> out = new ArrayList<>();
        for (Group g : new LinkedHashMap<String, Group>() {{
            ASSETS.values().forEach(v -> putIfAbsent(v.key(), v));
        }}.values()) {
            Integer n = counts.get(g.key());
            if (n != null) {
                out.add(new Feature(m.id(), g.key(), "Replaces: " + g.label(),
                        "Overwrites " + n + " of the game's own file" + (n == 1 ? "" : "s") + ".",
                        g.risky(), true, List.copyOf(prefixes.get(g.key()))));
            }
        }
        return out;
    }

    /** Writes a copy of the jar without the given files. Cached, so it is built once per choice. */
    private static Path withoutFiles(Path jar, List<String> prefixes) throws IOException {
        List<String> sorted = new ArrayList<>(new HashSet<>(prefixes));
        sorted.sort(null);
        String tag = Integer.toHexString((jar.toAbsolutePath() + "|" + Files.size(jar) + "|"
                + Files.getLastModifiedTime(jar).toMillis() + "|" + sorted).hashCode());
        Path out = Dirs.mk(Dirs.cache().resolve("filtered")).resolve(tag + "-" + jar.getFileName());
        if (Files.isRegularFile(out)) {
            return out;
        }
        Path tmp = out.resolveSibling(out.getFileName() + ".tmp");
        try (ZipFile zip = new ZipFile(jar.toFile());
             OutputStream os = Files.newOutputStream(tmp);
             ZipOutputStream zos = new ZipOutputStream(os)) {
            Enumeration<? extends ZipEntry> en = zip.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                String n = e.getName();
                boolean hide = false;
                for (String p : sorted) {
                    if (n.startsWith(p)) {
                        hide = true;
                        break;
                    }
                }
                if (hide) {
                    continue;
                }
                zos.putNextEntry(new ZipEntry(n));
                if (!e.isDirectory()) {
                    try (InputStream in = zip.getInputStream(e)) {
                        in.transferTo(zos);
                    }
                }
                zos.closeEntry();
            }
        }
        Files.move(tmp, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        return out;
    }

    // ---------------------------------------------------------------- code hooks

    private static Group groupOf(String target) {
        if (target.equals("net.minecraft.client.gui.screens.TitleScreen")) {
            return new Group("mixin:main_menu", "Changes the main menu", false);
        }
        if (target.equals("net.minecraft.client.gui.screens.PauseScreen")) {
            return new Group("mixin:pause_menu", "Changes the pause menu", false);
        }
        if (target.startsWith("net.minecraft.client.gui.screens.LoadingOverlay")
                || target.startsWith("net.minecraft.client.gui.screens.LevelLoadingScreen")) {
            return new Group("mixin:loading", "Changes the loading screens", false);
        }
        if (target.startsWith("net.minecraft.client.gui.screens.inventory.")) {
            return new Group("mixin:inventory", "Changes inventory screens", true);
        }
        if (target.startsWith("net.minecraft.client.gui.screens.")) {
            return new Group("mixin:menus", "Changes other menus", false);
        }
        if (target.startsWith("net.minecraft.client.gui.")) {
            return new Group("mixin:hud", "Changes the HUD and on-screen widgets", false);
        }
        if (target.startsWith("net.minecraft.client.renderer.") || target.startsWith("com.mojang.blaze3d.")
                || target.startsWith("net.minecraft.client.model.") || target.startsWith("net.minecraft.client.particle.")) {
            return new Group("mixin:rendering", "Changes how the world is drawn", true);
        }
        if (target.startsWith("net.minecraft.client.sounds.") || target.startsWith("net.minecraft.client.resources.sounds.")) {
            return new Group("mixin:sound", "Changes sound playback", true);
        }
        if (target.startsWith("net.minecraft.client.player.") || target.equals("net.minecraft.client.MouseHandler")
                || target.equals("net.minecraft.client.KeyboardHandler") || target.equals("net.minecraft.client.Camera")) {
            return new Group("mixin:controls", "Changes controls and the camera", true);
        }
        if (target.startsWith("net.minecraft.client.")) {
            return new Group("mixin:client", "Changes other parts of the game client", true);
        }
        if (target.startsWith("net.minecraft.world.entity.")) {
            return new Group("mixin:entities", "Changes mobs and players", true);
        }
        if (target.startsWith("net.minecraft.world.item.") || target.startsWith("net.minecraft.world.inventory.")) {
            return new Group("mixin:items", "Changes items and containers", true);
        }
        if (target.startsWith("net.minecraft.world.")) {
            return new Group("mixin:world", "Changes blocks and world behavior", true);
        }
        if (target.startsWith("net.minecraft.")) {
            return new Group("mixin:core", "Changes game internals", true);
        }
        return new Group("mixin:compat", "Changes other mods", true);
    }

    private static List<Feature> mixins(ModInfo m) throws IOException {
        if (m.mixins().isEmpty()) {
            return List.of();
        }
        Map<String, Group> groups = new LinkedHashMap<>();
        Map<String, List<String>> items = new HashMap<>();
        try (ZipFile zip = new ZipFile(m.jar().toFile())) {
            for (String config : m.mixins()) {
                ZipEntry ce = zip.getEntry(config);
                if (ce == null) {
                    continue;
                }
                JsonObject cfg;
                try (Reader r = new InputStreamReader(zip.getInputStream(ce), StandardCharsets.UTF_8)) {
                    cfg = JsonParser.parseReader(r).getAsJsonObject();
                }
                if (!cfg.has("package")) {
                    continue;
                }
                String pkg = cfg.get("package").getAsString().replace('.', '/');
                for (String list : new String[]{"mixins", "client", "server"}) {
                    if (!cfg.has(list) || !cfg.get(list).isJsonArray()) {
                        continue;
                    }
                    for (JsonElement e : cfg.getAsJsonArray(list)) {
                        String entry = e.getAsString();
                        ZipEntry cls = zip.getEntry(pkg + "/" + entry.replace('.', '/') + ".class");
                        if (cls == null) {
                            continue;
                        }
                        String target;
                        try (InputStream in = zip.getInputStream(cls)) {
                            target = removableTarget(in);
                        }
                        if (target != null) {
                            Group g = groupOf(target);
                            groups.putIfAbsent(g.key(), g);
                            items.computeIfAbsent(g.key(), k -> new ArrayList<>()).add(config + "|" + entry);
                        }
                    }
                }
            }
        }
        List<Feature> out = new ArrayList<>();
        for (Group g : groups.values()) {
            int n = items.get(g.key()).size();
            out.add(new Feature(m.id(), g.key(), g.label(), n + " code hook" + (n == 1 ? "" : "s") + " into the game.",
                    g.risky(), true, List.copyOf(items.get(g.key()))));
        }
        return out;
    }

    /**
     * Returns the class a hook targets, or null when the hook cannot be taken out safely.
     * A hook that is an interface, or adds one to its target, is something the mod's own code calls.
     * Taking that out would crash the mod, so those stay in.
     */
    private static String removableTarget(InputStream in) throws IOException {
        ClassNode node = new ClassNode();
        new ClassReader(in).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG);
        if ((node.access & Opcodes.ACC_INTERFACE) != 0 || !node.interfaces.isEmpty()) {
            return null;
        }
        List<AnnotationNode> all = new ArrayList<>();
        if (node.invisibleAnnotations != null) {
            all.addAll(node.invisibleAnnotations);
        }
        if (node.visibleAnnotations != null) {
            all.addAll(node.visibleAnnotations);
        }
        for (AnnotationNode a : all) {
            if (!"Lorg/spongepowered/asm/mixin/Mixin;".equals(a.desc) || a.values == null) {
                continue;
            }
            for (int i = 0; i + 1 < a.values.size(); i += 2) {
                Object v = a.values.get(i + 1);
                if (v instanceof List<?> list && !list.isEmpty()) {
                    Object first = list.get(0);
                    if (first instanceof Type t) {
                        return t.getClassName();
                    }
                    if (first instanceof String s) {
                        return s.replace('/', '.');
                    }
                }
            }
        }
        return null;
    }
}
