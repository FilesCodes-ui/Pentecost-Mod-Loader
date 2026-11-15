package khi.pentecost.game.menuworld.profile;

import java.util.ArrayList;
import java.util.List;

/**
 * One place the camera can visit. Found by the scanner (a vanilla structure or a cluster of player-made blocks) or
 * added by hand in the editor. Plain public fields so the profile file is easy to read and edit.
 */
public final class Poi {
    public enum Kind { STRUCTURE, BUILD, CUSTOM }

    public String id = "";
    public String name = "";
    public Kind kind = Kind.CUSTOM;
    /** The vanilla structure id for STRUCTURE points, such as minecraft:village_plains. */
    public String structureId = "";
    /** Centre, in blocks. */
    public int x, y, z;
    /** Extent in blocks. The camera frames the whole box. */
    public int sizeX = 32, sizeY = 32, sizeZ = 32;
    public boolean enabled = true;
    /** How often the camera picks this place relative to others. 0 never, 1 normal. */
    public double weight = 1.0;
    /** Camera moves allowed here (orbit, dolly, crane, push-in, fly-through, reveal, establishing). Empty means any. */
    public List<String> moves = new ArrayList<>();
    /**
     * How much a player was around here, in minutes of chunk time (the world's own InhabitedTime counter), and how
     * many player-style blocks (bricks, glass, lanterns ...) the scanner counted. Both are zero in a fresh seed.
     */
    public double playerMinutes = 0;
    public int manMadeBlocks = 0;
    /** True once the player changed this point, so a rescan never overwrites their edits. */
    public boolean edited = false;

    public Poi() {}

    /** Same id for the same kind and 16-block cell, so a rescan finds the same point again. */
    public static String makeId(Kind kind, int x, int z) {
        return kind.name().toLowerCase() + "@" + Math.floorDiv(x, 16) + "," + Math.floorDiv(z, 16);
    }

    public static Poi of(Kind kind, String name, int x, int y, int z, int sx, int sy, int sz) {
        Poi p = new Poi();
        p.kind = kind;
        p.name = name;
        p.x = x;
        p.y = y;
        p.z = z;
        p.sizeX = Math.max(8, sx);
        p.sizeY = Math.max(8, sy);
        p.sizeZ = Math.max(8, sz);
        p.id = makeId(kind, x, z);
        return p;
    }
}
