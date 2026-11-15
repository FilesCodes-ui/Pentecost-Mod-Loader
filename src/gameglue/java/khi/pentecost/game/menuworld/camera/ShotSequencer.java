package khi.pentecost.game.menuworld.camera;

import khi.pentecost.game.menuworld.profile.Poi;
import khi.pentecost.game.menuworld.source.Terrain;

import java.util.ArrayDeque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.DoubleSupplier;

/**
 * Plays the director's steps one after another on a camera rig. The next step is planned on a background thread
 * while the current shot plays, so choosing views, planning heights and walking routes through blocks never costs the
 * render thread a frame. The current shot also learns whether the camera flies on from it (no fade at its end) or
 * cuts (a fade) as soon as the next step is known. This is the one object the game's menu screen needs: call
 * {@link #advance} every frame and use the pose and {@link #fade}.
 */
public final class ShotSequencer {
    private record Item(Shot shot, Poi poi, boolean fadeIn, Future<ProfileDirector.Step> nextStep) {}

    private final ProfileDirector director;
    private final Terrain terrain;
    private final DoubleSupplier timeOfDay;
    private final CameraRig rig = new CameraRig();
    private final ArrayDeque<Item> queue = new ArrayDeque<>();
    private final ExecutorService planner;
    private Future<ProfileDirector.Step> ahead;
    private Item current;
    private boolean nextKnown;
    private Shot currentShot;
    private Poi currentPoi;

    public ShotSequencer(ProfileDirector director, Terrain terrain, DoubleSupplier timeOfDay) {
        this(director, terrain, timeOfDay, true);
    }

    /** With {@code async} false every step is planned on the calling thread, which headless checks want. */
    public ShotSequencer(ProfileDirector director, Terrain terrain, DoubleSupplier timeOfDay, boolean async) {
        this.director = director;
        this.terrain = terrain;
        this.timeOfDay = timeOfDay;
        this.planner = async ? Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Pentecost menu director");
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        }) : null;
    }

    /** The player's camera settings, passed to the rig. */
    public void style(double handheldScale, double clearance, double fadeSeconds, double fovOffset) {
        rig.style(handheldScale, clearance, fadeSeconds, fovOffset);
    }

    /** Whether the camera leans into turns. */
    public void banking(boolean on) {
        rig.banking(on);
    }

    /** Keeps the camera inside the generated area (block box). */
    public void limits(double minX, double minZ, double maxX, double maxZ) {
        rig.limits(minX, minZ, maxX, maxZ);
    }

    public Pose advance(double dt) {
        if (rig.finished()) {
            // If the next step is still being planned, hold the last view instead of stalling the frame.
            boolean ready = current == null || !queue.isEmpty() || ahead == null || ahead.isDone();
            if (ready) startNext();
        }
        learnNext();
        return rig.advance(dt, terrain);
    }

    /** 0 to 1, how visible the picture should be. */
    public double fade() {
        return rig.fade();
    }

    public Shot currentShot() {
        return currentShot;
    }

    /** True while the camera is under the ground. */
    public boolean underground() {
        return currentShot != null && currentShot.underground();
    }

    /** The point of interest being shown or flown to, null when the director found the view itself. */
    public Poi currentPoi() {
        return currentPoi;
    }

    /** Stops the planning thread. Call when the sequencer is no longer needed. */
    public void close() {
        if (ahead != null) ahead.cancel(true);
        if (planner != null) planner.shutdownNow();
    }

    private void startNext() {
        if (queue.isEmpty()) refill();
        Item it = queue.poll();
        // Until the next step is known the shot assumes a flight on, so the picture never fades down and snaps back up.
        rig.start(it.shot(), it.fadeIn(), false);
        current = it;
        nextKnown = it.nextStep() == null;
        currentShot = it.shot();
        currentPoi = it.poi();
    }

    /** Once the step after this shot is planned, a cut at the end fades and a flight does not. */
    private void learnNext() {
        if (nextKnown || current == null || current.nextStep() == null) return;
        Future<ProfileDirector.Step> f = current.nextStep();
        if (!f.isDone()) return;
        nextKnown = true;
        rig.fadeOut(await(f).cut());
    }

    private void refill() {
        ProfileDirector.Step step = await(ahead != null ? ahead : plan());
        ahead = plan();
        boolean flies = step.transit() != null;
        if (flies) queue.add(new Item(step.transit(), step.poi(), false, null));
        queue.add(new Item(step.shot(), step.poi(), !flies, ahead));
    }

    private Future<ProfileDirector.Step> plan() {
        double tod = timeOfDay.getAsDouble();
        if (planner == null) {
            try {
                return CompletableFuture.completedFuture(director.next(tod));
            } catch (RuntimeException e) {
                return CompletableFuture.failedFuture(e);
            }
        }
        return planner.submit(() -> director.next(tod));
    }

    private static <T> T await(Future<T> f) {
        try {
            return f.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("The camera director was interrupted.", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("The camera director failed: " + e.getCause(), e.getCause());
        }
    }
}
