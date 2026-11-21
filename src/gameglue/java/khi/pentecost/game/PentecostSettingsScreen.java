package khi.pentecost.game;

import khi.pentecost.boot.Dirs;
import khi.pentecost.boot.PentecostMain;
import khi.pentecost.game.menuworld.MenuHost;
import khi.pentecost.game.menuworld.SeedCatalog;
import khi.pentecost.game.menuworld.camera.Shot;
import khi.pentecost.game.menuworld.profile.MenuProfile;
import khi.pentecost.game.menuworld.profile.Poi;
import khi.pentecost.game.menuworld.source.SaveSource;
import khi.pentecost.control.Settings;
import net.minecraft.SharedConstants;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Options;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.PlayerModelPart;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

public final class PentecostSettingsScreen extends Screen {
    private enum Kind { INFO, BUTTON, SWITCH, CHOICE, SLIDER, PICK, SKIN, KEY, HEADER }

    private record Slider(int min, int max, int step, IntSupplier value, IntConsumer set, Supplier<String> text) {}

    private record Row(Kind kind, String title, String note, String about, String when, Supplier<String> value,
                       BooleanSupplier on, Runnable action, Slider slider, BooleanSupplier enabled) {
        static Row info(String title, String value) {
            return new Row(Kind.INFO, title, "", "", "", () -> value, null, null, null, () -> true);
        }

        static Row header(String title) {
            return new Row(Kind.HEADER, title, "", "", "", null, null, null, null, () -> true);
        }

        static Row button(String title, String note, Runnable action) {
            return new Row(Kind.BUTTON, title, note, note, "", () -> "Open", null, action, null, () -> true);
        }

        static Row toggle(String title, String note, String when, BooleanSupplier on, Runnable action) {
            return new Row(Kind.SWITCH, title, note, note, when, null, on, action, null, () -> true);
        }

        static Row choice(String title, String note, String when, Supplier<String> value, Runnable action) {
            return new Row(Kind.CHOICE, title, note, note, when, value, null, action, null, () -> true);
        }

        static Row pick(String title, String note, BooleanSupplier on, Runnable action) {
            return new Row(Kind.PICK, title, note, note, "Now", null, on, action, null, () -> true);
        }

        static Row live(String title, String note, Supplier<String> value) {
            return new Row(Kind.INFO, title, note, note, "", value, null, null, null, () -> true);
        }

        static Row act(String title, String note, String label, BooleanSupplier enabled, Runnable action) {
            return new Row(Kind.BUTTON, title, note, note, "", () -> label, null, action, null, enabled);
        }
    }

    private record Folder(String name, List<Row> rows) {}

    private record Shortcut(String label, Runnable action) {}

    private record Page(String name, String about, Supplier<List<Folder>> build, List<String> planned, Supplier<List<Shortcut>> shortcuts) {}

    private record Hit(int x, int y, int w, int h, Runnable action) {}

    private record SliderSpot(int x, int w, Slider slider) {}

    private final Screen parent;
    private final List<Page> pages = new ArrayList<>();
    private final Map<Integer, List<Folder>> built = new HashMap<>();
    private final Map<Integer, Integer> folderOf = new HashMap<>();
    private final List<Hit> hits = new ArrayList<>();
    private final Map<String, SliderSpot> sliderSpots = new HashMap<>();
    private int page = 1;
    private ScreenHarvest harvested;
    private final Scrollbar bar = new Scrollbar();
    private String search = "";
    private boolean searchFocus;
    private String searchBuilt = "\u0000";
    private List<Row> searchOut = List.of();
    private boolean showModPacks;
    private double scroll;
    private int scrollMax;
    private double lastMouseX;
    private String dragKey;
    private Row hovered;
    private Row pickPending;
    private int pickPage = -1;
    private long pickSince;
    private boolean pickReady;
    private Object embeddium;

    public PentecostSettingsScreen(Screen parent) {
        this(parent, "General");
    }

    public PentecostSettingsScreen(Screen parent, String startPage) {
        super(Component.literal("Pentecost settings"));
        this.parent = parent;
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        pages.add(new Page("General", "Versions and folders. Everything Pentecost keeps lives in the game folder.",
                () -> List.of(new Folder("About", List.of(
                        Row.info("Pentecost", PentecostMain.VERSION),
                        Row.info("Minecraft", SharedConstants.getCurrentVersion().getName()),
                        Row.info("Java", System.getProperty("java.version")))),
                        new Folder("Updates", List.of(
                                Row.live("Status", "What the last look at GitHub found.", Updater::statusText),
                                Row.toggle("Look for updates at startup", "Pentecost asks GitHub once when the main menu shows. "
                                        + "Nothing downloads until you press Update.", "Next start",
                                        () -> Settings.on(Settings.UPDATE_CHECK), () -> Settings.toggle(Settings.UPDATE_CHECK)),
                                Row.act("Check now", "Ask GitHub for the newest release.", "Check",
                                        () -> !Updater.busy(), () -> Updater.check(true)),
                                Row.act("Install the update", "Downloads the new installer, checks it, then closes the game so it can install.",
                                        "Update", Updater::available, () -> Updater.ask(this)))),
                        new Folder("Privacy", List.of(
                                Row.toggle("Show Pentecost in Discord", "Your Discord profile shows that you play Pentecost, what you are doing "
                                        + "and for how long. Server addresses and world names are never shown.", "Now",
                                        () -> Settings.on(DiscordStatus.KEY), () -> Settings.toggle(DiscordStatus.KEY))))),
                List.of(),
                () -> List.of(
                        new Shortcut("Game folder", () -> Util.getPlatform().openFile(mc.gameDirectory)),
                        new Shortcut("Logs", () -> Util.getPlatform().openFile(new java.io.File(mc.gameDirectory, "logs"))),
                        new Shortcut("Screenshots", () -> Util.getPlatform().openFile(new java.io.File(mc.gameDirectory, "screenshots"))))));
        pages.add(new Page("Interface", "How Pentecost looks: the window, the menus, the F3 screen and the loading screen.",
                () -> List.of(
                        new Folder("Window", List.of(
                                Row.choice("Window", "Borderless covers the screen with no frame. Switching apps stays instant.", "Now",
                                        () -> WindowMode.label(Settings.get(Settings.WINDOW)),
                                        () -> WindowMode.set(WindowMode.next(Settings.get(Settings.WINDOW)))))),
                        new Folder("Menus", List.of(
                                Row.toggle("Pentecost menus", "Pentecost draws the main and pause menus.", "Now",
                                        () -> Settings.on(Settings.MENUS), () -> Settings.toggle(Settings.MENUS)),
                                Row.toggle("Use the game's own settings screens", "Options, Sound, Chat, Language and the rest open "
                                        + "as the game draws them. Turn it off to come back to this screen.", "Now",
                                        () -> Settings.on(Settings.VANILLA_SETTINGS), () -> Settings.toggle(Settings.VANILLA_SETTINGS)),
                                Row.toggle("Restyle every screen", "Buttons, sliders, checkboxes and backgrounds everywhere, mods' too.", "Now",
                                        () -> Settings.on(Settings.RESTYLE), () -> Settings.toggle(Settings.RESTYLE)),
                                Row.toggle("Mod buttons in black and white", "Buttons mods add to the menus match the rest.", "Now",
                                        () -> Settings.on(Settings.GRAY_BUTTONS), () -> Settings.toggle(Settings.GRAY_BUTTONS)),
                                Row.toggle("Background pattern", "The slow dot pattern behind Pentecost's screens.", "Now",
                                        () -> Settings.on(Settings.PATTERN), () -> Settings.toggle(Settings.PATTERN)))),
                        new Folder("Menu Mods", menuModRows()),
                        new Folder("F3", List.of(
                                Row.toggle("Pentecost F3 screen", "Readable panels in place of the long text columns.", "Now",
                                        () -> Settings.on(Settings.OVERLAY), () -> Settings.toggle(Settings.OVERLAY)),
                                Row.choice("F3 detail", "Standard shows the usual facts. Full shows every line.", "Now",
                                        () -> "full".equals(Settings.get(Settings.OVERLAY_DETAIL)) ? "Full" : "Standard",
                                        () -> Settings.set(Settings.OVERLAY_DETAIL,
                                                "full".equals(Settings.get(Settings.OVERLAY_DETAIL)) ? "standard" : "full")))),
                        new Folder("Loading", List.of(
                                Row.toggle("Pentecost loading screen", "Logo, progress line, RAM gauge and time left while loading.", "Now",
                                        () -> Settings.on(Settings.LOADING), () -> Settings.toggle(Settings.LOADING)))),
                        new Folder("Pause camera", List.of(
                                Row.toggle("Cinematic pause camera", "While the game is paused the camera leaves your eyes and films you. "
                                        + "Press V in the pause menu to switch it too.", "Now",
                                        () -> Settings.on(Settings.PAUSE_CAMERA), () -> Settings.toggle(Settings.PAUSE_CAMERA)),
                                Row.choice("Pause camera style", "Cinematic plays orbits, cranes, push-ins, low shots and more. "
                                        + "Orbit only turns slowly around you.", "Now",
                                        () -> "orbit".equals(Settings.get(Settings.PAUSE_CAMERA_STYLE)) ? "Orbit only" : "Cinematic",
                                        () -> Settings.set(Settings.PAUSE_CAMERA_STYLE,
                                                "orbit".equals(Settings.get(Settings.PAUSE_CAMERA_STYLE)) ? "cinematic" : "orbit")))),
                        new Folder("Pentecost cape", List.of(
                                Row.toggle("Animated Pentecost cape", "The moving cape with the gas giant, the cloud sea and the "
                                        + "boat that rides it. Other Pentecost players see it on you, and you see it on them. "
                                        + "Off shows the game's own capes.", "Now",
                                        () -> Settings.on(PentecostCape.SETTING), () -> Settings.toggle(PentecostCape.SETTING)))),
                        new Folder("Name tags", List.of(
                                Row.choice("Name tag style", "Pentecost draws the name in plain letters with a hairline under it. "
                                        + "Game keeps the usual black box.", "Now",
                                        () -> "vanilla".equals(Settings.get(Settings.NAME_TAGS)) ? "Game" : "Pentecost",
                                        () -> Settings.set(Settings.NAME_TAGS,
                                                "vanilla".equals(Settings.get(Settings.NAME_TAGS)) ? "pentecost" : "vanilla")),
                                Row.choice("Tag length", "Short keeps the hairline and the DEV tag small. Long opens them fully. "
                                        + "Follow gaze opens them when you look at a player or stand close.", "Now",
                                        () -> switch (Settings.get(Settings.TAG_LENGTH)) {
                                            case "short" -> "Short";
                                            case "long" -> "Long";
                                            default -> "Follow gaze";
                                        },
                                        () -> Settings.set(Settings.TAG_LENGTH, switch (Settings.get(Settings.TAG_LENGTH)) {
                                            case "short" -> "long";
                                            case "long" -> "auto";
                                            default -> "short";
                                        }))))),
                List.of("Boot splash style", "Text size"), List::of));
        pages.add(new Page("Video", "Your video options. Embeddium adds many more when it is installed. Changes apply at once.",
                this::videoFolders, List.of(), this::videoShortcuts));
        pages.add(new Page("Shaders", "Pick a shader pack, or Off. Pentecost drives Oculus. It is not compatible with Sodium or Iris.",
                this::shaderFolders, List.of(), this::shaderShortcuts));
        pages.add(new Page("Shader pack", "The options of the pack in use, grouped the way the pack groups them. "
                + "Changes wait until you press Apply, then the pack reloads once.",
                this::packFolders, List.of(), this::packShortcuts));
        pages.add(new Page("Controls", "Mouse, movement and every key bind, mods' included. Click a key, then press the new one. "
                + "Escape clears it. A ! marks a key two actions share.",
                this::controlFolders, List.of(), this::controlShortcuts));
        pages.add(new Page("Skin", "Your skin and which layers show. The skin comes from your account.",
                this::skinFolders, List.of(),
                List::of));
        pages.add(new Page("Resource packs", "Turn packs on or off. Shift-click a pack in use to give it more priority. "
                + "Changes apply when you press Done.",
                this::resourcePackFolders, List.of(), this::resourcePackShortcuts));
        pages.add(new Page("Performance", "Ways Pentecost shortens the server tick, the time the game spends on the world each tick.",
                () -> List.of(new Folder("Server tick", List.of(
                        Row.toggle("Far mob throttle", "Mobs more than 48 blocks from every player think every third tick. "
                                + "Mobs near you, with a target, leashed or ridden are never slowed.", "Now",
                                () -> Settings.on(Settings.FAR_MOBS), () -> Settings.toggle(Settings.FAR_MOBS)),
                        Row.toggle("Spread chunk saving", "Chunks you leave unload a few at a time, so one tick does not "
                                + "save dozens of them at once.", "Restart",
                                () -> Settings.on(Settings.SPREAD_UNLOADS), () -> Settings.toggle(Settings.SPREAD_UNLOADS))))),
                List.of("Launch cache", "Chunk building threads", "Virtual threads on Java 25"),
                () -> List.of(new Shortcut("Mod impact", () -> minecraft.setScreen(new ModImpactScreen(this))))));
        pages.add(new Page("Java", "Memory, garbage collector and extra flags for the next launch. "
                + "Pentecost reads them each time it starts the game.",
                this::javaFolders, List.of(), this::javaShortcuts));
        pages.add(new Page("Mods", "Settings of the mods in this pack, as Pentecost rows. Pick a mod.",
                this::modsFolders, List.of(), List::of));
        if (startPage.startsWith("mod:")) {
            Page p = modPage(startPage.substring(4));
            if (p != null) {
                pages.add(p);
                startPage = p.name();
            }
        }
        pages.add(new Page("Mod loading", "Mods made for more than one loader in a single jar. Pick which side "
                + "Pentecost loads. Applies at the next launch.",
                this::modLoadingFolders, List.of(), List::of));
        pages.add(new Page("Gameplay", "The F5 camera, mob thinking, movement and the stall warning.",
                this::gameplayFolders, List.of(), List::of));
        pages.add(new Page("Building", "How the block you look at is marked.",
                this::buildingFolders, List.of(), List::of));
        pages.add(new Page("Server", "How much of the world runs around you, and what the server spends on it.",
                this::serverFolders, List.of(), List::of));
        pages.add(new Page("Sound", "Volume, limits, and muting by mod or by sound. The lists fill as sounds play.",
                this::soundFolders, List.of(), this::soundShortcuts));
        pages.add(new Page("Chat", "How chat looks and behaves.", this::chatFolders, List.of(), List::of));
        pages.add(new Page("Accessibility", "Reading, motion and glint options.", this::accessibilityFolders, List.of(), List::of));
        pages.add(new Page("Language", "The game's language. The game reloads its text when you choose one.",
                this::languageFolders, List.of(), List::of));
        pages.add(new Page("Menu world", "The live world behind the main menu: what it shows, where the camera goes and when. "
                + "Changes show when you go back to the main menu.",
                this::menuWorldFolders, List.of(), this::menuWorldShortcuts));
        pages.add(new Page("Profiles", "Save every Pentecost setting as a setup, switch between setups, and share them as files.",
                this::profileFolders, List.of(), this::profileShortcuts));
        for (int i = 0; i < pages.size(); i++) {
            if (pages.get(i).name().equalsIgnoreCase(startPage)) {
                page = i;
            }
        }
    }

    private List<Row> menuModRows() {
        List<Row> rows = new ArrayList<>();
        List<MenuMods.Entry> entries = MenuMods.entries();
        for (MenuMods.Entry e : entries) {
            rows.add(Row.pick(e.title(), e.note(), () -> MenuMods.selected().equals(e.id()), e.select()));
        }
        if (entries.size() == 1) {
            rows.add(Row.info("Menus from mods", "None found"));
        }
        rows.add(Row.choice("Pentecost button", "The small button on a mod's main menu that opens Mods, Join a friend "
                + "and Settings. Pick the corner it sits in, or turn it off. It moves when the menu opens.", "Now",
                MenuMods::placeLabel, MenuMods::nextPlace));
        return rows;
    }

    private Runnable menuAfter;
    private int placeDx, placeDz;

    private static final List<Shot.MoveType> MOVES = List.of(Shot.MoveType.ORBIT, Shot.MoveType.DOLLY, Shot.MoveType.CRANE,
            Shot.MoveType.PUSH_IN, Shot.MoveType.FLY_THROUGH, Shot.MoveType.REVEAL, Shot.MoveType.ESTABLISH);

    private static MenuProfile mp() {
        return MenuHost.profile();
    }

    private static String enumLabel(String name) {
        String t = name.toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(t.charAt(0)) + t.substring(1);
    }

