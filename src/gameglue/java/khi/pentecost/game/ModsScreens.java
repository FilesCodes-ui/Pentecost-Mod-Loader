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

public final class ModsScreens {
    private static final Set<String> OTHER_MODS_SCREENS = Set.of(
            "net.minecraftforge.client.gui.ModListScreen",
            "com.terraformersmc.modmenu.gui.ModsScreen");

    private static boolean tested;
    private static boolean testedSettings;
    private static boolean testedUpdate;

    private ModsScreens() {}

    public static void init() {
        Hooks.packSources = ModPacks::extend;
        PentecostPlayers.install();
        try {
            Class.forName("khi.pentecost.forgeglue.ModHandlers", true, khi.pentecost.core.PentecostRuntime.loader != null ? khi.pentecost.core.PentecostRuntime.loader : ModsScreens.class.getClassLoader()).getMethod("install").invoke(null);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            khi.pentecost.boot.Log.info("Mod tick threads not available: " + e);
        }
        khi.pentecost.core.MenuGuard.impl = new khi.pentecost.core.MenuGuard.Impl() {
            @Override
            public void beforeRealLevel() {
                khi.pentecost.game.menuworld.MenuHost.beforeRealLevel();
            }

            @Override
            public Object music() {
                return khi.pentecost.game.menuworld.MenuHost.showing() ? net.minecraft.sounds.Musics.MENU : null;
            }
        };
        Minecraft early = Minecraft.getInstance();
        if (early != null && early.gameDirectory != null) {
            FriendsScreen.useConfig(early);
            FriendsScreen.startWatcher(early);
        }
        if (early != null) {
            early.execute(() -> {
                try {
                    Account.frame();
                } catch (RuntimeException e) {
                    khi.pentecost.boot.Log.error("Cannot restore the account early: " + e);
                }
            });
        }
        khi.pentecost.core.Hooks.toasts = PentecostToast::render;
        ShapeOutline.install();
        OnlineSession.install();
        OnlineFriends.install();
        FeedHooks.install();
        DiscordStatus.install();
        khi.pentecost.core.ZoomHooks.press = PentecostToast::press;
        ProbeGame.install();
        PortalPassGame.install();
        Hooks.frame = () -> {
            PerfHud.frame();
            khi.pentecost.probe.Probe.frame();
            Zoom.frame();
            Stall.frame();
            Account.frame();
            CreativePreload.frame();
            Wardrobe.frame();
            khi.pentecost.messenger.Threads.frame();
            khi.pentecost.core.ModImpact.frame();
            EntityCulling.frame();
            PortalRefresh.frame();
            GuardUi.frame();
            PortalShadowCheck.install();
            if (Minecraft.getInstance().screen instanceof net.minecraft.client.gui.screens.TitleScreen
                    || Minecraft.getInstance().level != null) {
                khi.pentecost.translator.ShadowRun.maybeStart();
            }
            Updater.frame();
            WindowIcon.frame();
            testPause();
            khi.pentecost.game.menuworld.MenuHost.release(Minecraft.getInstance());
            DhProbe.tick();
            RunawayToast.install();
            DhTune.frame();
            CpuGovernor.frame();
            if (!(Minecraft.getInstance().screen instanceof net.minecraft.client.gui.screens.TitleScreen)
                    && khi.pentecost.game.menuworld.MenuHost.staysBehindMenus()) {
                khi.pentecost.game.menuworld.MenuHost.frame(Minecraft.getInstance());
            }
            khi.pentecost.game.pregen.Pregen.forgetStoppedServer(Minecraft.getInstance());
        };
        WidgetStyle.install();
        khi.pentecost.core.ZoomHooks.frameStart = PauseCinema::frame;
        Zoom.install();
        OrbitCamera.install();
        PentecostCape.install();
        NameTags.install();
        ChatStyle.install();
        FarMobs.install();
        SoundControl.install();
        Hooks.windowHints = WindowMode::beforeWindow;
        Hooks.windowBorn = WindowMode::windowBorn;
        Hooks.iconSet = WindowIcon::apply;
        Hooks.screenRouter = o -> route((Screen) o);
        Hooks.overlayRouter = o -> o instanceof net.minecraft.client.gui.screens.LoadingOverlay lo
                && khi.pentecost.control.Settings.on(khi.pentecost.control.Settings.LOADING)
                ? PentecostLoadingOverlay.replace(lo) : o;
        Hooks.screenFinalizer = (asked, shown) -> settle((Screen) asked, (Screen) shown);
    }

    private static int pauseWait = -1;

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

    private static Screen videoParent(Screen video) {
        try {
            java.lang.reflect.Field f = net.minecraft.client.gui.screens.OptionsSubScreen.class.getDeclaredField("lastScreen");
            f.setAccessible(true);
            return (Screen) f.get(video);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return Minecraft.getInstance().screen;
        }
    }

