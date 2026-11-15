package khi.pentecost.game.menuworld;

import java.util.List;

/**
 * Seeds picked from published 1.20 lists, with where the list said something worth seeing is. None of these
 * has been looked at in game yet. The anchor is only a starting point: the camera director scores the land
 * around it and finds its own views.
 */
public final class SeedCatalog {
    private SeedCatalog() {}

    public record Entry(String name, long seed, int anchorX, int anchorZ, String source) {}

    public static final List<Entry> ALL = List.of(
            new Entry("Cherry village", 4556775L, -1792, 544, "Village beside a cherry grove (Badlion, 1.20)"),
            new Entry("Cherry mines", -769185976586290351L, -125, -500, "Cherry groves and a village (Pro Game Guides, 1.20)"),
            new Entry("Cherry forest", -5592633653095784779L, -812, 1376, "Large cherry forest (1.20 list)"),
            new Entry("Canyon village", 6542604540806855638L, 0, 0, "Village in a canyon ringed by cherry grove (Sportskeeda, 1.20)"),
            new Entry("Mansion woods", -656589980139732745L, 24, -18, "Dark forest mansion, grove, lush cave (GameSkinny, 1.20.1)"),
            new Entry("Pink petals", -3779232957303710202L, 0, 0, "Cherry grove over ancient cities (Pro Game Guides, 1.20.1)"));

    public static Entry defaultEntry() {
        return ALL.get(0);
    }
}
