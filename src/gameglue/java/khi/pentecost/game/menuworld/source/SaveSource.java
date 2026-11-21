package khi.pentecost.game.menuworld.source;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class SaveSource {
    private SaveSource() {}

    static final int OLDEST_DATA_VERSION = 2825;
    static final int NEWEST_DATA_VERSION = 3465;

    public record Info(Path folder, String name, boolean usable, String problem, int dataVersion, int[] spawn,
                       double[] lastPos, long lastPlayed) {}

    public static Info inspect(Path folder) {
        String fallbackName = folder.getFileName() == null ? folder.toString() : folder.getFileName().toString();
        Path levelDat = folder.resolve("level.dat");
        if (!Files.isDirectory(folder)) return bad(folder, fallbackName, "That folder does not exist.");
        if (!Files.isRegularFile(levelDat)) return bad(folder, fallbackName, "This is not a Minecraft world (no level.dat).");
        CompoundTag data;
        try {
            data = NbtIo.readCompressed(levelDat.toFile()).getCompound("Data");
        } catch (IOException | RuntimeException e) {
            return bad(folder, fallbackName, "level.dat could not be read.");
        }
        String name = data.getString("LevelName");
        if (name.isEmpty()) name = fallbackName;
        int version = data.getInt("DataVersion");
        int[] spawn = data.contains("SpawnX") ? new int[] {data.getInt("SpawnX"), data.getInt("SpawnY"), data.getInt("SpawnZ")} : null;
        double[] last = null;
        CompoundTag player = data.getCompound("Player");
        ListTag pos = player.getList("Pos", Tag.TAG_DOUBLE);
        String dimension = player.contains("Dimension", Tag.TAG_STRING) ? player.getString("Dimension") : "minecraft:overworld";
        if (pos.size() == 3 && dimension.equals("minecraft:overworld")) {
            last = new double[] {pos.getDouble(0), pos.getDouble(1), pos.getDouble(2)};
        }
        long lastPlayed = data.getLong("LastPlayed");

        if (version != 0 && version < OLDEST_DATA_VERSION) {
            return new Info(folder, name, false, "Made before Minecraft 1.18. Open it once in 1.20.1 to update it.",
                    version, spawn, last, lastPlayed);
        }
        if (version > NEWEST_DATA_VERSION) {
            return new Info(folder, name, false, "Made in a newer Minecraft than 1.20.1.", version, spawn, last, lastPlayed);
        }
        if (new RegionReader(folder.resolve("region")).chunkBounds() == null) {
            return new Info(folder, name, false, "This world has no saved chunks yet.", version, spawn, last, lastPlayed);
        }
        if (spawn == null) {
            return new Info(folder, name, false, "The world spawn is missing from level.dat.", version, null, last, lastPlayed);
        }
        return new Info(folder, name, true, null, version, spawn, last, lastPlayed);
    }

    private static Info bad(Path folder, String name, String problem) {
        return new Info(folder, name, false, problem, 0, null, null, 0);
    }

    public static List<Info> list(Path savesDir) {
        List<Info> out = new ArrayList<>();
        if (!Files.isDirectory(savesDir)) return out;
        try (DirectoryStream<Path> dirs = Files.newDirectoryStream(savesDir, Files::isDirectory)) {
            for (Path d : dirs) {
                if (Files.isRegularFile(d.resolve("level.dat")) || Files.isDirectory(d.resolve("region"))) out.add(inspect(d));
            }
        } catch (IOException e) {
        }
        out.sort(Comparator.comparingLong(Info::lastPlayed).reversed().thenComparing(i -> i.name().toLowerCase()));
        return out;
    }

    public static int[] scanBox(Path folder, boolean whole, int centerBlockX, int centerBlockZ, int radiusChunks) {
        int cx = Math.floorDiv(centerBlockX, 16), cz = Math.floorDiv(centerBlockZ, 16);
        int[] around = {cx - radiusChunks, cz - radiusChunks, cx + radiusChunks - 1, cz + radiusChunks - 1};
        if (!whole) return around;
        int[] all = new RegionReader(folder.resolve("region")).chunkBounds();
        return all == null ? around : all;
    }
}
