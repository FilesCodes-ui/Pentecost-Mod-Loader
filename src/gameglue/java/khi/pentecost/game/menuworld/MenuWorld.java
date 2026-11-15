package khi.pentecost.game.menuworld;

import java.util.List;

/**
 * Entry point for the live menu world. Not wired into the title screen yet: the title screen still draws the
 * panorama. This class says what exists and what does not, so nothing pretends to work.
 * See docs/MENU_WORLD.md for the design and milestones.
 */
public final class MenuWorld {
    private MenuWorld() {}

    public record Stage(int number, String name, boolean done) {}

    public static final List<Stage> STAGES = List.of(
            new Stage(1, "Seeds, clock, camera director and rig, LOD sampling (skeleton, headless)", true),
            new Stage(2, "Pregeneration worker in a mod-free JVM, cache layout (command line only; settings progress UI comes with stage 8)", true),
            new Stage(3, "Fake ClientLevel fed from the cache (level and chunk feeding checked headless)", true),
            new Stage(4, "Camera rig drives the game camera, clock drives the sky and shaders (written, not yet seen in game)", false),
            new Stage(5, "Load and drop chunks by shot (written, not yet seen in game)", false),
            new Stage(6, "Ambient life", false),
            new Stage(7, "LOD rendering", false),
            new Stage(8, "Own saves, seeds, settings page (written, not yet seen in game; no typed seeds or coordinates yet)", false));

    /** True once the world can be drawn. Until then the title screen keeps the panorama. */
    public static boolean ready() {
        return MenuHost.showing();
    }
}
