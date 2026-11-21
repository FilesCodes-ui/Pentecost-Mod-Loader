package khi.pentecost.game;

import khi.pentecost.core.FluidDefer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.ticks.ScheduledTick;

import java.util.concurrent.ThreadLocalRandom;

public final class FluidDeferImpl implements FluidDefer.Impl {
    public static void install() {
        FluidDefer.impl = new FluidDeferImpl();
    }

    @Override
    public boolean defer(Object fluidState, Object level, Object pos, Object chunk) {
        if (!(fluidState instanceof FluidState fs) || !(level instanceof ServerLevel sl) || !(pos instanceof BlockPos p)
                || !(chunk instanceof LevelChunk c)) {
            return false;
        }
        int delay = 1 + ThreadLocalRandom.current().nextInt(10);
        c.getFluidTicks().schedule(new ScheduledTick<>(fs.getType(), p.immutable(), sl.getGameTime() + delay,
                sl.nextSubTickCount()));
        return true;
    }
}
