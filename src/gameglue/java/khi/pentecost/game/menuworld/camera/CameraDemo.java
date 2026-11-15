package khi.pentecost.game.menuworld.camera;

import khi.pentecost.game.menuworld.source.Terrain;

/**
 * Developer check, not used by the game. Plays shots over a made-up landscape and prints how each one behaved:
 * the lowest the camera got above the ground, and the fastest it turned. Run the class's main method.
 */
public final class CameraDemo {
    private CameraDemo() {}

    /** Rolling hills with a lake basin. */
    private static final class Hills implements Terrain {
        @Override
        public int heightAt(int x, int z) {
            double h = 80 + 30 * Math.sin(x * 0.01) + 22 * Math.cos(z * 0.013) + 9 * Math.sin((x + z) * 0.04);
            return (int) h;
        }

        @Override
        public boolean waterAt(int x, int z) {
            return heightAt(x, z) < seaLevel();
        }

        @Override
        public int colorAt(int x, int z) {
            return waterAt(x, z) ? 0x3A6EA5 : 0x6FA84F;
        }
    }

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 4556775L;
        Terrain terrain = new Hills();
        ShotDirector director = new ShotDirector(seed, terrain, 0, 0, 600);
        CameraRig rig = new CameraRig();
        double dt = 1.0 / 20.0;
        double tod = 600;
        System.out.printf("%-9s %-12s %6s %9s %10s %9s%n", "shot", "move", "secs", "minAbove", "maxTurn/s", "fovRange");
        for (int i = 0; i < 40; i++) {
            Shot shot = director.next(tod);
            rig.start(shot);
            double minAbove = Double.MAX_VALUE;
            double maxTurn = 0;
            double fovLo = 999;
            double fovHi = 0;
            Pose prev = null;
            while (!rig.finished()) {
                Pose p = rig.advance(dt, terrain);
                double ground = Math.max(terrain.heightAt((int) p.pos().x(), (int) p.pos().z()), terrain.seaLevel());
                minAbove = Math.min(minAbove, p.pos().y() - ground);
                fovLo = Math.min(fovLo, p.fov());
                fovHi = Math.max(fovHi, p.fov());
                if (prev != null) {
                    double dyaw = Math.abs(((p.yaw() - prev.yaw() + 540) % 360) - 180);
                    double dpitch = Math.abs(p.pitch() - prev.pitch());
                    maxTurn = Math.max(maxTurn, Math.hypot(dyaw, dpitch) / dt);
                }
                prev = p;
                tod = (tod + shot.seconds() / (shot.seconds() / dt) * 50) % 24000;
            }
            System.out.printf("%-9s %-12s %6.1f %9.1f %10.1f %4.0f-%3.0f%n", shot.id(), shot.type(), shot.seconds(),
                    minAbove, maxTurn, fovLo, fovHi);
        }
    }
}
