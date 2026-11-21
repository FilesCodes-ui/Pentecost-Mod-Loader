package khi.pentecost.game;

import khi.pentecost.boot.Log;

import java.util.HashMap;
import java.util.Map;

public final class CamTrace {
    private static final class S {
        double x, y, z, vx, vy, vz, yaw, pitch, fov, yawRate, pitchRate, fovRate;
        boolean has, hasRate;
        int logged;
    }

    private static final Map<String, S> STATE = new HashMap<>();

    private CamTrace() {}

    public static void sample(String tag, double dt, double x, double y, double z, double yaw, double pitch, double fov, String context) {
        S s = STATE.computeIfAbsent(tag, k -> new S());
        if (dt <= 1e-5) {
            return;
        }
        if (s.has && s.hasRate) {
            double ex = x - (s.x + s.vx * dt), ey = y - (s.y + s.vy * dt), ez = z - (s.z + s.vz * dt);
            double pos = Math.sqrt(ex * ex + ey * ey + ez * ez);
            double dyaw = wrap(yaw - (s.yaw + s.yawRate * dt));
            double dpitch = pitch - (s.pitch + s.pitchRate * dt);
            double dfov = fov - (s.fov + s.fovRate * dt);
            boolean stall = dt > 0.06;
            if ((pos > 0.3 || Math.abs(dyaw) > 2.5 || Math.abs(dpitch) > 2.0 || Math.abs(dfov) > 0.6 || stall) && s.logged < 120) {
                s.logged++;
                Log.info(String.format("[camtrace %s] jump pos=%.2f yaw=%.1f pitch=%.1f fov=%.2f dt=%.3f%s | at %.1f %.1f %.1f | %s",
                        tag, pos, dyaw, dpitch, dfov, dt, stall ? " STALL" : "", x, y, z, context));
            }
        }
        if (s.has) {
            s.vx = (x - s.x) / dt;
            s.vy = (y - s.y) / dt;
            s.vz = (z - s.z) / dt;
            s.yawRate = wrap(yaw - s.yaw) / dt;
            s.pitchRate = (pitch - s.pitch) / dt;
            s.fovRate = (fov - s.fov) / dt;
            s.hasRate = true;
        }
        s.x = x;
        s.y = y;
        s.z = z;
        s.yaw = yaw;
        s.pitch = pitch;
        s.fov = fov;
        s.has = true;
    }

    public static void reset(String tag) {
        STATE.remove(tag);
    }

    private static double wrap(double d) {
        d %= 360;
        if (d > 180) {
            d -= 360;
        } else if (d < -180) {
            d += 360;
        }
        return d;
    }
}
