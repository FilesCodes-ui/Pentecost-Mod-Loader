package khi.pentecost.messenger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModThreadsTest {
    private ModThreads mt;
    private final List<String> notes = new ArrayList<>();

    private ModThreads make(long timeoutMs) {
        ThreadPlan.Plan plan = new ThreadPlan.Plan(2, List.of(), Map.of("a", 0, "b", 1), Set.of("lib"), List.of(), 0, 0, 0);
        mt = new ModThreads(plan, timeoutMs, notes::add);
        return mt;
    }

    @AfterEach
    void stop() {
        if (mt != null) {
            mt.stop();
        }
    }

    @Test
    void callRunsOnTargetThreadAndReturns() throws Exception {
        make(2000);
        String name = mt.call("server", "a", () -> Thread.currentThread().getName());
        assertEquals("pentecost-mods-1", name);
        assertEquals(1, mt.stats().carried());
    }

    @Test
    void libraryAndUnknownRunInline() throws Exception {
        make(2000);
        String here = Thread.currentThread().getName();
        assertEquals(here, mt.call("server", "lib", () -> Thread.currentThread().getName()));
        assertEquals(here, mt.call("server", "nobody", () -> Thread.currentThread().getName()));
        assertEquals(0, mt.stats().carried());
    }

    @Test
    void sameThreadRunsInline() throws Exception {
        make(2000);
        int inner = mt.call("server", "a", () -> mt.call("a", "a", () -> mt.here()));
        assertEquals(0, inner);
        assertEquals(1, mt.stats().carried());
    }

    @Test
    void callBackIntoWaiterDoesNotLockUp() throws Exception {
        make(2000);
        // server -> a, a -> b, b -> a: a answers b's call while it waits.
        String r = mt.call("server", "a", () -> mt.call("a", "b", () -> mt.call("b", "a", () -> Thread.currentThread().getName())));
        assertEquals("pentecost-mods-1", r);
        assertTrue(notes.isEmpty());
    }

    @Test
    void exceptionComesBackToCaller() {
        make(2000);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> mt.call("server", "b", () -> { throw new IllegalStateException("boom"); }));
        assertEquals("boom", e.getMessage());
    }

    @Test
    void stuckThreadIsCutOffAndCallsRunHere() throws Exception {
        make(150);
        CountDownLatch release = new CountDownLatch(1);
        Thread blocker = new Thread(() -> {
            try {
                mt.call("server", "a", () -> {
                    release.await();
                    return null;
                });
            } catch (Exception ignored) {
                // Not part of the test.
            }
        });
        blocker.start();
        Thread.sleep(50);
        String here = Thread.currentThread().getName();
        String ran = mt.call("server", "a", () -> Thread.currentThread().getName());
        assertEquals(here, ran);
        assertEquals(1, mt.stats().cutOff());
        assertTrue(notes.get(0).contains("cut off"));
        String after = mt.call("server", "a", () -> Thread.currentThread().getName());
        assertEquals(here, after);
        release.countDown();
        blocker.join(2000);
    }

    @Test
    void pairsAreCounted() throws Exception {
        make(2000);
        mt.call("x", "a", () -> 1);
        mt.call("x", "a", () -> 1);
        assertEquals(2L, mt.pairCounts().get("x -> a"));
        assertNotEquals(0, mt.stats().carried());
    }
}
