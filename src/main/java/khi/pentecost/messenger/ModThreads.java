package khi.pentecost.messenger;

import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;

public final class ModThreads {
    public static final int HOME = -1;
    private static final int NONE = -2;

    private static final class Task {
        final Callable<?> work;
        volatile boolean done;
        volatile Object result;
        volatile Throwable error;
        final Thread waiter;

        Task(Callable<?> work, Thread waiter) {
            this.work = work;
            this.waiter = waiter;
        }

        void run() {
            try {
                result = work.call();
            } catch (Throwable t) {
                error = t;
            }
            done = true;
            LockSupport.unpark(waiter);
        }
    }

    public static volatile ModThreads shared;
    public static volatile khi.pentecost.core.ModOwners owners;

    public static final class Worker extends Thread {
        public final int index;
        public final ModThreads owner;
        public final java.util.ArrayList<Runnable> deferred = new java.util.ArrayList<>();
        public Object random;

        Worker(ModThreads owner, int index) {
            super(() -> owner.loop(index), "pentecost-mods-" + (index + 1));
            this.owner = owner;
            this.index = index;
            setDaemon(true);
        }
    }

    public static Worker worker() {
        return Thread.currentThread() instanceof Worker w ? w : null;
    }

    private final Queue<Task> homeInbox = new ConcurrentLinkedQueue<>();
    private final Map<String, Integer> threadOf;
    private final Set<String> libraries;
    private final Queue<Task>[] inbox;
    private final Thread[] threads;
    private final boolean[] cutOff;
    private final long timeoutNanos;
    private final Consumer<String> report;
    private final ThreadLocal<Integer> where = ThreadLocal.withInitial(() -> NONE);
    private volatile Thread home;
    private volatile boolean running = true;

    private final AtomicLong inline = new AtomicLong();
    private final AtomicLong carried = new AtomicLong();
    private final AtomicLong waitNanos = new AtomicLong();
    private final Map<String, AtomicLong> pairs = new ConcurrentHashMap<>();

    public record Stats(int threads, long inline, long carried, long averageWaitMicros, int cutOff) {}

    @SuppressWarnings("unchecked")
    public ModThreads(ThreadPlan.Plan plan, long timeoutMillis, Consumer<String> report) {
        this.threadOf = Map.copyOf(plan.threadOf());
        this.libraries = Set.copyOf(plan.libraries());
        this.timeoutNanos = timeoutMillis * 1_000_000L;
        this.report = report == null ? s -> {} : report;
        int n = plan.threads();
        inbox = new Queue[n];
        threads = new Thread[n];
        cutOff = new boolean[n];
        for (int i = 0; i < n; i++) {
            inbox[i] = new ConcurrentLinkedQueue<>();
            threads[i] = new Worker(this, i);
        }
        for (Thread t : threads) {
            t.start();
        }
    }

    public void setHome(Thread t) {
        home = t;
    }

    public int threadOf(String mod) {
        if (mod == null || libraries.contains(mod)) {
            return HOME;
        }
        return threadOf.getOrDefault(mod, HOME);
    }

    public int here() {
        int w = where.get();
        return w == NONE ? HOME : w;
    }

    @SuppressWarnings("unchecked")
    public <T> T call(String caller, String target, Callable<T> work) throws Exception {
        int to = threadOf(target);
        int from = here();
        if (to == from || to == HOME || !running || cutOff[to] || libraries.contains(target)) {
            inline.incrementAndGet();
            return work.call();
        }
        Task task = new Task(work, Thread.currentThread());
        pairs.computeIfAbsent((caller == null ? "?" : caller) + " -> " + target, k -> new AtomicLong()).incrementAndGet();
        carried.incrementAndGet();
        long start = System.nanoTime();
        inbox[to].add(task);
        LockSupport.unpark(threads[to]);
        while (!task.done) {
            if (from != HOME) {
                drain(from);
            } else if (Thread.currentThread() == home) {
                drainHome();
            }
            if (task.done) {
                break;
            }
            if (System.nanoTime() - start > timeoutNanos) {
                if (!cutOff[to]) {
                    cutOff[to] = true;
                    report.accept("Mod thread " + (to + 1) + " did not answer " + caller + " -> " + target + " within "
                            + timeoutNanos / 1_000_000 + " ms. It is cut off: its mods now run on the caller's thread.");
                }
                if (inbox[to].remove(task)) {
                    inline.incrementAndGet();
                    return work.call();
                }
                start = System.nanoTime();
            }
            LockSupport.parkNanos(20_000);
        }
        waitNanos.addAndGet(System.nanoTime() - start);
        if (task.error != null) {
            if (task.error instanceof Exception e) {
                throw e;
            }
            if (task.error instanceof Error e) {
                throw e;
            }
            throw new RuntimeException(task.error);
        }
        return (T) task.result;
    }

