package khi.pentecost.core;

import khi.pentecost.boot.Log;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public final class EventBus {
    public static final int QUARANTINE_AFTER = 3;

    private record Listener<T>(String modId, int priority, Consumer<T> handler) {}

    private final Map<Class<?>, List<Listener<?>>> listeners = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> failures = new ConcurrentHashMap<>();
    private final Set<String> quarantined = ConcurrentHashMap.newKeySet();

    public <T> void register(Class<T> type, String modId, Consumer<T> handler) {
        register(type, modId, 0, handler);
    }

    public <T> void register(Class<T> type, String modId, int priority, Consumer<T> handler) {
        List<Listener<?>> list = listeners.computeIfAbsent(type, k -> new CopyOnWriteArrayList<>());
        int at = 0;
        while (at < list.size() && list.get(at).priority() >= priority) {
            at++;
        }
        list.add(at, new Listener<>(modId, priority, handler));
    }

    @SuppressWarnings("unchecked")
    public <T> T post(T event) {
        List<Listener<?>> list = listeners.get(event.getClass());
        if (list == null) {
            return event;
        }
        for (Listener<?> raw : list) {
            Listener<T> l = (Listener<T>) raw;
            if (quarantined.contains(l.modId())) {
                continue;
            }
            try {
                l.handler().accept(event);
            } catch (Throwable t) {
                int n = failures.computeIfAbsent(l.modId(), k -> new AtomicInteger()).incrementAndGet();
                Log.error("Mod " + l.modId() + " failed while handling " + event.getClass().getSimpleName() + ": " + t);
                if (n >= QUARANTINE_AFTER && quarantined.add(l.modId())) {
                    Log.error("Mod " + l.modId() + " failed " + n + " times and was disabled. The game keeps running.");
                }
            }
        }
        return event;
    }

    public boolean isQuarantined(String modId) {
        return quarantined.contains(modId);
    }

    public Set<String> quarantined() {
        return Set.copyOf(quarantined);
    }
}
