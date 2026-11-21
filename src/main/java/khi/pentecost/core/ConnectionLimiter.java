package khi.pentecost.core;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Map;
import java.util.function.LongSupplier;

public final class ConnectionLimiter {
    public enum Verdict { ALLOW, TOO_MANY_OPEN, TOO_FAST, BANNED, BUSY }

    public record Limits(int perIpOpen, int perIpPerMinute, long banMs, int globalPerSecond, int floodSeconds, long pauseMs,
                         int maxTracked) {
        public static final Limits DEFAULT = new Limits(6, 12, 10 * 60_000L, 40, 5, 2 * 60_000L, 4096);
    }

    private static final class Entry {
        final ArrayDeque<Long> times = new ArrayDeque<>();
        int open;
        long bannedUntil;
        long lastSeen;
    }

    private static final long MINUTE = 60_000L;
    private static final long HOUR = 60 * MINUTE;

    private final Limits limits;
    private final LongSupplier clock;
    private final Map<String, Entry> byIp = new HashMap<>();
    private final ArrayDeque<Long> pauses = new ArrayDeque<>();
    private long pausedUntil;
    private long secondStart = Long.MIN_VALUE;
    private int secondCount;
    private int floodStreak;
    private int rejected;

    public ConnectionLimiter() {
        this(Limits.DEFAULT, () -> System.nanoTime() / 1_000_000L);
    }

    public ConnectionLimiter(Limits limits, LongSupplier millis) {
        this.limits = limits;
        this.clock = millis;
    }

    public static String key(SocketAddress address) {
        if (address instanceof InetSocketAddress isa && isa.getAddress() != null) {
            InetAddress a = isa.getAddress();
            if (a.isLoopbackAddress()) {
                return "loopback";
            }
            if (a instanceof Inet6Address) {
                return "v6:" + HexFormat.of().formatHex(a.getAddress(), 0, 8);
            }
            return a.getHostAddress();
        }
        return "unknown";
    }

    public synchronized Verdict admit(String ip) {
        long now = clock.getAsLong();
        if (ip.equals("loopback")) {
            return Verdict.ALLOW;
        }
        if (now < pausedUntil) {
            rejected++;
            return Verdict.BUSY;
        }

        if (secondStart == Long.MIN_VALUE || now - secondStart >= 1000) {
            if (secondStart != Long.MIN_VALUE) {
                if (secondCount > limits.globalPerSecond()) {
                    floodStreak = now - secondStart < 2000 ? floodStreak + 1 : 1;
                } else {
                    floodStreak = 0;
                }
            }
            secondStart = now;
            secondCount = 0;
            if (floodStreak >= limits.floodSeconds()) {
                pausedUntil = now + limits.pauseMs();
                pauses.addLast(now);
                floodStreak = 0;
                rejected++;
                return Verdict.BUSY;
            }
        }
        secondCount++;
        if (secondCount > limits.globalPerSecond()) {
            rejected++;
            return Verdict.BUSY;
        }

        Entry e = byIp.get(ip);
        if (e == null) {
            if (byIp.size() >= limits.maxTracked()) {
                prune(now);
                if (byIp.size() >= limits.maxTracked()) {
                    rejected++;
                    return Verdict.BUSY;
                }
            }
            e = new Entry();
            byIp.put(ip, e);
        }
        e.lastSeen = now;
        if (now < e.bannedUntil) {
            rejected++;
            return Verdict.BANNED;
        }
        while (!e.times.isEmpty() && now - e.times.peekFirst() >= MINUTE) {
            e.times.pollFirst();
        }
        if (e.times.size() >= limits.perIpPerMinute()) {
            e.bannedUntil = now + limits.banMs();
            rejected++;
            return Verdict.TOO_FAST;
        }
        if (e.open >= limits.perIpOpen()) {
            rejected++;
            return Verdict.TOO_MANY_OPEN;
        }
        e.times.addLast(now);
        e.open++;
        return Verdict.ALLOW;
    }

    public synchronized void released(String ip) {
        Entry e = byIp.get(ip);
        if (e != null && e.open > 0) {
            e.open--;
        }
    }

    private void prune(long now) {
        Iterator<Map.Entry<String, Entry>> it = byIp.entrySet().iterator();
        while (it.hasNext()) {
            Entry e = it.next().getValue();
            if (e.open == 0 && now >= e.bannedUntil && now - e.lastSeen >= MINUTE) {
                it.remove();
            }
        }
    }

    public synchronized boolean paused() {
        return clock.getAsLong() < pausedUntil;
    }

    public synchronized int pausesWithin(long windowMs) {
        long now = clock.getAsLong();
        while (!pauses.isEmpty() && now - pauses.peekFirst() >= HOUR) {
            pauses.pollFirst();
        }
        int n = 0;
        for (long t : pauses) {
            if (now - t < windowMs) {
                n++;
            }
        }
        return n;
    }

    public synchronized int rejectedTotal() {
        return rejected;
    }
}
