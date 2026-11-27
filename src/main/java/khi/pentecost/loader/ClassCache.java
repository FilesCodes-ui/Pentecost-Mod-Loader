package khi.pentecost.loader;

import khi.pentecost.boot.Dirs;
import khi.pentecost.boot.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Stream;
import java.util.zip.CRC32C;

public final class ClassCache {
    static final boolean CHECK = System.getenv("PENTECOST_CACHE_CHECK") != null;
    private static final int MAGIC = 0x50434332;
    private static final Set<String> IGNORED_ENV = Set.of(
            "PENTECOST_WORLD", "PENTECOST_WALL", "PENTECOST_JFR", "PENTECOST_CACHE_CHECK", "PENTECOST_NOCACHE", "PENTECOST_GC", "PENTECOST_JVM");

    private record Entry(long offset, int length, int rawCrc, int rawLength) {}

    private record Pending(String name, int rawCrc, int rawLength, byte[] data) {}

    private final Path pack;
    private final Path index;
    private final String fingerprint;
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final Map<String, Boolean> queued = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<Pending> pending = new ConcurrentLinkedQueue<>();
    private final FileChannel channel;
    private final LongAdder matches = new LongAdder();
    private final LongAdder differs = new LongAdder();
    private final Thread writer;
    private long end;
    private volatile boolean dirty;
    private volatile boolean off;
    private volatile boolean closing;

    public static ClassCache openFor(GameClassLoader loader, String choices) {
        if (System.getenv("PENTECOST_NOCACHE") != null) {
            Log.info("Class cache is off (PENTECOST_NOCACHE)");
            return null;
        }
        if (System.getenv("PENTECOST_REPORT") != null) {
            Log.info("Class cache is off while the mixin report runs");
            return null;
        }
        try {
            String key = fingerprint(loader.getURLs(), choices);
            ClassCache cache = new ClassCache(key);
            Log.info("Class cache: " + cache.entries.size() + " classes saved, key " + key.substring(0, 10)
                    + (CHECK ? ", check mode on" : ""));
            return cache;
        } catch (Throwable t) {
            Log.warn("Class cache is off: " + t);
            return null;
        }
    }

    private ClassCache(String fingerprint) throws IOException {
        this.fingerprint = fingerprint;
        Path dir = Dirs.mk(Dirs.cache().resolve("classes"));
        this.pack = dir.resolve("classes.pack");
        this.index = dir.resolve("classes.idx");
        loadIndex();
        this.channel = FileChannel.open(pack, StandardOpenOption.CREATE, StandardOpenOption.READ,
                StandardOpenOption.WRITE);
        if (channel.size() > end) {
            channel.truncate(end);
        }
        this.writer = new Thread(this::writeLoop, "Pentecost class cache writer");
        writer.setDaemon(true);
        writer.start();
        Runtime.getRuntime().addShutdownHook(new Thread(this::close, "Pentecost class cache close"));
    }

