package khi.pentecost.game.pregen;

/**
 * Headless check of follow mode's game-free parts ({@link FlightPath} and {@link FlightCone}), no game and no server.
 * Usage: FlightFollowCheck
 * Exits with 1 and says what was wrong when anything fails. Tickets and chunk futures need a live server and are
 * exercised in a test world instead.
 */
public final class FlightFollowCheck {
    private static final double ENTER = 12;
    private static final double MIN_HEIGHT = 8;
    private static int failures;

    public static void main(String[] args) {
        pathRules();
        coneRules();
        System.out.println(failures == 0 ? "FLIGHT FOLLOW CHECK PASSED" : "FLIGHT FOLLOW CHECK FAILED (" + failures + ")");
        if (failures != 0) {
            System.exit(1);
        }
    }

    /** Moves along x at the given blocks per second for the given ticks, at the given height. Returns the new x. */
    private static double fly(FlightPath p, double x, int ticks, double bps, double height) {
        for (int i = 0; i < ticks; i++) {
            x += bps / 20.0;
            p.update(x, 0, height, ENTER, MIN_HEIGHT);
        }
        return x;
    }

    private static void pathRules() {
        FlightPath standing = new FlightPath();
        fly(standing, 0, 200, 0, 60);
        expect("standing still never switches on", !standing.active());

        FlightPath walking = new FlightPath();
        fly(walking, 0, 200, 5.6, 0);
        expect("sprinting on the ground never switches on", !walking.active());

        FlightPath low = new FlightPath();
        fly(low, 0, 200, 25, 2);
        expect("fast but close to the ground does not switch on", !low.active());

        FlightPath slowHigh = new FlightPath();
        fly(slowHigh, 0, 200, 8, 60);
        expect("high but slow does not switch on", !slowHigh.active());

        FlightPath p = new FlightPath();
        double x = fly(p, 0, 10, 25, 60);
        expect("not on after half a second (it waits to be sure)", !p.active());
        x = fly(p, x, 40, 25, 60);
        expect("on after fast, high flight", p.active());
        expect("speed is about right (" + Math.round(p.speedBps()) + " blocks/s)", Math.abs(p.speedBps() - 25) < 2);
        expect("heading is east", p.dirX() > 0.99 && Math.abs(p.dirZ()) < 0.05);
        expect("a straight line has almost no turn", p.turnRadPerSec() < 0.05);

        x = fly(p, x, 10, 5, 60);
        expect("a short slow patch does not switch it off", p.active());
        x = fly(p, x, 120, 2, 60);
        expect("it switches off after a long slow patch", !p.active());

        FlightPath tp = new FlightPath();
        double tx = fly(tp, 0, 60, 25, 60);
        tp.update(tx + 5000, 0, 60, ENTER, MIN_HEIGHT);
        expect("a teleport resets it instead of reading as huge speed", !tp.active() && tp.speedBps() < 1);

        FlightPath circle = new FlightPath();
        double a = 0;
        double cx = 0;
        double cz = 0;
        for (int i = 0; i < 200; i++) {
            a += 0.02;
            cx += Math.cos(a) * 25 / 20.0;
            cz += Math.sin(a) * 25 / 20.0;
            circle.update(cx, cz, 60, ENTER, MIN_HEIGHT);
        }
        expect("turning registers (" + String.format("%.2f", circle.turnRadPerSec()) + " rad/s)",
                circle.turnRadPerSec() > 0.2);
        expect("turning widens the cone", FlightCone.halfAngle(circle.turnRadPerSec()) > FlightCone.halfAngle(0));
        expect("the cone never gets wider than the limit", FlightCone.halfAngle(100) <= 0.9 + 1e-9);
    }

    private static void coneRules() {
        double px = 8;
        double pz = 8;
        int view = 12;
        long[] full = FlightCone.ordered(px, pz, 1, 0, 25, FlightCone.halfAngle(0), view, 30, 100000);
        expect("a 25 blocks/s flight east gives a real cone (" + full.length + " chunks)", full.length > 500);

        double reach = FlightCone.reach(25, 30, view);
        boolean ok = true;
        boolean unique = true;
        java.util.Set<Long> seen = new java.util.HashSet<>();
        double tan = Math.tan(FlightCone.halfAngle(0));
        for (long c : full) {
            double rx = PregenPlan.x(c) + 0.5 - px / 16.0;
            double rz = PregenPlan.z(c) + 0.5 - pz / 16.0;
            if (rx < 0 || rx > reach || Math.abs(rz) > view + rx * tan + 1e-9) {
                ok = false;
            }
            unique &= seen.add(c);
        }
        expect("every chunk is ahead of the player and inside the band", ok);
        expect("no chunk appears twice", unique);

        long first = full[0];
        expect("the first chunk is on the spine, right in front",
                Math.abs(PregenPlan.z(first)) <= 1 && PregenPlan.x(first) <= 1);

        long[] head = FlightCone.ordered(px, pz, 1, 0, 25, FlightCone.halfAngle(0), view, 30, 100);
        boolean prefix = head.length == 100;
        for (int i = 0; i < head.length && prefix; i++) {
            prefix = head[i] == full[i];
        }
        expect("asking for fewer gives the best ones, in the same order", prefix);

        int farSpine = 0;
        int farSide = 0;
        for (int i = 0; i < 100; i++) {
            long c = full[i];
            if (Math.abs(PregenPlan.z(c)) <= 1) {
                farSpine = Math.max(farSpine, PregenPlan.x(c));
            }
            if (Math.abs(PregenPlan.z(c)) >= view - 1) {
                farSide++;
            }
        }
        expect("the spine reaches further than the edges within the first 100 chunks (spine to x=" + farSpine
                + ", " + farSide + " edge chunks)", farSpine > 10 && farSide == 0);

        long[] north = FlightCone.ordered(px, pz, 0, -1, 25, FlightCone.halfAngle(0), view, 30, 100000);
        long[] west = FlightCone.ordered(px, pz, -1, 0, 25, FlightCone.halfAngle(0), view, 30, 100000);
        expect("the same flight in other directions gives the same size of cone",
                north.length == full.length && west.length == full.length);

        long[] slow = FlightCone.ordered(px, pz, 1, 0, 12, FlightCone.halfAngle(0), view, 30, 100000);
        expect("a slower flight needs a shorter cone", slow.length < full.length);

        long[] wide = FlightCone.ordered(px, pz, 1, 0, 25, FlightCone.halfAngle(0.5), view, 30, 100000);
        expect("a turning flight needs a wider cone", wide.length > full.length);

        expect("no direction gives nothing", FlightCone.ordered(px, pz, 0, 0, 25, 0.2, view, 30, 100).length == 0);
        expect("a limit of zero gives nothing", FlightCone.ordered(px, pz, 1, 0, 25, 0.2, view, 30, 0).length == 0);

        long[] diag = FlightCone.ordered(px, pz, 1, 1, 25, FlightCone.halfAngle(0), view, 30, 100000);
        expect("a diagonal flight works too (" + diag.length + " chunks)", diag.length > 500);
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
