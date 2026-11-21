package khi.pentecost.messenger;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class IslandsFamilyTest {
    @Test
    void oneFamilyFarApartStaysOnOneThread() {
        int[] t = Islands.assignByFamily(new int[]{0, 100}, new int[]{0, 100}, new int[]{0, 0}, 1, 4);
        assertEquals(t[0], t[1]);
    }

    @Test
    void twoFamiliesFarApartSplit() {
        int[] t = Islands.assignByFamily(new int[]{0, 100}, new int[]{0, 100}, new int[]{0, 1}, 2, 4);
        assertNotEquals(t[0], t[1]);
    }

    @Test
    void neighboursJoinAcrossFamilies() {
        int[] t = Islands.assignByFamily(new int[]{0, 100, 101, 500}, new int[]{0, 100, 100, 500}, new int[]{0, 0, 1, 2}, 3, 4);
        assertEquals(t[0], t[1]);
        assertEquals(t[1], t[2]);
        assertNotEquals(t[0], t[3]);
    }
}
