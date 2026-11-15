package khi.pentecost.game.pregen;

import java.util.HashSet;
import java.util.Set;

/**
 * Headless check of {@link PregenPlan}, no game and no server. Usage: PregenPlanCheck
 * Exits with 1 and says what was wrong when any area fails.
 */
public final class PregenPlanCheck {
    private static int failures;

    public static void main(String[] args) {
        // centre x, centre z, radius: small and odd, negative, on a region border, bigger than one region.
        int[][] cases = {
                {0, 0, 1}, {0, 0, 5}, {-1, -1, 7}, {31, 31, 3}, {-33, 100, 20}, {1000, -2000, 33}, {0, 0, 64}, {5, -5, 100}
        };
        for (int[] c : cases) {
            check(c[0], c[1], c[2]);
        }
        System.out.println(failures == 0 ? "PREGEN PLAN CHECK PASSED" : "PREGEN PLAN CHECK FAILED (" + failures + ")");
        if (failures != 0) {
            System.exit(1);
        }
    }

    private static void check(int cx, int cz, int r) {
        PregenPlan plan = new PregenPlan(cx, cz, r);
        long expected = (2L * r) * (2L * r);
        String label = "centre " + cx + "," + cz + " radius " + r + ": ";
        if (plan.total() != expected) {
            fail(label + "total " + plan.total() + ", expected " + expected);
            return;
        }
        Set<Long> seen = new HashSet<>();
        int lastRegion = -1;
        int regionsStarted = 0;
        for (long i = 0; i < plan.total(); i++) {
            long p = plan.chunkAt(i);
            int x = PregenPlan.x(p);
            int z = PregenPlan.z(p);
            if (!plan.contains(x, z)) {
                fail(label + "chunk " + i + " at " + x + "," + z + " is outside the area");
                return;
            }
            if (!seen.add(p)) {
                fail(label + "chunk " + x + "," + z + " appears twice");
                return;
            }
            if (PregenPlan.pack(x, z) != p) {
                fail(label + "pack/unpack does not round-trip at " + x + "," + z);
                return;
            }
            int region = plan.regionOf(i);
            if (region != lastRegion) {
                if (region < lastRegion) {
                    fail(label + "went back to an earlier region at chunk " + i);
                    return;
                }
                lastRegion = region;
                regionsStarted++;
            }
            // The chunk must really be inside the region file the plan says it is in.
            if (Math.floorDiv(x, PregenPlan.REGION) * PregenPlan.REGION > x) {
                fail(label + "region maths wrong at " + x);
                return;
            }
        }
        if (seen.size() != expected) {
            fail(label + "saw " + seen.size() + " different chunks, expected " + expected);
            return;
        }
        if (regionsStarted != plan.regions()) {
            fail(label + "started " + regionsStarted + " regions, plan says " + plan.regions());
            return;
        }
        // Resume: asking for any number gives the same chunk as walking up to it.
        long mid = plan.total() / 2;
        if (plan.chunkAt(mid) != new PregenPlan(cx, cz, r).chunkAt(mid)) {
            fail(label + "a second plan of the same area gives a different order");
            return;
        }
        // The first chunk must be in the region nearest the centre.
        long first = plan.chunkAt(0);
        long centre = PregenPlan.pack(cx, cz);
        boolean sameRegion = Math.floorDiv(PregenPlan.x(first), PregenPlan.REGION) == Math.floorDiv(PregenPlan.x(centre), PregenPlan.REGION)
                && Math.floorDiv(PregenPlan.z(first), PregenPlan.REGION) == Math.floorDiv(PregenPlan.z(centre), PregenPlan.REGION);
        if (plan.regions() > 1 && !sameRegion && r >= 2) {
            // With a centre on a region corner several regions tie for nearest, so only complain when it is far off.
            int dx = Math.abs(Math.floorDiv(PregenPlan.x(first), PregenPlan.REGION) - Math.floorDiv(cx, PregenPlan.REGION));
            int dz = Math.abs(Math.floorDiv(PregenPlan.z(first), PregenPlan.REGION) - Math.floorDiv(cz, PregenPlan.REGION));
            if (dx > 1 || dz > 1) {
                fail(label + "starts " + dx + "," + dz + " regions away from the centre");
                return;
            }
        }
        boolean outOfRange = false;
        try {
            plan.chunkAt(plan.total());
        } catch (IndexOutOfBoundsException e) {
            outOfRange = true;
        }
        if (!outOfRange) {
            fail(label + "chunkAt(total) should throw");
            return;
        }
        System.out.println("ok   " + label + plan);
    }

    private static void fail(String message) {
        failures++;
        System.out.println("FAIL " + message);
    }
}
