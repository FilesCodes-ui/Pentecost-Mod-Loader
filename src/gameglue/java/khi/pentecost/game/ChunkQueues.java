package khi.pentecost.game;

import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ThreadedLevelLightEngine;

import java.lang.reflect.Field;
import java.util.List;

final class ChunkQueues {
    private static long lastRead;
    private static final int[] last = {-1, -1};
    private static Field lightTasks;
    private static boolean lightTasksMissing;

    private ChunkQueues() {}

    static int[] read(Minecraft mc) {
        long now = System.currentTimeMillis();
        if (now - lastRead < 250) {
            return last;
        }
        lastRead = now;
        last[0] = -1;
        last[1] = -1;
        IntegratedServer server = mc.getSingleplayerServer();
        if (server == null || mc.level == null) {
            return last;
        }
        try {
            ServerLevel level = server.getLevel(mc.level.dimension());
            if (level == null) {
                return last;
            }
            last[0] = level.getChunkSource().getPendingTasksCount();
            if (!lightTasksMissing && level.getChunkSource().getLightEngine() instanceof ThreadedLevelLightEngine light) {
                if (lightTasks == null) {
                    lightTasks = ThreadedLevelLightEngine.class.getDeclaredField("lightTasks");
                    lightTasks.setAccessible(true);
                }
                Object list = lightTasks.get(light);
                if (list instanceof List<?> l) {
                    last[1] = l.size();
                }
            }
        } catch (NoSuchFieldException e) {
            lightTasksMissing = true;
        } catch (ReflectiveOperationException | RuntimeException e) {
        }
        return last;
    }
}