    public static Screen route(Screen incoming) {
        String name = incoming.getClass().getName();
        if (incoming instanceof TitleScreen && khi.pentecost.auth.DevFeed.gate() != null) {
            khi.pentecost.boot.Splash.close();
            return new GateScreen(khi.pentecost.auth.DevFeed.gate());
        }
        if (incoming instanceof TitleScreen) {
            khi.pentecost.boot.Splash.close();
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
            boolean video = "video".equals(test);
            mc.tell(() -> mc.setScreen(video ? new net.minecraft.client.gui.screens.VideoSettingsScreen(mc.screen, mc.options)
                    : new net.minecraft.client.gui.screens.OptionsScreen(mc.screen, mc.options)));
            return incoming;
        }
        if (!testedSettings && "settings".equals(System.getenv("PENTECOST_TEST_SCREEN")) && incoming instanceof TitleScreen) {
            testedSettings = true;
            Log.info("Test: opening Pentecost settings");
            return new PentecostSettingsScreen(new PentecostTitleScreen());
        }
        if (!tested && "mods".equals(System.getenv("PENTECOST_TEST_SCREEN")) && incoming instanceof TitleScreen) {
            tested = true;
            Log.info("Test: opening the mods screen");
            return new PentecostModsScreen(incoming);
        }
        if (!testedUpdate && incoming instanceof TitleScreen && ("update".equals(System.getenv("PENTECOST_TEST_SCREEN"))
                || "updatetoast".equals(System.getenv("PENTECOST_TEST_SCREEN")))) {
            testedUpdate = true;
            Log.info("Test: showing the update " + ("update".equals(System.getenv("PENTECOST_TEST_SCREEN")) ? "prompt" : "toast"));
            return Updater.preview(incoming, "update".equals(System.getenv("PENTECOST_TEST_SCREEN")));
        }
        if (EulaScreen.leadsOnline(name) && !EulaScreen.agreed()) {
            return EulaScreen.gate(Minecraft.getInstance().screen, incoming);
        }
        if (!Account.signedIn() && Account.gated(name)) {
            Screen back = Minecraft.getInstance().screen;
            return new PentecostLoginScreen(back, incoming, "Multiplayer and Realms need a Minecraft account. Sign in to open them.");
        }
        if (name.equals(ExternalOptions.SHADER_SCREEN) && !ExternalOptions.bypass
                && khi.pentecost.control.Settings.on(khi.pentecost.control.Settings.RESTYLE)) {
            Screen back = ExternalOptions.shaderParentOf(incoming);
            return new PentecostSettingsScreen(back != null ? back : Minecraft.getInstance().screen, "Shaders");
        }
        if (name.equals(ExternalOptions.EMBEDDIUM_SCREEN) && !ExternalOptions.bypass
                && khi.pentecost.control.Settings.on(khi.pentecost.control.Settings.RESTYLE)) {
            Screen back = ExternalOptions.parentOf(incoming);
            return new PentecostSettingsScreen(back != null ? back : Minecraft.getInstance().screen, "Video");
        }
        String optionPage = optionPageFor(incoming);
        if (optionPage != null && !ExternalOptions.bypass
                && khi.pentecost.control.Settings.on(khi.pentecost.control.Settings.RESTYLE)
                && !khi.pentecost.control.Settings.on(khi.pentecost.control.Settings.VANILLA_SETTINGS)) {
            return new PentecostSettingsScreen(lastScreenOf(incoming), optionPage);
        }
        if (name.equals("net.minecraft.client.gui.screens.ShareToLanScreen")
                && !khi.pentecost.control.Settings.on(khi.pentecost.control.Settings.VANILLA_SETTINGS)) {
            return new WwlsScreen(lastScreenOf(incoming));
        }
        if (OTHER_MODS_SCREENS.contains(name)) {
            return new PentecostModsScreen(Minecraft.getInstance().screen);
        }
        if (incoming.getClass() == net.minecraft.client.gui.screens.worldselection.SelectWorldScreen.class
                && khi.pentecost.control.Settings.on(khi.pentecost.control.Settings.MENUS)) {
            return new PentecostWorldsScreen(lastScreenOf(incoming));
        }
        if (incoming.getClass() == net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen.class
                && khi.pentecost.control.Settings.on(khi.pentecost.control.Settings.MENUS)) {
            return new PentecostServersScreen(lastScreenOf(incoming));
        }
        if (incoming == PentecostCreateWorldScreen.showVanilla) {
            PentecostCreateWorldScreen.showVanilla = null;
            return incoming;
        }
        if (incoming.getClass() == net.minecraft.client.gui.screens.worldselection.CreateWorldScreen.class
                && khi.pentecost.control.Settings.on(khi.pentecost.control.Settings.MENUS)) {
            return new PentecostCreateWorldScreen((net.minecraft.client.gui.screens.worldselection.CreateWorldScreen) incoming);
        }
        if (khi.pentecost.control.Settings.on(khi.pentecost.control.Settings.MENUS)) {
            if (incoming.getClass() == net.minecraft.client.gui.screens.LevelLoadingScreen.class) {
                Screen made = PentecostLevelLoadingScreen.replace((net.minecraft.client.gui.screens.LevelLoadingScreen) incoming);
                if (made != null) {
                    return made;
                }
            }
            if (incoming.getClass() == net.minecraft.client.gui.screens.GenericDirtMessageScreen.class) {
                return new PentecostLevelLoadingScreen.Message(incoming.getTitle());
            }
            Screen flow = null;
            if (incoming.getClass() == net.minecraft.client.gui.screens.ConnectScreen.class) {
                flow = PentecostFlowScreens.connecting(incoming);
            } else if (incoming.getClass() == net.minecraft.client.gui.screens.DisconnectedScreen.class) {
                flow = PentecostFlowScreens.disconnected(incoming);
            } else if (incoming.getClass() == net.minecraft.client.gui.screens.ConfirmScreen.class) {
                flow = PentecostFlowScreens.confirm(incoming);
            }
            if (flow != null) {
                return flow;
            }
            if (incoming.getClass() == net.minecraft.client.gui.screens.ReceivingLevelScreen.class) {
                return new PentecostLevelLoadingScreen.Receiving();
            }
        }
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

    private static String optionPageFor(Screen s) {
        String n = s.getClass().getName();
        if (n.equals("net.minecraft.client.gui.screens.controls.KeyBindsScreen")
                || n.equals("net.minecraft.client.gui.screens.controls.ControlsScreen")) {
            return "Controls";
        }
        if (n.equals("net.minecraft.client.gui.screens.SkinCustomizationScreen")) {
            return "Skin";
        }
        switch (n) {
            case "net.minecraft.client.gui.screens.OptionsScreen":
                return "General";
            case "net.minecraft.client.gui.screens.SoundOptionsScreen":
                return "Sound";
            case "net.minecraft.client.gui.screens.ChatOptionsScreen":
                return "Chat";
            case "net.minecraft.client.gui.screens.LanguageSelectScreen":
                return "Language";
            case "net.minecraft.client.gui.screens.AccessibilityOptionsScreen":
                return "Accessibility";
            case "net.minecraft.client.gui.screens.MouseSettingsScreen":
                return "Controls";
            default:
                break;
        }
        if (n.equals("net.minecraft.client.gui.screens.packs.PackSelectionScreen")
                && mcScreen() instanceof net.minecraft.client.gui.screens.OptionsScreen) {
            return "Resource packs";
        }
        return null;
    }

    private static Screen lastScreenOf(Screen s) {
        for (Class<?> c = s.getClass(); c != null && c != Screen.class; c = c.getSuperclass()) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField("lastScreen");
                f.setAccessible(true);
                return (Screen) f.get(s);
            } catch (ReflectiveOperationException | RuntimeException e) {
            }
        }
        return mcScreen();
    }

    private static Screen mcScreen() {
        return Minecraft.getInstance().screen;
    }

    private static java.lang.reflect.Field showPauseMenu;

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

    private static String vanillaName(Screen s) {
        if (s instanceof PentecostTitleScreen) {
            return TitleScreen.class.getName();
        }
        if (s instanceof PentecostPauseScreen) {
            return PauseScreen.class.getName();
        }
        return s.getClass().getName();
    }

    public static Screen settle(Screen asked, Screen shown) {
        PauseCinema.screenChanging(shown);
        if (System.getenv("PENTECOST_TEST_SCREEN") != null) {
            Log.info("Test: screen asked " + (asked == null ? "none" : asked.getClass().getName()) + ", shown "
                    + (shown == null ? "none" : shown.getClass().getName()));
        }
        boolean embeddiumScreen = shown != null && shown.getClass().getName().equals(ExternalOptions.EMBEDDIUM_SCREEN);
        boolean gameVideo = shown != null && shown.getClass() == net.minecraft.client.gui.screens.VideoSettingsScreen.class
                && ExternalOptions.hasEmbeddium();
        if ((embeddiumScreen || gameVideo) && !ExternalOptions.bypass
                && khi.pentecost.control.Settings.on(khi.pentecost.control.Settings.RESTYLE)) {
            Screen back = embeddiumScreen ? ExternalOptions.parentOf(shown) : videoParent(shown);
            return new PentecostSettingsScreen(back, "Video");
        }
        if (shown != null && shown.getClass().getName().equals(ExternalOptions.SHADER_SCREEN) && !ExternalOptions.bypass
                && khi.pentecost.control.Settings.on(khi.pentecost.control.Settings.RESTYLE)) {
            Screen back = ExternalOptions.shaderParentOf(shown);
            return new PentecostSettingsScreen(back != null ? back : mcScreen(), "Shaders");
        }
        if (shown != null && shown == asked) {
            return reopened(shown);
        }
        if (shown == null) {
            return shown;
        }
        Minecraft mc = Minecraft.getInstance();
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
