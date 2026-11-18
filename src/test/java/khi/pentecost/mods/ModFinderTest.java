package khi.pentecost.mods;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModFinderTest {
    private static ModInfo mod(String id, String name, LoaderKind k) {
        return new ModInfo(id, name, "1", k, null, Map.of(), List.of(), List.of(), List.of(), null);
    }

    private static final List<ModInfo> MODS = List.of(
            mod("create", "Create", LoaderKind.FORGE),
            mod("cloth_config", "Cloth Config v11", LoaderKind.FORGE),
            mod("jei", "Just Enough Items", LoaderKind.FORGE),
            mod("xaerominimap", "Xaero's Minimap", LoaderKind.FORGE),
            mod("xaeroworldmap", "Xaero's World Map", LoaderKind.FORGE),
            mod("somelib", "API", LoaderKind.LIBRARY),
            mod("create_addon", "Create Addon", LoaderKind.FORGE),
            mod("sound_physics_remastered", "Sound Physics Remastered", LoaderKind.FORGE));

    private static String find(String id, boolean skipLibs) {
        return ModFinder.search(MODS, id, skipLibs).map(ModInfo::id).orElse(null);
    }

    @Test
    void exactIdWins() {
        assertEquals("create", find("create", true));
    }

    @Test
    void spellingIsEvenedOut() {
        assertEquals("cloth_config", find("cloth-config", true));
        assertEquals("jei", find("jei-fabric", true));
        assertEquals("create", find("create_fabric", true));
        assertEquals("create", find("Create-Forge", true));
    }

    @Test
    void displayNameMatches() {
        assertEquals("jei", find("just-enough-items", true));
    }

    @Test
    void commonWordsNeverMatch() {
        assertEquals(null, find("fabric-api", false));
        assertEquals(null, find("fabric", false));
    }

    @Test
    void unknownStaysMissing() {
        assertEquals(null, find("sodium", true));
        assertTrue(ModFinder.search(MODS, "", true).isEmpty());
    }

    @Test
    void wordPartsMatchWithFewestExtras() {
        assertEquals("create_addon", find("create_fabric_addon", true));
        assertEquals("sound_physics_remastered", find("sound-physics-fabric", true));
    }

    @Test
    void wordTieGivesNoAnswer() {
        // "xaero" is in both Xaero mods: no guess.
        assertEquals(null, find("xaero", true));
    }

    @Test
    void wordsNeverMatchInsideAWord() {
        List<ModInfo> only = List.of(mod("createaddition", "Create Crafts & Additions", LoaderKind.FORGE));
        assertEquals(null, ModFinder.search(only, "create_fabric_x", true).map(ModInfo::id).orElse(null));
    }

    @Test
    void evenRemovesLoaderWords() {
        assertEquals("create", ModFinder.even("create-fabric"));
        assertEquals("clothconfig", ModFinder.even("Cloth_Config"));
    }
}
