package khi.pentecost.game;

import khi.pentecost.boot.Log;

import java.lang.reflect.Field;

public final class DhProbe {
    private DhProbe() {}

    private static boolean tried;
    private static Object api;
    private static Field message, broken;
    private static java.lang.reflect.Method canGen, movingFast, overloaded;
    private static String lastGen = "";
    private static long next;
    private static String last = "";

    public static void tick() {
        long now = System.currentTimeMillis();
        if (now < next) return;
        next = now + 5000;
        if (!tried) {
            tried = true;
            try {
                Class<?> c = Class.forName("com.seibel.distanthorizons.core.api.internal.ClientApi", false,
                        Thread.currentThread().getContextClassLoader());
                api = c.getField("INSTANCE").get(null);
                message = c.getField("lastRenderParamValidationMessage");
                broken = c.getField("rendererDisabledBecauseOfExceptions");
                try {
                    Class<?> pool = Class.forName("com.seibel.distanthorizons.core.util.threading.ThreadPoolUtil", false,
                            Thread.currentThread().getContextClassLoader());
                    canGen = pool.getDeclaredMethod("worldGenThreadsCanRun");
                    movingFast = pool.getDeclaredMethod("cameraMovingFast");
                    overloaded = pool.getDeclaredMethod("chunkBuilderOverloaded");
                    canGen.setAccessible(true);
                    movingFast.setAccessible(true);
                    overloaded.setAccessible(true);
                } catch (ReflectiveOperationException | RuntimeException e) {
                    canGen = null;
                }
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                api = null;
            }
        }
        if (api == null) return;
        try {
            Object m = message.get(api);
            boolean b = broken.getBoolean(api);
            String now2 = (m == null ? "drawing" : "not drawing: " + m) + (b ? " (its renderer turned itself off after errors)" : "");
            if (!now2.equals(last)) {
                last = now2;
                Log.info("Distant Horizons: " + now2);
            }
            if (canGen != null) {
                boolean can = (Boolean) canGen.invoke(null);
                String gen = can ? "generating allowed"
                        : "generating held back:" + ((Boolean) movingFast.invoke(null) ? " camera moving fast" : "")
                        + ((Boolean) overloaded.invoke(null) ? " LOD builder busy" : "")
                        + " (or the server counts as unhealthy)";
                if (!gen.equals(lastGen)) {
                    lastGen = gen;
                    Log.info("Distant Horizons: " + gen);
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            api = null;
        }
    }
}