    private void loadIndex() {
        end = 0;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(index)))) {
            if (in.readInt() != MAGIC || !in.readUTF().equals(fingerprint)) {
                return;
            }
            long packLength = in.readLong();
            int count = in.readInt();
            if (packLength > Files.size(pack)) {
                return;
            }
            Map<String, Entry> loaded = new ConcurrentHashMap<>();
            for (int i = 0; i < count; i++) {
                String name = in.readUTF();
                loaded.put(name, new Entry(in.readLong(), in.readInt(), in.readInt(), in.readInt()));
            }
            entries.putAll(loaded);
            end = packLength;
        } catch (IOException | RuntimeException notUsable) {
            entries.clear();
            end = 0;
        }
    }

    byte[] get(String name, byte[] raw) {
        if (off) {
            return null;
        }
        Entry e = entries.get(name);
        if (e == null || e.rawLength != raw.length || e.rawCrc != crc(raw)) {
            return null;
        }
        try {
            ByteBuffer buf = ByteBuffer.allocate(e.length);
            long at = e.offset;
            while (buf.hasRemaining()) {
                int n = channel.read(buf, at);
                if (n < 0) {
                    throw new IOException("short read");
                }
                at += n;
            }
            return buf.array();
        } catch (IOException | RuntimeException failed) {
            off = true;
            Log.warn("Class cache stopped: " + failed);
            return null;
        }
    }

    void put(String name, byte[] raw, byte[] finished) {
        if (off || closing || entries.containsKey(name) || queued.putIfAbsent(name, Boolean.TRUE) != null) {
            return;
        }
        pending.add(new Pending(name, crc(raw), raw.length, finished));
    }

    void compare(String name, byte[] cached, byte[] fresh) {
        if (Arrays.equals(cached, fresh)) {
            matches.increment();
        } else {
            differs.increment();
            if (differs.sum() > 20) {
                return;
            }
            Log.warn("Class cache check: " + name + " differs (cached " + cached.length + " bytes, fresh "
                    + fresh.length + " bytes)");
        }
    }

    private static int crc(byte[] bytes) {
        CRC32C c = new CRC32C();
        c.update(bytes, 0, bytes.length);
        return (int) c.getValue();
    }

    private void writeLoop() {
        try {
            while (true) {
                Pending p = pending.poll();
                if (p == null) {
                    if (closing) {
                        return;
                    }
                    Thread.sleep(100);
                    continue;
                }
                ByteBuffer buf = ByteBuffer.wrap(p.data);
                long at = end;
                while (buf.hasRemaining()) {
                    at += channel.write(buf, at);
                }
                entries.put(p.name, new Entry(end, p.data.length, p.rawCrc, p.rawLength));
                end = at;
                dirty = true;
            }
        } catch (IOException | InterruptedException | RuntimeException failed) {
            off = true;
            Log.warn("Class cache stopped: " + failed);
        }
    }

    private void close() {
        closing = true;
        try {
            writer.join(30_000);
            if (CHECK) {
                Log.info("Class cache check: " + matches.sum() + " classes match, " + differs.sum() + " differ");
            }
            if (dirty && !off) {
                Path tmp = index.resolveSibling("classes.idx.tmp");
                try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp)))) {
                    out.writeInt(MAGIC);
                    out.writeUTF(fingerprint);
                    out.writeLong(end);
                    out.writeInt(entries.size());
                    for (Map.Entry<String, Entry> e : entries.entrySet()) {
                        out.writeUTF(e.getKey());
                        out.writeLong(e.getValue().offset);
                        out.writeInt(e.getValue().length);
                        out.writeInt(e.getValue().rawCrc);
                        out.writeInt(e.getValue().rawLength);
                    }
                }
                Files.move(tmp, index, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            }
            channel.close();
        } catch (IOException | InterruptedException | RuntimeException e) {
            System.out.println("[Pentecost] Class cache could not be saved: " + e);
        }
    }

    private static String fingerprint(URL[] urls, String choices) throws Exception {
        MessageDigest sha = MessageDigest.getInstance("SHA-1");
        feed(sha, "pentecost-class-cache-6");
        feed(sha, Runtime.version().toString());
        feed(sha, choices);
        feed(sha, khi.pentecost.compat.PassGuard.fingerprint());
        Map<String, String> env = new TreeMap<>();
        for (Map.Entry<String, String> e : System.getenv().entrySet()) {
            if (e.getKey().startsWith("PENTECOST_") && !IGNORED_ENV.contains(e.getKey())
                    && !e.getKey().startsWith("PENTECOST_TEST_")) {
                env.put(e.getKey(), e.getValue());
            }
        }
        feed(sha, env.toString());
        List<Path> roots = new ArrayList<>();
        for (URL u : urls) {
            roots.add(Path.of(u.toURI()));
        }
        var here = ClassCache.class.getProtectionDomain().getCodeSource();
        if (here != null) {
            roots.add(Path.of(here.getLocation().toURI()));
        }
        for (Path root : roots) {
            if (Files.isDirectory(root)) {
                List<Path> files;
                try (Stream<Path> walk = Files.walk(root)) {
                    files = walk.filter(Files::isRegularFile).sorted().toList();
                }
                for (Path f : files) {
                    feed(sha, root.relativize(f).toString());
                    feed(sha, Files.size(f) + ":" + Files.getLastModifiedTime(f).toMillis());
                }
            } else if (Files.exists(root)) {
                feed(sha, root.toString());
                feed(sha, Files.size(root) + ":" + Files.getLastModifiedTime(root).toMillis());
            } else {
                feed(sha, "missing:" + root);
            }
        }
        return HexFormat.of().formatHex(sha.digest());
    }

    private static void feed(MessageDigest sha, String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        sha.update(Integer.toString(b.length).getBytes(StandardCharsets.UTF_8));
        sha.update((byte) ':');
        sha.update(b);
    }
}
