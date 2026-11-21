package khi.pentecost.loader;

import khi.pentecost.boot.Log;

import java.util.concurrent.atomic.LongAdder;

public final class ClassStats {
    static final LongAdder CLASSES = new LongAdder();
    static final LongAdder HITS = new LongAdder();
    static final LongAdder SKIPPED = new LongAdder();
    static final LongAdder READ = new LongAdder();
    static final LongAdder CHAIN = new LongAdder();
    static final LongAdder MIXIN = new LongAdder();
    static final LongAdder TAIL = new LongAdder();
    static final LongAdder DEFINE = new LongAdder();
    static final LongAdder PEEKS = new LongAdder();
    static final LongAdder PEEK_NS = new LongAdder();

    private ClassStats() {}

    private static String s(LongAdder a) {
        return String.format(java.util.Locale.ROOT, "%.2f", a.sum() / 1e9);
    }

    public static void report(String at) {
        Log.info("Class stats at '" + at + "': " + CLASSES.sum() + " classes, " + HITS.sum()
                + " from the cache, " + SKIPPED.sum() + " kept out of it. Seconds summed over threads: read "
                + s(READ) + ", transformers " + s(CHAIN) + ", Mixin " + s(MIXIN) + ", after Mixin " + s(TAIL)
                + ", define " + s(DEFINE) + ". Mixin target reads " + PEEKS.sum() + " took " + s(PEEK_NS));
    }
}
