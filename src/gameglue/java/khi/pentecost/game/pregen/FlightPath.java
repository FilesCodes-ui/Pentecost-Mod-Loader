package khi.pentecost.game.pregen;

/**
 * Follows one player and says whether they are flying fast and high enough for the pregenerator to run ahead of them,
 * and which way they are going. No game classes, so it can be checked headless.
 * <p>
 * Call {@link #update} once per server tick. The speed and heading are smoothed over about one second, so a twitch of
 * the mouse does not swing the prediction. It switches on after about a second of fast, high flight and off after
 * about two seconds of slow or low flight, so it does not flicker when the player brushes the threshold.
 */
final class FlightPath {
    private static final double SMOOTH = 0.1;
    private static final int ON_TICKS = 20;
    private static final int OFF_TICKS = 40;
    /** A jump this long in one tick is a teleport, not flying. */
    private static final double TELEPORT = 64.0;

    private boolean have;
    private boolean hasHeading;
    private boolean active;
    private double lastX;
    private double lastZ;
    private double vx;
    private double vz;
    private double heading;
    private double turn;
    private int fast;
    private int slow;

    void reset() {
        have = false;
        hasHeading = false;
        active = false;
        vx = 0;
        vz = 0;
        turn = 0;
        fast = 0;
        slow = 0;
    }

    /**
     * @param heightAbove blocks between the player and the ground below them
     * @param enterBps    horizontal blocks per second needed to switch on
     * @param minHeight   blocks above the ground needed to switch on
     */
    void update(double x, double z, double heightAbove, double enterBps, double minHeight) {
        if (!have) {
            lastX = x;
            lastZ = z;
            have = true;
            return;
        }
        double dx = x - lastX;
        double dz = z - lastZ;
        lastX = x;
        lastZ = z;
        if (dx * dx + dz * dz > TELEPORT * TELEPORT) {
            reset();
            lastX = x;
            lastZ = z;
            have = true;
            return;
        }
        vx += (dx - vx) * SMOOTH;
        vz += (dz - vz) * SMOOTH;
        double perTick = Math.hypot(vx, vz);
        if (perTick > 0.05) {
            double h = Math.atan2(vz, vx);
            if (hasHeading) {
                double d = h - heading;
                while (d > Math.PI) {
                    d -= 2 * Math.PI;
                }
                while (d < -Math.PI) {
                    d += 2 * Math.PI;
                }
                turn += (Math.abs(d) * 20 - turn) * SMOOTH;
            }
            heading = h;
            hasHeading = true;
        } else {
            turn *= 1 - SMOOTH;
        }
        double bps = perTick * 20;
        boolean high = heightAbove >= minHeight;
        if (active) {
            if (bps < enterBps * 0.6 || !high) {
                if (++slow >= OFF_TICKS) {
                    active = false;
                    slow = 0;
                    fast = 0;
                }
            } else {
                slow = 0;
            }
        } else if (bps >= enterBps && high) {
            if (++fast >= ON_TICKS) {
                active = true;
                fast = 0;
                slow = 0;
            }
        } else {
            fast = 0;
        }
    }

    boolean active() {
        return active;
    }

    /** Smoothed horizontal speed in blocks per second. */
    double speedBps() {
        return Math.hypot(vx, vz) * 20;
    }

    /** Unit vector of the way they are going: east is (1, 0), south is (0, 1). */
    double dirX() {
        return hasHeading ? Math.cos(heading) : 0;
    }

    double dirZ() {
        return hasHeading ? Math.sin(heading) : 0;
    }

    /** Smoothed turning, in radians per second. Zero for a straight line. */
    double turnRadPerSec() {
        return turn;
    }
}
