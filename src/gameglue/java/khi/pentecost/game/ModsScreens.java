package khi.pentecost.game;

import khi.pentecost.boot.Log;
import khi.pentecost.control.Choices;
import khi.pentecost.core.Hooks;
import khi.pentecost.core.PentecostRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;

import java.util.Set;

/**
 * Pentecost owns two decisions about screens.
 *
 * The Mods button: whatever loader's mods screen a button asks for, Pentecost's own list opens.
 *
 * Screen swaps: when a mod shows its own screen in place of one of the game's (a custom main menu),
 * Pentecost notes which mod did it, and puts the game's screen back if the player turned that swap off.
 */
public final class ModsScreens {
    /** Mods screens of the loaders Pentecost runs. A request for any of them opens Pentecost's list. */
    private static final Set<String> OTHER_MODS_SCREENS = Set.of(
            "net.minecraftforge.client.gui.ModListScreen",
            "com.terraformersmc.modmenu.gui.ModsScreen");

    private static boolean tested;
    private static boolean testedSettings;

    private ModsScreens() {}

    /** Called once when the game starts. */
    public static void init() {
        Hooks.packSources = ModPacks::extend;
        Hooks.frame = () -> {
            PerfHud.frame();
            testPause();
        };
        WidgetStyle.install();
        Hooks.screenRouter = o -> route((Screen) o);
        Hooks.overlayRouter = o -> o instanceof net.minecraft.client.gui.screens.LoadingOverlay lo
                && khi.pentecost.control.Settings.on(khi.pentecost.control.Settings.LOADING)
                ? PentecostLoadingOverlay.replace(lo) : o;
        Hooks.screenFinalizer = (asked, shown) -> settle((Screen) asked, (Screen) shown);
    }

    private static int pauseWait = -1;

