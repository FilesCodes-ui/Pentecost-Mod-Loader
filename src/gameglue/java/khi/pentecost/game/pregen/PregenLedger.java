package khi.pentecost.game.pregen;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;

/**
 * Which chunks of a run are being made right now, and since when. No game classes, so it can be checked headless.
 * <p>
 * Three jobs: tell the progress file where it is safe to resume from, give up on chunks that never finish, and make
 * sure a chunk is only ever counted once even if its answer arrives after it was given up on.
 */
final class PregenLedger {
    private final ConcurrentSkipListSet<Long> inFlight = new ConcurrentSkipListSet<>();
    private final ConcurrentHashMap<Long, Long> admittedAt = new ConcurrentHashMap<>();

    void admit(long index, long nowNanos) {
        inFlight.add(index);
        admittedAt.put(index, nowNanos);
    }

    /** True when the chunk was being made. False when it was already finished or given up on: ignore that answer. */
    boolean complete(long index) {
        if (!inFlight.remove(index)) {
            return false;
        }
        admittedAt.remove(index);
        return true;
    }

    int size() {
        return inFlight.size();
    }

    /** Lowest chunk number that may not be on disk yet: the lower of the next one to ask for and the oldest in flight. */
    long safeIndex(long next) {
        try {
            return inFlight.isEmpty() ? next : Math.min(next, inFlight.first());
        } catch (NoSuchElementException e) {
            return next; // the last one finished between the two calls
        }
    }

    /** Removes and returns the chunks that have been in flight longer than the limit. */
    List<Long> takeOverdue(long nowNanos, long limitNanos) {
        List<Long> out = new ArrayList<>();
        for (var e : admittedAt.entrySet()) {
            long index = e.getKey();
            if (nowNanos - e.getValue() > limitNanos && complete(index)) {
                out.add(index);
            }
        }
        return out;
    }
}