    private <E extends Enum<E>> Row menuEnum(String title, String note, String when, Supplier<E> get,
                                             java.util.function.Consumer<E> set, Runnable after) {
        return Row.choice(title, note, when, () -> enumLabel(get.get().name()), () -> {
            E[] all = get.get().getDeclaringClass().getEnumConstants();
            set.accept(all[(get.get().ordinal() + 1) % all.length]);
            after.run();
        });
    }

    private Row menuSlider(String title, String note, String when, int min, int max, int step, IntSupplier value,
                           IntConsumer set, Supplier<String> text, BooleanSupplier enabled, Runnable after) {
        return new Row(Kind.SLIDER, title, note, note, when, text, null, null,
                new Slider(min, max, step, value, n -> {
                    set.accept(n);
                    menuAfter = after;
                }, text), enabled);
    }

    private Row menuToggle(String title, String note, String when, BooleanSupplier on, Runnable flip, Runnable after) {
        return Row.toggle(title, note, when, on, () -> {
            flip.run();
            after.run();
        });
    }

    private static String seedLabel() {
        for (SeedCatalog.Entry e : SeedCatalog.ALL) {
            if (e.seed() == mp().seed) return e.name();
        }
        return "Seed " + mp().seed;
    }

    private static void nextSeed() {
        MenuProfile p = mp();
        int at = -1;
        for (int i = 0; i < SeedCatalog.ALL.size(); i++) {
            if (SeedCatalog.ALL.get(i).seed() == p.seed) at = i;
        }
        SeedCatalog.Entry e = SeedCatalog.ALL.get((at + 1) % SeedCatalog.ALL.size());
        p.sourceKind = MenuProfile.SourceKind.SEED;
        p.seed = e.seed();
        p.centerMode = MenuProfile.CenterMode.COORDS;
        p.centerX = e.anchorX();
        p.centerZ = e.anchorZ();
        MenuHost.worldChanged();
    }

    private static String saveLabel() {
        String t = mp().savePath;
        if (t == null || t.isBlank()) return "None picked";
        try {
            java.nio.file.Path name = java.nio.file.Path.of(t).getFileName();
            return name == null ? t : name.toString();
        } catch (java.nio.file.InvalidPathException e) {
            return t;
        }
    }

    private void nextSave() {
        MenuProfile p = mp();
        List<SaveSource.Info> usable = new ArrayList<>();
        for (SaveSource.Info i : SaveSource.list(minecraft.getLevelSource().getBaseDir())) {
            if (i.usable()) usable.add(i);
        }
        if (usable.isEmpty()) return;
        int at = -1;
        for (int i = 0; i < usable.size(); i++) {
            if (usable.get(i).folder().toString().equals(p.savePath)) at = i;
        }
        SaveSource.Info pick = usable.get((at + 1) % usable.size());
        if (p.sourceKind == MenuProfile.SourceKind.SEED) p.targets = MenuProfile.Targets.PLAYER_BUILDS;
        p.sourceKind = MenuProfile.SourceKind.SAVE;
        p.savePath = pick.folder().toString();
        p.centerMode = MenuProfile.CenterMode.SPAWN;
        MenuHost.worldChanged();
    }

    private static boolean moveOn(Shot.MoveType t) {
        List<String> m = mp().moves;
        return m.isEmpty() || m.contains(t.name());
    }

    private static void flipMove(Shot.MoveType t) {
        List<String> now = new ArrayList<>();
        for (Shot.MoveType x : MOVES) {
            if (moveOn(x)) now.add(x.name());
        }
        if (moveOn(t)) {
            if (now.size() > 1) now.remove(t.name());
        } else {
            now.add(t.name());
        }
        mp().moves = now.size() == MOVES.size() ? new ArrayList<>() : now;
    }

    private static String clockText(int ticks) {
        int minutes = (int) (((ticks / 1000.0) + 6) * 60) % (24 * 60);
        return String.format(Locale.ROOT, "%02d:%02d", minutes / 60, minutes % 60);
    }

    private List<Row> placeRows() {
        MenuProfile p = mp();
        List<Row> rows = new ArrayList<>();
        int shown = 0;
        for (Poi poi : new ArrayList<>(p.points)) {
            if (shown++ >= 60) {
                rows.add(Row.info("More places", (p.points.size() - 60) + " more are in the profile file"));
                break;
            }
            String kind = switch (poi.kind) {
                case STRUCTURE -> "Found in the world";
                case BUILD -> "A player's build, " + Math.round(poi.playerMinutes) + " min of play nearby";
                case CUSTOM -> "Added by you";
            };
            rows.add(Row.toggle(poi.name + " (" + poi.x + ", " + poi.z + ")", kind + ", about " + poi.sizeX + " blocks wide. "
                    + "Turn it off and the camera skips it.", "Now",
                    () -> poi.enabled, () -> {
                        poi.enabled = !poi.enabled;
                        MenuHost.directorChanged();
                    }));
        }
        if (rows.isEmpty()) {
            rows.add(Row.info("Places", "None yet. They are found when the world is first read."));
        }
        return rows;
    }

    private List<Folder> menuWorldFolders() {
        Runnable reload = MenuHost::worldChanged;
        Runnable director = MenuHost::directorChanged;
        BooleanSupplier always = () -> true;
        BooleanSupplier seedWorld = () -> mp().sourceKind == MenuProfile.SourceKind.SEED;
        BooleanSupplier saveWorld = () -> mp().sourceKind == MenuProfile.SourceKind.SAVE;
        BooleanSupplier typedCentre = () -> mp().centerMode == MenuProfile.CenterMode.COORDS;

        List<Row> world = new ArrayList<>();
        world.add(Row.toggle("Live menu world", "Draws a real world behind the main menu in place of the turning panorama. "
                + "It is off until you turn it on. If it ever fails, it turns itself off and the panorama comes back.", "Now",
                MenuHost::enabled, () -> MenuHost.setEnabled(!MenuHost.enabled())));
        world.add(new Row(Kind.INFO, "Status", "", "", "", MenuHost::status, null, null, null, always));
        world.add(menuEnum("Source", "A seed is generated once and kept. A save is read where it is and never changed.", "Reload",
                () -> mp().sourceKind, v -> mp().sourceKind = v, reload));
        world.add(new Row(Kind.CHOICE, "Seed", "Picks the next seed from the list. Each one is generated once, then kept.", 
                "Picks the next seed from the list. Each one is generated once, then kept. To use a seed that is not in the list, "
                        + "put it in the profile file in the menu_world folder.", "Reload",
                MenuHostLabels::seed, null, MenuHostLabels::nextSeedAction, null, seedWorld));
        world.add(new Row(Kind.CHOICE, "World", "Picks the next of your saves. Your save is only read.",
                "Picks the next of your saves that this reader can use (Minecraft 1.18 to 1.20.1). Your save is only read, never changed.",
                "Reload", MenuHostLabels::save, null, this::nextSave, null, () -> true));
        world.add(new Row(Kind.BUTTON, "Agree to the Minecraft EULA", "Generating a seed runs Mojang's server once, which needs "
                + "you to agree to the Minecraft EULA (aka.ms/MinecraftEULA). Nothing is generated before you agree.",
                "Generating a seed runs Mojang's server once, which needs you to agree to the Minecraft EULA "
                        + "(aka.ms/MinecraftEULA). Nothing is generated before you agree.", "Reload",
                () -> mp().eulaAccepted ? "Agreed" : "Agree", null, () -> {
                    mp().eulaAccepted = true;
                    MenuHost.worldChanged();
                }, null, () -> !mp().eulaAccepted && seedWorld.getAsBoolean()));
        world.add(menuEnum("Centre", "Spawn centres on where the world starts. Typed centres on the numbers below.", "Reload",
                () -> mp().centerMode, v -> mp().centerMode = v, reload));
        world.add(new Row(Kind.BUTTON, "Use this seed's best spot", "Centres on the place the seed list recommends and sets the area "
                + "to 32 chunks. A world's own spawn can be somewhere dull, like a frozen plain.",
                "Centres on the place the seed list recommends and sets the area to 32 chunks. A world's own spawn can be "
                        + "somewhere dull, like a frozen plain. It is generated once and kept.", "Reload",
                () -> "Centre", null, () -> {
                    for (SeedCatalog.Entry e : SeedCatalog.ALL) {
                        if (e.seed() == mp().seed) {
                            mp().centerMode = MenuProfile.CenterMode.COORDS;
                            mp().centerX = e.anchorX();
                            mp().centerZ = e.anchorZ();
                            mp().radiusChunks = 32;
                        }
                    }
                    MenuHost.worldChanged();
                }, null, () -> seedWorld.getAsBoolean() && SeedCatalog.ALL.stream().anyMatch(e -> e.seed() == mp().seed)));
        world.add(menuSlider("Centre X", "The block the area is centred on, east and west.", "Reload", -30000, 30000, 16,
                () -> mp().centerX, n -> mp().centerX = n, () -> String.valueOf(mp().centerX), typedCentre, reload));
        world.add(menuSlider("Centre Z", "The block the area is centred on, north and south.", "Reload", -30000, 30000, 16,
                () -> mp().centerZ, n -> mp().centerZ = n, () -> String.valueOf(mp().centerZ), typedCentre, reload));
        world.add(menuSlider("Radius", "How far from the centre the camera may go. A bigger area is generated once and takes a long time: "
                + "32 is about 2 minutes, 64 about 8. The loaded-chunks slider below costs no generation.", "Reload",
                8, 64, 4, () -> mp().radiusChunks, n -> mp().radiusChunks = n, () -> mp().radiusChunks + " chunks", always, reload));
        world.add(menuSlider("Loaded around the camera", "Chunks really loaded around the camera. Higher looks farther and costs more memory. "
                + "The game's render distance still caps what is drawn.", "Reload",
                4, 24, 1, () -> mp().viewChunks, n -> mp().viewChunks = n, () -> mp().viewChunks + " chunks", always, reload));
        world.add(menuToggle("Search the whole save", "Looks at every part of a save for builds, not only the area around the centre.",
                "Reload", () -> mp().scanWholeSave, () -> mp().scanWholeSave = !mp().scanWholeSave, reload));
        world.add(Row.button("Look for places again", "Scans the world again for villages, other structures and player builds. "
                + "Places you edited or added keep your changes.", MenuHost::rescan));

        List<Row> camera = new ArrayList<>();
        camera.add(menuEnum("Where it goes", "All enabled visits every place that is on. Player builds prefers what a player made. "
                + "Region only ignores the list and finds views itself.", "Now",
                () -> mp().targets, v -> mp().targets = v, director));
        camera.add(menuSlider("Shortest shot", "The least time the camera stays on one view.", "Now", 4, 60, 1,
                () -> (int) Math.round(mp().shotSecondsMin), n -> {
                    mp().shotSecondsMin = n;
                    mp().shotSecondsMax = Math.max(mp().shotSecondsMax, n);
                }, () -> Math.round(mp().shotSecondsMin) + " s", always, director));
        camera.add(menuSlider("Longest shot", "The most time the camera stays on one view.", "Now", 4, 120, 1,
                () -> (int) Math.round(mp().shotSecondsMax), n -> {
                    mp().shotSecondsMax = n;
                    mp().shotSecondsMin = Math.min(mp().shotSecondsMin, n);
                }, () -> Math.round(mp().shotSecondsMax) + " s", always, director));
        camera.add(menuSlider("Handheld shake", "0 holds the camera still. 100% is the normal gentle drift. 200% doubles it.", "Now",
                0, 200, 10, () -> (int) Math.round(mp().handheld * 100), n -> mp().handheld = n / 100.0,
                () -> Math.round(mp().handheld * 100) + "%", always, director));
        camera.add(menuSlider("Least height", "The camera stays at least this far above the ground and water.", "Now", 3, 40, 1,
                () -> (int) Math.round(mp().clearance), n -> mp().clearance = n, () -> Math.round(mp().clearance) + " blocks", always, director));
        camera.add(menuSlider("Highest height", "The camera stays at most this far above the ground. Lower means less land to load and draw.", "Now", 12, 120, 2,
                () -> (int) Math.round(mp().maxHeight), n -> mp().maxHeight = n, () -> Math.round(mp().maxHeight) + " blocks", always, director));
        camera.add(menuSlider("Fade at cuts", "How long the screen fades to black at each cut.", "Now", 2, 30, 1,
                () -> (int) Math.round(mp().fadeSeconds * 10), n -> mp().fadeSeconds = n / 10.0,
                () -> String.format(Locale.ROOT, "%.1f s", mp().fadeSeconds), always, director));
        camera.add(menuSlider("Field of view", "Degrees added to the field of view of every shot.", "Now", -30, 30, 1,
                () -> (int) Math.round(mp().fovOffset), n -> mp().fovOffset = n,
                () -> (mp().fovOffset > 0 ? "+" : "") + Math.round(mp().fovOffset) + "\u00B0", always, director));
        for (Shot.MoveType t : MOVES) {
            camera.add(menuToggle("Move: " + enumLabel(t.name()), "Lets the camera use this move. At least one stays on.", "Now",
                    () -> moveOn(t), () -> flipMove(t), director));
        }

        List<Row> time = new ArrayList<>();
        time.add(menuEnum("Time of day", "Cycle runs a day in the length below. Real clock follows your clock. Fixed holds one time.", "Now",
                () -> mp().timeMode, v -> mp().timeMode = v, director));
        time.add(menuSlider("Day length", "How long a whole day lasts in real time.", "Now", 1, 120, 1,
                () -> (int) Math.round(mp().secondsPerDay / 60), n -> mp().secondsPerDay = n * 60.0,
                () -> Math.round(mp().secondsPerDay / 60) + " min", () -> mp().timeMode == MenuProfile.TimeMode.CYCLE, director));
        time.add(menuSlider("Start time", "The time of day the menu starts at, or holds in Fixed.", "Now", 0, 23750, 250,
                () -> mp().startTicks, n -> mp().startTicks = n, () -> clockText(mp().startTicks),
                () -> mp().timeMode != MenuProfile.TimeMode.REAL_CLOCK, director));

        List<Row> places = new ArrayList<>(placeRows());
        places.add(new Row(Kind.HEADER, "Add a place", "", "", "", null, null, null, null, always));
        places.add(menuSlider("East and west of the centre", "Where the new place goes, in blocks from the centre.", "Now",
                -2048, 2048, 16, () -> placeDx, n -> placeDx = n, () -> placeDx + " blocks", always, () -> { }));
        places.add(menuSlider("North and south of the centre", "Where the new place goes, in blocks from the centre.", "Now",
                -2048, 2048, 16, () -> placeDz, n -> placeDz = n, () -> placeDz + " blocks", always, () -> { }));
        places.add(Row.button("Add a place here", "Adds a place at those numbers. The camera will visit it like any other.", () -> {
            MenuProfile p = mp();
            int[] c = MenuHost.centre();
            int x = c[0] + placeDx, z = c[1] + placeDz;
            p.addCustom("Place " + (p.points.size() + 1), x, MenuHost.surfaceY(x, z), z, 24);
            MenuHost.directorChanged();
        }));
        places.add(Row.button("Remove the places I added", "Removes every place you added by hand. Places found in the world stay.", () -> {
            mp().points.removeIf(q -> q.kind == Poi.Kind.CUSTOM);
            MenuHost.directorChanged();
        }));

        List<Row> life = List.of(menuToggle("Animals", "Animals that live in each biome walk around in the distance, and a few "
                + "monsters at night. Nothing ever comes close to the camera.", "Now", () -> mp().mobs, () -> mp().mobs = !mp().mobs, director));

        return List.of(new Folder("World", world), new Folder("Camera", camera), new Folder("Time", time),
                new Folder("Places", places), new Folder("Life", life));
    }

    private List<Shortcut> menuWorldShortcuts() {
        return List.of(new Shortcut("Menu world folder",
                () -> Util.getPlatform().openFile(Dirs.mk(Dirs.config().resolve("pentecost").resolve("menu_world")).toFile())));
    }

    private static final class MenuHostLabels {
        static String seed() {
            return seedLabel();
        }

        static String save() {
            return saveLabel();
        }

        static void nextSeedAction() {
            nextSeed();
        }
    }

    private static Page planned(String name, String... items) {
        return new Page(name, "Coming to Pentecost.", List::of, List.of(items), List::of);
    }

    private List<Folder> folders(int index) {
        if (pages.get(index).name().equals("Menu world")) {
            return pages.get(index).build().get();
        }
        return built.computeIfAbsent(index, i -> pages.get(i).build().get());
    }

    private KeyMapping listening;

