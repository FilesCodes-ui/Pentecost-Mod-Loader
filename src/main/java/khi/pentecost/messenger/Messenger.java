package khi.pentecost.messenger;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;

/**
 * Pentecost's messenger: one thread that does nothing but move jobs and answers between threads.
 *
 * A sender asks for work on a lane (the game thread, the server thread, the worker pool or virtual threads) and names
 * the lane that gets the answer. The router thread takes every request from one lock-free queue, checks the sender,
 * and hands the job to its lane. Game and server lanes run their jobs when those threads pump them, so world state is
 * only ever touched by its own thread.
 *
 * Every request carries a fallback: the old single-thread way of doing the same thing. The fallback runs, on the
 * answer lane, when the messenger is off, when the queue is full, when the job throws, or when it takes too long.
 * A sender that fails QUARANTINE_AFTER times is quarantined and from then on always takes the fallback.
 * Every failure goes in the report.
 */
public final class Messenger {
    public static final int QUARANTINE_AFTER = 5;
    private static final int RING = 1 << 16;

    /** Numbers for the panel. */
    public record LaneStats(Lane lane, long sent, long done, long failed, int waiting) {}

    public record Stats(boolean on, boolean routerAlive, int workers, List<LaneStats> lanes, long routed, long overflow,
                        long timeouts, long fallbacks, double avgRouteMicros, double avgWaitMicros, Set<String> quarantined,
                        List<String> recentFailures, String reportFile) {}

    private static final class Envelope {
        final String from;
        final String route;
        final Lane lane;
        final Runnable job;
        final long sentAt = System.nanoTime();
        /** Only for requests with an answer. */
        final Ask<?> ask;

        Envelope(String from, String route, Lane lane, Runnable job, Ask<?> ask) {
            this.from = from;
            this.route = route;
            this.lane = lane;
            this.job = job;
            this.ask = ask;
        }
    }

    /** A request with an answer. Exactly one of answer or fallback runs, on the answer lane. */
    private final class Ask<T> {
        final String from;
        final String route;
        final Lane replyLane;
        final Consumer<T> onResult;
        final Runnable fallback;
        final long deadline;
        final AtomicBoolean settled = new AtomicBoolean();

        Ask(String from, String route, Lane replyLane, Consumer<T> onResult, Runnable fallback, long timeoutMs) {
            this.from = from;
            this.route = route;
            this.replyLane = replyLane;
            this.onResult = onResult;
            this.fallback = fallback;
            this.deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(1, timeoutMs));
        }

        void answer(T value) {
            if (settled.compareAndSet(false, true)) {
                deliver(replyLane, () -> {
                    try {
                        onResult.accept(value);
                    } catch (Throwable t) {
                        failed("answer failed", from, route, replyLane, t, "The answer could not be applied");
                    }
                });
            }
        }

