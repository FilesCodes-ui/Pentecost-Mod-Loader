package khi.pentecost.game.menuworld;

import java.time.LocalTime;

/**
 * The menu world's time of day, in game ticks: 0 is sunrise, 6000 noon, 12000 sunset, 18000 midnight.
 * It runs on its own clock, so it needs no server, and can follow the player's real clock instead.
 */
public final class MenuClock {
    public static final double DAY = 24000.0;

    private double ticks;
    private double ticksPerSecond;
    private boolean realClock;

    /** secondsPerDay: how long a whole day lasts in real time. Vanilla is 1200. */
    public MenuClock(double startTicks, double secondsPerDay) {
        this.ticks = ((startTicks % DAY) + DAY) % DAY;
        this.ticksPerSecond = DAY / Math.max(10, secondsPerDay);
    }

    public void followRealClock(boolean on) {
        realClock = on;
    }

    public void tick(double dtSeconds) {
        if (realClock) {
            LocalTime now = LocalTime.now();
            double hours = now.getHour() + now.getMinute() / 60.0 + now.getSecond() / 3600.0;
            ticks = ((hours - 6) * 1000 + DAY) % DAY;
        } else {
            ticks = (ticks + ticksPerSecond * dtSeconds) % DAY;
        }
    }

    public double ticks() {
        return ticks;
    }

    /** True near sunrise and sunset, when low sun makes the best light. */
    public boolean golden() {
        return ticks < 2000 || ticks >= 22000 || (ticks >= 11000 && ticks < 13500);
    }

    public boolean night() {
        return ticks >= 13500 && ticks < 22000;
    }
}