    private static String folderFor(String category) {
        return switch (category) {
            case "key.categories.movement" -> "Movement";
            case "key.categories.gameplay" -> "Gameplay";
            case "key.categories.inventory", "key.categories.creative" -> "Inventory";
            case "key.categories.multiplayer", "key.categories.ui", "key.categories.misc" -> "Interface";
            case "Pentecost" -> "Pentecost";
            default -> "Mods";
        };
    }

    private List<Folder> controlFolders() {
        Options o = minecraft.options;
        List<Folder> out = new ArrayList<>();
        out.add(new Folder("Mouse", List.of(
                new Row(Kind.SLIDER, "Mouse sensitivity", "How far the view turns for each move of the mouse.",
                        "How far the view turns for each move of the mouse. While you zoom, it slows down by itself.", "Now",
                        () -> (int) Math.round(o.sensitivity().get() * 200) + "%", null, null,
                        new Slider(0, 200, 1, () -> (int) Math.round(o.sensitivity().get() * 200),
                                n -> o.sensitivity().set(n / 200.0), () -> (int) Math.round(o.sensitivity().get() * 200) + "%"),
                        () -> true),
                optionToggle("Invert mouse", "Pushing the mouse forward looks down.", o.invertYMouse()),
                optionToggle("Smooth scrolling off", "Each wheel notch moves exactly one step.", o.discreteMouseScroll()),
                optionToggle("Toggle sneak", "Press once to sneak, again to stop.", o.toggleCrouch()),
                optionToggle("Toggle sprint", "Press once to sprint, again to stop.", o.toggleSprint()),
                optionToggle("Auto jump", "Step up one block without pressing jump.", o.autoJump()))));
        Map<String, Map<String, List<Row>>> groups = new LinkedHashMap<>();
        for (String name : List.of("Movement", "Gameplay", "Inventory", "Interface", "Pentecost", "Mods")) {
            groups.put(name, new LinkedHashMap<>());
        }
        for (KeyMapping k : o.keyMappings) {
            groups.get(folderFor(k.getCategory())).computeIfAbsent(k.getCategory(), c -> new ArrayList<>()).add(keyRow(k));
        }
        for (Map.Entry<String, Map<String, List<Row>>> group : groups.entrySet()) {
            List<Row> rows = new ArrayList<>();
            boolean headers = group.getKey().equals("Mods") || group.getKey().equals("Interface");
            for (Map.Entry<String, List<Row>> category : group.getValue().entrySet()) {
                if (headers) {
                    rows.add(Row.header(I18n.get(category.getKey())));
                }
                rows.addAll(category.getValue());
            }
            if (!rows.isEmpty()) {
                out.add(new Folder(group.getKey(), rows));
            }
        }
        return out;
    }

    private Row optionToggle(String title, String note, net.minecraft.client.OptionInstance<Boolean> option) {
        return Row.toggle(title, note, "Now", () -> Boolean.TRUE.equals(option.get()), () -> {
            option.set(!Boolean.TRUE.equals(option.get()));
            minecraft.options.save();
        });
    }

    private Row keyRow(KeyMapping k) {
        String about = "Default key: " + k.getDefaultKey().getDisplayName().getString()
                + ". Click, then press the new key or a mouse button. Escape clears it.";
        return new Row(Kind.KEY, I18n.get(k.getName()), "", about, "Now", () -> {
            if (listening == k) {
                return "Press a key...";
            }
            return k.getTranslatedKeyMessage().getString();
        }, () -> conflicts(k), () -> listening = listening == k ? null : k, null, () -> true);
    }

    private boolean conflicts(KeyMapping k) {
        if (k.isUnbound()) {
            return false;
        }
        for (KeyMapping other : minecraft.options.keyMappings) {
            if (other != k && k.same(other)) {
                return true;
            }
        }
        return false;
    }

    private void bindListening(InputConstants.Key key) {
        KeyMapping k = listening;
        listening = null;
        if (k == null) {
            return;
        }
        minecraft.options.setKey(k, key);
        KeyMapping.resetMapping();
        minecraft.options.save();
    }

    private List<Shortcut> controlShortcuts() {
        return List.of(new Shortcut("Reset all keys", () -> {
            for (KeyMapping k : minecraft.options.keyMappings) {
                k.setKey(k.getDefaultKey());
            }
            KeyMapping.resetMapping();
            minecraft.options.save();
        }));
    }

    private int keyRowDraw(GuiGraphics g, Row r, int x, int y, int w, int mx, int my, int top, int bottom) {
        int h = 22;
        boolean visible = y + h > top && y < bottom;
        boolean inside = my >= top && my < bottom;
        boolean over = visible && inside && Ui.over(mx, my, x, y, w, h);
        boolean waiting = r.value().get().startsWith("Press");
        boolean clash = r.on().getAsBoolean();
        if (over) {
            hovered = r;
            g.fill(x, y, x + w, y + h, Ui.HOVER);
        }
        g.drawString(font, font.plainSubstrByWidth(r.title(), w - 150), x + 4, y + 7, Ui.TEXT, false);
        String v = r.value().get();
        int bw = Math.max(90, Math.min(140, font.width(v) + 18));
        int bx = x + w - bw - 4;
        int color = clash ? 0xFFE08080 : waiting ? Ui.TEXT : Ui.MUTED;
        if (waiting) {
            g.fill(bx, y + 2, bx + bw, y + h - 2, Ui.TEXT);
        } else {
            Ui.outline(g, bx, y + 2, bx + bw, y + h - 2, over ? Ui.TEXT : color);
        }
        String shown = font.plainSubstrByWidth(v, bw - 10);
        g.drawString(font, shown, bx + bw / 2 - font.width(shown) / 2, y + 7,
                waiting ? Ui.BLACK : clash ? 0xFFE08080 : Ui.SOFT, false);
        if (clash) {
            g.drawString(font, "shared", bx - 6 - font.width("shared"), y + 7, 0xFFE08080, false);
        }
        g.fill(x, y + h, x + w, y + h + 1, Ui.LINE);
        if (visible && inside) {
            hits.add(new Hit(x, Math.max(y, top), w, Math.min(y + h, bottom) - Math.max(y, top), r.action()));
        }
        return y + h + 1;
    }

    private List<Folder> skinFolders() {
        Options o = minecraft.options;
        List<Row> layers = new ArrayList<>();
        for (PlayerModelPart part : PlayerModelPart.values()) {
            layers.add(Row.toggle(part.getName().getString(), "A layer of your skin.", "Now",
                    () -> o.isModelPartEnabled(part), () -> {
                        o.toggleModelPart(part, !o.isModelPartEnabled(part));
                        o.save();
                    }));
        }
        layers.add(Row.choice("Main hand", "The hand that holds items.", "Now",
                () -> o.mainHand().get() == HumanoidArm.LEFT ? "Left" : "Right",
                () -> {
                    o.mainHand().set(o.mainHand().get().getOpposite());
                    o.save();
                }));
        Row preview = new Row(Kind.SKIN, minecraft.getUser().getName(), "", "", "", () -> minecraft.getUser().getName(),
                null, null, null, () -> true);
        return List.of(new Folder("Preview", List.of(preview)), new Folder("Layers", layers));
    }

    private int skinRow(GuiGraphics g, int x, int y, int w) {
        ResourceLocation skin = AccountSkin.location();
        if (skin == null) {
            skin = minecraft.getSkinManager().getInsecureSkinLocation(minecraft.getUser().getGameProfile());
        }
        int s = 4;
        int cx = x + w / 2 - 8 * s / 2;
        RenderSystem.enableBlend();
        for (int layer = 0; layer < 2; layer++) {
            int dx = layer == 0 ? 0 : 0;
            int[][] parts = layer == 0
                    ? new int[][] {{0, 0, 8, 8, 8, 8}, {0, 8, 8, 12, 20, 20}, {-4, 8, 4, 12, 44, 20}, {8, 8, 4, 12, 36, 52},
                            {0, 20, 4, 12, 4, 20}, {4, 20, 4, 12, 20, 52}}
                    : new int[][] {{0, 0, 8, 8, 40, 8}, {0, 8, 8, 12, 20, 36}, {-4, 8, 4, 12, 44, 36}, {8, 8, 4, 12, 52, 52},
                            {0, 20, 4, 12, 4, 36}, {4, 20, 4, 12, 4, 52}};
            for (int[] p : parts) {
                g.blit(skin, cx + p[0] * s + dx, y + 8 + p[1] * s, p[2] * s, p[3] * s, p[4], p[5], p[2], p[3], 64, 64);
            }
        }
        RenderSystem.disableBlend();
        String name = minecraft.getUser().getName();
        g.drawString(font, name, x + w / 2 - font.width(name) / 2, y + 8 + 32 * s + 8, Ui.TEXT, false);
        return y + 8 + 32 * s + 28;
    }

    private net.minecraft.client.gui.screens.packs.PackSelectionModel packModel;
    private final Map<String, ResourceLocation> packIcons = new HashMap<>();
    private boolean packsChanged;

    private net.minecraft.client.gui.screens.packs.PackSelectionModel packModel() {
        if (packModel == null) {
            packModel = new net.minecraft.client.gui.screens.packs.PackSelectionModel(() -> packsChanged = true,
                    this::packIcon, minecraft.getResourcePackRepository(), repo -> minecraft.options.updateResourcePacks(repo));
        }
        return packModel;
    }

    private ResourceLocation packIcon(Pack pack) {
        return packIcons.computeIfAbsent(pack.getId(), id -> {
            ResourceLocation fallback = new ResourceLocation("textures/misc/unknown_pack.png");
            try (net.minecraft.server.packs.PackResources resources = pack.open()) {
                net.minecraft.server.packs.resources.IoSupplier<java.io.InputStream> icon = resources.getRootResource("pack.png");
                if (icon == null) {
                    return fallback;
                }
                try (java.io.InputStream in = icon.get()) {
                    com.mojang.blaze3d.platform.NativeImage image = com.mojang.blaze3d.platform.NativeImage.read(in);
                    ResourceLocation location = new ResourceLocation("pentecost", "pack/" + Integer.toHexString(id.hashCode()) + "/icon");
                    minecraft.getTextureManager().register(location, new net.minecraft.client.renderer.texture.DynamicTexture(image));
                    return location;
                }
            } catch (java.io.IOException | RuntimeException e) {
                return fallback;
            }
        });
    }

    private List<Folder> resourcePackFolders() {
        return List.of(new Folder("Packs", List.of()));
    }

    private boolean modPack(net.minecraft.client.gui.screens.packs.PackSelectionModel.Entry e) {
        String id = e.getId();
        return id.startsWith("pentecost/") || id.startsWith("mod:") || (e.isRequired() && !id.equals("vanilla"));
    }

    private List<Shortcut> resourcePackShortcuts() {
        return List.of(
                new Shortcut("Get packs", () -> minecraft.setScreen(new PackStoreScreen(this, khi.pentecost.packs.PackStore.Kind.RESOURCE,
                        () -> minecraft.getResourcePackDirectory(), () -> {
                            minecraft.getResourcePackRepository().reload();
                            packModel = null;
                        }))),
                new Shortcut(showModPacks ? "Hide mod packs" : "Show mod packs", () -> showModPacks = !showModPacks),
                new Shortcut("Pack folder", () -> Util.getPlatform().openFile(minecraft.getResourcePackDirectory().toFile())),
                new Shortcut("Refresh list", () -> {
                    if (packsChanged) {
                        applyPacks();
                    }
                    minecraft.getResourcePackRepository().reload();
                    packModel = null;
                }));
    }

    private void applyPacks() {
        if (!packsChanged || packModel == null) {
            return;
        }
        packsChanged = false;
        packModel.commit();
    }

    private int packColumns(GuiGraphics g, int x, int y, int w, int mx, int my, int top, int bottom) {
        var model = packModel();
        int gap = 16;
        int colW = (w - gap) / 2;
        int rx = x + colW + gap;
        List<net.minecraft.client.gui.screens.packs.PackSelectionModel.Entry> left = model.getUnselected()
                .filter(e -> showModPacks || !modPack(e)).toList();
        List<net.minecraft.client.gui.screens.packs.PackSelectionModel.Entry> right = model.getSelected()
                .filter(e -> showModPacks || !modPack(e)).toList();
        long hiddenCount = model.getSelected().filter(this::modPack).count() + model.getUnselected().filter(this::modPack).count();
        g.drawString(font, "AVAILABLE", x + 4, y, Ui.FAINT, false);
        g.drawString(font, "IN USE  (the top one wins)", rx + 4, y, Ui.FAINT, false);
        int ly = y + 16;
        int ry = y + 16;
        if (left.isEmpty()) {
            g.drawString(font, "No more packs. Put packs in the pack folder.", x + 4, ly, Ui.MUTED, false);
            ly += 14;
        }
        if (!showModPacks && hiddenCount > 0) {
            String note = hiddenCount + " mod packs hidden";
            g.drawString(font, note, rx + colW - 4 - font.width(note), y, Ui.FAINT, false);
        }
        for (var e : left) {
            ly = packCard(g, e, x, ly, colW, mx, my, top, bottom, false);
        }
        for (var e : right) {
            ry = packCard(g, e, rx, ry, colW, mx, my, top, bottom, true);
        }
        return Math.max(ly, ry);
    }

    private int packCard(GuiGraphics g, net.minecraft.client.gui.screens.packs.PackSelectionModel.Entry e, int x, int y, int w,
                         int mx, int my, int top, int bottom, boolean inUse) {
        int h = 46;
        boolean visible = y + h > top && y < bottom;
        boolean inside = my >= top && my < bottom;
        boolean over = visible && inside && Ui.over(mx, my, x, y, w, h);
        boolean canAdd = !inUse && e.canSelect();
        if (over && canAdd) {
            g.fill(x, y, x + w, y + h, Ui.HOVER);
        }
        Ui.outline(g, x, y, x + w, y + h, over && canAdd ? Ui.MUTED : 0x33FFFFFF);
        if (visible) {
            RenderSystem.enableBlend();
            g.blit(e.getIconTexture(), x + 6, y + 7, 0, 0, 32, 32, 32, 32);
            RenderSystem.disableBlend();
        }
        int tx = x + 46;
        int room = w - 52 - (inUse ? 48 : 14);
        g.drawString(font, font.plainSubstrByWidth(e.getTitle().getString(), room), tx, y + 7, Ui.TEXT, false);
        List<String> desc = Ui.wrap(font, e.getDescription().getString().replace('\n', ' '), w - 52);
        for (int i = 0; i < Math.min(2, desc.size()); i++) {
            String line = desc.get(i);
            if (i == 1 && desc.size() > 2) {
                line = font.plainSubstrByWidth(line, w - 64) + "...";
            }
            g.drawString(font, line, tx, y + 19 + i * 10, Ui.MUTED, false);
        }
        if (!e.getCompatibility().isCompatible()) {
            String warn = "Other version";
            g.drawString(font, warn, x + w - 8 - font.width(warn), y + h - 11, 0xFFE0B080, false);
        }
        if (inUse) {
            int bx = x + w - 18;
            if (packButton(g, "x", bx, y + 5, mx, my, e.canUnselect(), visible && inside, top, bottom, e::unselect)) {
            }
            packButton(g, "v", bx - 16, y + 5, mx, my, e.canMoveDown(), visible && inside, top, bottom, e::moveDown);
            packButton(g, "^", bx - 32, y + 5, mx, my, e.canMoveUp(), visible && inside, top, bottom, e::moveUp);
        } else {
            g.drawString(font, ">", x + w - 14, y + 7, canAdd ? Ui.SOFT : Ui.FAINT, false);
            if (canAdd && visible && inside) {
                hits.add(new Hit(x, Math.max(y, top), w, Math.min(y + h, bottom) - Math.max(y, top), e::select));
            }
        }
        return y + h + 4;
    }

    private boolean packButton(GuiGraphics g, String label, int x, int y, int mx, int my, boolean enabled, boolean live,
                               int top, int bottom, Runnable action) {
        boolean hover = enabled && live && Ui.over(mx, my, x, y, 14, 14);
        if (hover) {
            g.fill(x, y, x + 14, y + 14, Ui.TEXT);
        } else {
            Ui.outline(g, x, y, x + 14, y + 14, enabled ? 0x66FFFFFF : 0x22FFFFFF);
        }
        g.drawString(font, label, x + 7 - font.width(label) / 2, y + 3, hover ? Ui.BLACK : enabled ? Ui.TEXT : Ui.FAINT, false);
        if (enabled && live && y + 14 > top && y < bottom) {
            hits.add(new Hit(x, y, 14, 14, action));
        }
        return enabled;
    }

