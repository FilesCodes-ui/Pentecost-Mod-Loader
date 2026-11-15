package khi.pentecost.game.menuworld.camera;

import khi.pentecost.game.menuworld.source.Terrain;

/**
 * Plays one shot at a time. On top of the shot's rails it adds a spring on the aim, a gentle terrain safety net,
 * banking into turns, handheld drift, field of view breathing, and a fade at both ends.
 *
 * The heights of open-air shots are planned ahead by {@link PathFit}, so the terrain net here rarely has anything to
 * do. When it does, it lifts the camera quickly and lowers it slowly, from the land around the camera rather than
 * the single block below it, so it never hops. Guided shots (tunnels, streets) skip the terrain net: their routes
 * were planned through the blocks themselves.
 */
public final class CameraRig {
    private static final double LOOK_SMOOTH = 0.9;
    /** Seconds ahead along the path that the terrain net looks, so it reacts to land before the camera is over it. */
    private static final double[] LOOK_AHEAD = {0.8, 1.6, 2.5};
    private double clearance = 7.0;
    private double fadeSeconds = 1.2;
    private double handheldScale = 1.0;
    private double fovOffset = 0.0;
    private boolean banking = true;
    private boolean boxed;
    private double minX, minZ, maxX, maxZ;

    private Shot shot;
    private double time;
    private Vec aim;
    private Vec aimVelocity = Vec.ZERO;
    private double lift;
    private double phase;
    private double lastYaw = Double.NaN;
    private double yawRate;
    private double bankRoll;
    // one clock and eased handheld, roll and field of view that run through every shot, so a shot that carries on
    // from the last never restarts the sway or jumps the roll or zoom
    private double clock;
    private double handAmp, rollSm, fovSm;
    private boolean smoothed;
    private boolean fadeIn = true;
    private boolean fadeOut = true;
    private double fadeOutFrom = Double.NaN;

    /** The player's camera settings. Takes effect on the next frame. */
    public void style(double handheldScale, double clearance, double fadeSeconds, double fovOffset) {
        this.handheldScale = Math.max(0, handheldScale);
        this.clearance = Math.max(0, clearance);
        this.fadeSeconds = Math.max(0.05, fadeSeconds);
        this.fovOffset = fovOffset;
    }

    /** Whether the camera leans into turns. */
    public void banking(boolean on) {
        this.banking = on;
    }

    /** Keeps the camera over land that exists: inside this box (blocks). */
    public void limits(double minX, double minZ, double maxX, double maxZ) {
        this.boxed = minX < maxX && minZ < maxZ;
        this.minX = minX;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxZ = maxZ;
    }

    public void start(Shot next) {
        start(next, true, true);
    }

    /**
     * Starts a shot. With {@code fadeIn} false the shot carries on from the one before it (a flight between places):
     * the aim, the terrain lift and the banking are kept, so the picture does not jump. Likewise {@code fadeOut} false
     * means the next shot will carry on from this one, so the picture is not faded down at the end.
     */
    public void start(Shot next, boolean fadeIn, boolean fadeOut) {
        boolean carry = !fadeIn && aim != null;
        shot = next;
        time = 0;
        this.fadeIn = fadeIn;
        this.fadeOut = fadeOut;
        this.fadeOutFrom = fadeOut ? Math.max(0, next.seconds() - fadeSeconds) : Double.NaN;
        if (!carry) {
            aim = null;
            aimVelocity = Vec.ZERO;
            lift = 0;
            lastYaw = Double.NaN;
            yawRate = 0;
            bankRoll = 0;
            smoothed = false;
        }
        phase = (next.id().hashCode() & 0xFFFF) / 10000.0;
    }

    /** Changes whether the end of the playing shot fades, once it is known whether the next one flies on from it. */
    public void fadeOut(boolean fade) {
        this.fadeOut = fade;
        // A cut learned late fades from now on. It never starts from a half faded picture that jumps.
        this.fadeOutFrom = fade && shot != null ? Math.max(time, Math.max(0, shot.seconds() - fadeSeconds)) : Double.NaN;
    }

    public boolean finished() {
        return shot == null || time >= shot.seconds();
    }

    /** Seconds left in the playing shot. */
    public double remaining() {
        return shot == null ? 0 : Math.max(0, shot.seconds() - time);
    }

    public Shot shot() {
        return shot;
    }

    /** 0 to 1: how visible the picture should be, for a soft cut at the start and end of the shot. */
    public double fade() {
        if (shot == null) {
            return 0;
        }
        double in = fadeIn ? time / fadeSeconds : 1;
        double out = 1;
        if (fadeOut) {
            double span = Math.max(1e-3, shot.seconds() - (Double.isNaN(fadeOutFrom) ? shot.seconds() - fadeSeconds : fadeOutFrom));
            out = (shot.seconds() - time) / span;
        }
        return Math.max(0, Math.min(1, Math.min(in, out)));
    }

