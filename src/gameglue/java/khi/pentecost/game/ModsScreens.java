package khi.pentecost.game;

import khi.pentecost.boot.Log;
import khi.pentecost.control.Choices;
import khi.pentecost.core.Hooks;
import khi.pentecost.core.PentecostRuntime;
import net.minecraft.client.Minecraft;
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

    private ModsScreens() {}

    /** Called once when the game starts. */
    public static void init() {
        Hooks.packSources = ModPacks::extend;
        Hooks.screenRouter = o -> route((Screen) o);
        Hooks.screenFinalizer = (asked, shown) -> settle((Screen) asked, (Screen) shown);
    }

    /** The game calls this first for every screen it is asked to show. */
    public static Screen route(Screen incoming) {
        String name = incoming.getClass().getName();
        // Test switch: PENTECOST_TEST_SCREEN=mods opens the list once the main menu shows.
        if (!tested && "mods".equals(System.getenv("PENTECOST_TEST_SCREEN")) && incoming instanceof TitleScreen) {
            tested = true;
            Log.info("Test: opening the mods screen");
            return new PentecostModsScreen(incoming);
        }
        if (OTHER_MODS_SCREENS.contains(name)) {
            return new PentecostModsScreen(Minecraft.getInstance().screen);
        }
        return incoming;
    }

    /**
     * The game calls this last, with the screen it was asked for and the screen it is about to show.
     * They differ when a mod swapped one for the other.
     */
    public static Screen settle(Screen asked, Screen shown) {
        if (shown == null || shown == asked) {
            return shown;
        }
        Minecraft mc = Minecraft.getInstance();
        // With no world open, asking for "no screen" means the main menu.
        String askedName = asked != null ? asked.getClass().getName()
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
        return asked != null ? asked : new TitleScreen();
    }
}
