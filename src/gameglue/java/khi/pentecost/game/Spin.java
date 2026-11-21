package khi.pentecost.game;

final class Spin {
    private static final float AUTO = 22f;
    private static final long IDLE_MS = 30_000L;

    private float yaw;
    private float vel = AUTO;
    private boolean held;
    private double lastX;
    private long lastMoveNs;
    private long touchedAt;
    private long lastNs;

    Spin(float start) {
        yaw = start;
    }

    float yaw() {
        long now = System.nanoTime();
        float dt = lastNs == 0 ? 0 : Math.min(0.1f, (now - lastNs) / 1e9f);
        lastNs = now;
        if (held) {
            return yaw;
        }
        boolean resting = touchedAt != 0 && System.currentTimeMillis() - touchedAt < IDLE_MS;
        if (resting) {
            vel *= (float) Math.exp(-dt * 2.6);
            if (Math.abs(vel) < 0.5f) {
                vel = 0;
            }
        } else {
            float target = vel < 0 ? -AUTO : AUTO;
            vel += (target - vel) * Math.min(1f, dt * 0.8f);
        }
        yaw += vel * dt;
        return yaw;
    }

    boolean held() {
        return held;
    }

    void press(double x) {
        held = true;
        lastX = x;
        vel = 0;
        lastMoveNs = System.nanoTime();
        touchedAt = System.currentTimeMillis();
    }

    void drag(double x) {
        if (!held) {
            return;
        }
        long now = System.nanoTime();
        float dx = (float) (x - lastX);
        float dt = Math.max(0.004f, (now - lastMoveNs) / 1e9f);
        yaw += dx * 1.2f;
        float instant = Math.max(-900f, Math.min(900f, dx * 1.2f / dt));
        vel = vel * 0.6f + instant * 0.4f;
        lastX = x;
        lastMoveNs = now;
        touchedAt = System.currentTimeMillis();
    }

    void release() {
        if (!held) {
            return;
        }
        held = false;
        if (System.nanoTime() - lastMoveNs > 80_000_000L) {
            vel = 0;
        }
        touchedAt = System.currentTimeMillis();
    }
}