    private List<Folder> videoFolders() {
        List<Folder> out = new ArrayList<>();
        if (!ExternalOptions.hasEmbeddium()) {
            return nativeVideoFolders();
        }
        embeddium = ExternalOptions.embeddiumScreen(this);
        out.add(windowFolder());
        for (ExternalOptions.VideoPage vp : ExternalOptions.pages(embeddium)) {
            List<Row> rows = new ArrayList<>();
            for (ExternalOptions.VideoOption v : vp.options()) {
                rows.add(videoRow(v));
            }
            if (!rows.isEmpty()) {
                out.add(new Folder(vp.name(), rows));
            }
        }
        return out;
    }

    private Folder windowFolder() {
        return new Folder("Window", List.of(
                Row.choice("Window", "Windowed, Borderless or Fullscreen. Borderless covers the screen with no frame, so switching apps stays instant.", "Now",
                        () -> WindowMode.label(Settings.get(Settings.WINDOW)),
                        () -> WindowMode.set(WindowMode.next(Settings.get(Settings.WINDOW))))));
    }

    private List<Shortcut> videoShortcuts() {
        return ExternalOptions.hasEmbeddium()
                ? List.of(new Shortcut("Embeddium's own screen", () -> ExternalOptions.openEmbeddium(this)))
                : List.of(new Shortcut("Install Embeddium", () -> askInstall(OptionalMods.EMBEDDIUM, "Embeddium",
                        "Embeddium makes the game render faster and adds many more video options to this page. "
                                + "Pentecost downloads it from Modrinth into your mods folder. Restart Pentecost afterwards.")));
    }

    private static final String NOT_SODIUM = "Pentecost is not compatible with Sodium or Iris. Use Embeddium and Oculus.";

    private List<Folder> nativeVideoFolders() {
        Options o = minecraft.options;
        int maxDistance = minecraft.is64Bit() ? 32 : 16;
        int maxScale = Math.max(1, minecraft.getWindow().calculateScale(0, minecraft.isEnforceUnicode()));
        Row brightness = new Row(Kind.SLIDER, "Brightness", "How bright dark places are. Moody is the game's normal look.", "", "Now",
                () -> brightnessText((int) Math.round(o.gamma().get() * 100)), null, null,
                new Slider(0, 100, 1, () -> (int) Math.round(o.gamma().get() * 100), n -> {
                    o.gamma().set(n / 100.0);
                    optionsDirty = true;
                }, () -> brightnessText((int) Math.round(o.gamma().get() * 100))), () -> true);
        List<Folder> out = new ArrayList<>();
        out.add(new Folder("Quality", List.of(
                enumOptionText("Graphics", "Fast is the lightest. Fancy adds clearer leaves and water. Fabulous adds the most depth and costs the most.",
                        o.graphicsMode(), g -> switch (g) {
                            case FAST -> "Fast";
                            case FANCY -> "Fancy";
                            default -> "Fabulous";
                        }),
                intOptionText("Render distance", "How many chunks you can see. Lower is faster.", o.renderDistance(), 2, maxDistance, 1,
                        n -> n + " chunks"),
                intOptionText("Simulation distance", "How far from you the world keeps running: crops, mobs and fire.",
                        o.simulationDistance(), 5, maxDistance, 1, n -> n + " chunks"),
                optionToggle("Smooth lighting", "Softer shading on blocks. Off is a little faster.", o.ambientOcclusion()),
                enumOption("Clouds", "Off, Fast or Fancy clouds.", o.cloudStatus()),
                enumOption("Particles", "How many particles the game draws.", o.particles()),
                intOptionText("Biome blend", "How smoothly colors change where biomes meet.", o.biomeBlendRadius(), 0, 7, 1,
                        n -> n == 0 ? "Off" : (2 * n + 1) + "x" + (2 * n + 1)),
                intOptionText("Mipmap levels", "Smoother far-away textures. Needs a resource reload to show.", o.mipmapLevels(), 0, 4, 1,
                        n -> n == 0 ? "Off" : String.valueOf(n)),
                optionToggle("Entity shadows", "Soft shadows under mobs and players.", o.entityShadows()),
                numberOption("Entity distance", "How far away mobs and other entities are still drawn.", o.entityDistanceScaling(), 100, 50, 500, "%"))));
        out.add(windowFolder());
        out.add(new Folder("Display", List.of(
                intOptionText("Max framerate", "The most frames per second the game draws.", o.framerateLimit(), 10, 260, 10,
                        n -> n >= 260 ? "Unlimited" : n + " fps"),
                optionToggle("VSync", "Matches the screen's refresh rate. Stops tearing, can add a little delay.", o.enableVsync()),
                intOptionText("GUI scale", "How big menus and the hotbar are. Auto picks the largest that fits.", o.guiScale(), 0, maxScale, 1,
                        n -> n == 0 ? "Auto" : String.valueOf(n)),
                brightness,
                intOptionText("Field of view", "How wide the world looks.", o.fov(), 30, 110, 1, n -> n + "\u00B0"),
                optionToggle("View bobbing", "The view sways while you walk.", o.bobView()),
                enumOption("Attack indicator", "Where the cooldown of your swing shows.", o.attackIndicator()))));
        out.add(new Folder("Performance", List.of(
                enumOptionText("Chunk builder", "Threaded keeps the game smooth. Blocking draws changes sooner but can stutter.",
                        o.prioritizeChunkUpdates(), p -> switch (p) {
                            case NONE -> "Threaded";
                            case PLAYER_AFFECTED -> "Semi blocking";
                            default -> "Fully blocking";
                        }),
                Row.info("More options", "Install Embeddium for chunk, culling and engine options"))));
        out.add(new Folder("Get more", List.of(
                Row.live("Embeddium", "Faster rendering and many more options on this page.",
                        () -> OptionalMods.status(OptionalMods.EMBEDDIUM, false)),
                Row.act("Install Embeddium", "Downloads it from Modrinth into your mods folder. Restart afterwards.", "Install",
                        () -> !OptionalMods.busy(), () -> askInstall(OptionalMods.EMBEDDIUM, "Embeddium",
                                "Embeddium makes the game render faster and adds many more video options to this page. "
                                        + "Pentecost downloads it from Modrinth into your mods folder. Restart Pentecost afterwards.")),
                Row.live("Sodium and Iris", NOT_SODIUM, () -> "Not supported"))));
        return out;
    }

    private static String brightnessText(int percent) {
        return percent == 0 ? "Moody" : percent >= 100 ? "Bright" : percent + "%";
    }

    private <E extends Enum<E>> Row enumOptionText(String title, String note, net.minecraft.client.OptionInstance<E> option,
                                                   java.util.function.Function<E, String> label) {
        return Row.choice(title, note, "Now", () -> label.apply(option.get()), () -> {
            E[] all = option.get().getDeclaringClass().getEnumConstants();
            option.set(all[(option.get().ordinal() + 1) % all.length]);
            optionsDirty = true;
        });
    }

    private Row intOptionText(String title, String note, net.minecraft.client.OptionInstance<Integer> option, int min, int max,
                              int step, java.util.function.IntFunction<String> text) {
        Supplier<String> shown = () -> text.apply(option.get());
        return new Row(Kind.SLIDER, title, note, note, "Now", shown, null, null,
                new Slider(min, max, step, () -> option.get(), n -> {
                    option.set(n);
                    optionsDirty = true;
                }, shown), () -> true);
    }

    private void askInstall(String slug, String name, String text) {
        minecraft.setScreen(new net.minecraft.client.gui.screens.ConfirmScreen(yes -> {
            if (yes) {
                OptionalMods.install(slug, name);
            }
            minecraft.setScreen(this);
        }, Component.literal("Install " + name + "?"), Component.literal(text),
                Component.literal("Install"), Component.literal("Not now")));
    }

    private Row videoRow(ExternalOptions.VideoOption v) {
        String when = v.restart() ? "Restart" : "Now";
        BooleanSupplier enabled = () -> ExternalOptions.available(v);
        return switch (v.kind()) {
            case "switch" -> new Row(Kind.SWITCH, v.name(), v.tooltip(), v.about(), when, null,
                    () -> Boolean.TRUE.equals(ExternalOptions.value(v)),
                    () -> ExternalOptions.set(embeddium, v, !Boolean.TRUE.equals(ExternalOptions.value(v))), null, enabled);
            case "choice" -> new Row(Kind.CHOICE, v.name(), v.tooltip(), v.about(), when, () -> ExternalOptions.choiceText(v), null,
                    () -> ExternalOptions.nextChoice(embeddium, v, hasShiftDown()), null, enabled);
            case "slider" -> {
                int[] range = ExternalOptions.sliderRange(v);
                yield new Row(Kind.SLIDER, v.name(), v.tooltip(), v.about(), when, () -> ExternalOptions.sliderText(v), null, null,
                        new Slider(range[0], range[1], Math.max(1, range[2]),
                                () -> ExternalOptions.value(v) instanceof Integer n ? n : range[0],
                                n -> ExternalOptions.setOnly(v, n),
                                () -> ExternalOptions.sliderText(v)), enabled);
            }
            default -> new Row(Kind.INFO, v.name(), v.tooltip(), v.about(), when, () -> String.valueOf(ExternalOptions.value(v)),
                    null, null, null, enabled);
        };
    }

    private List<Folder> shaderFolders() {
        List<Row> rows = new ArrayList<>();
        if (!ExternalOptions.hasShaders()) {
            rows.add(Row.live("Oculus", "Oculus adds shader packs. Pentecost drives it from this page.",
                    () -> OptionalMods.status(OptionalMods.OCULUS, false)));
            rows.add(Row.act("Install Oculus", "Downloads Oculus, and Embeddium which it needs, from Modrinth into your mods folder. "
                    + "Restart afterwards.", "Install", () -> !OptionalMods.busy(), this::askInstallOculus));
            rows.add(Row.live("Sodium and Iris", NOT_SODIUM, () -> "Not supported"));
            return List.of(new Folder("Packs", rows));
        }
        rows.add(Row.pick("Off", "No shader pack. The game's own look.", () -> !ExternalOptions.shadersOn(),
                () -> ExternalOptions.setShaders(false)));
        if (ExternalOptions.installBundledPack()) {
            rows.add(Row.pick("Pentecost shaders", "Made for Pentecost. Sun and moon shadows, warm torches, softer colors.",
                    () -> ExternalOptions.shadersOn() && ExternalOptions.BUNDLED_PACK.equals(ExternalOptions.currentPack()),
                    ExternalOptions::usePentecostPack));
        }
        for (String pack : ExternalOptions.packs()) {
            if (ExternalOptions.BUNDLED_PACK.equals(pack)) {
                continue;
            }
            String shown = pack.endsWith(".zip") ? pack.substring(0, pack.length() - 4) : pack;
            rows.add(Row.pick(shown, pack.endsWith(".zip") ? "Zip pack." : "Folder pack.",
                    () -> ExternalOptions.shadersOn() && pack.equals(ExternalOptions.currentPack()),
                    () -> ExternalOptions.usePack(pack)));
        }
        Row portals = Row.toggle("Skip shadows in dark portal views",
                "When a portal shows a place where the sun reaches nothing, such as a closed Backrooms room, Pentecost skips "
                        + "the shader pack's shadow pass for that view. Turn it off to compare frame times.", "Now",
                () -> Settings.on("render.portalShadow"), () -> Settings.toggle("render.portalShadow"));
        return List.of(new Folder("Packs", rows), new Folder("Portals", List.of(portals)));
    }

    private void openPage(String name) {
        for (int i = 0; i < pages.size(); i++) {
            if (pages.get(i).name().equals(name)) {
                built.remove(i);
                if (name.equals("Shader pack")) {
                    packPath.clear();
                    packPath.add(0);
                }
                page = i;
                scroll = 0;
                return;
            }
        }
    }

    private String shaderProfile = "Pick one";

    private List<ExternalOptions.ShaderScreen> packScreens = List.of();
    private final List<Integer> packPath = new ArrayList<>(List.of(0));

    private List<String> packCrumbs() {
        List<String> out = new ArrayList<>();
        for (int i : packPath) {
            if (i >= 0 && i < packScreens.size()) {
                out.add(packScreens.get(i).title());
            }
        }
        return out;
    }

    private void packGoTo(int depth) {
        while (packPath.size() > depth + 1) {
            packPath.remove(packPath.size() - 1);
        }
        built.remove(page);
        scroll = 0;
    }

