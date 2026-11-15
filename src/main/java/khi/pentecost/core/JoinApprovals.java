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

/**
 * Who may be in a world open to friends. The host says yes or no to each account, and nobody gets in before that.
 * Accounts are told apart by UUID, which Mojang has already checked by the time the game knows it, so a made-up
 * name cannot borrow a friend's approval.
 *
 * A person who has not been decided on is turned away and the host is asked once. After a yes, the same account
 * can join. After a no, it is turned away every time until the world closes.
 * Everything here is bounded, so a flood of strangers cannot make it grow without end.
 */
public final class JoinApprovals {
    public enum Decision {
        /** Approved by the host: let them in. */
        ALLOW,
        /** Not decided yet: turn them away, the host has been asked. */
        ASK,
        /** The host said no: turn them away. */
        DENY,
        /** So many people are waiting that no more are taken: turn them away. */
        FULL
    }

    /** Someone waiting for a yes or a no. The name is cleaned for showing. */
    public record Request(UUID id, String name, long askedAt) {}

    public static final int MAX_PENDING = 20;
    public static final int MAX_DENIED = 500;
    /** A request nobody answers is dropped after this long, and a new try asks again. */
    public static final long ASK_MS = 5 * 60_000L;

    private final LongSupplier clock;
    private final Consumer<Request> onAsk;
    private final Map<UUID, Request> pending = new LinkedHashMap<>();
    private final Set<UUID> approved = new HashSet<>();
    private final Set<UUID> denied = new LinkedHashSet<>();

    /** onAsk is called once for each new request, from whichever thread called check, outside any lock. */
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

    /** Lets an account in. Only someone who asked can be approved. Returns false for anyone else. */
    public synchronized boolean approve(UUID id) {
        if (pending.remove(id) == null) {
            return false;
        }
        approved.add(id);
        return true;
    }

    /** Turns an account away for the rest of the session. */
    public synchronized void deny(UUID id) {
        pending.remove(id);
        denied.add(id);
        if (denied.size() > MAX_DENIED) {
            Iterator<UUID> oldest = denied.iterator();
            oldest.next();
            oldest.remove();
        }
    }

    /** Those waiting for an answer, oldest first. */
    public synchronized List<Request> pending() {
        long now = clock.getAsLong();
        pending.values().removeIf(r -> now - r.askedAt() >= ASK_MS);
        return new ArrayList<>(pending.values());
    }

    /** Minecraft names are letters, digits and underscores, up to 16. Anything else is dropped before it is shown. */
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