    public <T> T runOn(String mod, Callable<T> work) throws Exception {
        return call("server", mod, work);
    }

    @SuppressWarnings("unchecked")
    public <T> T callHome(Callable<T> work) throws Exception {
        Worker w = worker();
        if (w == null || w.owner != this || home == null) {
            return work.call();
        }
        Task task = new Task(work, Thread.currentThread());
        homeInbox.add(task);
        LockSupport.unpark(home);
        while (!task.done) {
            drain(w.index);
            if (!task.done) {
                LockSupport.parkNanos(20_000);
            }
        }
        if (task.error != null) {
            if (task.error instanceof Exception e) {
                throw e;
            }
            if (task.error instanceof Error e) {
                throw e;
            }
            throw new RuntimeException(task.error);
        }
        return (T) task.result;
    }

    public int threadCount() {
        return threads.length;
    }

    public Worker workerAt(int index) {
        return index >= 0 && index < threads.length ? (Worker) threads[index] : null;
    }

    public void drainHome() {
        Task t;
        while ((t = homeInbox.poll()) != null) {
            t.run();
        }
    }

    public void runAll(Map<Integer, Runnable> batches, Runnable idle, long slowMillis) {
        if (batches.isEmpty()) {
            return;
        }
        java.util.List<Task> tasks = new java.util.ArrayList<>();
        java.util.List<Integer> where = new java.util.ArrayList<>();
        for (Map.Entry<Integer, Runnable> b : batches.entrySet()) {
            int to = b.getKey();
            where.add(to);
            Runnable job = b.getValue();
            Task task = new Task(() -> {
                job.run();
                return null;
            }, Thread.currentThread());
            tasks.add(task);
            if (to < 0 || to >= threads.length || cutOff[to] || !running) {
                task.run();
            } else {
                inbox[to].add(task);
                LockSupport.unpark(threads[to]);
            }
        }
        long start = System.nanoTime();
        boolean told = false;
        while (true) {
            drainHome();
            boolean all = true;
            for (Task t : tasks) {
                if (!t.done) {
                    all = false;
                    break;
                }
            }
            if (all) {
                break;
            }
            if (idle != null) {
                idle.run();
            }
            if (!told && System.nanoTime() - start > slowMillis * 1_000_000L) {
                told = true;
                StringBuilder b = new StringBuilder("Mod threads took over " + slowMillis + " ms for one batch. The server waits for them.");
                for (int i = 0; i < tasks.size(); i++) {
                    int to = where.get(i);
                    if (tasks.get(i).done || to < 0 || to >= threads.length) {
                        continue;
                    }
                    b.append("\n  Mod thread ").append(to + 1).append(" is at:");
                    StackTraceElement[] st = threads[to].getStackTrace();
                    for (int k = 0; k < Math.min(st.length, 20); k++) {
                        b.append("\n    ").append(st[k]);
                    }
                }
                report.accept(b.toString());
            }
            LockSupport.parkNanos(10_000);
        }
        for (Task t : tasks) {
            if (t.error != null) {
                report.accept("A mod thread batch failed: " + t.error);
            }
        }
    }

    private void drain(int index) {
        Task t;
        while ((t = inbox[index].poll()) != null) {
            t.run();
        }
    }

    private void loop(int index) {
        where.set(index);
        while (running) {
            Task t = inbox[index].poll();
            if (t == null) {
                LockSupport.parkNanos(2_000_000);
                continue;
            }
            t.run();
        }
    }

    public void stop() {
        running = false;
        for (Thread t : threads) {
            LockSupport.unpark(t);
        }
    }

    public Stats stats() {
        long c = carried.get();
        int cut = 0;
        for (boolean b : cutOff) {
            if (b) {
                cut++;
            }
        }
        return new Stats(threads.length, inline.get(), c, c == 0 ? 0 : waitNanos.get() / c / 1000, cut);
    }

    public Map<String, Long> pairCounts() {
        Map<String, Long> m = new java.util.TreeMap<>();
        pairs.forEach((k, v) -> m.put(k, v.get()));
        return m;
    }
}
