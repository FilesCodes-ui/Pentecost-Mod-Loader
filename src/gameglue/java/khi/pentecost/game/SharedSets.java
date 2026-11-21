package khi.pentecost.game;

import net.minecraft.world.level.Level;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

final class SharedSets {
    private static final Map<Level, Boolean> DONE = Collections.synchronizedMap(new WeakHashMap<>());

    private SharedSets() {}

    static void makeSafe(Level level) {
        if (DONE.putIfAbsent(level, Boolean.TRUE) != null) {
            return;
        }
        List<String> swapped = new ArrayList<>();
        for (Class<?> c = level.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || !f.getName().contains("$")) {
                    continue;
                }
                Class<?> t = f.getType();
                if (t != Set.class && t != List.class) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                    Object v = f.get(level);
                    Object safe = null;
                    if (v instanceof Set<?> s && !(s.getClass().getName().startsWith("java.util.concurrent."))) {
                        Set<Object> n = ConcurrentHashMap.newKeySet();
                        for (Object o : s) {
                            if (o != null) {
                                n.add(o);
                            }
                        }
                        safe = n;
                    } else if (v instanceof List<?> l && !(l instanceof CopyOnWriteArrayList)) {
                        safe = new CopyOnWriteArrayList<>(l);
                    }
                    if (safe != null) {
                        f.set(level, safe);
                        swapped.add(f.getName());
                    }
                } catch (ReflectiveOperationException | RuntimeException e) {
                }
            }
        }
        if (!swapped.isEmpty()) {
            khi.pentecost.boot.Log.info("Mob threads: made " + String.join(", ", swapped) + " safe to read from several threads");
        }
    }
}
