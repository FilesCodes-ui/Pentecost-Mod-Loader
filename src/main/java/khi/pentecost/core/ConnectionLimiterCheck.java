package khi.pentecost.core;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicLong;

import khi.pentecost.core.ConnectionLimiter.Limits;
import khi.pentecost.core.ConnectionLimiter.Verdict;

/** Headless check for ConnectionLimiter and HostGuard's decisions. A fake clock stands in for time. */
public final class ConnectionLimiterCheck {
    private static int failures;

    private ConnectionLimiterCheck() {}

    private static void expect(boolean ok, String what) {
        if (!ok) {
            failures++;
            System.out.println("FAIL: " + what);
        }
    }

    public static void main(String[] args) throws Exception {
        // One address holding many connections at once.
        AtomicLong now = new AtomicLong(1000);
        ConnectionLimiter l = new ConnectionLimiter(Limits.DEFAULT, now::get);
        for (int i = 0; i < 6; i++) {
            expect(l.admit("203.0.113.5") == Verdict.ALLOW, "open connection " + (i + 1));
        }
        expect(l.admit("203.0.113.5") == Verdict.TOO_MANY_OPEN, "seventh open connection is refused");
        l.released("203.0.113.5");
        expect(l.admit("203.0.113.5") == Verdict.ALLOW, "a closed connection frees a slot");
        expect(l.admit("198.51.100.7") == Verdict.ALLOW, "another address is not affected");

        // One address reconnecting again and again gets a temporary ban, then is let back in.
        for (int i = 0; i < 12; i++) {
            expect(l.admit("192.0.2.9") == Verdict.ALLOW, "reconnect " + (i + 1));
            l.released("192.0.2.9");
        }
        expect(l.admit("192.0.2.9") == Verdict.TOO_FAST, "thirteenth connection in a minute is refused");
        expect(l.admit("192.0.2.9") == Verdict.BANNED, "and the address stays banned");
        expect(l.admit("198.51.100.8") == Verdict.ALLOW, "others still get in during a ban");
        now.addAndGet(9 * 60_000L);
        expect(l.admit("192.0.2.9") == Verdict.BANNED, "still banned after nine minutes");
        now.addAndGet(2 * 60_000L);
        expect(l.admit("192.0.2.9") == Verdict.ALLOW, "allowed again after the ban ends");

        // The host's own machine is never limited.
        for (int i = 0; i < 200; i++) {
            expect(l.admit("loopback") == Verdict.ALLOW, "loopback connection " + i);
        }

        // A flood from many addresses pauses everything, then recovers.
        AtomicLong t2 = new AtomicLong(1000);
        ConnectionLimiter flood = new ConnectionLimiter(Limits.DEFAULT, t2::get);
        int allowed = 0;
        for (int sec = 0; sec <= 5; sec++) {
            t2.set(1000 + sec * 1000L);
            for (int i = 0; i < 60; i++) {
                if (flood.admit("10." + sec + ".0." + i) == Verdict.ALLOW) {
                    allowed++;
                }
            }
        }
        expect(allowed == 5 * 40, "only 40 per second get in during a flood, got " + allowed);
        expect(flood.paused(), "five flooded seconds in a row pause new connections");
        expect(flood.admit("1.2.3.4") == Verdict.BUSY, "refused while paused");
        expect(flood.pausesWithin(10 * 60_000L) == 1, "the pause is counted");
        t2.addAndGet(121_000L);
        expect(!flood.paused(), "the pause ends");
        expect(flood.admit("1.2.3.4") == Verdict.ALLOW, "connections work again after the pause");

        // A calm second in between resets the flood count.
        AtomicLong t3 = new AtomicLong(1000);
        ConnectionLimiter mixed = new ConnectionLimiter(Limits.DEFAULT, t3::get);
        int[] perSecond = {60, 60, 5, 60, 60, 60};
        for (int sec = 0; sec < perSecond.length; sec++) {
            t3.set(1000 + sec * 1000L);
            for (int i = 0; i < perSecond[sec]; i++) {
                mixed.admit("20." + sec + ".0." + i);
            }
        }
        expect(!mixed.paused(), "a calm second stops a flood from adding up");

        // Memory is bounded: when all slots are in use new addresses wait, and old ones are forgotten.
        AtomicLong t4 = new AtomicLong(1000);
        ConnectionLimiter small = new ConnectionLimiter(new Limits(6, 12, 600_000L, 1000, 5, 120_000L, 10), t4::get);
        for (int i = 0; i < 10; i++) {
            expect(small.admit("30.0.0." + i) == Verdict.ALLOW, "tracked address " + i);
        }
        expect(small.admit("30.0.0.99") == Verdict.BUSY, "an eleventh address is refused while all ten are in use");
        for (int i = 0; i < 10; i++) {
            small.released("30.0.0." + i);
        }
        t4.addAndGet(61_000L);
        expect(small.admit("30.0.0.99") == Verdict.ALLOW, "idle addresses are forgotten to make room");

        // Address keys: IPv6 counts by network, loopback is recognised.
        String a = ConnectionLimiter.key(new InetSocketAddress(InetAddress.getByName("2001:db8:1:2::1"), 1));
        String b = ConnectionLimiter.key(new InetSocketAddress(InetAddress.getByName("2001:db8:1:2:ffff::9"), 1));
        String c = ConnectionLimiter.key(new InetSocketAddress(InetAddress.getByName("2001:db8:1:3::1"), 1));
        expect(a.equals(b), "two addresses in one IPv6 network share a key");
        expect(!a.equals(c), "another IPv6 network has its own key");
        expect(ConnectionLimiter.key(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 1)).equals("loopback"), "127.0.0.1");
        expect(ConnectionLimiter.key(new InetSocketAddress(InetAddress.getByName("::1"), 1)).equals("loopback"), "::1");
        expect(ConnectionLimiter.key(new InetSocketAddress(InetAddress.getByName("203.0.113.5"), 1)).equals("203.0.113.5"), "IPv4 key");

        // The guard's decisions.
        long min = 60_000L;
        expect(HostGuard.verdict(29 * min, 0, 0) == null, "29 quiet minutes stays open");
        expect(HostGuard.verdict(30 * min, 0, 0) != null, "30 quiet minutes closes");
        expect(HostGuard.verdict(90 * min, 1, 0) == null, "a friend online keeps it open");
        expect(HostGuard.verdict(0, 1, 2) == null, "two floods are survived");
        expect(HostGuard.verdict(0, 1, 3) != null, "three floods close it even with friends online");

        if (failures > 0) {
            System.out.println(failures + " check(s) failed");
            System.exit(1);
        }
        System.out.println("ConnectionLimiterCheck passed");
    }
}