    private List<Folder> packFolders() {
        List<Folder> out = new ArrayList<>();
        if (!ExternalOptions.hasShaders()) {
            out.add(new Folder("Pack", List.of(Row.info("Shaders", "Install Oculus on the Shaders page"))));
            return out;
        }
        packScreens = ExternalOptions.shaderMenu();
        if (packScreens.isEmpty()) {
            packPath.clear();
            packPath.add(0);
            out.add(new Folder("Pack", List.of(Row.info("No pack in use", "Pick one on the Shaders page"))));
            return out;
        }
        Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < packScreens.size(); i++) {
            index.put(packScreens.get(i).id(), i);
        }
        packPath.removeIf(i -> i < 0 || i >= packScreens.size());
        if (packPath.isEmpty()) {
            packPath.add(0);
        }
        ExternalOptions.ShaderScreen sc = packScreens.get(packPath.get(packPath.size() - 1));
        List<Row> groups = new ArrayList<>();
        List<Row> rows = new ArrayList<>();
        for (ExternalOptions.ShaderEntry en : sc.entries()) {
            String about = en.about().isEmpty() ? "An option of this shader pack." : en.about();
            switch (en.kind()) {
                case "bool" -> rows.add(new Row(Kind.SWITCH, en.label(), firstLine(about), about, "On apply", null,
                        () -> "true".equals(ExternalOptions.shaderValue(en.id(), true)),
                        () -> ExternalOptions.setShaderValue(en.id(),
                                "true".equals(ExternalOptions.shaderValue(en.id(), true)) ? "false" : "true"),
                        null, () -> true));
                case "string" -> rows.add(new Row(Kind.CHOICE, en.label(), firstLine(about), about, "On apply",
                        () -> ExternalOptions.shaderValueLabel(en.id(), ExternalOptions.shaderValue(en.id(), false)),
                        null, () -> {
                            List<String> values = en.values();
                            if (values.isEmpty()) {
                                return;
                            }
                            int at = values.indexOf(ExternalOptions.shaderValue(en.id(), false));
                            ExternalOptions.setShaderValue(en.id(), values.get((at + 1) % values.size()));
                        }, null, () -> true));
                case "link" -> {
                    Integer target = index.get(en.id());
                    if (target != null && !packPath.contains(target)) {
                        String note = en.about().isEmpty() ? "A group of options." : firstLine(en.about());
                        groups.add(new Row(Kind.BUTTON, en.label(), note,
                                en.about().isEmpty() ? "Opens this group of options." : en.about(), "", () -> "Open  >", null,
                                () -> {
                                    packPath.add(target);
                                    built.remove(page);
                                    scroll = 0;
                                }, null, () -> true));
                    }
                }
                case "profile" -> rows.add(0, new Row(Kind.CHOICE, en.label(), firstLine(about), about, "On apply",
                        () -> shaderProfile, null, () -> {
                            List<String> names = en.values();
                            int at = names.indexOf(shaderProfile);
                            shaderProfile = names.get((at + 1) % names.size());
                            ExternalOptions.useShaderProfile(shaderProfile);
                        }, null, () -> true));
                default -> { }
            }
        }
        List<Row> all = new ArrayList<>(groups);
        all.addAll(rows);
        if (all.isEmpty()) {
            all.add(Row.info("This group", "Has no options"));
        }
        out.add(new Folder(sc.title(), all));
        return out;
    }

    private static String firstLine(String s) {
        int dot = s.indexOf(". ");
        String line = dot > 0 ? s.substring(0, dot + 1) : s;
        return line.length() > 90 ? line.substring(0, 87) + "..." : line;
    }

    private List<Shortcut> packShortcuts() {
        if (!ExternalOptions.hasShaders()) {
            return List.of();
        }
        int waiting = ExternalOptions.pendingShaderChanges();
        List<Shortcut> out = new ArrayList<>();
        out.add(new Shortcut(waiting > 0 ? "Apply " + waiting + (waiting == 1 ? " change" : " changes") : "Apply", () -> {
            ExternalOptions.applyShaderChanges();
            built.remove(page);
        }));
        if (waiting > 0) {
            out.add(new Shortcut("Undo changes", () -> {
                ExternalOptions.discardShaderChanges();
                built.remove(page);
            }));
        }
        out.add(new Shortcut("Reset to defaults", () -> {
            ExternalOptions.resetShaderOptions();
            shaderProfile = "Pick one";
            built.remove(page);
        }));
        if (packPath.size() > 1) {
            out.add(new Shortcut("Up one level", () -> packGoTo(packPath.size() - 2)));
        }
        out.add(new Shortcut("Back to packs", () -> openPage("Shaders")));
        return out;
    }

    private void askInstallOculus() {
        askInstall(OptionalMods.OCULUS, "Oculus", "Oculus lets you use shader packs, and Pentecost has a page for them. "
                + "Pentecost downloads it, and Embeddium which it needs, from Modrinth into your mods folder. Restart Pentecost afterwards.");
    }

    private List<Shortcut> shaderShortcuts() {
        if (!ExternalOptions.hasShaders()) {
            return List.of(new Shortcut("Install Oculus", this::askInstallOculus));
        }
        return List.of(
                new Shortcut("Get shaders", () -> minecraft.setScreen(new PackStoreScreen(this, khi.pentecost.packs.PackStore.Kind.SHADER,
                        ExternalOptions::packFolder, () -> built.remove(page)))),
                new Shortcut("Use Pentecost shaders", () -> {
                    ExternalOptions.usePentecostPack();
                    built.remove(page);
                }),
                new Shortcut("Pack settings", () -> openPage("Shader pack")),
                new Shortcut("Shader folder", () -> {
                    java.nio.file.Path dir = ExternalOptions.packFolder();
                    if (dir != null) {
                        Util.getPlatform().openFile(dir.toFile());
                    }
                }),
                new Shortcut("Refresh list", () -> built.remove(page)));
    }

    private boolean optionsDirty;

    private Row numberOption(String title, String note, net.minecraft.client.OptionInstance<Double> option,
                             int mult, int min, int max, String unit) {
        IntSupplier value = () -> (int) Math.round(option.get() * mult);
        Supplier<String> text = () -> value.getAsInt() + unit;
        return new Row(Kind.SLIDER, title, note, note, "Now", text, null, null,
                new Slider(min, max, 1, value, n -> {
                    option.set(n / (double) mult);
                    optionsDirty = true;
                }, text), () -> true);
    }

    private Row intOption(String title, String note, net.minecraft.client.OptionInstance<Integer> option,
                          int min, int max, String unit) {
        IntSupplier value = () -> option.get();
        Supplier<String> text = () -> value.getAsInt() + unit;
        return new Row(Kind.SLIDER, title, note, note, "Now", text, null, null,
                new Slider(min, max, 1, value, n -> {
                    option.set(n);
                    optionsDirty = true;
                }, text), () -> true);
    }

    private <E extends Enum<E>> Row enumOption(String title, String note, net.minecraft.client.OptionInstance<E> option) {
        return Row.choice(title, note, "Now", () -> {
            String s = option.get().name().toLowerCase(Locale.ROOT).replace('_', ' ');
            return Character.toUpperCase(s.charAt(0)) + s.substring(1);
        }, () -> {
            E[] all = option.get().getDeclaringClass().getEnumConstants();
            option.set(all[(option.get().ordinal() + 1) % all.length]);
            optionsDirty = true;
        });
    }

    private Row settingSlider(String title, String note, String when, String key, int def, int min, int max, int step,
                              String unit, String zero) {
        IntSupplier value = () -> Settings.num(key, def);
        Supplier<String> text = () -> zero != null && value.getAsInt() == 0 ? zero : value.getAsInt() + unit;
        return new Row(Kind.SLIDER, title, note, note, when, text, null, null,
                new Slider(min, max, step, value, n -> Settings.set(key, String.valueOf(n)), text), () -> true);
    }

    private Row scaledSlider(String title, String note, String key, int def, int min, int max, int step, int scale, String unit) {
        IntSupplier value = () -> Settings.num(key, def);
        Supplier<String> text = () -> {
            double v = value.getAsInt() / (double) scale;
            String n = scale == 100 ? String.format(java.util.Locale.ROOT, "%.2f", v) : String.format(java.util.Locale.ROOT, "%.1f", v);
            return n + unit;
        };
        return new Row(Kind.SLIDER, title, note, note, "Now", text, null, null,
                new Slider(min, max, step, value, n -> Settings.set(key, String.valueOf(n)), text), () -> true);
    }

    private List<Folder> cameraFolders() {
        String c = "camera.";
        return List.of(
                new Folder("F5 camera", List.of(
                        settingToggle("Pentecost F5 camera", "F5 cycles Wide, Follow and Close, and the mouse swings the camera "
                                + "all the way around you. Off: the game's own F5.", "Now", OrbitCamera.SETTING),
                        Row.choice("Style", "Classic feels close to the game's own F5. Modern is the full Pentecost camera. "
                                + "Cinematic is soft, slow and wide. Picking one sets every camera setting below.", "Now",
                                OrbitCamera::presetLabel,
                                () -> OrbitCamera.preset(switch (OrbitCamera.presetLabel()) {
                                    case "Modern" -> "cinematic";
                                    case "Cinematic" -> "classic";
                                    default -> "modern";
                                })),
                        settingToggle("Wide in the cycle", "Include the far camera when you press F5.", "Now", c + "useWide"),
                        settingToggle("Follow in the cycle", "Include the middle camera when you press F5.", "Now", c + "useFollow"),
                        settingToggle("Close in the cycle", "Include the over-the-shoulder camera when you press F5.", "Now", c + "useClose"),
                        settingSlider("Hold F5 for first person", "Hold F5 this long to glide straight back to first person.", "Now",
                                c + "hold", 500, 150, 2000, 50, " ms", null),
                        Row.act("Reset the F5 camera", "Every F5 camera setting back to how Pentecost ships it.", "Reset",
                                () -> true, OrbitCamera::resetAll))),
                new Folder("F5 camera: distance", List.of(
                        scaledSlider("Wide distance", "How far the Wide camera sits from your head.", c + "wide", 90, 30, 250, 5, 10, " blocks"),
                        scaledSlider("Wide height", "How far above your head the Wide camera sits.", c + "wideHeight", 15, 0, 60, 1, 10, " blocks"),
                        scaledSlider("Follow distance", "How far the Follow camera sits from your head.", c + "follow", 50, 20, 150, 1, 10, " blocks"),
                        scaledSlider("Follow height", "How far above your head the Follow camera sits.", c + "followHeight", 8, 0, 40, 1, 10, " blocks"),
                        scaledSlider("Close distance", "How far the Close camera sits from your head.", c + "close", 16, 6, 60, 1, 10, " blocks"),
                        scaledSlider("Close height", "How far above your head the Close camera sits.", c + "closeHeight", 3, 0, 20, 1, 10, " blocks"),
                        scaledSlider("Shoulder offset", "How far to the side the Close camera sits.", c + "shoulder", 8, 0, 20, 1, 10, " blocks"),
                        Row.choice("Shoulder side", "Which shoulder the Close camera looks over.", "Now",
                                () -> "left".equals(Settings.get(c + "shoulderSide")) ? "Left" : "Right",
                                () -> Settings.set(c + "shoulderSide", "left".equals(Settings.get(c + "shoulderSide")) ? "right" : "left")),
                        settingToggle("Left Alt swaps shoulder", "Tap Left Alt to move the Close camera to your other shoulder.", "Now", c + "shoulderKey"),
                        settingToggle("Auto shoulder swap", "When a wall is on your shoulder side, the camera moves to the open "
                                + "side on its own, and back when the wall is gone.", "Now", c + "autoShoulder"),
                        Row.info("Fine-tune with the mouse", "Hold F5 and scroll to move the current camera closer or farther."))),
                new Folder("F8 cinematic camera", List.of(
                        Row.info("What it does", "F8 turns on a cinematic third-person camera, like Better Third Person. "
                                + "Your head never follows the camera. WASD move you relative to the camera and your "
                                + "character turns to run where you steer. Attacking or using an item turns you to the "
                                + "camera's aim. F8 again goes back to the camera you had. F5 or holding F5 leaves it too."),
                        scaledSlider("Distance", "How far the cinematic camera sits from your head.", c + "cineDist", 40, 15, 150, 1, 10, " blocks"),
                        scaledSlider("Height", "How far above your head it sits.", c + "cineHeight", 6, 0, 40, 1, 10, " blocks"),
                        settingSlider("Camera smoothing", "How softly the camera glides after the mouse in this mode.", "Now",
                                c + "cineSmoothing", 120, 0, 400, 10, " ms", "Off"))),
                new Folder("F5 camera: keys and free camera", List.of(
                        Row.info("Lock-on", "Side mouse button"),
                        Row.info("Shoulder swap", "Left Alt"),
                        Row.info("Cinematic camera", "F8"),
                        Row.info("Free camera", "F7: fly the camera on its own to film. WASD moves, jump and sneak go up "
                                + "and down, sprint is faster, scroll zooms. It bumps into blocks and into you. Your character "
                                + "stays still. F7 again to return."),
                        Row.info("Clean shot", "F1 while in F7 hides the HUD and every name tag, yours included."),
                        Row.info("Change a key", "Controls page, Pentecost group. The game marks keys that clash in red."))),
                new Folder("F5 camera: walking", List.of(
                        settingToggle("Over the head while walking", "After you walk a moment, the camera moves from your shoulder "
                                + "to above your head, so you see where you are going.", "Now", c + "overHead"),
                        settingSlider("Walk time before it moves", "How long you walk before the camera goes over your head.", "Now",
                                c + "overHeadDelay", 500, 0, 3000, 50, " ms", null),
                        settingSlider("Stand time before it returns", "How long you stand still before it goes back to your shoulder.", "Now",
                                c + "overHeadBack", 800, 0, 3000, 50, " ms", null),
                        scaledSlider("Height over the head", "How far above your head it sits while you walk.", c + "overHeadLift", 55, 0, 200, 5, 100, " blocks"),
                        settingSlider("Body turn smoothing", "How softly your body turns toward the camera when you walk. "
                                + "Lower turns faster.", "Now", c + "walkTurn", 120, 10, 600, 10, " ms", null),
                        settingSlider("Body turn speed limit", "Your body never turns faster than this.", "Now",
                                c + "turnSpeed", 720, 90, 2000, 30, " degrees a second", null))),
                new Folder("F5 camera: smart", List.of(
                        settingToggle("Cave sense", "In tunnels and tight rooms the camera moves in before it hits a wall, "
                                + "and back out in the open.", "Now", c + "smartCave"),
                        settingToggle("Low ceilings", "Under a low roof the camera drops instead of pressing into it.", "Now", c + "smartCeiling"),
                        settingToggle("Speed distance", "The faster you go on a horse, elytra or minecart, the farther back "
                                + "the camera sits, up to 4 blocks.", "Now", c + "smartSpeed"),
                        settingToggle("Look-ahead", "When you move fast the camera leans toward where you are going.", "Now", c + "smartLook"),
                        settingToggle("Combat pull-back", "With hostile mobs near you, Wide and Follow ease out and up so "
                                + "you see more around you.", "Now", c + "smartCombat"),
                        settingToggle("Lock-on", "Press the lock-on key (side mouse button) while the camera looks at a mob "
                                + "to keep you and it in view. Press again, move the mouse far, or let the mob leave to let go.",
                                "Now", c + "lockOn"),
                        settingToggle("Bow aiming", "Drawing a bow, crossbow or trident moves the camera over your shoulder "
                                + "for the shot, then back.", "Now", c + "bowZoom"),
                        settingToggle("See-through blocks", "Glass, leaves, fences and other blocks you can see through "
                                + "don't push the camera in.", "Now", c + "seeThrough"),
                        settingToggle("Comfort mode", "For motion sickness. Turns off speed distance, look-ahead, combat "
                                + "pull-back and all smoothing. The camera only moves when you move the mouse or walk.",
                                "Now", c + "comfort"))),
                new Folder("F5 camera: aim and feel", List.of(
                        settingSlider("Mouse smoothing", "The camera glides after the mouse for a softer, filmic feel. "
                                + "0 follows the mouse exactly.", "Now", c + "smoothing", 30, 0, 200, 5, " ms", "Off"),
                        settingSlider("Step and jump smoothing", "The camera rides softly up stairs and jumps instead of "
                                + "bouncing with your head.", "Now", c + "bumpSmoothing", 70, 0, 300, 5, " ms", "Off"),
                        settingSlider("Neck range", "How far your head turns to follow the camera before the camera moves on "
                                + "around you on its own.", "Now", c + "neck", 70, 20, 150, 5, " degrees", null),
                        settingSlider("Aim smoothing", "How softly your aim follows the camera. Lower is snappier.", "Now",
                                c + "aimSmooth", 50, 5, 400, 5, " ms", null),
                        settingSlider("Camera sensitivity", "How fast the mouse swings the camera around you.", "Now",
                                c + "sensitivity", 100, 20, 300, 5, "%", null),
                        settingSlider("Glide time", "How long the camera takes to move between Wide, Follow, Close and first person.", "Now",
                                c + "glide", 450, 50, 1500, 25, " ms", null),
                        settingSlider("Wall ease-out", "How slowly the camera moves back out after a wall pushed it in.", "Now",
                                c + "wallEase", 400, 20, 1500, 20, " ms", null),
                        settingToggle("Crosshair", "Marks the exact spot your character aims at, wherever it is on screen.", "Now", c + "crosshair"),
                        settingToggle("Crosshair in Wide", "Show the crosshair in the far camera too.", "Now", c + "crosshairWide"),
                        settingToggle("See-through body up close", "Your body fades when the camera comes close to it.", "Now", c + "ghost"),
                        scaledSlider("Fade starts at", "How close the camera gets to your body before it starts to fade.", c + "ghostStart", 10, 4, 30, 1, 10, " blocks"))));
    }

    private Row settingToggle(String title, String note, String when, String key) {
        return Row.toggle(title, note, when, () -> Settings.on(key), () -> Settings.toggle(key));
    }

    private static String nice(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private List<Folder> soundFolders() {
        Options o = minecraft.options;
        List<Row> volume = new ArrayList<>();
        for (SoundSource src : SoundSource.values()) {
            volume.add(numberOption(src == SoundSource.MASTER ? "Master volume" : nice(src.getName()),
                    src == SoundSource.MASTER ? "Every sound at once." : "Volume of the " + src.getName() + " sound group.",
                    o.getSoundSourceOptionInstance(src), 100, 0, 100, "%"));
        }
        List<Folder> out = new ArrayList<>();
        out.add(new Folder("Volume", volume));
        out.add(new Folder("Options", List.of(
                optionToggle("Show subtitles", "Words on screen for the sounds around you.", o.showSubtitles()),
                optionToggle("Directional audio", "Sounds come from the side they are on, with better stereo.", o.directionalAudio()))));
        out.add(new Folder("Limits", List.of(
                settingSlider("Sounds per second", "At most this many sounds start each second. Music, records and voice chat are never cut. "
                        + "Off lets everything play.", "Now", Settings.SOUND_LIMIT, 0, 0, 200, 5, " per second", "Off"),
                settingSlider("Same sound spacing", "The same sound cannot start again within this time. "
                        + "Helps with machines and mobs that repeat one sound many times.", "Now",
                        Settings.SOUND_GAP, 0, 0, 500, 10, " ms", "Off"))));
        List<Row> mods = new ArrayList<>();
        for (SoundControl.Seen s : SoundControl.mods()) {
            String ns = s.name();
            mods.add(Row.toggle("Mute " + ns, "Played " + s.count() + " times so far this session.", "Now",
                    () -> SoundControl.modMuted(ns), () -> SoundControl.toggleMod(ns)));
        }
        if (mods.isEmpty()) {
            mods.add(Row.info("Nothing played yet", "Play for a moment, then press Refresh lists"));
        }
        out.add(new Folder("Mods", mods));
        List<Row> sounds = new ArrayList<>();
        for (SoundControl.Seen s : SoundControl.sounds()) {
            String id = s.name();
            sounds.add(Row.toggle("Mute " + id, "Played " + s.count() + " times so far this session.", "Now",
                    () -> SoundControl.soundMuted(id), () -> SoundControl.toggleSound(id)));
        }
        if (sounds.isEmpty()) {
            sounds.add(Row.info("Nothing played yet", "Play for a moment, then press Refresh lists"));
        }
        out.add(new Folder("Sounds", sounds));
        return out;
    }

    private List<Shortcut> soundShortcuts() {
        return List.of(
                new Shortcut("Refresh lists", () -> built.remove(page)),
                new Shortcut("Clear counts", () -> {
                    SoundControl.clearCounts();
                    built.remove(page);
                }),
                new Shortcut("Unmute everything", () -> {
                    Settings.set(Settings.SOUND_MUTED_MODS, "");
                    Settings.set(Settings.SOUND_MUTED, "");
                }));
    }

    private List<Folder> chatFolders() {
        Options o = minecraft.options;
        return List.of(
                new Folder("Chat", List.of(
                        enumOption("Chat", "Show everything, only commands, or nothing.", o.chatVisibility()),
                        optionToggle("Colors", "Chat messages show the colors servers send.", o.chatColors()),
                        optionToggle("Web links", "Links in chat can be clicked.", o.chatLinks()),
                        optionToggle("Ask before opening links", "A question first, so one stray click opens nothing.", o.chatLinksPrompt()),
                        optionToggle("Command suggestions", "Suggestions appear while you type a command.", o.autoSuggestions()),
                        optionToggle("Hide matched names", "Names of players in a message are not repeated.", o.hideMatchedNames()),
                        optionToggle("Only secure chat", "Hides messages whose sender cannot be verified.", o.onlyShowSecureChat()))),
                new Folder("Size", List.of(
                        numberOption("Text size", "How big chat letters are.", o.chatScale(), 100, 0, 100, "%"),
                        numberOption("Line spacing", "Space between chat lines.", o.chatLineSpacing(), 100, 0, 100, "%"),
                        numberOption("Width", "How wide the chat is.", o.chatWidth(), 100, 0, 100, "%"),
                        numberOption("Height, open", "How tall the chat is while you type.", o.chatHeightFocused(), 100, 0, 100, "%"),
                        numberOption("Height, closed", "How tall the chat is while you play.", o.chatHeightUnfocused(), 100, 0, 100, "%"))),
                new Folder("Look", List.of(
                        numberOption("Text opacity", "How solid the letters are.", o.chatOpacity(), 100, 0, 100, "%"),
                        numberOption("Background opacity", "How dark the box behind the letters is.", o.textBackgroundOpacity(), 100, 0, 100, "%"),
                        numberOption("Message delay", "Seconds before a new message shows. Gives you time to look away.", o.chatDelay(), 1, 0, 6, " s"))),
                new Folder("Pentecost chat", List.of(
                        Row.choice("Chat style", "Pentecost draws each line with a soft wash and a hairline down the edge. "
                                + "Game keeps the usual boxes.", "Now",
                                () -> "vanilla".equals(Settings.get(ChatStyle.THEME)) ? "Game" : "Pentecost",
                                () -> Settings.set(ChatStyle.THEME,
                                        "vanilla".equals(Settings.get(ChatStyle.THEME)) ? "pentecost" : "vanilla")),
                        Row.toggle("Chat motion", "The chat glides up when a line arrives, the hairline flashes and drifts, "
                                + "and the input opens from the middle.", "Now",
                                () -> ChatStyle.option(ChatStyle.ANIMATION, true), () -> ChatStyle.flip(ChatStyle.ANIMATION, true)),
                        Row.toggle("Chat tabs", "All, Players and System just above the input box. "
                                + "A number counts lines that arrived on another tab.", "Now",
                                () -> ChatStyle.option(ChatStyle.TABS, true), () -> ChatStyle.flip(ChatStyle.TABS, true)),
                        Row.toggle("Clickable links", "Links in plain chat text open when you click them. The game asks first.", "Now",
                                () -> ChatStyle.option(ChatStyle.LINKS, true), () -> ChatStyle.flip(ChatStyle.LINKS, true)),
                        Row.toggle("Clickable coordinates", "Coordinates like 120 64 -340 copy to the clipboard when clicked.", "Now",
                                () -> ChatStyle.option(ChatStyle.COORDS, true), () -> ChatStyle.flip(ChatStyle.COORDS, true)),
                        Row.toggle("Highlight mentions", "Your name in someone else's message is bold and underlined.", "Now",
                                () -> ChatStyle.option(ChatStyle.MENTIONS, true), () -> ChatStyle.flip(ChatStyle.MENTIONS, true)),
                        Row.toggle("Mention sound", "A short sound when someone says your name.", "Now",
                                () -> ChatStyle.option(ChatStyle.MENTION_SOUND, true), () -> ChatStyle.flip(ChatStyle.MENTION_SOUND, true)),
                        Row.toggle("Time stamps", "The time in front of every chat line.", "Now",
                                () -> ChatStyle.option(ChatStyle.TIMESTAMPS, false), () -> ChatStyle.flip(ChatStyle.TIMESTAMPS, false)))));
    }

    private List<Folder> accessibilityFolders() {
        Options o = minecraft.options;
        return List.of(
                new Folder("Reading", List.of(
                        enumOption("Narrator", "Reads chat and menus out loud.", o.narrator()),
                        optionToggle("Show subtitles", "Words on screen for the sounds around you.", o.showSubtitles()),
                        optionToggle("High contrast", "Stronger outlines on the interface.", o.highContrast()),
                        optionToggle("Background only for chat", "Menus keep their plain background.", o.backgroundForChatOnly()))),
                new Folder("Motion", List.of(
                        numberOption("Screen effects", "How strong nausea and portal wobble are.", o.screenEffectScale(), 100, 0, 100, "%"),
                        numberOption("FOV effects", "How much speed changes the field of view.", o.fovEffectScale(), 100, 0, 100, "%"),
                        numberOption("Darkness pulse", "How much the Warden's darkness pulses.", o.darknessEffectScale(), 100, 0, 100, "%"),
                        optionToggle("Hide lightning flash", "Lightning no longer flashes the sky.", o.hideLightningFlash()),
                        optionToggle("View bobbing", "The view sways while you walk.", o.bobView()))),
                new Folder("Enchant glint", List.of(
                        numberOption("Glint speed", "How fast the shine moves across enchanted items.", o.glintSpeed(), 100, 0, 100, "%"),
                        numberOption("Glint strength", "How bright the shine is.", o.glintStrength(), 100, 0, 100, "%"))));
    }

    private List<Folder> languageFolders() {
        List<Row> rows = new ArrayList<>();
        for (Map.Entry<String, net.minecraft.client.resources.language.LanguageInfo> e
                : minecraft.getLanguageManager().getLanguages().entrySet()) {
            String code = e.getKey();
            String label = e.getValue().name() + " (" + e.getValue().region() + ")";
            rows.add(Row.pick(label, "Language code " + code + ". The game reloads its text when you choose it.",
                    () -> code.equals(minecraft.options.languageCode), () -> {
                        minecraft.getLanguageManager().setSelected(code);
                        minecraft.options.languageCode = code;
                        minecraft.options.save();
                        minecraft.reloadResourcePacks();
                    }));
        }
        return List.of(new Folder("Languages", rows));
    }

    private static int machineGigabytes() {
        try {
            java.lang.management.OperatingSystemMXBean os = java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            if (os instanceof com.sun.management.OperatingSystemMXBean big) {
                return (int) (big.getTotalMemorySize() >> 30);
            }
        } catch (RuntimeException | LinkageError e) {
        }
        return 16;
    }

    private static final String[][] GCS = {
            {"g1", "G1", "The default. Balanced pauses and speed."},
            {"zgc", "Generational ZGC", "Very short pauses. Uses more memory and a little more CPU."},
            {"parallel", "Parallel", "Highest raw speed, longer pauses. Fine for short sessions."}};

    private List<Folder> modsFolders() {
        List<Row> rows = new ArrayList<>();
        for (khi.pentecost.mods.ModInfo m : ModConfigs.withSettings()) {
            boolean files = ModConfigs.hasFiles(m.id());
            boolean screen = ModConfigs.screenFor(m).isPresent();
            String has = files && screen ? "Config files and its own screen" : files ? "Config files" : "Its own screen";
            rows.add(Row.button(m.name(), has + ".", () -> minecraft.setScreen(new PentecostSettingsScreen(this, "mod:" + m.id()))));
        }
        if (rows.isEmpty()) {
            rows.add(Row.info("No mod in this pack has settings", ""));
        }
        return List.of(new Folder("Mods with settings", rows));
    }

    private Page modPage(String id) {
        khi.pentecost.mods.ModInfo m = ModConfigs.byId(id);
        if (m == null) {
            return null;
        }
        java.util.Optional<java.util.function.Function<Screen, Screen>> own = ModConfigs.screenFor(m);
        Screen settings = this;
        return new Page(m.name(), "Settings of " + m.name() + ", read from its config files and its own settings screen. "
                + "Changes go through the mod's own code and save the way the mod saves.", () -> {
            List<Folder> out = new ArrayList<>();
            java.util.LinkedHashMap<String, List<Row>> groups = new java.util.LinkedHashMap<>();
            for (khi.pentecost.core.SettingItem it : ModConfigs.fileItems(id)) {
                groups.computeIfAbsent(it.group(), k -> new ArrayList<>()).add(rowOf(it));
            }
            if (own.isPresent() && harvested == null) {
                try {
                    Screen s = own.get().apply(settings);
                    if (s != null && s != settings) {
                        harvested = ScreenHarvest.of(s);
                    }
                } catch (RuntimeException | LinkageError e) {
                    khi.pentecost.boot.Log.warn("Could not build the settings screen of " + id + ": " + e);
                }
            }
            if (harvested != null) {
                for (khi.pentecost.core.SettingItem it : harvested.items()) {
                    groups.computeIfAbsent("Screen: " + it.group(), k -> new ArrayList<>()).add(rowOf(it));
                }
            }
            for (var e : groups.entrySet()) {
                out.add(new Folder(e.getKey(), e.getValue()));
            }
            List<Row> open = new ArrayList<>();
            own.ifPresent(f -> open.add(Row.button("Open " + m.name() + "'s own screen",
                    "The screen as the mod made it, for anything the rows above do not cover.",
                    () -> minecraft.setScreen(f.apply(settings)))));
            if (open.isEmpty() && out.isEmpty()) {
                open.add(Row.info("Nothing to show", "This mod's settings load with a world. Open one first."));
            }
            out.add(new Folder("The mod's own screen", open));
            return out;
        }, List.of(), List::of);
    }

    private Row rowOf(khi.pentecost.core.SettingItem it) {
        String note = it.note() == null ? "" : it.note();
        return switch (it.kind()) {
            case TOGGLE -> Row.toggle(it.title(), note, "Now", it.on(), it.action());
            case CHOICE -> Row.choice(it.title(), note, "Now", it.value(), it.action());
            case SLIDER -> new Row(Kind.SLIDER, it.title(), note, note, "Now", it.value(), null, null,
                    new Slider(it.min(), it.max(), it.step(), it.get(), it.set(), it.value()), () -> true);
            case BUTTON -> Row.act(it.title(), note, it.value() == null ? "Press" : it.value().get(), () -> true, it.action());
            case INFO -> Row.live(it.title(), note, it.value());
        };
    }

    private List<Folder> modLoadingFolders() {
        List<Row> rows = new java.util.ArrayList<>();
        for (khi.pentecost.mods.ModDiscovery.TwoSided t : khi.pentecost.mods.ModDiscovery.TWO_SIDED.values()) {
            String setting = khi.pentecost.mods.ModDiscovery.sideSetting(t.key());
            rows.add(Row.choice(t.file(), "Has " + String.join(", ", t.sides()) + ". Loaded this launch as "
                            + t.chosen() + (t.auto() ? " (Pentecost's pick)." : " (your pick)."), "Restart",
                    () -> {
                        String v = Settings.get(setting);
                        return v.isEmpty() ? "Auto" : Character.toUpperCase(v.charAt(0)) + v.substring(1);
                    },
                    () -> {
                        String v = Settings.get(setting);
                        int i = t.sides().indexOf(v);
                        String next = i + 1 < t.sides().size() ? t.sides().get(i + 1) : "";
                        Settings.set(setting, next);
                    }));
        }
        if (rows.isEmpty()) {
            rows.add(Row.info("No mod in this pack has more than one side", ""));
        }
        return List.of(new Folder("Which side loads", rows));
    }

    private List<Folder> javaFolders() {
        int machine = machineGigabytes();
        int cap = khi.pentecost.control.MemoryPlan.cap(machine);
        int[] tier = khi.pentecost.control.MemoryPlan.tier(machine);
        Row heap = settingSlider("Memory", "The working size. The game is set to use about this much. Your PC has about " + machine
                + " GB, so Pentecost keeps 2 GB free for it. Too little stutters, too much starves other programs.",
                "Restart", Settings.JAVA_MEMORY, Math.min(cap, tier[0]), 2, cap, 1, " GB", null);
        Row reserve = settingSlider("Backup memory", "Extra room above Memory, for spikes. The game reaches into it only when it "
                + "runs short and lets it go afterwards. The most the game can use is Memory plus this. None turns it off.",
                "Restart", Settings.JAVA_RESERVE, tier[1], 0, Math.max(1, Math.min(16, machine - 4)), 1, " GB", "None");
        Row young = settingSlider("New objects area", "The part of memory for short-lived objects. A big area means fewer cleanups. "
                + "Half of the memory suits the game. It never goes above memory minus 1 GB.", "Restart",
                Settings.JAVA_YOUNG, 0, 0, Math.max(1, cap - 1), 1, " GB", "Half of memory");
        java.util.List<String> jvmFlags = java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments();
        khi.pentecost.control.MemoryPlan.Effective now = khi.pentecost.control.MemoryPlan.effective(jvmFlags);
        long ceilingMb = Runtime.getRuntime().maxMemory() >> 20;
        long startMb = now.startMb() > 0 ? now.startMb() : ceilingMb;
        String workingNow = Math.round(startMb / 1024.0) + " GB";
        String backupNow = ceilingMb > startMb ? Math.round((ceilingMb - startMb) / 1024.0) + " GB" : "None";
        List<Row> gc = new ArrayList<>();
        for (String[] g : GCS) {
            gc.add(Row.pick(g[1], g[2], () -> g[0].equals(Settings.get(Settings.JAVA_GC).isEmpty() ? "g1" : Settings.get(Settings.JAVA_GC)),
                    () -> Settings.set(Settings.JAVA_GC, g[0])));
        }
        java.nio.file.Path flags = khi.pentecost.boot.Dirs.config().resolve("pentecost").resolve("jvm-flags.txt");
        return List.of(
                new Folder("Memory", List.of(heap, reserve, young,
                        Row.info("Working size now", workingNow),
                        Row.info("Backup now", backupNow),
                        Row.info("Ceiling now", Math.round(ceilingMb / 1024.0) + " GB"))),
                new Folder("Garbage collector", gc),
                new Folder("Extra flags", List.of(
                        Row.button("Edit extra flags", "One flag per line. A line starting with # is ignored. "
                                + "They load after the settings above, so they win.", () -> {
                            try {
                                java.nio.file.Files.createDirectories(flags.getParent());
                                if (!java.nio.file.Files.exists(flags)) {
                                    java.nio.file.Files.writeString(flags, "# One JVM flag per line, for example -XX:+UseStringDeduplication\n");
                                }
                                Util.getPlatform().openFile(flags.toFile());
                            } catch (java.io.IOException e) {
                                khi.pentecost.boot.Log.error("Cannot open " + flags + ": " + e);
                            }
                        }))),
                new Folder("Java", List.of(
                        Row.info("Version", System.getProperty("java.version")),
                        Row.info("Vendor", System.getProperty("java.vendor")),
                        Row.info("Runtime", System.getProperty("java.vm.name")),
                        Row.info("Folder", System.getProperty("java.home")))));
    }

    private List<Shortcut> javaShortcuts() {
        return List.of(new Shortcut("Reset Java settings", () -> {
            Settings.reset(Settings.JAVA_MEMORY);
            Settings.reset(Settings.JAVA_RESERVE);
            Settings.reset(Settings.JAVA_YOUNG);
            Settings.reset(Settings.JAVA_GC);
        }));
    }

    private List<Folder> gameplayFolders() {
        Options o = minecraft.options;
        List<Folder> out = new ArrayList<>(cameraFolders());
        out.addAll(gameplayBaseFolders(o));
        return out;
    }

    private List<Folder> gameplayBaseFolders(Options o) {
        return List.of(
                new Folder("Mobs", List.of(
                        settingToggle("Far mob throttle", "Mobs far from every player think less often. "
                                + "Mobs near you, with a target, leashed or ridden never slow.", "Now", Settings.FAR_MOBS),
                        settingSlider("Far distance", "Mobs farther than this from every player count as far.", "Now",
                                Settings.FAR_RANGE, 48, 24, 96, 8, " blocks", null),
                        settingSlider("Far thinking rate", "A far mob thinks once every this many ticks. Higher saves more and looks choppier.", "Now",
                                Settings.FAR_EVERY, 3, 2, 8, 1, " ticks", null))),
                new Folder("Movement", List.of(
                        optionToggle("Auto jump", "Step up one block without pressing jump.", o.autoJump()),
                        optionToggle("Toggle sneak", "Press once to sneak, again to stop.", o.toggleCrouch()),
                        optionToggle("Toggle sprint", "Press once to sprint, again to stop.", o.toggleSprint()),
                        optionToggle("View bobbing", "The view sways while you walk.", o.bobView()))),
                new Folder("Combat", List.of(
                        enumOption("Attack indicator", "Where the cooldown of your swing shows.", o.attackIndicator()))),
                new Folder("Warnings", List.of(
                        settingToggle("Stall warning", "A short message above the hotbar whenever one frame takes too long. "
                                + "Use it to find out when and where the game freezes.", "Now", Settings.STALL_WARNING),
                        settingSlider("Stall limit", "A frame longer than this counts as a stall.", "Now",
                                Settings.STALL_MS, 150, 50, 1000, 10, " ms", null))));
    }

    private List<Folder> buildingFolders() {
        return List.of(new Folder("Block outline", List.of(
                Row.choice("Color", "The box around the block you look at.", "Now",
                        () -> Settings.get(Settings.OUTLINE_COLOR).isEmpty() ? "Black" : Settings.get(Settings.OUTLINE_COLOR),
                        () -> {
                            String now = Settings.get(Settings.OUTLINE_COLOR).isEmpty() ? "Black" : Settings.get(Settings.OUTLINE_COLOR);
                            String[] all = khi.pentecost.core.ClientHooks.COLORS;
                            int at = 0;
                            for (int i = 0; i < all.length; i++) {
                                if (all[i].equalsIgnoreCase(now)) {
                                    at = i;
                                }
                            }
                            Settings.set(Settings.OUTLINE_COLOR, all[(at + 1) % all.length]);
                        }),
                settingSlider("Strength", "How solid the box is. The game's own is 40 percent. "
                        + "Raise it to see blocks in dark caves and bright snow.", "Now",
                        Settings.OUTLINE_ALPHA, 40, 0, 100, 5, "%", null),
                settingToggle("Follow the shape", "The outline follows the block's real model: plants by their leaves, "
                        + "machines with every part. Off: the game's plain box.", "Now", Settings.OUTLINE_SHAPE))),
                new Folder("Creative inventory", List.of(
                        optionToggle("Operator items tab", "Show the Operator Items tab in the creative inventory: command blocks, structure blocks, barriers and light blocks. You need to be an operator and in creative mode.",
                                minecraft.options.operatorItemsTab()))));
    }

    private List<Folder> serverFolders() {
        Options o = minecraft.options;
        return List.of(
                new Folder("Distance", List.of(
                        intOption("Simulation distance", "How far from you the world runs: mobs, crops, redstone. "
                                + "The chunks ticked grow with the square of this. 12 to 8 ticks about 54 percent fewer chunks.",
                                o.simulationDistance(), 5, 32, " chunks"),
                        intOption("Render distance", "How far you see. Costs the render thread, not the world tick.",
                                o.renderDistance(), 2, 32, " chunks"),
                        numberOption("Entity distance", "How far mobs and other entities are drawn.",
                                o.entityDistanceScaling(), 100, 50, 500, "%"))),
                new Folder("Server tick", List.of(
                        settingToggle("Spread chunk saving", "Chunks you leave unload a few at a time.", "Restart", Settings.SPREAD_UNLOADS),
                        settingToggle("Far mob throttle", "Mobs far from every player think less often.", "Now", Settings.FAR_MOBS),
                        settingToggle("Parallel world generation", "New chunks get their surface and caves on all the world "
                                + "generation threads at once, not one chunk at a time. Land appears faster when you fly or "
                                + "explore. Turns itself off if it ever fails.", "Now", Settings.PARALLEL_GEN),
                        settingToggle("Lookahead", "While you move fast (flying, gliding, riding, sailing), Pentecost "
                                + "follows your path and makes the land you will see before you get there, soonest first. "
                                + "It backs off when the game is busy. Single player and worlds opened to friends; a "
                                + "dedicated server uses /pentecost pregen follow on.", "Now", Settings.FOLLOW_SOLO),
                        settingToggle("Spread new water over ticks", "Water and lava in brand-new chunks start flowing "
                                + "over the next half second instead of all in one tick, so new land does not stall the "
                                + "server. The water ends up the same.", "Now", Settings.SPREAD_WATER))),
                new Folder("Threads (prototype)", List.of(
                        settingToggle("Messenger thread", "A thread that only moves work between threads, and a worker pool. "
                                + "Anything that fails falls back to the old way and is written to logs/pentecost-messenger.txt. "
                                + "Press F6 to see what it does.", "Now", Settings.MESSENGER),
                        settingToggle("Pathfinding on workers", "Mobs search for paths on the workers while the messenger is on. "
                                + "A mob type that fails goes back to the old way by itself.", "Now", Settings.ASYNC_PATHS),
                        settingToggle("Hide mobs and machines behind walls", "Workers check which mobs, machines and chests are fully behind "
                                + "solid blocks, and the game skips drawing them. Players, named and glowing mobs always show. "
                                + "Needs the messenger on.", "Now", Settings.CULL_ENTITIES),
                        settingToggle("Mods' mobs on mod threads", "Each mod's mobs tick on that mod's own thread, at the same time as "
                                + "other mods' mobs. Their world changes land on the server thread in order. A mod whose mob fails "
                                + "goes back to the old way by itself. Needs the messenger on. Test on a copy of your world.", "Now", Settings.MOB_THREADS),
                        settingToggle("Minecraft's mobs too", "Minecraft's own mobs join the mod threads the same way. Villagers, "
                                + "traders, piglins, wardens and allays stay on the server thread, since they share village and "
                                + "memory data. Needs the switch above. Test on a copy of your world.", "Now", Settings.VANILLA_MOB_THREADS),
                        settingToggle("Mods' machines on mod threads", "Mods' machines, like Create's shafts, belts and mixers, "
                                + "tick on mod threads in groups by place, so a whole contraption stays on one thread. Block "
                                + "changes land on the server thread in order. Minecraft's own machines stay home. A mod whose "
                                + "machine fails goes back by itself. Needs the messenger on. Test on a copy of your world.", "Now", Settings.BLOCK_THREADS),
                        settingToggle("Mods' tick work on mod threads", "Each mod's every-tick work runs on its own mod thread, "
                                + "sorted by the mod thread plan: addons share their parent's thread. Groups run at the same "
                                + "time and their world changes land in order. A mod that fails goes back to the old way. "
                                + "Needs the messenger on. Test on a copy of your world.", "Now", Settings.MOD_TICKS),
                        Row.button("Open messenger report", "The file with every failure: which mod or mob, what it was doing, and the stack trace.",
                                () -> {
                                    java.io.File f = khi.pentecost.boot.Dirs.run().resolve("logs").resolve("pentecost-messenger.txt").toFile();
                                    Util.getPlatform().openFile(f.exists() ? f : f.getParentFile());
                                }))),
                new Folder("World Wide Local Server", List.of(
                        Row.button("Open or join a world", "Friends join your world with a short code. No port forwarding. "
                                + "Open a world first to host, or type a friend's code to join.",
                                () -> minecraft.setScreen(new WwlsScreen(this))))),
                new Folder("Operators and commands", List.of(
                        Row.act("Server tools", "Operators, whitelist, bans, autosave and the stop countdown. For operators on a server or in a world open to friends. Open it from the pause menu while playing.",
                                "Open", () -> ServerScreen.available(minecraft), () -> minecraft.setScreen(new ServerScreen(this))),
                        Row.act("Commands", "A terminal that runs commands in groups, saved groups, switches for whole groups of Pentecost commands, and who may use each one. For operators in a world.",
                                "Open", () -> minecraft.player != null && minecraft.player.hasPermissions(2), () -> minecraft.setScreen(new CommandsScreen(this))))),
                new Folder("Friends who cannot connect", List.of(
                        Row.button("playit.gg helper", "Steps and links for homes where friends cannot connect directly, and a box to join by address.",
                                () -> minecraft.setScreen(new PlayitScreen(this))))));
    }

    private List<Folder> profileFolders() {
        List<String> names = Profiles.names();
        List<Row> use = new ArrayList<>();
        List<Row> del = new ArrayList<>();
        for (String n : names) {
            use.add(new Row(Kind.BUTTON, n, "Switch every Pentecost setting to this setup.", "Switch every Pentecost setting to this setup.",
                    "", () -> "Use", null, () -> {
                        Profiles.apply(n);
                        built.clear();
                    }, null, () -> true));
            del.add(new Row(Kind.BUTTON, n, "Delete this saved setup. Your current settings stay as they are.",
                    "Delete this saved setup. Your current settings stay as they are.", "", () -> "Delete", null, () -> {
                        Profiles.delete(n);
                        built.remove(page);
                    }, null, () -> true));
        }
        if (use.isEmpty()) {
            use.add(Row.info("No saved setups", "Press Save current setup"));
        }
        List<Folder> out = new ArrayList<>();
        out.add(new Folder("Switch", use));
        if (!del.isEmpty()) {
            out.add(new Folder("Delete", del));
        }
        out.add(new Folder("Share", List.of(
                Row.button("Open setups folder", "Each setup is one .json file. Send a file to a friend, "
                        + "or drop one in here and press Refresh.", () -> Util.getPlatform().openFile(Profiles.folder().toFile())))));
        return out;
    }

    private List<Shortcut> profileShortcuts() {
        return List.of(
                new Shortcut("Save current setup", () -> {
                    Profiles.saveNew();
                    built.remove(page);
                }),
                new Shortcut("Refresh", () -> built.remove(page)));
    }

    @Override
    public void onClose() {
        if (harvested != null) {
            ScreenHarvest h = harvested;
            harvested = null;
            h.leave();
        }
        runPendingPick();
        if (optionsDirty) {
            optionsDirty = false;
            minecraft.options.save();
        }
        applyPacks();
        boolean menuPicked = MenuMods.takeChanged();
        minecraft.setScreen(menuPicked && parent instanceof net.minecraft.client.gui.screens.TitleScreen
                ? new net.minecraft.client.gui.screens.TitleScreen() : parent);
    }

    private void choosePick(Row r) {
        pickPending = r;
        pickPage = page;
        pickSince = System.nanoTime();
        pickReady = false;
    }

    private void runPendingPick() {
        Row r = pickPending;
        pickPending = null;
        pickPage = -1;
        pickReady = false;
        if (r != null && r.action() != null) {
            r.action().run();
        }
    }

    List<String> pageNames() {
        List<String> out = new ArrayList<>();
        for (Page p : pages) {
            out.add(p.name());
        }
        return out;
    }

    void openPage(net.minecraft.client.Minecraft mc, int index) {
        page = Math.max(0, Math.min(pages.size() - 1, index));
        scroll = 0;
        search = "";
        searchFocus = false;
        mc.setScreen(this);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        if (pickPending != null && (pickReady || pickPage != page
                || System.nanoTime() - pickSince > 600_000_000L)) {
            runPendingPick();
        }
        g.fill(0, 0, width, height, Ui.back(minecraft));
        Ui.dither(g, width, height);
        float ui = Ui.scale(minecraft);
        int vw = (int) (width / ui);
        int vh = (int) (height / ui);
        int mx = (int) (mouseX / ui);
        int my = (int) (mouseY / ui);
        hits.clear();
        sliderSpots.clear();
        hovered = null;
        g.pose().pushPose();
        g.pose().scale(ui, ui, 1);

        int margin = 48;
        int y = 26;
        Ui.logo(g, margin, y - 2, 2, Ui.TEXT);
        Ui.wordmark(g, "SETTINGS", margin + (Ui.LOGO_W + 5) * 2, y, 2, Ui.TEXT);
        String version = "Pentecost " + PentecostMain.VERSION;
        g.drawString(font, version, vw - margin - font.width(version), y + 18, Ui.FAINT, false);
        y += 28 + 12;
        g.fill(margin, y, vw - margin, y + 1, Ui.LINE);
        int top = y + 16;
        int bottom = vh - 44;

        int sideW = 118;
        int sy = top;
        for (int i = 0; i < pages.size(); i++) {
            boolean active = i == page;
            boolean hover = Ui.over(mx, my, margin - 6, sy, sideW, 18);
            if (hover && !active) {
                g.fill(margin - 6, sy, margin - 6 + sideW, sy + 18, Ui.HOVER);
            }
            sy += 20;
        }
        float barY = Ui.ease("settings:pagebar", top + page * 20, 16f);
        g.fill(margin - 6, Math.round(barY), margin - 6 + sideW, Math.round(barY) + 18, Ui.TEXT);
        sy = top;
        for (int i = 0; i < pages.size(); i++) {
            boolean active = i == page;
            boolean under = Math.abs(barY - sy) < 9;
            g.drawString(font, (active ? "> " : "") + pages.get(i).name(), margin, sy + 5,
                    under ? Ui.BLACK : Ui.over(mx, my, margin - 6, sy, sideW, 18) ? Ui.TEXT : Ui.MUTED, false);
            int index = i;
            hits.add(new Hit(margin - 6, sy, sideW, 18, () -> {
                if ("Skin".equals(pages.get(index).name())) {
                    minecraft.setScreen(new WardrobeScreen(this, this));
                    return;
                }
                page = index;
                scroll = 0;
                search = "";
                searchFocus = false;
            }));
            sy += 20;
        }

        int rightW = vw >= 820 ? 220 : 0;
        int rx = vw - margin - rightW;
        int cx = margin + sideW + 22;
        int cw = (rightW > 0 ? rx - 22 : vw - margin) - cx;

        Page p = pages.get(page);
        List<Folder> folders = folders(page);
        boolean searching = !search.isBlank();
        if (searching) {
            folders = List.of(new Folder("Results", searchRows()));
        }
        int folder = Math.min(folderOf.getOrDefault(page, 0), Math.max(0, folders.size() - 1));
        int rowsTop = top;
        if (folders.size() > 1) {
            int tx = cx;
            int ty = top;
            int pickedX = cx;
            int pickedW = 0;
            int pickedY = ty;
            for (int i = 0; i < folders.size(); i++) {
                String name = folders.get(i).name();
                int w = font.width(name);
                if (tx + w > cx + cw) {
                    tx = cx;
                    ty += 18;
                }
                boolean active = i == folder;
                boolean hover = Ui.over(mx, my, tx - 4, ty - 4, w + 8, 16);
                g.drawString(font, name, tx, ty, active ? Ui.TEXT : hover ? Ui.SOFT : Ui.MUTED, false);
                if (active) {
                    pickedX = tx;
                    pickedW = w;
                    pickedY = ty;
                }
                int index = i;
                hits.add(new Hit(tx - 4, ty - 4, w + 8, 16, () -> {
                    folderOf.put(page, index);
                    scroll = 0;
                }));
                tx += w + 16;
            }
            float ux = Ui.ease("settings:tabx:" + page, pickedX, 16f);
            float uw = Ui.ease("settings:tabw:" + page, pickedW, 16f);
            float uy = Ui.ease("settings:taby:" + page, pickedY, 16f);
            g.fill(Math.round(ux), Math.round(uy) + 11, Math.round(ux + uw), Math.round(uy) + 12, Ui.TEXT);
            rowsTop = ty + 22;
            g.fill(cx, rowsTop - 6, cx + cw, rowsTop - 5, Ui.LINE);
        }

        if (!searching && folders.size() <= 1 && "Shader pack".equals(pages.get(page).name()) && !packCrumbs().isEmpty()) {
            List<String> crumbs = packCrumbs();
            int tx = cx;
            int ty = top;
            int sep = font.width("  /  ");
            for (int i = 0; i < crumbs.size(); i++) {
                String name = crumbs.get(i);
                int w = font.width(name);
                if (tx + w > cx + cw) {
                    tx = cx;
                    ty += 18;
                }
                boolean last = i == crumbs.size() - 1;
                boolean hover = !last && Ui.over(mx, my, tx - 4, ty - 4, w + 8, 16);
                g.drawString(font, name, tx, ty, last ? Ui.TEXT : hover ? Ui.SOFT : Ui.MUTED, false);
                if (last) {
                    g.fill(tx, ty + 11, tx + w, ty + 12, Ui.TEXT);
                } else {
                    int depth = i;
                    hits.add(new Hit(tx - 4, ty - 4, w + 8, 16, () -> packGoTo(depth)));
                    g.drawString(font, "  /  ", tx + w, ty, Ui.FAINT, false);
                }
                tx += w + sep;
            }
            rowsTop = ty + 22;
            g.fill(cx, rowsTop - 6, cx + cw, rowsTop - 5, Ui.LINE);
        }
        g.enableScissor((int) (cx * ui), (int) (rowsTop * ui), (int) ((cx + cw + 4) * ui), (int) (bottom * ui));
        int py = rowsTop - (int) scroll;
        boolean packPage = !searching && "Resource packs".equals(p.name());
        List<Row> rows = packPage || folders.isEmpty() ? List.of() : folders.get(folder).rows();
        if (packPage) {
            py = packColumns(g, cx, py, cw, mx, my, rowsTop, bottom);
        }
        boolean picks = rows.stream().anyMatch(r -> r.kind() == Kind.PICK);
        int pickTarget = -1;
        for (Row r : rows) {
            if (r.kind() == Kind.PICK) {
                boolean chosen = pickPending != null && pickPage == page
                        ? pickPending.title().equals(r.title()) : r.on().getAsBoolean();
                if (chosen) {
                    pickTarget = py;
                }
                py = pick(g, r, cx, py, cw, mx, my, rowsTop, bottom);
            } else {
                py = row(g, r, cx, py, cw, mx, my, rowsTop, bottom);
            }
        }
        if (picks && pickTarget >= 0) {
            float eased = Ui.ease("settings:pick:" + page, pickTarget + (int) scroll, 14f);
            float ay = eased - (int) scroll;
            arrow(g, cx + 10, Math.round(ay) + 12, Ui.TEXT);
            if (pickPending != null && (Math.abs(eased - (pickTarget + (int) scroll)) < 0.6f
                    || System.nanoTime() - pickSince > 400_000_000L)) {
                pickReady = true;
            }
        }
        if (!searching && !p.planned().isEmpty()) {
            g.drawString(font, "COMING", cx, py + 4, Ui.FAINT, false);
            py += 18;
            for (String s : p.planned()) {
                g.drawString(font, s, cx + 4, py, Ui.MUTED, false);
                py += 14;
            }
        }
        g.disableScissor();
        scrollMax = Math.max(0, py + (int) scroll - bottom + 8);
        scroll = Math.max(0, Math.min(scrollMax, scroll));
        bar.draw(g, cx + cw + 6, rowsTop, bottom, scroll, scrollMax, mx, my);

        int sw = 200;
        int sx = vw - margin - sw;
        int sy0 = 18;
        boolean sHover = Ui.over(mx, my, sx, sy0, sw, 18);
        Ui.outline(g, sx, sy0, sx + sw, sy0 + 18, searchFocus ? Ui.TEXT : sHover ? Ui.MUTED : 0x47FFFFFF);
        if (search.isEmpty() && !searchFocus) {
            g.drawString(font, "Search settings", sx + 8, sy0 + 5, Ui.FAINT, false);
            String hint = "/";
            g.drawString(font, hint, sx + sw - 8 - font.width(hint), sy0 + 5, Ui.FAINT, false);
        } else {
            String shown = font.plainSubstrByWidth(search, sw - 24, true);
            g.drawString(font, shown, sx + 8, sy0 + 5, Ui.TEXT, false);
            if (searchFocus && (System.currentTimeMillis() / 500) % 2 == 0) {
                g.fill(sx + 9 + font.width(shown), sy0 + 4, sx + 10 + font.width(shown), sy0 + 14, Ui.TEXT);
            }
            if (!search.isEmpty()) {
                g.drawString(font, "x", sx + sw - 14, sy0 + 5, sHover ? Ui.TEXT : Ui.MUTED, false);
            }
        }
        hits.add(new Hit(sx, sy0, sw, 18, () -> {
            searchFocus = true;
        }));

        if (rightW > 0) {
            details(g, p, rx, top, rightW, bottom, mx, my);
        }

        String done = "Done";
        int dw = font.width(done) + 24;
        int dx = vw - margin - dw;
        int dy = vh - 32;
        boolean hover = Ui.over(mx, my, dx, dy, dw, 18);
        if (hover) {
            g.fill(dx, dy, dx + dw, dy + 18, Ui.TEXT);
        } else {
            Ui.outline(g, dx, dy, dx + dw, dy + 18, Ui.MUTED);
        }
        g.drawString(font, done, dx + 12, dy + 5, hover ? Ui.BLACK : Ui.TEXT, false);
        hits.add(new Hit(dx, dy, dw, 18, this::onClose));
        g.pose().popPose();
    }

    private static void arrow(GuiGraphics g, int x, int y, int color) {
        for (int r = 0; r < 7; r++) {
            int w = r <= 3 ? r + 1 : 7 - r;
            g.fill(x, y - 3 + r, x + w, y - 2 + r, color);
        }
    }

    private void details(GuiGraphics g, Page p, int x, int y, int w, int bottom, int mx, int my) {
        g.fill(x, y, x + w, bottom, 0x660A0A0B);
        Ui.outline(g, x, y, x + w, bottom, Ui.LINE);
        int ix = x + 12;
        int iw = w - 24;
        int ty = y + 12;
        g.drawString(font, "DETAILS", ix, ty, Ui.FAINT, false);
        ty += 16;
        if (hovered != null) {
            for (String line : Ui.wrap(font, hovered.title(), iw)) {
                g.drawString(font, line, ix, ty, Ui.TEXT, false);
                ty += 11;
            }
            ty += 4;
            if (!hovered.when().isEmpty()) {
                String when = "Applies " + switch (hovered.when()) {
                    case "Now" -> "right away";
                    case "Done" -> "when you press Done";
                    case "On apply" -> "when you press Apply";
                    default -> "after a restart";
                };
                g.drawString(font, when, ix, ty, Ui.SOFT, false);
                ty += 14;
            }
            String about = hovered.about().isEmpty() ? hovered.note() : hovered.about();
            for (String line : Ui.wrap(font, about, iw)) {
                if (ty > bottom - 80) {
                    break;
                }
                g.drawString(font, line, ix, ty, Ui.MUTED, false);
                ty += 11;
            }
        } else {
            for (String line : Ui.wrap(font, p.about(), iw)) {
                g.drawString(font, line, ix, ty, Ui.MUTED, false);
                ty += 11;
            }
        }
        List<Shortcut> shortcuts = p.shortcuts().get();
        if (!shortcuts.isEmpty()) {
            int sy = bottom - 12 - shortcuts.size() * 22;
            g.drawString(font, "SHORTCUTS", ix, sy - 14, Ui.FAINT, false);
            for (Shortcut s : shortcuts) {
                boolean hover = Ui.over(mx, my, ix, sy, iw, 18);
                if (hover) {
                    g.fill(ix, sy, ix + iw, sy + 18, Ui.TEXT);
                } else {
                    Ui.outline(g, ix, sy, ix + iw, sy + 18, 0x47FFFFFF);
                }
                g.drawString(font, s.label(), ix + 8, sy + 5, hover ? Ui.BLACK : Ui.TEXT, false);
                hits.add(new Hit(ix, sy, iw, 18, () -> {
                    s.action().run();
                }));
                sy += 22;
            }
        }
    }

    private int pick(GuiGraphics g, Row r, int x, int y, int w, int mx, int my, int top, int bottom) {
        boolean visible = y + 24 > top && y < bottom;
        boolean inside = my >= top && my < bottom;
        boolean hover = visible && inside && Ui.over(mx, my, x, y, w, 24);
        boolean on = pickPending != null && pickPage == page
                ? pickPending.title().equals(r.title()) : r.on().getAsBoolean();
        if (hover) {
            hovered = r;
            g.fill(x, y, x + w, y + 24, Ui.HOVER);
        }
        Ui.outline(g, x, y, x + w, y + 24, on ? Ui.TEXT : hover ? Ui.MUTED : 0x33FFFFFF);
        g.drawString(font, r.title(), x + 24, y + 8, on ? Ui.TEXT : Ui.SOFT, false);
        if (on) {
            String tag = "In use";
            g.drawString(font, tag, x + w - 10 - font.width(tag), y + 8, Ui.FAINT, false);
        }
        if (visible && inside) {
            hits.add(new Hit(x, Math.max(y, top), w, Math.min(y + 24, bottom) - Math.max(y, top), () -> choosePick(r)));
        }
        return y + 28;
    }

    private int row(GuiGraphics g, Row r, int x, int y, int w, int mx, int my, int top, int bottom) {
        if (r.kind() == Kind.SKIN) {
            return skinRow(g, x, y, w);
        }
        if (r.kind() == Kind.KEY) {
            return keyRowDraw(g, r, x, y, w, mx, my, top, bottom);
        }
        if (r.kind() == Kind.HEADER) {
            g.drawString(font, r.title().toUpperCase(Locale.ROOT), x + 4, y + 10, Ui.FAINT, false);
            return y + 24;
        }
        List<String> noteLines = r.note().isEmpty() ? List.of() : Ui.wrap(font, r.note(), w - 12);
        if (noteLines.size() > 3) {
            noteLines = new ArrayList<>(noteLines.subList(0, 3));
            String last = noteLines.get(2);
            noteLines.set(2, font.plainSubstrByWidth(last, w - 24) + "...");
        }
        int h = Math.max(28, 24 + noteLines.size() * 10 + 4);
        boolean enabled = r.enabled().getAsBoolean();
        boolean visible = y + h > top && y < bottom;
        boolean inside = my >= top && my < bottom;
        boolean over = visible && inside && Ui.over(mx, my, x, y, w, h);
        if (over) {
            hovered = r;
        }
        boolean clickable = enabled && visible && (r.action() != null || r.slider() != null);
        if (over && clickable && r.kind() != Kind.SLIDER) {
            g.fill(x, y, x + w, y + h, Ui.HOVER);
        }
        g.drawString(font, r.title(), x + 4, y + 7, enabled ? Ui.TEXT : Ui.FAINT, false);
        int right = x + w - 6;
        String key = pages.get(page).name() + ":" + r.title();
        switch (r.kind()) {
            case SWITCH -> {
                int sx = right - 24;
                Ui.toggle(g, "settings:" + key, sx, y + 6, r.on().getAsBoolean() && enabled);
                right = sx - 10;
            }
            case SLIDER -> {
                Slider s = r.slider();
                int bw = Math.min(160, w / 3);
                int bx = right - bw;
                int value = s.value().getAsInt();
                float target = s.max() > s.min() ? (value - s.min()) / (float) (s.max() - s.min()) : 0f;
                float t = Ui.ease("settings:slider:" + key, target * 1000f, 20f) / 1000f;
                g.fill(bx, y + 11, bx + bw, y + 12, Ui.FAINT);
                g.fill(bx, y + 11, bx + Math.round(bw * t), y + 12, enabled ? Ui.TEXT : Ui.FAINT);
                int kx = bx + Math.round((bw - 4) * t);
                boolean active = key.equals(dragKey);
                g.fill(kx, y + 6, kx + 4, y + 17, enabled && (over || active) ? Ui.TEXT : Ui.SOFT);
                String text = s.text().get();
                g.drawString(font, text, bx - 10 - font.width(text), y + 7, enabled ? Ui.SOFT : Ui.FAINT, false);
                right = bx - 20 - font.width(text);
                if (clickable && inside) {
                    sliderSpots.put(key, new SliderSpot(bx, bw, s));
                    hits.add(new Hit(bx - 6, y, bw + 12, Math.min(h, 24), () -> {
                        dragKey = key;
                        slide(sliderSpots.get(key));
                    }));
                }
            }
            case CHOICE, BUTTON -> {
                String v = r.value().get();
                int vwid = font.width(v);
                boolean hot = over && clickable;
                if (hot) {
                    g.fill(right - vwid - 14, y + 3, right, y + 19, Ui.TEXT);
                } else {
                    Ui.outline(g, right - vwid - 14, y + 3, right, y + 19, enabled ? 0x66FFFFFF : 0x22FFFFFF);
                }
                g.drawString(font, v, right - vwid - 7, y + 7, hot ? Ui.BLACK : enabled ? Ui.TEXT : Ui.FAINT, false);
                right -= vwid + 24;
            }
            case INFO -> {
                if (r.value() != null) {
                    String v = r.value().get();
                    g.drawString(font, v, right - font.width(v), y + 7, Ui.SOFT, false);
                    right -= font.width(v) + 10;
                }
            }
            default -> { }
        }
        if ("Restart".equals(r.when())) {
            int tw = font.width("Restart");
            Ui.outline(g, right - tw - 8, y + 5, right, y + 17, Ui.LINE);
            g.drawString(font, "Restart", right - tw - 4, y + 7, Ui.FAINT, false);
        }
        int ny = y + 21;
        for (String line : noteLines) {
            g.drawString(font, line, x + 4, ny, Ui.FAINT, false);
            ny += 10;
        }
        g.fill(x, y + h, x + w, y + h + 1, Ui.LINE);
        if (clickable && r.action() != null && inside) {
            hits.add(new Hit(x, Math.max(y, top), w, Math.min(y + h, bottom) - Math.max(y, top), r.action()));
        }
        return y + h + 1;
    }

    private void slide(SliderSpot spot) {
        if (spot == null) {
            return;
        }
        Slider s = spot.slider();
        float ui = Ui.scale(minecraft);
        double mx = lastMouseX / ui;
        float t = (float) Math.max(0, Math.min(1, (mx - spot.x()) / spot.w()));
        int raw = s.min() + Math.round(t * (s.max() - s.min()));
        int snapped = s.min() + Math.round((raw - s.min()) / (float) s.step()) * s.step();
        snapped = Math.max(s.min(), Math.min(s.max(), snapped));
        if (snapped != s.value().getAsInt()) {
            s.set().accept(snapped);
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (listening == null && searchFocus) {
            if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
                if (search.isEmpty()) {
                    searchFocus = false;
                } else {
                    search = "";
                }
            } else if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER || keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER) {
                searchFocus = false;
            } else if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_BACKSPACE && !search.isEmpty()) {
                search = hasControlDown() ? "" : search.substring(0, search.length() - 1);
                scroll = 0;
            }
            return true;
        }
        if (listening == null && (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_SLASH
                || (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_F && hasControlDown()))) {
            searchFocus = true;
            return true;
        }
        if (listening == null && keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE && !search.isEmpty()) {
            search = "";
            return true;
        }
        if (listening != null) {
            bindListening(keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE ? InputConstants.UNKNOWN
                    : InputConstants.getKey(keyCode, scanCode));
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (listening != null && button != 0) {
            bindListening(InputConstants.Type.MOUSE.getOrCreate(button));
            return true;
        }
        if (button != 0) {
            return super.mouseClicked(mouseX, mouseY, button);
        }
        lastMouseX = mouseX;
        float ui = Ui.scale(minecraft);
        double mx = mouseX / ui;
        double my = mouseY / ui;
        if (bar.press((int) mx, (int) my)) {
            scroll = bar.scrollAt((int) my);
            return true;
        }
        searchFocus = false;
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit h = hits.get(i);
            if (mx >= h.x() && mx < h.x() + h.w() && my >= h.y() && my < h.y() + h.h()) {
                minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
                h.action().run();
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dx, double dy) {
        if (bar.dragging()) {
            scroll = bar.scrollAt((int) (mouseY / Ui.scale(minecraft)));
            return true;
        }
        if (dragKey != null) {
            lastMouseX = mouseX;
            slide(sliderSpots.get(dragKey));
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        bar.release();
        if (dragKey != null) {
            if (embeddium != null) {
                ExternalOptions.apply(embeddium);
            }
            minecraft.options.save();
            if (menuAfter != null) {
                Runnable r = menuAfter;
                menuAfter = null;
                r.run();
            }
        }
        dragKey = null;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean charTyped(char c, int modifiers) {
        if (searchFocus && listening == null && c >= ' ' && search.length() < 40) {
            search += c;
            scroll = 0;
            return true;
        }
        return super.charTyped(c, modifiers);
    }

    private List<Row> searchRows() {
        String q = search.toLowerCase(Locale.ROOT).trim();
        if (q.equals(searchBuilt)) {
            return searchOut;
        }
        List<Row> out = new ArrayList<>();
        for (int i = 0; i < pages.size(); i++) {
            String pageName = pages.get(i).name();
            if (pageName.equals("Shader pack") || pageName.equals("Resource packs") || !pages.get(i).planned().isEmpty()) {
                continue;
            }
            for (Folder f : folders(i)) {
                List<Row> found = new ArrayList<>();
                for (Row r : f.rows()) {
                    if (r.kind() == Kind.HEADER || r.kind() == Kind.SKIN) {
                        continue;
                    }
                    String hay = (r.title() + " " + r.note() + " " + r.about() + " " + f.name() + " " + pageName).toLowerCase(Locale.ROOT);
                    if (hay.contains(q)) {
                        found.add(r);
                    }
                }
                if (!found.isEmpty()) {
                    out.add(Row.header(pageName + "  /  " + f.name()));
                    out.addAll(found);
                }
            }
        }
        if (out.isEmpty()) {
            out.add(Row.info("No settings match", search));
        }
        searchBuilt = q;
        searchOut = out;
        return out;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        scroll = Math.max(0, Math.min(scrollMax, scroll - delta * 24));
        return true;
    }

    @Override
    public boolean isPauseScreen() {
        return true;
    }
}
