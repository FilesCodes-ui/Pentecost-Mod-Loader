package khi.pentecost.boot;

import khi.pentecost.mods.LoaderKind;
import khi.pentecost.mods.ModInfo;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StartupGuardTest {
    private static ModInfo mod(String id, String jar, String... deps) {
        return new ModInfo(id, id, "1", LoaderKind.FORGE, Path.of(jar), Map.of(), List.of(deps), List.of(), List.of(), null);
    }

    @Test
    void firstCrashRetriesSecondTurnsOff() {
        assertEquals(StartupGuard.Decision.RETRY, StartupGuard.decide(0, "", 0, "create"));
        assertEquals(StartupGuard.Decision.TURN_OFF, StartupGuard.decide(1, "create", 1, "create"));
        assertEquals(StartupGuard.Decision.RETRY, StartupGuard.decide(1, "create", 1, "jei"), "a different mod starts over");
        assertEquals(StartupGuard.Decision.GIVE_UP, StartupGuard.decide(StartupGuard.MAX_STREAK, "create", 1, "create"));
    }

    @Test
    void onlyStartupCrashesCount() {
        assertTrue(StartupGuard.isStartupCrash("Time: now\nDescription: Initializing game\n"));
        assertTrue(!StartupGuard.isStartupCrash("Time: now\nDescription: Rendering entity in world\n"));
        assertTrue(!StartupGuard.isStartupCrash(null));
    }

    @Test
    void unknownModNeverTurnsAnythingOff() {
        assertEquals(StartupGuard.Decision.RETRY, StartupGuard.decide(1, "", 1, null));
    }

    @Test
    void findsTheModInForgeAndFabricErrors() {
        assertEquals("create", StartupGuard.failingMod(
                "ModLoadingException: Create (create) encountered an error during the common_setup event phase", null));
        assertEquals("sodium", StartupGuard.failingMod(
                "Could not execute entrypoint stage 'main' due to errors, provided by 'sodium'!", null));
        assertEquals("jei", StartupGuard.failingMod("nothing useful", "jei"));
        assertNull(StartupGuard.failingMod("nothing useful", "minecraft"));
    }

    @Test
    void takesEveryModThatNeedsIt() {
        List<ModInfo> mods = List.of(
                mod("create", "create.jar"),
                mod("powergrid", "powergrid.jar", "create"),
                mod("powergrid_addon", "pga.jar", "powergrid"),
                mod("ponder", "create.jar"),
                mod("jei", "jei.jar", "minecraft"),
                mod("minecraft", "mc.jar"));
        List<String> with = StartupGuard.withDependents("create", mods);
        assertTrue(with.contains("powergrid"));
        assertTrue(with.contains("powergrid_addon"), "needs it through another mod");
        assertTrue(with.contains("ponder"), "lives in the same jar");
        assertTrue(!with.contains("jei") && !with.contains("minecraft") && !with.contains("create"));
    }
}
