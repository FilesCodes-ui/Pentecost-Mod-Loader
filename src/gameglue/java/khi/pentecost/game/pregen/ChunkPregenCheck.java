package khi.pentecost.game.pregen;

import java.util.List;

public final class ChunkPregenCheck {
    private static final float LIMIT = 40.0F;
    private static int failures;

    public static void main(String[] args) {
        windowRules();
        ledgerRules();
        flightDemand();
        System.out.println(failures == 0 ? "CHUNK PREGEN CHECK PASSED" : "CHUNK PREGEN CHECK FAILED (" + failures + ")");
        if (failures != 0) {
            System.exit(1);
        }
    }

    private static void windowRules() {
        PregenWindow w = new PregenWindow(8);
        expect("window starts at the gentle size", w.size() == 8);

        w.adjust(false, false, 128, 8, 5f, LIMIT);
        expect("empty server runs the full window", w.size() == 128);

        w.adjust(true, false, 128, 8, 5f, LIMIT);
        expect("a player joining drops it to gentle at once", w.size() == 8);

        for (int i = 0; i < 50; i++) {
            w.adjust(true, false, 128, 8, 60f, LIMIT);
        }
        expect("slow ticks shrink it, but never below the minimum", w.size() == PregenWindow.MIN);

        for (int i = 0; i < 50; i++) {
            w.adjust(true, false, 128, 8, 10f, LIMIT);
        }
        expect("it grows back to gentle once ticks recover, and stops there", w.size() == 8);

        for (int i = 0; i < 20; i++) {
            w.adjust(true, true, 128, 8, 90f, LIMIT);
        }
        expect("speed full ignores slow ticks even with players on", w.size() == 128);

        PregenWindow tiny = new PregenWindow(0);
        expect("a bad setting cannot make the window smaller than the minimum", tiny.size() == PregenWindow.MIN);
        tiny.adjust(false, false, 0, 0, 0f, LIMIT);
        expect("a full window of zero is still the minimum", tiny.size() == PregenWindow.MIN);
    }

    private static void ledgerRules() {
        PregenLedger l = new PregenLedger();
        expect("empty ledger resumes from next", l.safeIndex(100) == 100 && l.size() == 0);

        l.admit(40, 1_000);
        l.admit(41, 2_000);
        l.admit(42, 3_000);
        expect("three in flight", l.size() == 3);
        expect("resume point is the oldest chunk still being made", l.safeIndex(43) == 40);

        expect("completing a chunk in flight counts", l.complete(40));
        expect("completing it again does not count twice", !l.complete(40));
        expect("resume point moves up as the lowest finishes", l.safeIndex(43) == 41);
        expect("resume point never goes past next", l.safeIndex(41) == 41 && l.safeIndex(10) == 10);

        List<Long> none = l.takeOverdue(5_000, 10_000);
        expect("nothing is overdue yet", none.isEmpty() && l.size() == 2);

        List<Long> late = l.takeOverdue(3_600, 1_500);
        expect("only the chunk past the limit is given up on (41 asked at 2000, now 3600, limit 1500)",
                late.equals(List.of(41L)) && l.size() == 1);
        expect("a late answer for a chunk given up on is ignored", !l.complete(41));
        expect("the other chunk is still tracked and can finish", l.complete(42) && l.size() == 0);
        expect("overdue on an empty ledger is empty", l.takeOverdue(Long.MAX_VALUE / 2, 1).isEmpty());

        PregenLedger big = new PregenLedger();
        for (long i = 0; i < 256; i++) {
            big.admit(1000 + i, i);
        }
        List<Long> all = big.takeOverdue(10_000, 100);
        long expected = 0;
        for (long i = 0; i < 256; i++) {
            if (10_000 - i > 100) {
                expected++;
            }
        }
        expect("overdue count matches (" + expected + ")", all.size() == expected && big.size() == 256 - expected);
    }

    private static void flightDemand() {
        System.out.println("Flight demand: new chunks per second needed at 20 blocks/s (a band 2*distance+1 chunks wide)");
        for (int distance : new int[] {8, 12, 16, 24}) {
            double perSecond = 20.0 / 16.0 * (2 * distance + 1);
            System.out.println(String.format("  render distance %2d: %.0f chunks/s", distance, perSecond));
        }
    }

    private static void expect(String what, boolean ok) {
        if (ok) {
            System.out.println("ok   " + what);
        } else {
            failures++;
            System.out.println("FAIL " + what);
        }
    }
}
