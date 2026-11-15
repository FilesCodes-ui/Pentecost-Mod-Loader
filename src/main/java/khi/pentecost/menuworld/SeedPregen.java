package khi.pentecost.menuworld;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Generates the chunks of one seed with an unmodified vanilla 1.20.1 server in its own JVM, then copies the region
 * files into the menu-world cache. Nothing here touches the game or any mod, so no mod biome, block or feature can
 * end up in the cache. Run once per seed and area; later launches only read the cache.
 *
 * The server needs the Mojang EULA accepted. {@link #run} writes eula=true into its own temporary folder, so the
 * caller must have asked the player first.
 *
 * Cache layout: {@code <cacheDir>/meta.json}, {@code <cacheDir>/progress.json} and {@code <cacheDir>/region/r.X.Z.mca}.
 * meta.json is written last and says the area is finished. progress.json lists the chunk rectangles that are
 * finished, so a run for a bigger or moved area, or one that was stopped, carries on instead of starting again.
 */
public final class SeedPregen {
    private SeedPregen() {}

    public static final String MC_VERSION = "1.20.1";

    /** The server being generated with right now, so a newer request can stop it instead of running beside it. */
    private static volatile Process active;

    static {
        // A server left running after the game closes would keep working on a world nobody asked for.
        Runtime.getRuntime().addShutdownHook(new Thread(SeedPregen::cancelRunning, "pregen-stop"));
    }

    /** Stops the server that is generating now, if any. The run that started it then ends with an error. */
    public static void cancelRunning() {
        Process p = active;
        if (p != null) p.destroyForcibly();
    }

    /** Two servers on the default port cannot both start, so each one gets a free port of its own. */
    private static int freePort() {
        try (java.net.ServerSocket s = new java.net.ServerSocket(0)) {
            return s.getLocalPort();
        } catch (IOException e) {
            return 25565;
        }
    }
    private static final int BLOCK_CHUNKS = 16; // forceload takes at most 256 chunks per command

    /** The area is a square of 2 * radiusChunks chunks per side around the centre block. */
    public record Area(long seed, int centerBlockX, int centerBlockZ, int radiusChunks) {
        /** Pass this as the centre to centre the area on the generated world's spawn point. */
        public static final int SPAWN = Integer.MIN_VALUE;

        public boolean centerOnSpawn() { return centerBlockX == SPAWN; }

        public Area withCenter(int x, int z) { return new Area(seed, x, z, radiusChunks); }

        public int minChunkX() { return Math.floorDiv(centerBlockX, 16) - radiusChunks; }
        public int minChunkZ() { return Math.floorDiv(centerBlockZ, 16) - radiusChunks; }
        public int chunksPerSide() { return radiusChunks * 2; }
        public int totalChunks() { return chunksPerSide() * chunksPerSide(); }
    }

    public record Progress(String stage, int chunksDone, int chunksTotal) {}

    public static boolean isComplete(Path cacheDir, Area area) {
        Path meta = cacheDir.resolve("meta.json");
        if (!Files.isRegularFile(meta)) return false;
        try {
            JsonObject o = JsonParser.parseString(Files.readString(meta)).getAsJsonObject();
            if (!o.get("complete").getAsBoolean() || o.get("seed").getAsLong() != area.seed()
                    || o.get("radiusChunks").getAsInt() < area.radiusChunks()) return false;
            if (area.centerOnSpawn()) return o.has("spawnCentered") && o.get("spawnCentered").getAsBoolean();
            return o.get("centerBlockX").getAsInt() == area.centerBlockX()
                    && o.get("centerBlockZ").getAsInt() == area.centerBlockZ();
        } catch (Exception e) {
            return false;
        }
    }

    /** The vanilla server's classpath: the client jar plus the libraries its version JSON lists for this OS. */
    static List<Path> serverClasspath(Path home) throws IOException {
        Path versionDir = home.resolve("cache").resolve("minecraft").resolve(MC_VERSION);
        JsonObject version = JsonParser.parseString(
                Files.readString(versionDir.resolve(MC_VERSION + ".json"), StandardCharsets.UTF_8)).getAsJsonObject();
        List<Path> cp = new ArrayList<>();
        cp.add(versionDir.resolve("client.jar"));
        List<String> missing = new ArrayList<>();
        for (JsonElement el : version.getAsJsonArray("libraries")) {
            JsonObject lib = el.getAsJsonObject();
            if (lib.has("rules") && !ruleAllows(lib.getAsJsonArray("rules"))) continue;
            JsonObject downloads = lib.getAsJsonObject("downloads");
            if (downloads == null || !downloads.has("artifact")) continue;
            String rel = downloads.getAsJsonObject("artifact").get("path").getAsString();
            Path p = home.resolve("cache").resolve("libraries").resolve(rel.replace('/', java.io.File.separatorChar));
            if (Files.isRegularFile(p)) cp.add(p); else missing.add(rel);
        }
        if (!Files.isRegularFile(cp.get(0))) throw new IOException("Missing " + cp.get(0));
        if (!missing.isEmpty()) throw new IOException("Missing libraries: " + missing);
        return cp;
    }

    private static boolean ruleAllows(JsonArray rules) {
        String os = System.getProperty("os.name", "").toLowerCase();
        String name = os.contains("win") ? "windows" : os.contains("mac") ? "osx" : "linux";
        boolean allowed = false;
        for (JsonElement el : rules) {
            JsonObject r = el.getAsJsonObject();
            String ruleOs = r.has("os") && r.getAsJsonObject("os").has("name")
                    ? r.getAsJsonObject("os").get("name").getAsString() : null;
            if (ruleOs == null || ruleOs.equals(name)) allowed = r.get("action").getAsString().equals("allow");
        }
        return allowed;
    }

    // ------------------------------------------------------------------ one run, which can be pointed at a new area

    /**
     * One generation. Its target can change while it runs (a new radius or centre): the run then carries on with the
     * chunks it has already finished instead of starting again.
     */
    private static final class Job {
        final long seed;
        volatile Area target;
        volatile Consumer<Progress> progress;
        /** Finish what is being generated, save it and end. Set by {@link #stopRunning}. */
        volatile boolean stop;
        /** The loop decided it is done, so a new target can no longer join. Guarded by LOCK. */
        boolean closed;

        Job(long seed, Area target, Consumer<Progress> progress) {
            this.seed = seed;
            this.target = target;
            this.progress = progress;
        }

        void report(String stage, int done, int total) {
            Consumer<Progress> p = progress;
            if (p != null) p.accept(new Progress(stage, done, total));
        }
    }

    private static final Object LOCK = new Object();
    private static Job job; // guarded by LOCK

    /**
     * Points the run that is going now at a new area, so it carries on instead of starting again. Returns true when
     * a run for this seed exists (it takes the new area, or it is already saving and the next run will find the cache
     * almost done). Returns false when nothing runs or it runs another seed: the caller starts its own.
     *
     * @param progress where the run reports from now on, or null to keep the one it has
     */
    public static boolean retarget(Area area, Consumer<Progress> progress) {
        synchronized (LOCK) {
            Job j = job;
            if (j == null || j.seed != area.seed()) return false;
            if (!j.closed && !j.stop) {
                j.target = area;
                if (progress != null) j.progress = progress;
            }
            return true;
        }
    }

    /**
     * Asks the run that is going now to stop after the block it is on, keeping everything it has finished. Unlike
     * {@link #cancelRunning} nothing is lost: the next run for the seed carries on from there.
     */
    public static void stopRunning() {
        synchronized (LOCK) {
            if (job != null) job.stop = true;
        }
    }

    /** The cache's finished chunks, as chunk rectangles {minX, minZ, maxX, maxZ}, both ends included. */
    private static List<int[]> loadDone(Path cacheDir) {
        List<int[]> done = new ArrayList<>();
        try {
            Path pf = cacheDir.resolve("progress.json");
            if (Files.isRegularFile(pf)) {
                JsonObject o = JsonParser.parseString(Files.readString(pf)).getAsJsonObject();
                for (JsonElement el : o.getAsJsonArray("done")) {
                    JsonArray a = el.getAsJsonArray();
                    done.add(new int[] {a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt(), a.get(3).getAsInt()});
                }
            }
            Path meta = cacheDir.resolve("meta.json");
            if (Files.isRegularFile(meta)) {
                JsonObject o = JsonParser.parseString(Files.readString(meta)).getAsJsonObject();
                if (o.get("complete").getAsBoolean()) {
                    int r = o.get("radiusChunks").getAsInt();
                    int cx = Math.floorDiv(o.get("centerBlockX").getAsInt(), 16);
                    int cz = Math.floorDiv(o.get("centerBlockZ").getAsInt(), 16);
                    done.add(new int[] {cx - r, cz - r, cx + r - 1, cz + r - 1});
                }
            }
        } catch (Exception e) {
            done.clear(); // unreadable bookkeeping: trust nothing, the server still loads what is on disk
        }
        return done;
    }

    private static boolean covered(List<int[]> done, int[] r) {
        for (int[] d : done) {
            if (d[0] <= r[0] && d[1] <= r[1] && d[2] >= r[2] && d[3] >= r[3]) return true;
        }
        return false;
    }

    /**
     * The next block of the area that is not finished, nearest the centre first, or null when the area is done.
     * Blocks sit on a fixed grid (16 chunks, from chunk 0) and are cut to the area, so a block that was finished for
     * a smaller area is still finished when the area grows around it. finished[0] gets the chunks already done.
     */
    private static int[] nextBlock(Area a, List<int[]> done, int[] finished) {
        int minX = a.minChunkX(), minZ = a.minChunkZ();
        int maxX = minX + a.chunksPerSide() - 1, maxZ = minZ + a.chunksPerSide() - 1;
        long ccx = minX + a.radiusChunks(), ccz = minZ + a.radiusChunks();
        int[] best = null;
        long bestD = Long.MAX_VALUE;
        int fin = 0;
        for (int tx = Math.floorDiv(minX, BLOCK_CHUNKS); tx <= Math.floorDiv(maxX, BLOCK_CHUNKS); tx++) {
            for (int tz = Math.floorDiv(minZ, BLOCK_CHUNKS); tz <= Math.floorDiv(maxZ, BLOCK_CHUNKS); tz++) {
                int[] r = {Math.max(minX, tx * BLOCK_CHUNKS), Math.max(minZ, tz * BLOCK_CHUNKS),
                        Math.min(maxX, tx * BLOCK_CHUNKS + BLOCK_CHUNKS - 1), Math.min(maxZ, tz * BLOCK_CHUNKS + BLOCK_CHUNKS - 1)};
                if (covered(done, r)) {
                    fin += (r[2] - r[0] + 1) * (r[3] - r[1] + 1);
                    continue;
                }
                long dx = ((long) r[0] + r[2]) / 2 - ccx, dz = ((long) r[1] + r[3]) / 2 - ccz;
                long d = dx * dx + dz * dz;
                if (d < bestD) {
                    bestD = d;
                    best = r;
                }
            }
        }
        finished[0] = fin;
        return best;
    }

    private static void writeProgress(Path cacheDir, List<int[]> done) throws IOException {
        JsonArray rects = new JsonArray();
        for (int[] r : done) {
            JsonArray a = new JsonArray();
            for (int v : r) a.add(v);
            rects.add(a);
        }
        JsonObject o = new JsonObject();
        o.add("done", rects);
        Files.writeString(cacheDir.resolve("progress.json"), new Gson().toJson(o));
    }

    /**
     * Generates the area and fills the cache. Blocks until done. Throws if the server fails or stalls.
     * A cache that is only partly there is carried on, not started again: chunks it already has are not generated.
     * The progress callback runs on the calling thread, or on the thread of whoever called {@link #retarget}.
     */
    public static void run(Path home, Path cacheDir, Area requested, Consumer<Progress> progress)
            throws IOException, InterruptedException {
        run(home, cacheDir, requested, progress, 0);
    }

    /** @param cores how many processors the server may use, or 0 for all of them. A game is running: use few. */
    public static void run(Path home, Path cacheDir, Area requested, Consumer<Progress> progress, int cores)
            throws IOException, InterruptedException {
        Job j = new Job(requested.seed(), requested, progress);
        synchronized (LOCK) {
            job = j;
        }
        try {
            runJob(home, cacheDir, j, cores);
        } finally {
            synchronized (LOCK) {
                if (job == j) job = null;
            }
        }
    }

    private static void runJob(Path home, Path cacheDir, Job j, int cores) throws IOException, InterruptedException {
        Files.createDirectories(cacheDir);
        // A folder left by a run that was killed (the game closed, a crash) is of no use. Only one run goes at a time.
        try (Stream<Path> old = Files.list(cacheDir)) {
            for (Path d : (Iterable<Path>) old::iterator) {
                if (Files.isDirectory(d) && d.getFileName().toString().startsWith(".work-")) deleteTree(d);
            }
        }
        // meta.json stays: what the cache already holds is still good while this run adds to it.
        List<int[]> done = loadDone(cacheDir);
        int[] finished = {0};

        // Everything the cache already covers? Then there is nothing to generate: only the meta changes.
        {
            Area first = j.target;
            JsonObject old = readMeta(cacheDir);
            if (old != null && old.has("spawnX") && old.has("spawnY") && old.has("spawnZ")) {
                Area want = first.centerOnSpawn() ? first.withCenter(old.get("spawnX").getAsInt(), old.get("spawnZ").getAsInt()) : first;
                boolean sameWorld = !first.centerOnSpawn() || (old.has("spawnCentered") && old.get("spawnCentered").getAsBoolean());
                if (sameWorld && nextBlock(want, done, finished) == null) {
                    synchronized (LOCK) {
                        if (j.target == first) j.closed = true; else want = null;
                    }
                    if (want != null) {
                        writeMeta(cacheDir, want, first.centerOnSpawn(),
                                new int[] {old.get("spawnX").getAsInt(), old.get("spawnY").getAsInt(), old.get("spawnZ").getAsInt()}, done);
                        j.report("Done", want.totalChunks(), want.totalChunks());
                        return;
                    }
                }
            }
        }

        Path work = Files.createTempDirectory(cacheDir, ".work-");
        Process proc = null;
        try {
            // The player agreed to the Mojang EULA (https://aka.ms/MinecraftEULA) before this runs.
            Files.writeString(work.resolve("eula.txt"), "eula=true\n");
            Area startArea = j.target;
            Files.writeString(work.resolve("server.properties"),
                    "level-seed=" + startArea.seed() + "\nonline-mode=false\nspawn-protection=0\nview-distance=3\n"
                            + "simulation-distance=3\nspawn-monsters=false\nspawn-animals=false\nserver-port=" + freePort() + "\n"
                            + "generate-structures=true\nenable-command-block=false\nmax-tick-time=-1\n");

            // Carry on from the cache: the server finds these region files and loads the chunks instead of generating them.
            Path cachedRegion = cacheDir.resolve("region");
            Path workRegion = Files.createDirectories(work.resolve("world").resolve("region"));
            if (Files.isDirectory(cachedRegion)) {
                try (Stream<Path> s = Files.list(cachedRegion)) {
                    for (Path p : (Iterable<Path>) s::iterator) {
                        if (p.getFileName().toString().endsWith(".mca")) {
                            Files.copy(p, workRegion.resolve(p.getFileName().toString()));
                        }
                    }
                }
            }

            StringBuilder cp = new StringBuilder();
            for (Path p : serverClasspath(home)) {
                if (cp.length() > 0) cp.append(java.io.File.pathSeparator);
                cp.append(p);
            }
            String javaExe = Path.of(System.getProperty("java.home"), "bin", "java").toString();
            List<String> cmd = new ArrayList<>();
            cmd.add(javaExe);
            cmd.add("-Xmx2G");
            if (cores > 0) {
                cmd.add("-XX:ActiveProcessorCount=" + cores); // the server sizes its worker pool from this
            }
            cmd.addAll(List.of("-cp", cp.toString(), "net.minecraft.server.Main", "--nogui", "--universe", work.toString()));
            proc = new ProcessBuilder(cmd).directory(work.toFile()).redirectErrorStream(true).start();

            active = proc;
            final List<String> log = new CopyOnWriteArrayList<>();
            final Process serverProc = proc;
            Thread pump = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(serverProc.getInputStream(), StandardCharsets.UTF_8))) {
                    for (String ln; (ln = r.readLine()) != null; ) log.add(ln);
                } catch (IOException ignored) {
                }
            }, "pregen-log");
            pump.setDaemon(true);
            pump.start();
            Writer in = new OutputStreamWriter(proc.getOutputStream(), StandardCharsets.UTF_8);

            j.report("Starting the vanilla server", 0, startArea.totalChunks());
            if (waitFor(log, 0, l -> l.contains("Done ("), proc, 300_000) < 0) {
                throw new IOException("The vanilla server did not finish starting:\n" + tail(log));
            }

            int[] spawn = null;
            Area raw, want = null;
            boolean stopped = false;
            while (true) {
                raw = j.target; // may be a new area since the last block: carry on towards that one
                if (j.stop) {
                    stopped = true;
                    synchronized (LOCK) {
                        j.closed = true;
                    }
                    break;
                }
                if (raw.centerOnSpawn() && spawn == null) {
                    // The server writes level.dat on a save. Its spawn point is where the player would start.
                    int mark = log.size();
                    send(in, "save-all flush");
                    if (waitFor(log, mark, l -> l.contains("Saved the game"), proc, 120_000) < 0) {
                        throw new IOException("The server did not save, so the spawn point is unknown:\n" + tail(log));
                    }
                    spawn = readSpawn(work.resolve("world").resolve("level.dat"));
                    if (spawn == null) throw new IOException("Could not read the world spawn from level.dat");
                }
                want = raw.centerOnSpawn() ? raw.withCenter(spawn[0], spawn[2]) : raw;
                int[] block = nextBlock(want, done, finished);
                if (block == null) {
                    synchronized (LOCK) {
                        if (j.target == raw) { // nobody pointed the run elsewhere while it checked
                            j.closed = true;
                            break;
                        }
                    }
                    continue;
                }
                int x0 = block[0], z0 = block[1], x1 = block[2], z1 = block[3];
                String span = (x0 * 16) + " " + (z0 * 16) + " " + (x1 * 16 + 15) + " " + (z1 * 16 + 15);
                j.report("Generating terrain", finished[0], want.totalChunks());
                send(in, "forceload add " + span);
                // The chunk is full once "execute if loaded" passes. Check all four corners of the block.
                int[][] corners = {{x0, z0}, {x1, z1}, {x0, z1}, {x1, z0}};
                for (int[] c : corners) {
                    long deadline = System.currentTimeMillis() + 180_000;
                    while (true) {
                        int mark = log.size();
                        send(in, "execute if loaded " + (c[0] * 16 + 8) + " 64 " + (c[1] * 16 + 8));
                        int hit = waitFor(log, mark, l -> l.contains("Test passed") || l.contains("Test failed"),
                                serverProc, 15_000);
                        if (hit >= 0 && log.get(hit).contains("Test passed")) break;
                        if (!serverProc.isAlive()) throw new IOException("The server stopped:\n" + tail(log));
                        if (System.currentTimeMillis() > deadline) {
                            throw new IOException("Chunk " + c[0] + "," + c[1] + " never finished:\n" + tail(log));
                        }
                        Thread.sleep(500);
                    }
                }
                send(in, "forceload remove " + span);
                done.add(block);
            }

            Area fin = want;
            j.report("Saving", fin == null ? 0 : fin.totalChunks(), fin == null ? 0 : fin.totalChunks());
            send(in, "stop");
            if (!proc.waitFor(180, java.util.concurrent.TimeUnit.SECONDS)) {
                proc.destroyForcibly();
                throw new IOException("The server did not stop in time");
            }

            // Keep the region files that hold finished chunks. The spawn area's files are left behind.
            Path srcRegion = work.resolve("world").resolve("region");
            Files.createDirectories(cachedRegion);
            java.util.Set<String> names = new java.util.TreeSet<>();
            for (int[] r : done) {
                for (int rx = Math.floorDiv(r[0], 32); rx <= Math.floorDiv(r[2], 32); rx++) {
                    for (int rz = Math.floorDiv(r[1], 32); rz <= Math.floorDiv(r[3], 32); rz++) {
                        names.add("r." + rx + "." + rz + ".mca");
                    }
                }
            }
            for (String name : names) {
                Path src = srcRegion.resolve(name);
                if (!Files.isRegularFile(src)) continue;
                // Copy beside it and move into place, so whoever is reading the cache never sees half a file.
                Path tmp = cachedRegion.resolve(name + ".tmp");
                Files.copy(src, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                try {
                    Files.move(tmp, cachedRegion.resolve(name), java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                            java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                    Files.move(tmp, cachedRegion.resolve(name), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
            writeProgress(cacheDir, done); // after the files it describes

            if (stopped) return; // an unfinished run leaves the meta as it was; the next run carries on
            if (fin == null || done.isEmpty()) throw new IOException("The server wrote no region files for the area");
            boolean any = false;
            for (String name : names) any |= Files.isRegularFile(cachedRegion.resolve(name));
            if (!any) throw new IOException("The server wrote no region files for the area");

            Path levelDat = work.resolve("world").resolve("level.dat");
            if (Files.isRegularFile(levelDat)) {
                Files.copy(levelDat, cacheDir.resolve("level.dat"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                if (spawn == null) spawn = readSpawn(levelDat);
            }
            writeMeta(cacheDir, fin, raw.centerOnSpawn(), spawn, done);
            j.report("Done", fin.totalChunks(), fin.totalChunks());
        } finally {
            if (proc != null && proc.isAlive()) proc.destroyForcibly();
            if (active == proc) active = null;
            deleteTree(work);
        }
    }

    private static JsonObject readMeta(Path cacheDir) {
        try {
            Path meta = cacheDir.resolve("meta.json");
            if (!Files.isRegularFile(meta)) return null;
            JsonObject o = JsonParser.parseString(Files.readString(meta)).getAsJsonObject();
            return o.get("complete").getAsBoolean() ? o : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** meta.json says the area is finished. Written last, after the files it describes. */
    private static void writeMeta(Path cacheDir, Area area, boolean spawnCentered, int[] spawn, List<int[]> done)
            throws IOException {
        JsonArray regions = new JsonArray();
        long bytes = 0;
        int rx0 = Math.floorDiv(area.minChunkX(), 32), rx1 = Math.floorDiv(area.minChunkX() + area.chunksPerSide() - 1, 32);
        int rz0 = Math.floorDiv(area.minChunkZ(), 32), rz1 = Math.floorDiv(area.minChunkZ() + area.chunksPerSide() - 1, 32);
        for (int rx = rx0; rx <= rx1; rx++) {
            for (int rz = rz0; rz <= rz1; rz++) {
                String name = "r." + rx + "." + rz + ".mca";
                Path f = cacheDir.resolve("region").resolve(name);
                if (Files.isRegularFile(f)) {
                    bytes += Files.size(f);
                    regions.add(name);
                }
            }
        }
        if (regions.isEmpty()) throw new IOException("The cache has no region files for the area");
        JsonObject meta = new JsonObject();
        meta.addProperty("version", MC_VERSION);
        meta.addProperty("seed", area.seed());
        meta.addProperty("centerBlockX", area.centerBlockX());
        meta.addProperty("centerBlockZ", area.centerBlockZ());
        meta.addProperty("radiusChunks", area.radiusChunks());
        meta.addProperty("bytes", bytes);
        meta.addProperty("spawnCentered", spawnCentered);
        if (spawn != null) {
            meta.addProperty("spawnX", spawn[0]);
            meta.addProperty("spawnY", spawn[1]);
            meta.addProperty("spawnZ", spawn[2]);
        }
        meta.add("regions", regions);
        meta.addProperty("complete", true);
        Files.writeString(cacheDir.resolve("meta.json"), new Gson().toJson(meta));
    }

    /**
     * The world spawn {x, y, z} from a level.dat, or null. level.dat is gzip NBT. This only needs three int tags, so
     * it looks for them by name instead of parsing the whole file (this class has no game code to parse NBT with).
     */
    static int[] readSpawn(Path levelDat) {
        try (java.util.zip.GZIPInputStream in = new java.util.zip.GZIPInputStream(Files.newInputStream(levelDat))) {
            byte[] b = in.readAllBytes();
            Integer x = findInt(b, "SpawnX"), y = findInt(b, "SpawnY"), z = findInt(b, "SpawnZ");
            return x == null || y == null || z == null ? null : new int[] {x, y, z};
        } catch (IOException e) {
            return null;
        }
    }

    /** Value of the first int tag with this name: tag id 3, a two-byte name length, the name, then four bytes. */
    private static Integer findInt(byte[] b, String name) {
        byte[] n = name.getBytes(StandardCharsets.UTF_8);
        outer:
        for (int i = 0; i + 3 + n.length + 4 <= b.length; i++) {
            if (b[i] != 3 || b[i + 1] != 0 || b[i + 2] != n.length) continue;
            for (int k = 0; k < n.length; k++) if (b[i + 3 + k] != n[k]) continue outer;
            int o = i + 3 + n.length;
            return ((b[o] & 0xFF) << 24) | ((b[o + 1] & 0xFF) << 16) | ((b[o + 2] & 0xFF) << 8) | (b[o + 3] & 0xFF);
        }
        return null;
    }

    private static void send(Writer in, String cmd) throws IOException {
        in.write(cmd + "\n");
        in.flush();
    }

    /** Index of the first line at or after {@code from} that matches, or -1 on timeout or when the process ends. */
    private static int waitFor(List<String> log, int from, Predicate<String> match, Process p, long timeoutMs)
            throws InterruptedException {
        long end = System.currentTimeMillis() + timeoutMs;
        int next = from;
        while (System.currentTimeMillis() < end) {
            while (next < log.size()) {
                if (match.test(log.get(next))) return next;
                next++;
            }
            if (!p.isAlive() && next >= log.size()) return -1;
            Thread.sleep(100);
        }
        return -1;
    }

    private static String tail(List<String> log) {
        int from = Math.max(0, log.size() - 20);
        return String.join("\n", log.subList(from, log.size()));
    }

    private static void deleteTree(Path dir) {
        try (Stream<Path> s = Files.walk(dir)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignored) {
        }
    }

    /** Command line use: SeedPregen seed centerBlockX centerBlockZ radiusChunks. Paths come from pentecost.home. */
    public static void main(String[] args) throws Exception {
        long seed = Long.parseLong(args[0]);
        // SeedPregen <seed> spawn <radius>   or   SeedPregen <seed> <blockX> <blockZ> <radius>
        Area area = args[1].equalsIgnoreCase("spawn")
                ? new Area(seed, Area.SPAWN, Area.SPAWN, Integer.parseInt(args[2]))
                : new Area(seed, Integer.parseInt(args[1]), Integer.parseInt(args[2]), Integer.parseInt(args[3]));
        Path home = Path.of(System.getProperty("pentecost.home", "."));
        Path cache = Path.of("config", "pentecost", "menu_world", Long.toString(seed)).toAbsolutePath();
        if (isComplete(cache, area)) {
            System.out.println("Already generated: " + cache);
            return;
        }
        long t0 = System.currentTimeMillis();
        int[] last = {-1};
        run(home, cache, area, p -> {
            int pct = p.chunksTotal() == 0 ? 0 : p.chunksDone() * 100 / p.chunksTotal();
            if (pct != last[0] || p.stage().equals("Done")) {
                last[0] = pct;
                System.out.println(p.stage() + " " + p.chunksDone() + "/" + p.chunksTotal() + " (" + pct + "%)");
            }
        });
        System.out.println("Finished in " + (System.currentTimeMillis() - t0) / 1000 + " s, cache at " + cache);
    }
}
