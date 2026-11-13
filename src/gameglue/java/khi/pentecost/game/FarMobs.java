package khi.pentecost.game;

import khi.pentecost.core.ServerTuning;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

/** Decides which mobs are far enough from every player to think less often. */
final class FarMobs implements ServerTuning.Mobs {
    private static final double RANGE_SQ = 48.0 * 48.0;

    static void install() {
        ServerTuning.mobs = new FarMobs();
    }

    @Override
    public boolean skip(Object o) {
        if (!(o instanceof Mob m)) {
            return false;
        }
        Level level = m.level();
        if (level.isClientSide() || m.tickCount < 40 || m.isNoAi() || m.isPassenger() || m.isVehicle()
                || m.isLeashed() || m.getTarget() != null) {
            return false;
        }
        for (Player p : level.players()) {
            if (!p.isSpectator() && p.distanceToSqr(m) < RANGE_SQ) {
                return false;
            }
        }
        return (m.tickCount + m.getId()) % 3 != 0;
    }
}
