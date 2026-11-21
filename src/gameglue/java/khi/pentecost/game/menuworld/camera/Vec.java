package khi.pentecost.game.menuworld.camera;

public record Vec(double x, double y, double z) {
    public static final Vec ZERO = new Vec(0, 0, 0);

    public Vec add(Vec o) {
        return new Vec(x + o.x, y + o.y, z + o.z);
    }

    public Vec add(double dx, double dy, double dz) {
        return new Vec(x + dx, y + dy, z + dz);
    }

    public Vec sub(Vec o) {
        return new Vec(x - o.x, y - o.y, z - o.z);
    }

    public Vec scale(double k) {
        return new Vec(x * k, y * k, z * k);
    }

    public Vec withY(double ny) {
        return new Vec(x, ny, z);
    }

    public double dot(Vec o) {
        return x * o.x + y * o.y + z * o.z;
    }

    public double length() {
        return Math.sqrt(dot(this));
    }

    public double horizontal() {
        return Math.sqrt(x * x + z * z);
    }

    public double dist(Vec o) {
        return sub(o).length();
    }

    public Vec normalized() {
        double l = length();
        return l < 1e-9 ? ZERO : scale(1.0 / l);
    }

    public static Vec lerp(Vec a, Vec b, double t) {
        return new Vec(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t, a.z + (b.z - a.z) * t);
    }
}
