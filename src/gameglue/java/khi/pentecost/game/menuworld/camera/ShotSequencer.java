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

    public void style(double handheldScale, double clearance, double fadeSeconds, double fovOffset) {
        rig.style(handheldScale, clearance, fadeSeconds, fovOffset);
    }

    public void banking(boolean on) {
        rig.banking(on);
    }

    public void limits(double minX, double minZ, double maxX, double maxZ) {
        rig.limits(minX, minZ, maxX, maxZ);
    }

    public Pose advance(double dt) {
        if (rig.finished()) {
            boolean ready = current == null || !queue.isEmpty() || ahead == null || ahead.isDone();
            if (ready) startNext();
        }
        learnNext();
        return rig.advance(dt, terrain);
    }

    public double fade() {
        return rig.fade();
    }

    public Shot currentShot() {
        return currentShot;
    }

    public boolean underground() {
        return currentShot != null && currentShot.underground();
    }

    public Poi currentPoi() {
        return currentPoi;
    }

    public void close() {
        if (ahead != null) ahead.cancel(true);
        if (planner != null) planner.shutdownNow();
    }

    private void startNext() {
        if (queue.isEmpty()) refill();
        Item it = queue.poll();
        rig.start(it.shot(), it.fadeIn(), false);
        current = it;
        nextKnown = it.nextStep() == null;
        currentShot = it.shot();
        currentPoi = it.poi();
    }

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
