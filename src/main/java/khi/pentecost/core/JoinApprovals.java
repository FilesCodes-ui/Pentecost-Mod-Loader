package khi.pentecost.core;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

public final class JoinApprovals {
    public enum Decision {
        ALLOW,
        ASK,
        DENY,
        FULL
    }

    public record Request(UUID id, String name, long askedAt) {}

    public static final int MAX_PENDING = 20;
    public static final int MAX_DENIED = 500;
    public static final long ASK_MS = 5 * 60_000L;

    private final LongSupplier clock;
    private final Consumer<Request> onAsk;
    private final Map<UUID, Request> pending = new LinkedHashMap<>();
    private final Set<UUID> approved = new HashSet<>();
    private final Set<UUID> denied = new LinkedHashSet<>();

    public JoinApprovals(LongSupplier clock, Consumer<Request> onAsk) {
        this.clock = clock;
        this.onAsk = onAsk;
    }

    public JoinApprovals(Consumer<Request> onAsk) {
        this(System::currentTimeMillis, onAsk);
    }

    public Decision check(UUID id, String name) {
        Request fresh;
        synchronized (this) {
            if (approved.contains(id)) {
                return Decision.ALLOW;
            }
            if (denied.contains(id)) {
                return Decision.DENY;
            }
            long now = clock.getAsLong();
            pending.values().removeIf(r -> now - r.askedAt() >= ASK_MS);
            if (pending.containsKey(id)) {
                return Decision.ASK;
            }
            if (pending.size() >= MAX_PENDING) {
                return Decision.FULL;
            }
            fresh = new Request(id, clean(name), now);
            pending.put(id, fresh);
        }
        onAsk.accept(fresh);
        return Decision.ASK;
    }

    public synchronized boolean approve(UUID id) {
        if (pending.remove(id) == null) {
            return false;
        }
        approved.add(id);
        return true;
    }

    public synchronized void deny(UUID id) {
        pending.remove(id);
        denied.add(id);
        if (denied.size() > MAX_DENIED) {
            Iterator<UUID> oldest = denied.iterator();
            oldest.next();
            oldest.remove();
        }
    }

    public synchronized List<Request> pending() {
        long now = clock.getAsLong();
        pending.values().removeIf(r -> now - r.askedAt() >= ASK_MS);
        return new ArrayList<>(pending.values());
    }

    static String clean(String name) {
        StringBuilder sb = new StringBuilder();
        if (name != null) {
            for (int i = 0; i < name.length() && sb.length() < 16; i++) {
                char c = name.charAt(i);
                if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_') {
                    sb.append(c);
                }
            }
        }
        return sb.length() == 0 ? "unknown" : sb.toString();
    }
}
