package khi.pentecost.game.menuworld.camera;

public record Pose(Vec pos, double yaw, double pitch, double roll, double fov) {
    public static Pose looking(Vec pos, Vec target, double roll, double fov) {
        Vec d = target.sub(pos);
        double yaw = Math.toDegrees(Math.atan2(-d.x(), d.z()));
        double pitch = -Math.toDegrees(Math.atan2(d.y(), d.horizontal()));
        return new Pose(pos, yaw, pitch, roll, fov);
    }
}
