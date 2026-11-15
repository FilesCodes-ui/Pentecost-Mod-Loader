package khi.pentecost.game.menuworld.source;

import java.nio.file.Path;

/**
 * Where the menu world's land comes from: a seed, generated once into a cache by a mod-free worker, or one of
 * the player's own saves, read as it is. Both end up as region files the chunk feeder can read.
 */
public record WorldSource(Kind kind, long seed, Path save) {

    public enum Kind { SEED, SAVE }

    public static WorldSource ofSeed(long seed) {
        return new WorldSource(Kind.SEED, seed, null);
    }

    public static WorldSource ofSave(Path saveFolder) {
        return new WorldSource(Kind.SAVE, 0, saveFolder);
    }

    /** Folder the region files and LOD tiles for this source live in, under Pentecost's config folder. */
    public Path cacheDir(Path pentecostConfig) {
        Path root = pentecostConfig.resolve("menu_world");
        return kind == Kind.SEED ? root.resolve(Long.toString(seed)) : save;
    }

    /** A seed must be generated before it can be shown. A save is already there. */
    public boolean needsPregen() {
        return kind == Kind.SEED;
    }
}
