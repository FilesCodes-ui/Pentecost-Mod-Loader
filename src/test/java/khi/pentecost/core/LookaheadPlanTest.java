package khi.pentecost.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LookaheadPlanTest {
    @Test
    void straightFlightEastNeedsTheNextColumnFirst() {
        LookaheadPlan.Result r = LookaheadPlan.plan(8, 8, 20, 0, 0, 12, 10, 10_000);
        assertTrue(r.size() > 0);
        assertEquals(13, LookaheadPlan.x(r.keys()[0]));
        for (int i = 1; i < r.size(); i++) {
            assertTrue(r.seconds()[i] >= r.seconds()[i - 1], "soonest first");
        }
        for (long k : r.keys()) {
            int x = LookaheadPlan.x(k);
            int z = LookaheadPlan.z(k);
            assertTrue(!(Math.abs(x) <= 12 && Math.abs(z) <= 12), "already loaded chunks are left out");
            assertTrue(x >= 0, "nothing behind the player (the side margin may reach the player's own column)");
        }
    }

    @Test
    void standingStillPlansNothing() {
        assertEquals(0, LookaheadPlan.plan(0, 0, 0, 0, 0, 12, 30, 1000).size());
    }

    @Test
    void turningLeftBendsThePathNorth() {
        LookaheadPlan.Result straight = LookaheadPlan.plan(8, 8, 20, 0, 0, 6, 20, 100_000);
        LookaheadPlan.Result turning = LookaheadPlan.plan(8, 8, 20, 0, 0.8, 6, 20, 100_000);
        assertTrue(meanZ(turning) > meanZ(straight) + 2, "a turn moves the planned chunks to that side");
    }

    @Test
    void packingRoundTripsNegativeCoordinates() {
        long k = LookaheadPlan.pack(-123, -4567);
        assertEquals(-123, LookaheadPlan.x(k));
        assertEquals(-4567, LookaheadPlan.z(k));
        assertEquals((-123 & 0xFFFFFFFFL) | ((-4567 & 0xFFFFFFFFL) << 32), k);
    }

    @Test
    void reachIsCapped() {
        LookaheadPlan.Result r = LookaheadPlan.plan(0, 0, 200, 0, 0, 8, 60, 1_000_000);
        for (long k : r.keys()) {
            assertTrue(LookaheadPlan.x(k) <= LookaheadPlan.MAX_REACH_CHUNKS + 8 + 6);
        }
    }

    private static double meanZ(LookaheadPlan.Result r) {
        double s = 0;
        for (long k : r.keys()) {
            s += LookaheadPlan.z(k);
        }
        return s / r.size();
    }
}
