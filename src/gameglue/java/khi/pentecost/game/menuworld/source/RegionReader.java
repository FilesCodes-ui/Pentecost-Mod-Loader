package khi.pentecost.game.menuworld.source;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/** Reads chunks out of Anvil region files (r.X.Z.mca). Read only. Safe to call from a worker thread. */
public final class RegionReader implements ChunkSource {
    private static final Pattern REGION_FILE = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");

    private final Path regionDir;

    public RegionReader(Path regionDir) {
        this.regionDir = regionDir;
    }

    /** The chunk's NBT, or null when the chunk is not in the file (or is stored in an external .mcc file). */
    public CompoundTag readChunk(int chunkX, int chunkZ) throws IOException {
        Path file = regionDir.resolve("r." + Math.floorDiv(chunkX, 32) + "." + Math.floorDiv(chunkZ, 32) + ".mca");
        if (!Files.isRegularFile(file)) return null;
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            int slot = (chunkX & 31) + (chunkZ & 31) * 32;
            raf.seek(slot * 4L);
            return readAt(raf, raf.readInt() >>> 8);
        }
    }

    /** Opens each region file once, which is far faster than {@link #readChunk} for scanning many chunks. */
    @Override
    public void forEach(int minCx, int minCz, int maxCx, int maxCz, Visitor visitor) throws IOException {
        for (int rx = Math.floorDiv(minCx, 32); rx <= Math.floorDiv(maxCx, 32); rx++) {
            for (int rz = Math.floorDiv(minCz, 32); rz <= Math.floorDiv(maxCz, 32); rz++) {
                Path file = regionDir.resolve("r." + rx + "." + rz + ".mca");
                if (!Files.isRegularFile(file)) continue;
                try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
                    byte[] header = new byte[4096];
                    if (raf.length() < 8192) continue;
                    raf.readFully(header);
                    ByteBuffer table = ByteBuffer.wrap(header);
                    for (int slot = 0; slot < 1024; slot++) {
                        int offset = table.getInt(slot * 4) >>> 8;
                        if (offset == 0) continue;
                        int cx = rx * 32 + (slot & 31);
                        int cz = rz * 32 + (slot >> 5);
                        if (cx < minCx || cx > maxCx || cz < minCz || cz > maxCz) continue;
                        CompoundTag tag;
                        try {
                            tag = readAt(raf, offset);
                        } catch (IOException | RuntimeException damaged) {
                            continue; // one damaged chunk must not stop a scan
                        }
                        if (tag != null) visitor.chunk(cx, cz, tag);
                    }
                }
            }
        }
    }

    /** The box of chunks that region files exist for, or null. Whole regions, so it can be a little larger than the world. */
    @Override
    public int[] chunkBounds() {
        if (!Files.isDirectory(regionDir)) return null;
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(regionDir, "r.*.*.mca")) {
            for (Path f : files) {
                Matcher m = REGION_FILE.matcher(f.getFileName().toString());
                if (!m.matches() || isEmpty(f)) continue;
                int rx = Integer.parseInt(m.group(1)), rz = Integer.parseInt(m.group(2));
                minX = Math.min(minX, rx * 32);
                minZ = Math.min(minZ, rz * 32);
                maxX = Math.max(maxX, rx * 32 + 31);
                maxZ = Math.max(maxZ, rz * 32 + 31);
            }
        } catch (IOException e) {
            return null;
        }
        return minX == Integer.MAX_VALUE ? null : new int[] {minX, minZ, maxX, maxZ};
    }

    /** A region file with no chunk table is a leftover, not a part of the world. */
    private static boolean isEmpty(Path f) {
        try {
            return Files.size(f) < 8192;
        } catch (IOException e) {
            return true;
        }
    }

    private static CompoundTag readAt(RandomAccessFile raf, int offset) throws IOException {
        if (offset == 0) return null;
        raf.seek(offset * 4096L);
        int length = raf.readInt();
        int compression = raf.readByte();
        if (length <= 1 || compression > 3) return null;
        byte[] data = new byte[length - 1];
        raf.readFully(data);
        InputStream raw = new ByteArrayInputStream(data);
        InputStream in = switch (compression) {
            case 1 -> new GZIPInputStream(raw);
            case 2 -> new InflaterInputStream(raw);
            case 3 -> raw;
            default -> null;
        };
        if (in == null) return null;
        try (DataInputStream dis = new DataInputStream(new BufferedInputStream(in))) {
            return NbtIo.read(dis);
        }
    }
}
