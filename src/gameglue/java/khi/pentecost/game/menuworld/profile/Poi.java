package khi.pentecost.game.menuworld.profile;

import java.util.ArrayList;
import java.util.List;

public final class Poi {
    public enum Kind { STRUCTURE, BUILD, CUSTOM }

    public String id = "";
    public String name = "";
    public Kind kind = Kind.CUSTOM;
    public String structureId = "";
    public int x, y, z;
    public int sizeX = 32, sizeY = 32, sizeZ = 32;
    public boolean enabled = true;
    public double weight = 1.0;
    public List<String> moves = new ArrayList<>();
    public double playerMinutes = 0;
    public int manMadeBlocks = 0;
    public boolean edited = false;

    public Poi() {}

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
