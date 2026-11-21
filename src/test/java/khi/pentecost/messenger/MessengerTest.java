package khi.pentecost.messenger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessengerTest {
    private Messenger m;

    @AfterEach
    void off() {
        if (m != null) {
            m.setOn(false);
        }
    }

    private static void pumpUntil(Messenger m, Lane lane, java.util.function.BooleanSupplier ok) throws InterruptedException {
        long end = System.currentTimeMillis() + 2000;
        while (!ok.getAsBoolean() && System.currentTimeMillis() < end) {
            m.pump(lane, 1_000_000);
            Thread.sleep(1);
        }
        m.pump(lane, 1_000_000);
    }

    @Test
    void ringKeepsEveryItemOnceUnderManySenders() throws Exception {
        Ring<Integer> ring = new Ring<>(1024);
        int senders = 8;
        int each = 50_000;
        Set<Integer> seen = ConcurrentHashMap.newKeySet();
        AtomicInteger got = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        Thread[] ts = new Thread[senders];
        for (int s = 0; s < senders; s++) {
            int base = s * each;
            ts[s] = new Thread(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    return;
                }
                for (int i = 0; i < each; i++) {
                    while (!ring.offer(base + i)) {
                        Thread.onSpinWait();
                    }
                }
            });
            ts[s].start();
        }
        Thread reader = new Thread(() -> {
            while (got.get() < senders * each) {
                Integer v = ring.poll();
                if (v != null) {
                    assertTrue(seen.add(v), "seen twice: " + v);
                    got.incrementAndGet();
                }
            }
        });
        reader.start();
        start.countDown();
        for (Thread t : ts) {
            t.join(20_000);
        }
        reader.join(20_000);
        assertEquals(senders * each, seen.size());
        assertNull(ring.poll());
    }

    @Test
    void ringSaysFullAndRecovers() {
        Ring<Integer> ring = new Ring<>(4);
        for (int i = 0; i < 4; i++) {
            assertTrue(ring.offer(i));
        }
        assertFalse(ring.offer(9));
        assertEquals(0, ring.poll());
        assertTrue(ring.offer(9));
        assertEquals(4, ring.size());
    }

    @Test
    void offRunsTheFallbackRightAway() {
        m = new Messenger(null, 2);
        AtomicReference<String> path = new AtomicReference<>();
        m.ask("mod", "r", Lane.WORKER, () -> "new", Lane.SERVER, path::set, () -> path.set("old"), 100);
        assertEquals("old", path.get());
    }

    @Test
    void answersComeBackOnTheAnswerLane() throws Exception {
        m = new Messenger(null, 2);
        m.setOn(true);
        AtomicReference<String> worked = new AtomicReference<>();
        AtomicReference<String> answered = new AtomicReference<>();
        m.ask("mod", "r", Lane.WORKER, () -> {
            worked.set(Thread.currentThread().getName());
            return 42;
        }, Lane.SERVER, v -> answered.set(Thread.currentThread().getName() + ":" + v), () -> answered.set("fallback"), 1000);
        pumpUntil(m, Lane.SERVER, () -> answered.get() != null);
        assertTrue(worked.get().startsWith("pentecost-worker-"), worked.get());
        assertEquals(Thread.currentThread().getName() + ":42", answered.get());
    }

    @Test
    void aJobThatThrowsTakesTheFallbackAndIsReported(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("report.txt");
        m = new Messenger(file, 2);
        m.setOn(true);
        AtomicReference<String> path = new AtomicReference<>();
        m.ask("brokenmod", "path", Lane.WORKER, () -> {
            throw new IllegalStateException("boom");
        }, Lane.SERVER, v -> path.set("new"), () -> path.set("old"), 1000);
        pumpUntil(m, Lane.SERVER, () -> path.get() != null);
        assertEquals("old", path.get());
        String text = Files.readString(file);
        assertTrue(text.contains("brokenmod"), text);
        assertTrue(text.contains("boom"), text);
        assertEquals(1, m.stats().fallbacks());
    }

    @Test
    void aSlowJobTimesOutAndTheLateAnswerIsIgnored() throws Exception {
        m = new Messenger(null, 2);
        m.setOn(true);
        AtomicInteger answers = new AtomicInteger();
        AtomicReference<String> path = new AtomicReference<>();
        CountDownLatch release = new CountDownLatch(1);
        m.ask("slow", "r", Lane.WORKER, () -> {
            release.await(5, TimeUnit.SECONDS);
            return 1;
        }, Lane.SERVER, v -> {
            answers.incrementAndGet();
            path.set("new");
        }, () -> path.set("old"), 20);
        pumpUntil(m, Lane.SERVER, () -> path.get() != null);
        assertEquals("old", path.get());
        release.countDown();
        Thread.sleep(50);
        m.pump(Lane.SERVER, 1_000_000);
        assertEquals(0, answers.get());
        assertEquals(1, m.stats().timeouts());
    }

    @Test
    void aSenderThatKeepsFailingIsQuarantined() throws Exception {
        m = new Messenger(null, 2);
        m.setOn(true);
        AtomicInteger old = new AtomicInteger();
        for (int i = 0; i < Messenger.QUARANTINE_AFTER; i++) {
            m.ask("bad", "r", Lane.WORKER, () -> {
                throw new RuntimeException("no");
            }, Lane.SERVER, v -> { }, old::incrementAndGet, 1000);
        }
        pumpUntil(m, Lane.SERVER, () -> old.get() == Messenger.QUARANTINE_AFTER);
        assertTrue(m.isQuarantined("bad"));
        AtomicReference<String> path = new AtomicReference<>();
        m.ask("bad", "r", Lane.WORKER, () -> "x", Lane.SERVER, path::set, () -> path.set("old"), 1000);
        assertEquals("old", path.get());
        m.ask("good", "r", Lane.WORKER, () -> "new", Lane.SERVER, path::set, () -> path.set("old"), 1000);
        pumpUntil(m, Lane.SERVER, () -> "new".equals(path.get()));
        assertEquals("new", path.get());
        m.release("bad");
        assertFalse(m.isQuarantined("bad"));
    }

    @Test
    void anAnswerThatThrowsIsCaughtNotThrownIntoTheGame() throws Exception {
        m = new Messenger(null, 2);
        m.setOn(true);
        AtomicInteger ran = new AtomicInteger();
        m.ask("mod", "r", Lane.WORKER, () -> 1, Lane.GAME, v -> {
            ran.incrementAndGet();
            throw new RuntimeException("apply failed");
        }, () -> { }, 1000);
        pumpUntil(m, Lane.GAME, () -> ran.get() == 1);
        assertEquals(1, ran.get());
        assertEquals(1, m.stats().recentFailures().size());
    }

    @Test
    void manyRequestsFromManyThreadsAllSettleExactlyOnce() throws Exception {
        m = new Messenger(null, 4);
        m.setOn(true);
        int threads = 6;
        int each = 3000;
        AtomicInteger settled = new AtomicInteger();
        AtomicInteger news = new AtomicInteger();
        Thread[] ts = new Thread[threads];
        for (int t = 0; t < threads; t++) {
            ts[t] = new Thread(() -> {
                for (int i = 0; i < each; i++) {
                    int v = i;
                    m.ask("mod", "r", Lane.WORKER, () -> v * 2, Lane.SERVER, x -> {
                        news.incrementAndGet();
                        settled.incrementAndGet();
                    }, settled::incrementAndGet, 5000);
                }
            });
            ts[t].start();
        }
        for (Thread t : ts) {
            t.join(10_000);
        }
        long end = System.currentTimeMillis() + 10_000;
        while (settled.get() < threads * each && System.currentTimeMillis() < end) {
            m.pump(Lane.SERVER, 5_000_000);
        }
        assertEquals(threads * each, settled.get());
        assertTrue(news.get() > 0);
        assertEquals(0, m.pump(Lane.SERVER, 1_000_000));
    }

    @Test
    void ioLaneRunsOnVirtualThreads() throws Exception {
        m = new Messenger(null, 1);
        m.setOn(true);
        AtomicReference<Boolean> virtual = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        m.send("mod", "r", Lane.IO, () -> {
            virtual.set(Thread.currentThread().isVirtual());
            done.countDown();
        });
        assertTrue(done.await(2, TimeUnit.SECONDS));
        assertTrue(virtual.get());
    }
}
