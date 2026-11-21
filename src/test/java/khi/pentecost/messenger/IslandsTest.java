package khi.pentecost.messenger;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class IslandsTest {
    @Test
    void touchingCellsShareAThread() {
        int[] t = Islands.assign(new int[]{0, 1, 1, 10}, new int[]{0, 0, 1, 10}, 4);
        assertEquals(t[0], t[1]);
        assertEquals(t[1], t[2]);
        assertNotEquals(t[0], t[3]);
    }

    @Test
    void farIslandsSpreadOverThreads() {
        int[] t = Islands.assign(new int[]{0, 10, 20, 30}, new int[]{0, 0, 0, 0}, 4);
        assertEquals(4, java.util.Arrays.stream(t).distinct().count());
    }

    @Test
    void biggestIslandGetsItsOwnThread() {
        int[] t = Islands.assign(new int[]{0, 0, 0, 0, 0, 10, 20}, new int[]{0, 0, 0, 0, 0, 0, 0}, 2);
        assertEquals(t[5], t[6]);
        assertNotEquals(t[0], t[5]);
    }

    @Test
    void cellsAreTwoChunksWide() {
        assertEquals(0, Islands.cell(31.9));
        assertEquals(1, Islands.cell(32));
        assertEquals(-1, Islands.cell(-0.5));
    }

    @Test
    void oneThreadPutsAllOnZero() {
        int[] t = Islands.assign(new int[]{0, 50}, new int[]{0, 50}, 1);
        assertEquals(0, t[0]);
        assertEquals(0, t[1]);
    }
}
