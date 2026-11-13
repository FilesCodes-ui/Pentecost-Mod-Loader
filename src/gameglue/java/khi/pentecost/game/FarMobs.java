package khi.pentecost.game;

import khi.pentecost.core.ServerTuning;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

/** Decides which mobs are far enough from every player to think less often. */
final class FarMobs implements ServerTuning.Mobs {
    private static double rangeSq = 48.0 * 48.0;
    private static int every = 3;
    private static long refreshed;

    static void install() {
        ServerTuning.mobs = new FarMobs();
    }

    @Override
    public boolean skip(Object o) {
        if (!(o instanceof Mob m)) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (now - refreshed > 1000) {
            refreshed = now;
            double r = Math.max(24, khi.pentecost.control.Settings.num(khi.pentecost.control.Settings.FAR_RANGE, 48));
            rangeSq = r * r;
            every = Math.max(2, khi.pentecost.control.Settings.num(khi.pentecost.control.Settings.FAR_EVERY, 3));
        }
        Level level = m.level();
        if (level.isClientSide() || m.tickCount < 40 || m.isNoAi() || m.isPassenger() || m.isVehicle()
                || m.isLeashed() || m.getTarget() != null) {
            return false;
        }
        for (Player p : level.players()) {
            if (!p.isSpectator() && p.distanceToSqr(m) < rangeSq) {
                return false;
            }
        }
        return (m.tickCount + m.getId()) % every != 0;
    }
}
