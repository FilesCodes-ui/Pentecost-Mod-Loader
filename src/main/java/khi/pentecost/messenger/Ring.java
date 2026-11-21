package khi.pentecost.messenger;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

final class Ring<E> {
    private final Object[] items;
    private final AtomicLongArray seq;
    private final int mask;
    private final AtomicLong head = new AtomicLong();
    private final AtomicLong tail = new AtomicLong();

    Ring(int capacity) {
        if (capacity < 2 || Integer.bitCount(capacity) != 1) {
            throw new IllegalArgumentException("Capacity must be a power of two");
        }
        items = new Object[capacity];
        seq = new AtomicLongArray(capacity);
        mask = capacity - 1;
        for (int i = 0; i < capacity; i++) {
            seq.set(i, i);
        }
    }

    boolean offer(E e) {
        long pos = tail.get();
        while (true) {
            int at = (int) (pos & mask);
            long dif = seq.get(at) - pos;
            if (dif == 0) {
                if (tail.compareAndSet(pos, pos + 1)) {
                    items[at] = e;
                    seq.set(at, pos + 1);
                    return true;
                }
                pos = tail.get();
            } else if (dif < 0) {
                return false;
            } else {
                pos = tail.get();
            }
        }
    }

    @SuppressWarnings("unchecked")
    E poll() {
        long pos = head.get();
        while (true) {
            int at = (int) (pos & mask);
            long dif = seq.get(at) - (pos + 1);
            if (dif == 0) {
                if (head.compareAndSet(pos, pos + 1)) {
                    E e = (E) items[at];
                    items[at] = null;
                    seq.set(at, pos + mask + 1);
                    return e;
                }
                pos = head.get();
            } else if (dif < 0) {
                return null;
            } else {
                pos = head.get();
            }
        }
    }

    int size() {
        return (int) Math.max(0, tail.get() - head.get());
    }

    int capacity() {
        return mask + 1;
    }
}