    /** Test switch: PENTECOST_TEST_SCREEN=pause opens the pause menu about two seconds into a world. */
    private static void testPause() {
        String test = System.getenv("PENTECOST_TEST_SCREEN");
        if (pauseWait == -2 || !("pause".equals(test) || "f3".equals(test))) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || (mc.screen != null && !"f3".equals(test))) {
            return;
        }
        if (pauseWait < 0) {
            pauseWait = 120;
        } else if (--pauseWait == 0) {
            pauseWait = -2;
            if ("f3".equals(test)) {
                Log.info("Test: showing F3");
                mc.options.renderDebug = true;
            } else {
                Log.info("Test: opening the pause menu");
                mc.pauseGame(false);
            }
        }
    }

    /** The game calls this first for every screen it is asked to show. */
    public static Screen route(Screen incoming) {
        String name = incoming.getClass().getName();
        if (incoming instanceof TitleScreen) {
            khi.pentecost.core.Timeline.mark("Main menu");
            WindowMode.applySaved();
        }
        String test = System.getenv("PENTECOST_TEST_SCREEN");
        if (!testedSettings && incoming instanceof TitleScreen && "newworld".equals(test)) {
            testedSettings = true;
            Minecraft mc = Minecraft.getInstance();
            Log.info("Test: opening Create New World");
            mc.tell(() -> net.minecraft.client.gui.screens.worldselection.CreateWorldScreen.openFresh(mc, mc.screen));
            return incoming;
        }
        if (!testedSettings && incoming instanceof TitleScreen && ("options".equals(test) || "video".equals(test))) {
            testedSettings = true;
            Minecraft mc = Minecraft.getInstance();
            Log.info("Test: opening " + test);
            Screen back = new PentecostTitleScreen();
            return "video".equals(test) ? new net.minecraft.client.gui.screens.VideoSettingsScreen(back, mc.options)
                    : new net.minecraft.client.gui.screens.OptionsScreen(back, mc.options);
        }
        if (!testedSettings && "settings".equals(System.getenv("PENTECOST_TEST_SCREEN")) && incoming instanceof TitleScreen) {
            testedSettings = true;
            Log.info("Test: opening Pentecost settings");
            return new PentecostSettingsScreen(new PentecostTitleScreen());
        }
        // Test switch: PENTECOST_TEST_SCREEN=mods opens the list once the main menu shows.
        if (!tested && "mods".equals(System.getenv("PENTECOST_TEST_SCREEN")) && incoming instanceof TitleScreen) {
            tested = true;
            Log.info("Test: opening the mods screen");
            return new PentecostModsScreen(incoming);
        }
        if (OTHER_MODS_SCREENS.contains(name)) {
            return new PentecostModsScreen(Minecraft.getInstance().screen);
        }
        // The game's own menus get Pentecost's layout. Mods still see the game's menu underneath.
        if (khi.pentecost.control.Settings.on(khi.pentecost.control.Settings.MENUS)) {
            if (incoming.getClass() == TitleScreen.class) {
                return new PentecostTitleScreen();
            }
            if (incoming.getClass() == PauseScreen.class) {
                return new PentecostPauseScreen(showsMenu((PauseScreen) incoming));
            }
        }
        return incoming;
    }

    private static java.lang.reflect.Field showPauseMenu;

    /** False for the small pause screen (F3+Esc), which shows only the title. */
    private static boolean showsMenu(PauseScreen screen) {
        try {
            if (showPauseMenu == null) {
                showPauseMenu = PauseScreen.class.getDeclaredField("showPauseMenu");
                showPauseMenu.setAccessible(true);
            }
            return showPauseMenu.getBoolean(screen);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return true;
        }
    }

    /**
     * A mod's screen opened directly, not as a swap. If it once replaced one of the game's menus
     * and the player turned that off, the game's menu shows instead.
     */
    private static Screen reopened(Screen shown) {
        String name = shown.getClass().getName();
        if (name.startsWith("net.minecraft.") || name.startsWith("khi.pentecost.") || PentecostRuntime.loader == null) {
            return shown;
        }
        String owner = PentecostRuntime.loader.modOf(name);
        if (owner == null) {
            return shown;
        }
        for (java.util.Map.Entry<String, String> e : Choices.seenScreens(owner).entrySet()) {
            if (!e.getValue().equals(name) || !Choices.isOff(owner, "screen:" + e.getKey())) {
                continue;
            }
            if (e.getKey().equals(TitleScreen.class.getName())) {
                return route(new TitleScreen());
            }
            if (e.getKey().equals(PauseScreen.class.getName())) {
                return route(new PauseScreen(true));
            }
        }
        return shown;
    }

    /** The game's class name for a screen, so Pentecost's menus count as the game's own. */
    private static String vanillaName(Screen s) {
        if (s instanceof PentecostTitleScreen) {
            return TitleScreen.class.getName();
        }
        if (s instanceof PentecostPauseScreen) {
            return PauseScreen.class.getName();
        }
        return s.getClass().getName();
    }

    /**
     * The game calls this last, with the screen it was asked for and the screen it is about to show.
     * They differ when a mod swapped one for the other.
     */
    public static Screen settle(Screen asked, Screen shown) {
        if (shown != null && shown == asked) {
            return reopened(shown);
        }
        if (shown == null) {
            return shown;
        }
        Minecraft mc = Minecraft.getInstance();
        // With no world open, asking for "no screen" means the main menu.
        String askedName = asked != null ? vanillaName(asked)
                : mc.level == null ? TitleScreen.class.getName() : null;
        String shownName = shown.getClass().getName();
        if (askedName == null || !askedName.startsWith("net.minecraft.")
                || shownName.startsWith("net.minecraft.") || shownName.startsWith("khi.pentecost.")) {
            return shown;
        }
        String owner = PentecostRuntime.loader == null ? null : PentecostRuntime.loader.modOf(shownName);
        if (owner == null) {
            return shown;
        }
        Choices.seeScreen(owner, askedName, shownName);
        if (!Choices.isOff(owner, "screen:" + askedName)) {
            return shown;
        }
        return asked != null ? asked : route(new TitleScreen());
    }
}
