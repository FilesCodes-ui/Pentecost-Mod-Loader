package khi.pentecost.game.menuworld.source;

import java.nio.file.Path;

public record WorldSource(Kind kind, long seed, Path save) {

    public enum Kind { SEED, SAVE }

    public static WorldSource ofSeed(long seed) {
        return new WorldSource(Kind.SEED, seed, null);
    }

    public static WorldSource ofSave(Path saveFolder) {
        return new WorldSource(Kind.SAVE, 0, saveFolder);
    }

    public Path cacheDir(Path pentecostConfig) {
        Path root = pentecostConfig.resolve("menu_world");
        return kind == Kind.SEED ? root.resolve(Long.toString(seed)) : save;
    }

    public boolean needsPregen() {
        return kind == Kind.SEED;
    }
}
