package khi.pentecost.game.menuworld;

import khi.pentecost.boot.Dirs;
import khi.pentecost.boot.Log;
import khi.pentecost.game.menuworld.source.ChunkData;
import khi.pentecost.game.menuworld.source.RegionReader;
import khi.pentecost.menuworld.SeedPregen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.Registry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.ticks.ProtoChunkTicks;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

final class MenuFarLod {
    private static final Pattern REGION = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");
    private static final int CORES = Math.max(2, Runtime.getRuntime().availableProcessors() / 4);
    private static final int QUEUE_MAX = 192;

    private static volatile Thread thread;
    private static volatile boolean stop;

    private MenuFarLod() {}

    static int radius() {
        try {
            String s = khi.pentecost.control.Settings.get("menu.lodRadius");
            if (s != null) return Math.max(0, Math.min(512, Integer.parseInt(s.trim())));
        } catch (RuntimeException ignored) {
        }
        return 128;
    }

    static synchronized void start(ClientLevel level, Registry<Biome> biomes, long seed, Path nearRegion, Path farDir,
                                   Path mark, int centerX, int centerZ, boolean generate) {
        if (thread != null || !MenuDh.active()) return;
        stop = false;
        int r = farDir == null ? 0 : radius();
        Thread t = new Thread(() -> {
            try {
                run(level, biomes, seed, nearRegion, farDir, mark, centerX, centerZ, r, generate);
            } catch (InterruptedException e) {
            } catch (Throwable e) {
                Log.warn("Menu world: far LOD stopped: " + e);
            } finally {
                synchronized (MenuFarLod.class) {
                    if (thread == Thread.currentThread()) thread = null;
                }
            }
        }, "Pentecost menu LOD");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        thread = t;
        t.start();
    }

    static synchronized void stop() {
        stop = true;
        Thread t = thread;
        if (t != null) t.interrupt();
    }

    private static boolean going() {
        return !stop && MenuDh.active();
    }

    private static void run(ClientLevel level, Registry<Biome> biomes, long seed, Path nearRegion, Path farDir,
                            Path mark, int cx, int cz, int r, boolean generate) throws InterruptedException, IOException {
        for (int i = 0; i < 600 && !MenuDh.levelReady(level); i++) {
            if (!going()) return;
            Thread.sleep(100);
        }
        if (!MenuDh.levelReady(level)) {
            Log.warn("Menu world: Distant Horizons never loaded its level, so no far LOD");
            return;
        }

        String stamp = String.valueOf(Files.isDirectory(nearRegion) ? Files.getLastModifiedTime(nearRegion).toMillis() : 0);
        boolean fedBefore = Files.isRegularFile(mark) && Files.readString(mark).trim().equals(stamp);
        if (!fedBefore) {
            int n = feedFolder(level, biomes, nearRegion, null, null, false);
            if (!going()) return;
            Files.createDirectories(mark.getParent());
            Files.writeString(mark, stamp);
            Log.info("Menu world: " + n + " chunks of the menu world went to Distant Horizons");
        }

        if (r <= 0) return;
        SeedPregen.Area area = new SeedPregen.Area(seed, cx, cz, r);
        int[] box = {area.minChunkX(), area.minChunkZ(), area.minChunkX() + area.chunksPerSide() - 1,
                area.minChunkZ() + area.chunksPerSide() - 1};
        Thread gen = null;
        if (generate && !SeedPregen.isComplete(farDir, area)) {
            gen = new Thread(() -> {
                try {
                    SeedPregen.runFar(Dirs.home(), farDir, area, CORES);
                } catch (InterruptedException e) {
                } catch (Throwable e) {
                    Log.warn("Menu world: generating far land stopped: " + e.getMessage());
                }
            }, "Pentecost menu LOD generation");
            gen.setDaemon(true);
            gen.setPriority(Thread.MIN_PRIORITY);
            gen.start();
            Log.info("Menu world: generating land out to " + r + " chunks for Distant Horizons");
        }

        int total = 0;
        while (going()) {
            total += feedFolder(level, biomes, farDir.resolve("region"), SeedPregen.finished(farDir), box, true);
            if (gen == null || !gen.isAlive()) {
                total += feedFolder(level, biomes, farDir.resolve("region"), SeedPregen.finished(farDir), box, true);
                break;
            }
            Thread.sleep(5_000);
        }
        if (!going() && gen != null && gen.isAlive()) SeedPregen.stopRunning();
        if (total > 0) Log.info("Menu world: " + total + " far chunks went to Distant Horizons");
    }

    private static int feedFolder(ClientLevel level, Registry<Biome> biomes, Path regionDir, List<int[]> done,
                                  int[] area, boolean delete) throws InterruptedException, IOException {
        if (!Files.isDirectory(regionDir)) return 0;
        List<Path> files = new ArrayList<>();
        try (Stream<Path> s = Files.list(regionDir)) {
            s.filter(p -> REGION.matcher(p.getFileName().toString()).matches()).forEach(files::add);
        }
        RegionReader reader = new RegionReader(regionDir);
        int n = 0;
        for (Path f : files) {
            if (!going()) break;
            Matcher m = REGION.matcher(f.getFileName().toString());
            if (!m.matches()) continue;
            int rx = Integer.parseInt(m.group(1)), rz = Integer.parseInt(m.group(2));
            if (done != null && !regionFinished(done, rx, rz, area)) continue;
            boolean all = true;
            for (int x = rx * 32; x < rx * 32 + 32 && going(); x++) {
                for (int z = rz * 32; z < rz * 32 + 32 && going(); z++) {
                    while (going() && MenuDh.queued() > QUEUE_MAX) Thread.sleep(25);
                    try {
                        CompoundTag tag = reader.readChunk(x, z);
                        if (tag == null) continue;
                        ChunkData d = ChunkData.build(tag, biomes, -4, 24);
                        if (d == null) continue;
                        MenuDh.chunk(level, proto(level, biomes, d));
                        n++;
                    } catch (IOException | RuntimeException e) {
                    }
                }
            }
            if (!going()) all = false;
            if (delete && all) {
                try {
                    Files.deleteIfExists(f);
                } catch (IOException e) {
                }
            }
        }
        return n;
    }

    private static boolean regionFinished(List<int[]> done, int rx, int rz, int[] area) {
        for (int x = Math.max(rx * 32, area[0]); x <= Math.min(rx * 32 + 31, area[2]); x++) {
            for (int z = Math.max(rz * 32, area[1]); z <= Math.min(rz * 32 + 31, area[3]); z++) {
                boolean in = false;
                for (int[] d : done) {
                    if (x >= d[0] && x <= d[2] && z >= d[1] && z <= d[3]) {
                        in = true;
                        break;
                    }
                }
                if (!in) return false;
            }
        }
        return true;
    }

    private static ProtoChunk proto(ClientLevel level, Registry<Biome> biomes, ChunkData d) {
        ProtoChunk c = new ProtoChunk(new ChunkPos(d.x, d.z), UpgradeData.EMPTY, d.sections, new ProtoChunkTicks<>(),
                new ProtoChunkTicks<>(), level, biomes, null);
        c.setStatus(ChunkStatus.FULL);
        Heightmap.primeHeightmaps(c, EnumSet.of(Heightmap.Types.WORLD_SURFACE, Heightmap.Types.OCEAN_FLOOR,
                Heightmap.Types.MOTION_BLOCKING, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES));
        return c;
    }
}
