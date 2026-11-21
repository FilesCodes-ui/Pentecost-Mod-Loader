package khi.pentecost.game.pregen;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;

final class PregenLedger {
    private final ConcurrentSkipListSet<Long> inFlight = new ConcurrentSkipListSet<>();
    private final ConcurrentHashMap<Long, Long> admittedAt = new ConcurrentHashMap<>();

    void admit(long index, long nowNanos) {
        inFlight.add(index);
        admittedAt.put(index, nowNanos);
    }

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

    long safeIndex(long next) {
        try {
            return inFlight.isEmpty() ? next : Math.min(next, inFlight.first());
        } catch (NoSuchElementException e) {
            return next;
        }
    }

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
