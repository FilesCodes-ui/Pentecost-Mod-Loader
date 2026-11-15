package khi.pentecost.game.menuworld;

import khi.pentecost.boot.Dirs;
import khi.pentecost.boot.Log;
import khi.pentecost.game.menuworld.camera.Pose;
import khi.pentecost.game.menuworld.camera.ProfileDirector;
import khi.pentecost.game.menuworld.camera.ShotSequencer;
import khi.pentecost.game.menuworld.camera.Vec;
import khi.pentecost.game.menuworld.mobs.AmbientLife;
import khi.pentecost.game.menuworld.profile.MenuProfile;
import khi.pentecost.game.menuworld.profile.ProfileStore;
import khi.pentecost.game.menuworld.source.ChunkData;
import khi.pentecost.game.menuworld.source.RegionReader;
import khi.pentecost.game.menuworld.source.WorldSource;
import khi.pentecost.menuworld.SeedBundle;
import khi.pentecost.menuworld.SeedPregen;
import net.minecraft.client.CameraType;
import net.minecraft.client.ClientRecipeBook;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.stats.StatsCounter;
import net.minecraft.util.Mth;
import net.minecraft.world.level.biome.Biome;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Runs the live menu world behind the main menu. The title screen calls {@link #attach}, {@link #frame} and
 * {@link #detach}; the settings screen reads the status and calls the change methods. Everything except the
 * preparation and the chunk reading happens on the render thread.
 *
 * Safety: nothing is shown unless the player turned it on. If anything throws, the menu world turns itself off and
 * the panorama comes back. If the game stops hard while it is showing, a marker file turns it off at the next start.
 *
 * Status: written against the verified 1.20.1 names and compiled; not yet seen in the game.
 */
public final class MenuHost {
    private MenuHost() {}

    public enum State { OFF, PREPARING, NEEDS_EULA, FAILED, READY }

    private record Outcome(int generation, MenuPrep.Ready ready, Throwable error) {}

    private record Result(int x, int z, ChunkData data, boolean skipped) {}

    private record Saved(CameraType camera, int fov, boolean hideGui) {}

    private static final class Live {
        final MenuPrep.Ready ready;
        final MenuLevel menu;
        final LocalPlayer player;
        final MultiPlayerGameMode mode;
        final RegionReader reader;
        final Registry<Biome> biomes;
        final int view;
        final ExecutorService worker;
        final ConcurrentLinkedQueue<Result> results = new ConcurrentLinkedQueue<>();
        final Set<Long> loaded = new HashSet<>();
        final Set<Long> inflight = new HashSet<>();
        final Set<Long> missing = new HashSet<>();
        volatile int cx = Integer.MIN_VALUE, cz = Integer.MIN_VALUE;
        volatile boolean closed;
        boolean restream;
        MenuClock clock;
        ShotSequencer sequencer;
        AmbientLife.Crowd crowd;
        double visible, waited, fade;
        boolean announced, warned;

        Live(MenuPrep.Ready ready, MenuLevel menu, LocalPlayer player, MultiPlayerGameMode mode, int view) {
            this.ready = ready;
            this.menu = menu;
            this.player = player;
            this.mode = mode;
            this.view = view;
            this.reader = new RegionReader(ready.regionDir());
            this.biomes = ready.access().registryOrThrow(Registries.BIOME);
            java.util.concurrent.atomic.AtomicInteger count = new java.util.concurrent.atomic.AtomicInteger();
            this.worker = Executors.newFixedThreadPool(READERS, r -> {
                Thread t = new Thread(r, "Pentecost menu chunks " + count.incrementAndGet());
                t.setDaemon(true);
                t.setPriority(Thread.NORM_PRIORITY - 1);
                return t;
            });
        }

        void close() {
            closed = true;
            if (sequencer != null) sequencer.close();
            worker.shutdownNow();
        }
    }

    private static volatile boolean readErrorLogged;
    private static final Object PREP_LOCK = new Object();
    /** A new world is being made. The world on screen, if any, stays until the new one is ready. */
    private static volatile boolean busy;
    /** Threads that read and build chunks. The render thread only hands finished chunks to the level. */
    private static final int READERS = Math.max(2, Math.min(3, Runtime.getRuntime().availableProcessors() / 4));
    private static final int FEED_MAX = 12;
    private static final long FEED_BUDGET_NANOS = 5_000_000L;

    private static MenuProfile profile;
    private static volatile State state = State.OFF;
    private static volatile String message = "";
    private static volatile MenuPrep.Progress progress;
    private static volatile int generation;
    private static volatile Outcome outcome;
    private static Live live;
    private static boolean wanted, attached, rebuildDirector, sessionOff;
    private static Saved saved;
    private static int frames;
    private static long lastNanos;

    // ------------------------------------------------------------------ profile and status

    private static Path configDir() {
        return Dirs.config().resolve("pentecost");
    }

    private static Path profileFile() {
        return ProfileStore.file(configDir());
    }

    private static Path marker() {
        return configDir().resolve("menu_world").resolve("showing.marker");
    }

    public static synchronized MenuProfile profile() {
        if (profile == null) {
            boolean existed = Files.isRegularFile(profileFile());
            profile = ProfileStore.load(profileFile());
            if (!existed) {
                SeedCatalog.Entry e = SeedCatalog.defaultEntry();
                profile.seed = e.seed();
                profile.centerMode = MenuProfile.CenterMode.COORDS;
                profile.centerX = e.anchorX();
                profile.centerZ = e.anchorZ();
            }
            try {
                if (Files.deleteIfExists(marker()) && profile.enabled) {
                    profile.enabled = false;
                    message = "Turned off: the game stopped while the live menu world was showing.";
                    save();
                }
            } catch (IOException e) {
                // a marker that cannot be read or deleted changes nothing
            }
        }
        return profile;
    }

    public static synchronized void save() {
        if (profile == null) return;
        try {
            ProfileStore.save(profileFile(), profile);
        } catch (IOException e) {
            Log.warn("Could not save the menu world settings: " + e.getMessage());
        }
    }

    public static State state() {
        return state;
    }

    /** One plain line for the settings screen. */
    public static String status() {
        MenuProfile p = profile();
        if (!p.enabled) return message.isEmpty() ? "Off" : message;
        if (busy) {
            MenuPrep.Progress g = progress;
            String what = g == null ? "Getting ready" : g.total() > 0 ? g.text() + ": " + g.done() + " of " + g.total() : g.text();
            return state == State.READY ? what + " (the current world stays until it is done)" : what;
        }
        return switch (state) {
            case OFF -> "Starts when the main menu opens";
            case PREPARING -> "Getting ready";
            case NEEDS_EULA, FAILED -> message;
            case READY -> message.isEmpty() ? "Ready" : message;
        };
    }

    public static boolean needsEula() {
        return state == State.NEEDS_EULA;
    }

    /** True while the world is the thing the title screen shows. */
    public static boolean showing() {
        return attached && live != null && state == State.READY;
    }

    /** 0 to 1, how much black to draw over the world: loading, and the fade at each cut. */
    public static float cover() {
        Live l = live;
        if (!showing() || l == null) return 0F;
        return (float) Mth.clamp(1.0 - l.visible * l.fade, 0.0, 1.0);
    }

    // ------------------------------------------------------------------ changes from the settings screen

    public static boolean enabled() {
        return profile().enabled;
    }

    public static void setEnabled(boolean on) {
        MenuProfile p = profile();
        p.enabled = on;
        message = "";
        sessionOff = false;
        save();
        dropLive(Minecraft.getInstance());
        state = State.OFF;
        if (on) startPrep(false);
    }

    /** The world, the centre, the radius or the view distance changed: read and scan again. */
    public static void worldChanged() {
        save();
        if (profile().enabled) {
            startPrep(false);
        } else {
            dropLive(Minecraft.getInstance());
            state = State.OFF;
        }
    }

    /** Look for places again, even though the world did not change. */
    public static void rescan() {
        MenuProfile p = profile();
        p.scanKey = "";
        save();
        if (p.enabled) startPrep(true);
    }

    /** Places, camera or time settings changed: the world stays, the director starts over. */
    public static void directorChanged() {
        save();
        rebuildDirector = true;
    }

    /** The block the shown area is centred on, {x, z}. */
    public static int[] centre() {
        Live l = live;
        if (l != null) return new int[] {l.ready.centerX(), l.ready.centerZ()};
        MenuProfile p = profile();
        return new int[] {p.centerX, p.centerZ};
    }

    /** Ground height at a column of the shown area, or a middling guess when nothing is loaded. */
    public static int surfaceY(int x, int z) {
        Live l = live;
        if (l == null) return 70;
        int y = l.ready.terrain().heightAt(x, z);
        return y > -60 ? y : 70;
    }

    // ------------------------------------------------------------------ the title screen's side

    public static void attach(Minecraft mc) {
        wanted = false;
        MenuProfile p = profile();
        if (!p.enabled || sessionOff) return;
        wanted = true;
        if (live == null && state == State.OFF) startPrep(false);
    }

    public static void detach(Minecraft mc) {
        wanted = false;
        unhook(mc);
    }

    /**
     * Called every frame by the game loop. While a real world is open the menu world has nothing to do: its level,
     * chunk readers, planner thread and a running world generation would only cost memory and processor time that the
     * game needs. It is let go here, and built again the next time the main menu shows.
     */
    public static void release(Minecraft mc) {
        if (mc.level == null) return;
        Live l = live;
        if (l != null && mc.level == l.menu.level) return; // the menu world itself is showing
        if (live != null || busy) {
            wanted = false;
            generation++; // a preparation still running is for a screen that is gone
            busy = false;
            outcome = null;
            if (!warming) SeedPregen.stopRunning(); // a full-speed run stops and keeps what it finished; the gentle background run keeps going
            dropLive(mc);
            if (state != State.FAILED) state = State.OFF;
            Log.info("Menu world: let go while a world is open");
        }
        warmUp();
    }

    // ------------------------------------------------------------------ generating while a world is open

    /** True while the seed is being generated in the background because a real world is open. */
    private static volatile boolean warming;
    private static volatile String warmKey = "";
    /** The area last looked at this session, so the check runs once and not every frame. Game thread only. */
    private static String warmTried = "";
    /** Processors the background server may use: the game is running and needs the rest. */
    private static final int WARM_CORES = Math.max(2, Runtime.getRuntime().availableProcessors() / 4);

    private static SeedPregen.Area areaOf(MenuProfile p) {
        boolean spawn = p.centerMode == MenuProfile.CenterMode.SPAWN;
        return new SeedPregen.Area(p.seed, spawn ? SeedPregen.Area.SPAWN : p.centerX,
                spawn ? SeedPregen.Area.SPAWN : p.centerZ, p.radiusChunks);
    }

    private static String areaKey(MenuProfile p) {
        return p.seed + ":" + p.centerMode + ":" + p.centerX + "," + p.centerZ + ":" + p.radiusChunks;
    }

    /**
     * While a real world is open the menu is not showing, so this is the time to make the menu world: on few
     * processors, at the lowest thread priority, so it is done when the player is back at the main menu. Needs the
     * menu world on and a seed as the source. Generating needs the EULA agreed, the same as at the menu; unpacking a
     * packed seed does not. Setting
     * menu.generateInGame = false turns it off. A packed copy of the seed is used first, when there is one.
     */
    private static void warmUp() {
        if ("false".equals(khi.pentecost.control.Settings.get("menu.generateInGame"))) return;
        MenuProfile p = profile();
        if (!p.enabled || p.sourceKind != MenuProfile.SourceKind.SEED) return;
        // Unpacking a packed seed runs no server, so it needs no EULA. Only generating does.
        if (!p.eulaAccepted && !Files.isRegularFile(SeedBundle.bundleFile(Dirs.home(), p.seed))) return;
        String key = areaKey(p);
        if (key.equals(warmTried)) return;
        if (warming) {
            // The area changed while it generates: point the run at the new one, it carries on from what it has.
            if (SeedPregen.retarget(areaOf(p), null)) {
                warmTried = key;
                warmKey = key;
            } else {
                SeedPregen.stopRunning(); // another seed: stop it; the next frame starts the new one
            }
            return;
        }
        warmTried = key;
        MenuProfile snapshot = ProfileStore.copy(p);
        Path config = configDir();
        Path home = Dirs.home();
        warming = true;
        warmKey = key;
        Thread t = new Thread(() -> {
            synchronized (PREP_LOCK) {
                try {
                    boolean spawn = snapshot.centerMode == MenuProfile.CenterMode.SPAWN;
                    SeedPregen.Area area = new SeedPregen.Area(snapshot.seed, spawn ? SeedPregen.Area.SPAWN : snapshot.centerX,
                            spawn ? SeedPregen.Area.SPAWN : snapshot.centerZ, snapshot.radiusChunks);
                    Path dir = WorldSource.ofSeed(snapshot.seed).cacheDir(config);
                    if (!SeedPregen.isComplete(dir, area)) SeedBundle.install(home, dir, area);
                    if (!SeedPregen.isComplete(dir, area) && snapshot.eulaAccepted) {
                        Log.info("Menu world: generating in the background while a world is open");
                        SeedPregen.run(home, dir, area, pr -> {
                            if (busy) progress = new MenuPrep.Progress(MenuPrep.Phase.GENERATING, pr.stage(), pr.chunksDone(), pr.chunksTotal());
                        }, WARM_CORES);
                        Log.info("Menu world: background generation finished");
                    }
                } catch (Throwable e) {
                    Log.warn("Menu world: background generation stopped: " + e.getMessage());
                } finally {
                    warming = false;
                }
            }
        }, "Pentecost menu world (background)");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    /** Called once per frame by the title screen, before it draws. */
    public static void frame(Minecraft mc) {
        if (!wanted) return;
        try {
            Outcome o = outcome;
            if (o != null) {
                outcome = null;
                if (o.generation() == generation) adopt(mc, o);
            }
            Live l = live;
            if (l == null || state != State.READY) return;
            if (!attached && !hook(mc, l)) return;
            if (mc.level != l.menu.level) {
                // A real world took the screen. Leave the game's fields alone.
                attached = false;
                wanted = false;
                restoreOptions(mc);
                return;
            }
            step(mc, l);
        } catch (Throwable t) {
            failHard(mc, t);
        }
    }

    // ------------------------------------------------------------------ preparation

    private static void startPrep(boolean forceScan) {
        int gen = ++generation;
        if (live == null) state = State.PREPARING;
        busy = true;
        progress = null;
        outcome = null;
        // A generation that is running for this seed is pointed at the new area and carries on (a new radius or
        // centre never starts it again). One for another seed stops and keeps what it finished. The preparation
        // below waits for the run and then reads the result.
        MenuProfile now = profile();
        boolean sameSeed = now.sourceKind == MenuProfile.SourceKind.SEED && SeedPregen.retarget(areaOf(now), pr -> {
            if (gen == generation) progress = new MenuPrep.Progress(MenuPrep.Phase.GENERATING, pr.stage(), pr.chunksDone(), pr.chunksTotal());
        });
        if (sameSeed) {
            if (warming) warmKey = areaKey(now);
        } else {
            SeedPregen.stopRunning();
        }
        MenuProfile snapshot = ProfileStore.copy(profile());
        Path config = configDir();
        Path home = Dirs.home();
        Thread t = new Thread(() -> {
            synchronized (PREP_LOCK) {
                if (gen != generation) return; // a newer request came while this one waited
                try {
                    MenuPrep.Ready r = MenuPrep.prepare(home, config, snapshot, forceScan, p -> {
                        if (gen == generation) progress = p;
                    });
                    outcome = new Outcome(gen, r, null);
                } catch (Throwable e) {
                    outcome = new Outcome(gen, null, e);
                }
            }
        }, "Pentecost menu world");
        t.setDaemon(true);
        t.start();
    }

    private static void adopt(Minecraft mc, Outcome o) {
        busy = false;
        if (o.error() != null) {
            Throwable e = o.error();
            State next;
            String text;
            if (e instanceof MenuPrep.NeedsEula) {
                next = State.NEEDS_EULA;
                text = "Generating this seed needs your agreement to the Minecraft EULA.";
            } else if (e instanceof MenuPrep.Failed) {
                next = State.FAILED;
                text = e.getMessage();
            } else {
                next = State.FAILED;
                text = "The menu world could not start: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                Log.warn(text);
            }
            message = text;
            if (live == null) state = next; // a world already on screen keeps running; the settings page shows the reason
            return;
        }
        MenuPrep.Ready r = o.ready();
        MenuProfile p = profile();
        p.switchPointsTo(p.sourceKey());
        if (r.scanned() != null) p.mergeScanned(r.scanned());
        p.scanKey = r.scanKey();
        save();

        dropLive(mc); // the new world is ready: only now does the old one go
        int view = p.viewChunks;
        MenuLevel menu = MenuLevel.create(mc, r.access(), p.sourceKind == MenuProfile.SourceKind.SEED ? p.seed : 0L, view);
        LocalPlayer player = new LocalPlayer(mc, menu.level, menu.listener(), new StatsCounter(), new ClientRecipeBook(), false, false);
        MultiPlayerGameMode mode = new MultiPlayerGameMode(mc, menu.listener());
        Live l = new Live(r, menu, player, mode, view);
        configureClock(l, p);
        l.sequencer = newSequencer(l, p);
        Log.info("Menu world: level built, waiting for the land near the camera");
        live = l;
        lastNanos = 0;
        state = State.READY;
        message = "";
    }

    private static void configureClock(Live l, MenuProfile p) {
        boolean fixed = p.timeMode == MenuProfile.TimeMode.FIXED;
        double start = l.clock == null || fixed ? p.startTicks : l.clock.ticks();
        l.clock = new MenuClock(start, fixed ? 1e9 : p.secondsPerDay);
        l.clock.followRealClock(p.timeMode == MenuProfile.TimeMode.REAL_CLOCK);
    }

    private static ShotSequencer newSequencer(Live l, MenuProfile p) {
        ProfileDirector d = new ProfileDirector(System.nanoTime(), p, l.ready.terrain(), l.ready.centerX(), l.ready.centerZ());
        // The camera only goes where the world was generated and loads: places and camera stay inside the box.
        int[] b = l.ready.box();
        int span = (b[2] - b[0] + 1) * 16;
        int far = Math.min(96, span / 4);
        int near = Math.min(48, span / 8);
        d.area(b[0] * 16 + far, b[1] * 16 + far, b[2] * 16 + 15 - far, b[3] * 16 + 15 - far);
        d.eyeBox(b[0] * 16 + near, b[1] * 16 + near, b[2] * 16 + 15 - near, b[3] * 16 + 15 - near);
        ShotSequencer s = new ShotSequencer(d, l.ready.terrain(), () -> l.clock.ticks());
        s.style(p.handheld, p.clearance, p.fadeSeconds, p.fovOffset);
        s.limits(b[0] * 16 + near, b[1] * 16 + near, b[2] * 16 + 15 - near, b[3] * 16 + 15 - near);
        return s;
    }

    // ------------------------------------------------------------------ swapping the level in and out

    private static boolean hook(Minecraft mc, Live l) throws IOException {
        if (mc.level != null) return false; // a real world is loaded: never touch it
        saved = new Saved(mc.options.getCameraType(), mc.options.fov().get(), mc.options.hideGui);
        Files.createDirectories(marker().getParent());
        Files.writeString(marker(), "showing");
        armShutdownCleanup();

        mc.options.setCameraType(CameraType.FIRST_PERSON);
        mc.options.setServerRenderDistance(l.view); // before the renderer gets the level, so fog ends where the land does
        khi.pentecost.core.MenuDistance.chunks = l.view; // the menu world uses its own distance, not the one set in Video
        mc.options.hideGui = true;
        mc.gameRenderer.setRenderHand(false);
        mc.gameRenderer.setRenderBlockOutline(false);

        ClientLevel level = l.menu.level;
        mc.level = level;
        l.crowd = new AmbientLife.Crowd(level);
        mc.player = l.player;
        mc.gameMode = l.mode;
        mc.setCameraEntity(l.player);
        mc.levelRenderer.setLevel(level);
        mc.particleEngine.setLevel(level);
        mc.getBlockEntityRenderDispatcher().setLevel(level);

        l.cx = Integer.MIN_VALUE;
        l.loaded.clear();
        l.inflight.clear();
        l.missing.clear();
        l.visible = 0;
        l.waited = 0;
        frames = 0;
        attached = true;
        return true;
    }

    private static boolean shutdownCleanupArmed;

    /**
     * Closing the game from the main menu (window X, Quit, a launcher's close) never closes the title screen, so the
     * marker stayed and the next start read it as a crash and turned the menu world off, every time. A clean exit
     * has Minecraft no longer running when the JVM shuts down, so the hook removes the marker then. A crash exits
     * while the game still counts as running, and a kill skips the hook: the marker stays and still catches those.
     */
    private static void armShutdownCleanup() {
        if (shutdownCleanupArmed) return;
        shutdownCleanupArmed = true;
        Path m = marker();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                if (!Minecraft.getInstance().isRunning()) Files.deleteIfExists(m);
            } catch (Throwable e) {
                // at exit there is nothing left to do about it
            }
        }, "Pentecost menu world marker cleanup"));
    }

    private static void unhook(Minecraft mc) {
        if (!attached) return;
        attached = false;
        Live l = live;
        if (l != null && l.crowd != null) {
            l.crowd.clear();
            l.crowd = null;
        }
        if (l != null && mc.level == l.menu.level) {
            mc.levelRenderer.setLevel(null);
            mc.particleEngine.setLevel(null);
            mc.getBlockEntityRenderDispatcher().setLevel(null);
            mc.level = null;
            mc.player = null;
            mc.gameMode = null;
            mc.setCameraEntity(null);
        }
        mc.options.setServerRenderDistance(0);
        khi.pentecost.core.MenuDistance.chunks = 0;
        restoreOptions(mc);
        try {
            Files.deleteIfExists(marker());
        } catch (IOException e) {
            // the marker only matters after a hard stop
        }
    }

    private static void restoreOptions(Minecraft mc) {
        Saved s = saved;
        saved = null;
        khi.pentecost.core.ZoomHooks.fovOverride = 0;
        if (s != null) {
            mc.options.setCameraType(s.camera());
            mc.options.fov().set(s.fov());
            mc.options.hideGui = s.hideGui();
        }
        mc.gameRenderer.setRenderHand(true);
        mc.gameRenderer.setRenderBlockOutline(true);
    }

    private static void dropLive(Minecraft mc) {
        unhook(mc);
        if (live != null) {
            live.close();
            live = null;
        }
    }

    private static void failHard(Minecraft mc, Throwable t) {
        Log.error("The live menu world stopped: " + t);
        try {
            dropLive(mc);
        } catch (Throwable ignored) {
            // already failing: the panorama must come back whatever else breaks
        }
        wanted = false;
        sessionOff = true;
        state = State.FAILED;
        message = "The live menu world stopped (" + t.getClass().getSimpleName() + ") and was turned off.";
        MenuProfile p = profile();
        p.enabled = false;
        save();
    }

    // ------------------------------------------------------------------ every frame

    private static void step(Minecraft mc, Live l) {
        MenuProfile p = profile();
        long now = System.nanoTime();
        double dt = lastNanos == 0 ? 0.016 : Math.min(0.1, (now - lastNanos) / 1e9);
        lastNanos = now;

        if (rebuildDirector) {
            rebuildDirector = false;
            configureClock(l, p);
            l.sequencer = newSequencer(l, p);
        }
        l.clock.tick(dt);
        Pose pose = l.sequencer.advance(dt);
        l.fade = l.sequencer.fade();
        place(mc, l, pose);
        khi.pentecost.game.menuworld.camera.Shot cs = l.sequencer.currentShot();
        khi.pentecost.game.CamTrace.sample("menu", dt, pose.pos().x(), pose.pos().y(), pose.pos().z(), pose.yaw(),
                pose.pitch(), pose.fov(), "shot=" + (cs == null ? "-" : cs.id()) + " fade=" + String.format("%.2f", l.fade));

        ClientLevel level = l.menu.level;
        level.setDayTime((long) l.clock.ticks());
        level.setRainLevel(0F);
        level.setThunderLevel(0F);

        stream(l, pose.pos());
        updateVisible(l, dt);

        if (l.crowd != null) {
            if (p.mobs && l.visible > 0.2) {
                l.crowd.update(pose.pos().x(), pose.pos().y(), pose.pos().z(), pose.yaw(), pose.fov(),
                        (double) mc.getWindow().getWidth() / Math.max(1, mc.getWindow().getHeight()),
                        mc.gui.getGuiTicks(), (long) l.clock.ticks(), dt);
            } else if (l.crowd.size() > 0) {
                l.crowd.clear();
            }
        }

        if (++frames == 120) {
            try {
                Files.deleteIfExists(marker()); // it has been drawing for a while without stopping the game
            } catch (IOException e) {
                // harmless
            }
        }
    }

    private static void place(Minecraft mc, Live l, Pose pose) {
        Vec v = pose.pos();
        LocalPlayer pl = l.player;
        double y = v.y() - pl.getEyeHeight();
        // Spectator-like: nothing pushes it, and it is not drawn, so neither the model nor its shadow (shader
        // shadow maps included) can show in the picture.
        pl.setInvisible(true);
        pl.noPhysics = true;
        pl.setNoGravity(true);
        pl.setPos(v.x(), y, v.z());
        pl.xo = v.x();
        pl.yo = y;
        pl.zo = v.z();
        pl.xOld = v.x();
        pl.yOld = y;
        pl.zOld = v.z();
        float yaw = (float) pose.yaw();
        float pitch = (float) Mth.clamp(pose.pitch(), -90.0, 90.0);
        pl.setYRot(yaw);
        pl.setXRot(pitch);
        pl.yRotO = yaw;
        pl.xRotO = pitch;
        pl.yHeadRot = yaw;
        pl.yHeadRotO = yaw;
        // Whole degrees made every zoom and field of view move jump in steps, so the exact value goes through the hook.
        khi.pentecost.core.ZoomHooks.fovOverride = Mth.clamp(pose.fov(), 30.0, 110.0)
                / Math.max(1.0, khi.pentecost.core.ZoomHooks.factor);
    }

    // ------------------------------------------------------------------ chunks

    private static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    private static int keyX(long k) {
        return (int) (k >> 32);
    }

    private static int keyZ(long k) {
        return (int) k;
    }

    private static boolean inBox(int[] box, int x, int z) {
        return x >= box[0] && x <= box[2] && z >= box[1] && z <= box[3];
    }

    private static void stream(Live l, Vec cam) {
        int cx = Math.floorDiv((int) Math.floor(cam.x()), 16);
        int cz = Math.floorDiv((int) Math.floor(cam.z()), 16);
        if (cx != l.cx || cz != l.cz || l.restream) {
            l.cx = cx;
            l.cz = cz;
            l.restream = false;
            Iterator<Long> it = l.loaded.iterator();
            while (it.hasNext()) {
                long k = it.next();
                if (Math.max(Math.abs(keyX(k) - cx), Math.abs(keyZ(k) - cz)) > l.view + 1) {
                    l.menu.drop(keyX(k), keyZ(k));
                    it.remove();
                }
            }
            l.menu.setViewCenter(cx, cz);
            l.missing.removeIf(k -> Math.max(Math.abs(keyX(k) - cx), Math.abs(keyZ(k) - cz)) > l.view + 1);
            request(l, cx, cz);
        }

        long start = System.nanoTime();
        int fed = 0;
        Result r;
        while (fed < FEED_MAX && System.nanoTime() - start < FEED_BUDGET_NANOS && (r = l.results.poll()) != null) {
            long k = key(r.x(), r.z());
            l.inflight.remove(k);
            if (r.skipped() || (r.data() != null && Math.max(Math.abs(r.x() - l.cx), Math.abs(r.z() - l.cz)) > l.view + 1)) {
                l.restream = true; // it arrived after the camera moved on: the game would log a warning for each one
            } else if (r.data() == null) {
                l.missing.add(k);
            } else if (l.menu.feed(r.data())) {
                l.loaded.add(k);
                fed++;
            } else {
                l.restream = true; // it arrived outside the window: ask again from where the camera is now
            }
        }
        if (fed > 0) l.menu.finishBatch();
    }

    private static void request(Live l, int cx, int cz) {
        int[] box = l.ready.box();
        List<long[]> want = new ArrayList<>();
        for (int dx = -l.view; dx <= l.view; dx++) {
            for (int dz = -l.view; dz <= l.view; dz++) {
                int x = cx + dx, z = cz + dz;
                if (!inBox(box, x, z)) continue;
                long k = key(x, z);
                if (l.loaded.contains(k) || l.inflight.contains(k) || l.missing.contains(k)) continue;
                want.add(new long[] {(long) dx * dx + (long) dz * dz, k});
            }
        }
        want.sort((a, b) -> Long.compare(a[0], b[0]));
        for (int i = 0; i < want.size(); i += 8) {
            List<Long> batch = new ArrayList<>();
            for (int j = i; j < Math.min(i + 8, want.size()); j++) {
                long k = want.get(j)[1];
                batch.add(k);
                l.inflight.add(k);
            }
            try {
                l.worker.execute(() -> readBatch(l, batch));
            } catch (RuntimeException e) {
                return; // the worker is shut down: the level is being dropped
            }
        }
    }

    /** Runs on the worker thread. */
    private static void readBatch(Live l, List<Long> batch) {
        for (long k : batch) {
            int x = keyX(k), z = keyZ(k);
            if (l.closed) return;
            if (Math.max(Math.abs(x - l.cx), Math.abs(z - l.cz)) > l.view + 1) {
                l.results.add(new Result(x, z, null, true)); // the camera moved on before this was read
                continue;
            }
            try {
                CompoundTag tag = l.reader.readChunk(x, z);
                ChunkData d = tag == null ? null : ChunkData.build(tag, l.biomes, -4, 24);
                if (d != null) d.packetBytes(); // built here, so the render thread has nothing to prepare
                l.results.add(new Result(x, z, d, false));
            } catch (Throwable e) {
                if (!readErrorLogged) {
                    readErrorLogged = true;
                    Log.warn("Menu world: a chunk could not be read (" + x + ", " + z + "): " + e);
                }
                l.results.add(new Result(x, z, null, false));
            }
        }
    }

    /** The picture stays black until the land around the camera is in, so a cut never shows empty space. */
    private static void updateVisible(Live l, double dt) {
        int[] box = l.ready.box();
        boolean really = true;
        for (int dx = -3; dx <= 3 && really; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                int x = l.cx + dx, z = l.cz + dz;
                if (!inBox(box, x, z)) continue;
                long k = key(x, z);
                if (!l.loaded.contains(k) && !l.missing.contains(k)) {
                    really = false;
                    break;
                }
            }
        }
        if (really) l.waited = 0;
        else l.waited += dt;
        boolean show = really || l.waited > 12;
        if (really && !l.announced) {
            l.announced = true;
            Log.info("Menu world: showing, " + l.loaded.size() + " chunks in, " + l.missing.size() + " missing");
        }
        if (!really && l.waited > 12 && !l.warned) {
            l.warned = true;
            Log.warn("Menu world: the land near the camera did not load: " + l.loaded.size() + " in, "
                    + l.inflight.size() + " waiting, " + l.missing.size() + " missing");
        }
        l.visible = show ? Math.min(1.0, l.visible + dt / 0.7) : Math.max(0.0, l.visible - dt / 0.2);
    }
}
