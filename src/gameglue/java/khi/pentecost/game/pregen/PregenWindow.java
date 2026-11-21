package khi.pentecost.game.pregen;

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
            window = gentle;
        }
    }
}
