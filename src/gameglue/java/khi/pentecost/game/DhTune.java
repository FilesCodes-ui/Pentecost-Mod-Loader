package khi.pentecost.game;

import khi.pentecost.boot.Log;
import khi.pentecost.core.LodBridge;

import java.lang.reflect.Method;

final class DhTune {
    private DhTune() {}

    private static boolean tried;
    private static Object ratio;
    private static Method set, clear;
    private static double applied = -1;
    private static long next;

    static void frame() {
        long now = System.currentTimeMillis();
        if (now < next) return;
        next = now + 1000;
        if (!tried) {
            tried = true;
            try {
                ClassLoader l = Thread.currentThread().getContextClassLoader();
                Class<?> delayed = Class.forName("com.seibel.distanthorizons.api.DhApi$Delayed", true, l);
                Object configs = delayed.getField("configs").get(null);
                if (configs == null) {
                    tried = false;
                    return;
                }
                Object mt = configs.getClass().getMethod("multiThreading").invoke(configs);
                ratio = mt.getClass().getMethod("threadRuntimeRatio").invoke(mt);
                Class<?> value = Class.forName("com.seibel.distanthorizons.api.interfaces.config.IDhApiConfigValue", true, l);
                set = value.getMethod("setValue", Object.class);
                clear = value.getMethod("clearValue");
                Log.info("Distant Horizons: its threads follow Pentecost's frame budget");
            } catch (ClassNotFoundException e) {
                ratio = null;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                ratio = null;
                Log.warn("Distant Horizons: frame budget not available: " + e);
            }
        }
        if (ratio == null) return;
        double b = Math.round(LodBridge.budget() * 20) / 20.0;
        if (b == applied) return;
        try {
            if (b >= 1.0) clear.invoke(ratio);
            else set.invoke(ratio, b);
            applied = b;
        } catch (ReflectiveOperationException | RuntimeException e) {
            ratio = null;
            Log.warn("Distant Horizons: frame budget stopped: " + e);
        }
    }
}
