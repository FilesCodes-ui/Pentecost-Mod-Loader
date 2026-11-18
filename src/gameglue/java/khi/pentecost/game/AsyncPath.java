package khi.pentecost.game;

import khi.pentecost.messenger.PathHooks;
import khi.pentecost.messenger.PendingPath;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.function.Supplier;

/**
 * A path whose search runs on a worker. Until the answer comes, the navigation treats it as "on the way" and the mob waits.
 * Anything that reads it early makes it search right there, on the calling thread, the old way: correct, only not faster.
 * Every method of Path is answered from the real path once there is one.
 */
final class AsyncPath extends Path implements PendingPath {
    private final BlockPos target;
    private final Supplier<Path> searchHere;
    private final String site;
    private volatile boolean ready;
    private Path real;

    AsyncPath(BlockPos target, Supplier<Path> searchHere, String site) {
        super(new ArrayList<>(), target, false);
        this.target = target;
        this.searchHere = searchHere;
        this.site = site;
    }

    @Override
    public boolean pending() {
        return !ready;
    }

    /** Takes the answer. False when an answer was already taken. */
    synchronized boolean settle(Path p) {
        if (ready) {
            return false;
        }
        real = p != null ? p : new Path(new ArrayList<>(), target, false);
        ready = true;
        return true;
    }

    private Path real() {
        if (!ready) {
            Path p = searchHere.get();
            if (settle(p)) {
                PathHooks.forcedAt(site);
            }
        }
        return real;
    }

    @Override public void advance() { real().advance(); }
    @Override public boolean notStarted() { return !ready || real().notStarted(); }
    @Override public boolean isDone() { return ready && real().isDone(); }
    @Override public Node getEndNode() { return real().getEndNode(); }
    @Override public Node getNode(int i) { return real().getNode(i); }
    @Override public void truncateNodes(int i) { real().truncateNodes(i); }
    @Override public void replaceNode(int i, Node n) { real().replaceNode(i, n); }
    @Override public int getNodeCount() { return real().getNodeCount(); }
    @Override public int getNextNodeIndex() { return real().getNextNodeIndex(); }
    @Override public void setNextNodeIndex(int i) { real().setNextNodeIndex(i); }
    @Override public Vec3 getEntityPosAtNode(Entity e, int i) { return real().getEntityPosAtNode(e, i); }
    @Override public BlockPos getNodePos(int i) { return real().getNodePos(i); }
    @Override public Vec3 getNextEntityPos(Entity e) { return real().getNextEntityPos(e); }
    @Override public BlockPos getNextNodePos() { return real().getNextNodePos(); }
    @Override public Node getNextNode() { return real().getNextNode(); }
    @Override public Node getPreviousNode() { return real().getPreviousNode(); }
    @Override public boolean sameAs(Path other) { return real().sameAs(other instanceof AsyncPath a ? a.real() : other); }
    @Override public boolean canReach() { return real().canReach(); }
    @Override public Node[] getOpenSet() { return real().getOpenSet(); }
    @Override public Node[] getClosedSet() { return real().getClosedSet(); }
    @Override public void writeToStream(FriendlyByteBuf buf) { real().writeToStream(buf); }
    @Override public BlockPos getTarget() { return ready ? real.getTarget() : target; }
    @Override public float getDistToTarget() { return real().getDistToTarget(); }
    @Override public String toString() { return ready ? real.toString() : "Path{searching on a worker, target=" + target + "}"; }
}
