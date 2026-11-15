package khi.pentecost.game.menuworld.camera;

import java.util.function.DoubleUnaryOperator;

/** Speed curves for a shot. Each maps shot progress 0..1 to path progress 0..1. */
public enum Ease implements DoubleUnaryOperator {
    LINEAR {
        @Override
        public double applyAsDouble(double t) {
            return clamp(t);
        }
    },
    /** Gentle start and stop. */
    SMOOTH {
        @Override
        public double applyAsDouble(double t) {
            t = clamp(t);
            return t * t * (3 - 2 * t);
        }
    },
    /** Very gentle start and stop; the camera feels like it has weight. */
    SMOOTHER {
        @Override
        public double applyAsDouble(double t) {
            t = clamp(t);
            return t * t * t * (t * (6 * t - 15) + 10);
        }
    },
    /** Fast at first, settling slowly. Good for a reveal that lands. */
    OUT_QUINT {
        @Override
        public double applyAsDouble(double t) {
            t = clamp(t);
            return 1 - Math.pow(1 - t, 5);
        }
    },
    /** Eases up to a steady speed, holds it, and eases down. A flight keeps one pace instead of peaking. */
    CRUISE {
        @Override
        public double applyAsDouble(double t) {
            t = clamp(t);
            final double r = 0.3;
            final double v = 1 / (1 - r);
            if (t < r) return v * t * t / (2 * r);
            if (t > 1 - r) return 1 - v * (1 - t) * (1 - t) / (2 * r);
            return v * r / 2 + v * (t - r);
        }
    },
    /** Slow build, then a push. */
    IN_CUBIC {
        @Override
        public double applyAsDouble(double t) {
            t = clamp(t);
            return t * t * t;
        }
    };

    static double clamp(double t) {
        return t < 0 ? 0 : Math.min(t, 1);
    }

    public double apply(double t) {
        return applyAsDouble(t);
    }
}