    public Pose advance(double dt, Terrain terrain) {
        clock += dt;
        time = Math.min(shot.seconds(), time + dt);
        double u = shot.ease().apply(time / shot.seconds());

        Vec eye = shot.eye().at(u);
        if (boxed) {
            eye = new Vec(Math.max(minX, Math.min(maxX, eye.x())), eye.y(), Math.max(minZ, Math.min(maxZ, eye.z())));
        }
        Vec want = shot.look().at(u);
        if (aim == null) {
            aim = want;
        } else {
            Vec[] r = smoothDamp(aim, want, aimVelocity, LOOK_SMOOTH, dt);
            aim = r[0];
            aimVelocity = r[1];
        }

        if (!shot.guided()) {
            double minClear = shot.clearance() > 0 ? shot.clearance() : clearance;
            double soft = GroundField.envelope(terrain, eye.x(), eye.z(), 6) + minClear;
            double needNow = Math.max(0, soft - eye.y());
            // Look ahead along the path: the land the camera will be over in the next seconds counts now, so it starts
            // rising before a ridge and does not dip back down just before one.
            double need = needNow;
            double total = shot.seconds();
            for (double ahead : LOOK_AHEAD) {
                double t2 = time + ahead;
                if (t2 >= total) {
                    break;
                }
                Vec f = shot.eye().at(shot.ease().apply(t2 / total));
                if (boxed) {
                    f = new Vec(Math.max(minX, Math.min(maxX, f.x())), f.y(), Math.max(minZ, Math.min(maxZ, f.z())));
                }
                double fneed = GroundField.envelope(terrain, f.x(), f.z(), 6) + minClear - f.y();
                need = Math.max(need, fneed);
            }
            double tau = need > lift ? (need > needNow + 0.5 ? 0.9 : 0.4) : 2.2;
            double step = (need - lift) * (1 - Math.exp(-dt / tau));
            lift += Math.max(-3.0 * dt, Math.min(8.0 * dt, step)); // never a hop, even when the land arrives late
            eye = eye.add(0, lift, 0);
            double hard = GroundField.envelope(terrain, eye.x(), eye.z(), 2) + 1.4;
            if (eye.y() < hard) {
                eye = eye.withY(hard);
            }
        }

        Pose base = Pose.looking(eye, aim, 0, 70);

        double turn = Double.isNaN(lastYaw) ? 0 : wrap(base.yaw() - lastYaw);
        lastYaw = base.yaw();
        if (dt > 1e-4) {
            yawRate += (turn / dt - yawRate) * (1 - Math.exp(-dt / 0.45));
        }
        double bankTarget = banking ? Math.max(-14, Math.min(14, shot.bank() * yawRate * 0.9)) : 0;
        bankRoll += (bankTarget - bankRoll) * (1 - Math.exp(-dt / 0.5));

        double hT = shot.handheld() * handheldScale;
        double rollT = shot.roll() + bankRoll;
        double fovT = shot.fovFrom() + (shot.fovTo() - shot.fovFrom()) * u + fovOffset;
        if (!smoothed) {
            handAmp = hT;
            rollSm = rollT;
            fovSm = fovT;
            smoothed = true;
        } else {
            double k = 1 - Math.exp(-dt / 0.6);
            handAmp += (hT - handAmp) * k;
            rollSm += (rollT - rollSm) * k;
            fovSm += (fovT - fovSm) * (1 - Math.exp(-dt / 0.35));
        }
        double h = handAmp;
        double s = clock;
        double yaw = h * (0.35 * Math.sin(s * 0.9) + 0.15 * Math.sin(s * 2.3 + 1.7));
        double pitch = h * (0.25 * Math.sin(s * 0.7 + 0.5) + 0.1 * Math.sin(s * 2.9));
        double roll = rollSm + h * 0.3 * Math.sin(s * 0.6 + 2.1);
        double fov = fovSm + 0.4 * Math.sin(clock * 0.5);

        return new Pose(base.pos(), base.yaw() + yaw, base.pitch() + pitch, roll, fov);
    }

    private static double wrap(double deg) {
        double d = deg % 360;
        if (d > 180) {
            d -= 360;
        } else if (d < -180) {
            d += 360;
        }
        return d;
    }

    /** Critically damped spring toward a target. Returns {value, velocity}. */
    private static double[] smoothDamp(double cur, double target, double vel, double smooth, double dt) {
        double omega = 2.0 / smooth;
        double x = omega * dt;
        double exp = 1.0 / (1.0 + x + 0.48 * x * x + 0.235 * x * x * x);
        double change = cur - target;
        double temp = (vel + omega * change) * dt;
        double nv = (vel - omega * temp) * exp;
        return new double[] {target + (change + temp) * exp, nv};
    }

    private static Vec[] smoothDamp(Vec cur, Vec target, Vec vel, double smooth, double dt) {
        double[] a = smoothDamp(cur.x(), target.x(), vel.x(), smooth, dt);
        double[] b = smoothDamp(cur.y(), target.y(), vel.y(), smooth, dt);
        double[] c = smoothDamp(cur.z(), target.z(), vel.z(), smooth, dt);
        return new Vec[] {new Vec(a[0], b[0], c[0]), new Vec(a[1], b[1], c[1])};
    }
}
