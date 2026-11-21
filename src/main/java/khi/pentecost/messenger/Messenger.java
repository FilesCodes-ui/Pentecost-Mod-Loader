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

public final class Messenger {
    public static final int QUARANTINE_AFTER = 5;
    private static final int RING = 1 << 16;

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
        final Ask<?> ask;

        Envelope(String from, String route, Lane lane, Runnable job, Ask<?> ask) {
            this.from = from;
            this.route = route;
            this.lane = lane;
            this.job = job;
            this.ask = ask;
        }
    }

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

    public static int defaultWorkers() {
        return Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() - 2));
    }

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

    public void drop(Lane lane) {
        mailboxes.get(lane).clear();
    }

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

    public void note(String line) {
        report.note(line);
    }

    public boolean isQuarantined(String from) {
        return quarantined.contains(from);
    }

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
