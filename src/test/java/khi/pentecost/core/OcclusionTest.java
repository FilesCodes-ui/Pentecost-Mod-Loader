package khi.pentecost.core;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OcclusionTest {
    private static Occlusion.Solid walls(Set<String> cells) {
        return (x, y, z) -> cells.contains(x + "," + y + "," + z);
    }

    private static Set<String> wallAtX(int wx, int from, int to) {
        Set<String> s = new HashSet<>();
        for (int y = from; y <= to; y++) {
            for (int z = from; z <= to; z++) {
                s.add(wx + "," + y + "," + z);
            }
        }
        return s;
    }

    @Test
    void openAirIsClear() {
        assertTrue(Occlusion.clear(0.5, 0.5, 0.5, 10.5, 3.5, -4.5, walls(Set.of()), 100));
    }

    @Test
    void aWallBlocksAndAGapDoesNot() {
        Set<String> wall = wallAtX(5, -10, 10);
        assertFalse(Occlusion.clear(0.5, 0.5, 0.5, 10.5, 0.5, 0.5, walls(wall), 100));
        wall.remove("5,0,0");
        assertTrue(Occlusion.clear(0.5, 0.5, 0.5, 10.5, 0.5, 0.5, walls(wall), 100));
    }

    @Test
    void theCamerasAndTheTargetsOwnBlocksNeverBlock() {
        Set<String> s = new HashSet<>(Set.of("0,0,0", "10,0,0"));
        assertTrue(Occlusion.clear(0.5, 0.5, 0.5, 10.5, 0.5, 0.5, walls(s), 100));
    }

    @Test
    void aMobPeekingOverAWallIsVisible() {
        // Wall from y=-10 to y=1. The mob's box reaches y=2.5, above it.
        Set<String> wall = new HashSet<>();
        for (int y = -10; y <= 1; y++) {
            for (int z = -10; z <= 10; z++) {
                wall.add("5," + y + "," + z);
            }
        }
        assertTrue(Occlusion.visible(0.5, 2.6, 0.5, 9.7, 0.0, 0.2, 10.3, 2.5, 0.8, walls(wall)));
        // A full wall hides it.
        assertFalse(Occlusion.visible(0.5, 0.5, 0.5, 9.7, 0.0, 0.2, 10.3, 1.8, 0.8, walls(wallAtX(5, -10, 10))));
    }

    @Test
    void negativeCoordinatesWork() {
        Set<String> wall = wallAtX(-5, -10, 10);
        assertFalse(Occlusion.clear(0.5, 0.5, 0.5, -10.5, 0.5, 0.5, walls(wall), 100));
        assertTrue(Occlusion.clear(0.5, 0.5, 0.5, -3.5, 0.5, 0.5, walls(wall), 100));
    }

    @Test
    void hiddenListLookups() {
        CullHooks.set(new int[]{3, 9, 42});
        assertTrue(CullHooks.hidden(9));
        assertFalse(CullHooks.hidden(10));
        CullHooks.clear();
        assertFalse(CullHooks.hidden(9));
        assertArrayEquals(new int[0], new int[CullHooks.count()]);
    }
}
