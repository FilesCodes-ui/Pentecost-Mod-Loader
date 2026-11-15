package khi.pentecost.game.pregen;

/**
 * How many chunks may be made at once. No game classes, so it can be checked headless.
 * <p>
 * Nobody on the server (or speed set to full): the big window. Players on: the small one, shrinking while the server
 * tick is slow and growing back when it is not, so friends on a WWLS world are not made to lag.
 */
final class PregenWindow {
    static final int MIN = 2;
    private double window;

    PregenWindow(int start) {
        this.window = Math.max(MIN, start);
    }

    int size() {
        return (int) window;
    }

    void adjust(boolean players, boolean forceFull, int full, int gentle, float averageTickMs, float limitMs) {
        if (!players || forceFull) {
            window = Math.max(MIN, full);
            return;
        }
        gentle = Math.max(MIN, gentle);
        if (averageTickMs > limitMs) {
            window = Math.max(MIN, window * 0.8);
        } else if (window < gentle) {
            window = Math.min(gentle, window + 0.5);
        } else if (window > gentle) {
            window = gentle; // the last player came online while it ran at full speed
        }
    }
}