        void fail(String kind, Throwable t, String note) {
            if (settled.compareAndSet(false, true)) {
                failed(kind, from, route, replyLane, t, note);
                fallbacks.increment();
                deliver(replyLane, safe(from, route, replyLane, fallback));
            }
        }
    }

    private final Ring<Envelope> inbox = new Ring<>(RING);
    private final Map<Lane, ConcurrentLinkedQueue<Runnable>> mailboxes = new EnumMap<>(Lane.class);
    private final Map<Lane, LongAdder> sent = new EnumMap<>(Lane.class);
    private final Map<Lane, LongAdder> done = new EnumMap<>(Lane.class);
    private final Map<Lane, LongAdder> failedCount = new EnumMap<>(Lane.class);
    private final Map<String, AtomicInteger> failures = new ConcurrentHashMap<>();
    private final Set<String> quarantined = ConcurrentHashMap.newKeySet();
    private final LongAdder routed = new LongAdder();
    private final LongAdder overflow = new LongAdder();
    private final LongAdder timeouts = new LongAdder();
    private final LongAdder fallbacks = new LongAdder();
    private final LongAdder routeNanos = new LongAdder();
    private final LongAdder waitNanos = new LongAdder();
    private final LongAdder waitCount = new LongAdder();
    private final AtomicLong wakeups = new AtomicLong();
    private final Report report;
    private final int workerCount;

    private volatile boolean on;
    private volatile Thread router;
    private volatile boolean routerSleeping;
    private volatile ThreadPoolExecutor workers;
    private volatile ExecutorService io;
    /** Owned by the router thread only. */
    private final PriorityQueue<Ask<?>> pending = new PriorityQueue<>((a, b) -> Long.compare(a.deadline, b.deadline));

    private static volatile Messenger shared;

    public Messenger(Path reportFile, int workers) {
        this.report = new Report(reportFile);
        this.workerCount = Math.max(1, workers);
        for (Lane l : Lane.values()) {
            mailboxes.put(l, new ConcurrentLinkedQueue<>());
            sent.put(l, new LongAdder());
            done.put(l, new LongAdder());
            failedCount.put(l, new LongAdder());
        }
    }

    /** Workers for a machine: leave two cores for the game and server threads, never more than four. */
    public static int defaultWorkers() {
        return Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() - 2));
    }

    /** The messenger the game uses. Made on first use, with the report in the game's logs folder. */
    public static Messenger get() {
        Messenger m = shared;
        if (m == null) {
            synchronized (Messenger.class) {
                m = shared;
                if (m == null) {
                    Path file = null;
                    try {
                        file = khi.pentecost.boot.Dirs.run().resolve("logs").resolve("pentecost-messenger.txt");
                    } catch (RuntimeException | LinkageError e) {
                        // No game folder, as in tests: no report file.
                    }
                    m = new Messenger(file, defaultWorkers());
                    shared = m;
                }
            }
        }
        return m;
    }

    public boolean on() {
        return on;
    }

    /** Turns the messenger on or off. Off, every request takes its fallback on the caller's thread right away. */
    public synchronized void setOn(boolean want) {
        if (want == on) {
            return;
        }
        if (want) {
            if (workers == null) {
                AtomicInteger n = new AtomicInteger();
                workers = new ThreadPoolExecutor(workerCount, workerCount, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), r -> {
                    Thread t = new Thread(r, "pentecost-worker-" + n.incrementAndGet());
                    t.setDaemon(true);
                    t.setPriority(Thread.NORM_PRIORITY - 1);
                    return t;
                });
                io = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("pentecost-io-", 1).factory());
            }
            if (router == null || !router.isAlive()) {
                Thread t = new Thread(this::route, "pentecost-messenger");
                t.setDaemon(true);
                t.setPriority(Thread.NORM_PRIORITY + 1);
                router = t;
                t.start();
            }
            report.note("Messenger on: " + workerCount + " workers");
        } else {
            report.note("Messenger off");
        }
        on = want;
    }

    // ------------------------------------------------------------------ sending

    /**
     * Runs work on workLane and hands the result to onResult on replyLane. If anything goes wrong, fallback runs on
     * replyLane in its place. When the messenger is off, or the sender is quarantined, fallback runs right now on the
     * calling thread: call this from the thread replyLane names.
     */
    public <T> void ask(String from, String route, Lane workLane, Callable<T> work, Lane replyLane, Consumer<T> onResult,
                        Runnable fallback, long timeoutMs) {
        if (!on || quarantined.contains(from)) {
            fallback.run();
            return;
        }
        Ask<T> ask = new Ask<>(from, route, replyLane, onResult, fallback, timeoutMs);
        Runnable job = () -> {
            if (ask.settled.get()) {
                return;
            }
            T value;
            try {
                value = work.call();
            } catch (Throwable t) {
                failedCount.get(workLane).increment();
                ask.fail("job failed", t, "The job threw. The old path ran instead.");
                return;
            }
            ask.answer(value);
        };
        if (!offer(new Envelope(from, route, workLane, job, ask))) {
            overflow.increment();
            fallbacks.increment();
            fallback.run();
        }
    }

    /** Runs a job on a lane, no answer. Off, it runs right now on the calling thread. */
    public void send(String from, String route, Lane lane, Runnable job) {
        if (!on || quarantined.contains(from)) {
            safe(from, route, lane, job).run();
            return;
        }
        if (!offer(new Envelope(from, route, lane, safe(from, route, lane, job), null))) {
            overflow.increment();
            safe(from, route, lane, job).run();
        }
    }

    private boolean offer(Envelope e) {
        if (!inbox.offer(e)) {
            return false;
        }
        sent.get(e.lane).increment();
        if (routerSleeping) {
            Thread r = router;
            if (r != null) {
                wakeups.incrementAndGet();
                LockSupport.unpark(r);
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ the router thread

    private void route() {
        int idle = 0;
        while (true) {
            Envelope e = inbox.poll();
            if (e != null) {
                idle = 0;
                dispatch(e);
                continue;
            }
            expire();
            if (++idle < 200) {
                Thread.onSpinWait();
                continue;
            }
            routerSleeping = true;
            if (inbox.size() == 0) {
                // Short sleeps, so timeouts are still checked about every 0.2 ms when nothing comes in.
                LockSupport.parkNanos(200_000);
            }
            routerSleeping = false;
            idle = 0;
        }
    }

    private void dispatch(Envelope e) {
        long start = System.nanoTime();
        routed.increment();
        if (e.ask != null) {
            if (quarantined.contains(e.from)) {
                e.ask.fail("quarantined", null, "The sender is quarantined. The old path ran.");
                return;
            }
            pending.add(e.ask);
        }
        long queued = e.sentAt;
        Lane lane = e.lane;
        Runnable timed = () -> {
            waitNanos.add(System.nanoTime() - queued);
            waitCount.increment();
            try {
                e.job.run();
            } finally {
                done.get(lane).increment();
            }
        };
        try {
            switch (lane) {
                case WORKER -> workers.execute(timed);
                case IO -> io.execute(timed);
                default -> mailboxes.get(lane).add(timed);
            }
        } catch (RuntimeException ex) {
            if (e.ask != null) {
                e.ask.fail("rejected", ex, "The lane did not take the job");
            } else {
                failed("rejected", e.from, e.route, lane, ex, "The lane did not take the job");
            }
        }
        routeNanos.add(System.nanoTime() - start);
    }

    private void expire() {
        long now = System.nanoTime();
        while (!pending.isEmpty()) {
            Ask<?> a = pending.peek();
            if (a.settled.get()) {
                pending.poll();
                continue;
            }
            if (a.deadline > now) {
                return;
            }
            pending.poll();
            timeouts.increment();
            a.fail("timeout", new TimeoutException("No answer in time"), "The job took too long. The old path ran instead.");
        }
    }

    // ------------------------------------------------------------------ lanes the game pumps

    private void deliver(Lane lane, Runnable r) {
        switch (lane) {
            case WORKER -> {
                ThreadPoolExecutor w = workers;
                if (w != null) {
                    w.execute(r);
                } else {
                    r.run();
                }
            }
            case IO -> {
                ExecutorService v = io;
                if (v != null) {
                    v.execute(r);
                } else {
                    r.run();
                }
            }
            default -> mailboxes.get(lane).add(r);
        }
    }

    /**
     * Runs the jobs waiting for a lane, on the calling thread, until none are left or budgetNanos is spent.
     * The game calls this once a frame for GAME and the server once a tick for SERVER. Returns how many ran.
     */
    public int pump(Lane lane, long budgetNanos) {
        ConcurrentLinkedQueue<Runnable> box = mailboxes.get(lane);
        long end = System.nanoTime() + budgetNanos;
        int n = 0;
        Runnable r;
        while ((r = box.poll()) != null) {
            try {
                r.run();
            } catch (Throwable t) {
                failed("job failed", "unknown", "pump", lane, t, null);
            }
            n++;
            if ((n & 15) == 0 && System.nanoTime() > end) {
                break;
            }
        }
        return n;
    }

    /** Throws away what waits for a lane, when its thread goes away (a world closes). The fallbacks are not run. */
    public void drop(Lane lane) {
        mailboxes.get(lane).clear();
    }

    // ------------------------------------------------------------------ failures

    private Runnable safe(String from, String route, Lane lane, Runnable job) {
        return () -> {
            try {
                job.run();
            } catch (Throwable t) {
                failedCount.get(lane).increment();
                failed("job failed", from, route, lane, t, null);
            }
        };
    }

    private void failed(String kind, String from, String route, Lane lane, Throwable t, String note) {
        report.add(kind, from, route, lane, Thread.currentThread().getName(), t, note);
        if ("quarantined".equals(kind)) {
            return;
        }
        int n = failures.computeIfAbsent(from, k -> new AtomicInteger()).incrementAndGet();
        if (n >= QUARANTINE_AFTER && quarantined.add(from)) {
            report.note("QUARANTINED " + from + " after " + n + " failures. It uses the old path until the game restarts.");
        }
    }

    /** A line in the report, for decisions worth knowing about (a job sent back to the old way, for one). */
    public void note(String line) {
        report.note(line);
    }

    public boolean isQuarantined(String from) {
        return quarantined.contains(from);
    }

    /** Takes a sender out of quarantine, for the panel. */
    public void release(String from) {
        quarantined.remove(from);
        failures.remove(from);
    }

    public Stats stats() {
        List<LaneStats> lanes = new ArrayList<>();
        for (Lane l : Lane.values()) {
            int waiting = switch (l) {
                case WORKER -> workers == null ? 0 : workers.getQueue().size();
                case IO -> 0;
                default -> mailboxes.get(l).size();
            };
            lanes.add(new LaneStats(l, sent.get(l).sum(), done.get(l).sum(), failedCount.get(l).sum(), waiting));
        }
        long r = routed.sum();
        long w = waitCount.sum();
        Thread t = router;
        return new Stats(on, t != null && t.isAlive(), workerCount, lanes, r, overflow.sum(), timeouts.sum(), fallbacks.sum(),
                r == 0 ? 0 : routeNanos.sum() / 1000.0 / r, w == 0 ? 0 : waitNanos.sum() / 1000.0 / w,
                Set.copyOf(quarantined), report.recent(), report.file() == null ? "" : report.file().toString());
    }
}
