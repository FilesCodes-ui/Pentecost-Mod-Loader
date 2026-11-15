package khi.pentecost.game.menuworld.camera;

import khi.pentecost.game.menuworld.camera.Shot.MoveType;
import khi.pentecost.game.menuworld.source.Terrain;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Decides the next shot. It keeps a pool of scored vantage points, picks one, picks a camera move that suits the
 * time of day and the pacing, and composes the shot. The same master seed gives the same sequence, so a bad shot can
 * be found again.
 *
 * Pacing: moves are calm (slow, wide, still) or energetic (fast, close to the land). After two of one kind in a row
 * the other kind is favoured, so the menu breathes instead of running on at one speed.
 */
public final class ShotDirector {
    private static final int POOL = 48;
    private static final Set<MoveType> ENERGETIC = EnumSet.of(MoveType.SKIM, MoveType.FLYBY, MoveType.FLY_THROUGH,
            MoveType.RIDGE, MoveType.SPIRAL, MoveType.DOLLY, MoveType.PUSH_IN, MoveType.DUTCH);

    private final Random rnd;
    private final Terrain terrain;
    private final int centerX;
    private final int centerZ;
    private final int radius;
    private final Deque<Vantage> pool = new ArrayDeque<>();
    private MoveType last;
    private boolean lastEnergetic;
    private int streak;
    private int counter;

    public ShotDirector(long masterSeed, Terrain terrain, int centerX, int centerZ, int radius) {
        this.rnd = new Random(masterSeed);
        this.terrain = terrain;
        this.centerX = centerX;
        this.centerZ = centerZ;
        this.radius = radius;
    }

    /** The next shot. timeOfDay is in game ticks, 0 at sunrise, 6000 noon, 12000 sunset, 18000 midnight. */
    public Shot next(double timeOfDay) {
        return next(timeOfDay, null);
    }

    /** Same, limited to the given moves. Null or empty means any. */
    public Shot next(double timeOfDay, Set<MoveType> allowed) {
        Shot s = candidate(timeOfDay, allowed, Set.of());
        note(s.type());
        return s;
    }

    /** A shot of the next good view in the pool, not yet counted as played. Call {@link #note} for the one that is. */
    public Shot candidate(double timeOfDay, Set<MoveType> allowed, Set<MoveType> skip) {
        if (pool.isEmpty()) {
            List<Vantage> found = Vantage.scan(terrain, centerX, centerZ, radius, POOL, rnd);
            Collections.shuffle(found, rnd);
            pool.addAll(found);
        }
        return nextAt(pool.poll(), timeOfDay, allowed, skip);
    }

    /**
     * A shot of a given vantage, for a place the caller chose (a point of interest). {@code allowed} limits the camera
     * moves; null or empty means any move except the ones that need other systems. Does not count as played: call
     * {@link #note} for the shot that is.
     */
    public Shot nextAt(Vantage v, double tod, Set<MoveType> allowed) {
        return nextAt(v, tod, allowed, Set.of());
    }

    /** Same, never using the moves in {@code skip} (a candidate for each move is tried once) unless nothing else is left. */
    public Shot nextAt(Vantage v, double tod, Set<MoveType> allowed, Set<MoveType> skip) {
        Templates.Template t = pick(tod, allowed, skip);
        return t.compose(v, terrain, rnd, "shot-" + counter++, tod);
    }

    /** Records that a shot of this move was played, for the "never twice in a row" rule and the pacing. */
    public void note(MoveType type) {
        boolean energetic = ENERGETIC.contains(type);
        streak = (last != null && energetic == lastEnergetic) ? streak + 1 : 1;
        lastEnergetic = energetic;
        last = type;
    }

    private Templates.Template pick(double tod, Set<MoveType> allowed, Set<MoveType> skip) {
        // Never the same move twice in a row, unless the allowed list leaves no other choice.
        for (int relax = 0; relax < 3; relax++) {
            List<Templates.Template> options = new ArrayList<>();
            List<Double> weights = new ArrayList<>();
            double total = 0;
            for (Templates.Template t : Templates.ALL) {
                if (allowed != null && !allowed.isEmpty() && !allowed.contains(t.type())) {
                    continue;
                }
                if (relax < 2 && skip.contains(t.type())) {
                    continue;
                }
                if (relax < 1 && t.type() == last) {
                    continue;
                }
                double w = weight(t.type(), tod);
                if (streak >= 2 && ENERGETIC.contains(t.type()) == lastEnergetic) {
                    w *= 0.3;
                }
                options.add(t);
                weights.add(w);
                total += w;
            }
            if (options.isEmpty() || total <= 0) {
                continue;
            }
            double roll = rnd.nextDouble() * total;
            for (int i = 0; i < options.size(); i++) {
                roll -= weights.get(i);
                if (roll <= 0) {
                    return options.get(i);
                }
            }
            return options.get(options.size() - 1);
        }
        return Templates.ALL.get(0);
    }

    static double weight(MoveType type, double tod) {
        boolean golden = tod < 2000 || tod >= 22000 || (tod >= 11000 && tod < 13500);
        boolean night = tod >= 13500 && tod < 22000;
        if (golden) {
            return switch (type) {
                case SUNSET -> 5;
                case CRANE, REVEAL, ORBIT -> 3;
                case ESTABLISH, SPIRAL, TILT_UP, SKIM -> 2;
                default -> 1;
            };
        }
        if (night) {
            return switch (type) {
                case SUNSET -> 0;
                case ESTABLISH -> 4;
                case TELEPHOTO -> 3;
                case ORBIT, BIRDS_EYE, TILT_UP -> 2;
                case CRANE, SPIRAL -> 1;
                default -> 0.4;
            };
        }
        return switch (type) {
            case SUNSET -> 0;
            case FLY_THROUGH, FLYBY -> 3;
            case SKIM, RIDGE -> 2.5;
            case DOLLY, ORBIT, PUSH_IN, SPIRAL -> 2;
            case BIRDS_EYE, TELEPHOTO -> 1.5;
            default -> 1;
        };
    }
}
