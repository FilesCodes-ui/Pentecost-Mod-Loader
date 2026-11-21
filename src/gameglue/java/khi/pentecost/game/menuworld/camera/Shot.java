package khi.pentecost.game.menuworld.camera;

public record Shot(String id, MoveType type, Rail eye, Rail look, double seconds, Ease ease,
                   double fovFrom, double fovTo, double roll, double handheld,
                   double bank, double clearance, boolean guided) {

    public enum MoveType {
        ORBIT, DOLLY, CRANE, PUSH_IN, FLY_THROUGH, REVEAL, ESTABLISH,
        SPIRAL,
        SKIM,
        RIDGE,
        BIRDS_EYE,
        TILT_UP,
        SUNSET,
        DUTCH,
        TELEPHOTO,
        FLYBY,
        STREET,
        TUNNEL,
        TRACK,
        TRANSIT
    }

    public Shot(String id, MoveType type, Rail eye, Rail look, double seconds, Ease ease,
                double fovFrom, double fovTo, double roll, double handheld) {
        this(id, type, eye, look, seconds, ease, fovFrom, fovTo, roll, handheld, 0, 0, false);
    }

    public Shot withSeconds(double s) {
        return new Shot(id, type, eye, look, s, ease, fovFrom, fovTo, roll, handheld, bank, clearance, guided);
    }

    public Shot withRails(Rail newEye, Rail newLook) {
        return new Shot(id, type, newEye, newLook, seconds, ease, fovFrom, fovTo, roll, handheld, bank, clearance, guided);
    }

    public Shot withRoll(double r) {
        return new Shot(id, type, eye, look, seconds, ease, fovFrom, fovTo, r, handheld, bank, clearance, guided);
    }

    public Shot withStyle(double newBank, double newClearance) {
        return new Shot(id, type, eye, look, seconds, ease, fovFrom, fovTo, roll, handheld, newBank, newClearance, guided);
    }

    public boolean underground() {
        return type == MoveType.TUNNEL;
    }
}
