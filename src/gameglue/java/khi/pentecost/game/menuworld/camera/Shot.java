package khi.pentecost.game.menuworld.camera;

/**
 * One continuous camera shot.
 *
 * @param eye       where the camera is, by progress
 * @param look      what it aims at, by progress
 * @param seconds   how long the shot lasts
 * @param ease      speed curve along both rails
 * @param fovFrom   field of view at the start, degrees
 * @param fovTo     field of view at the end, degrees
 * @param roll      steady tilt, degrees
 * @param handheld  0 for a locked-off camera, 1 for a lively one
 * @param bank      how much the camera leans into turns: 0 none, 1 like a small aircraft
 * @param clearance least height over the ground for this shot, blocks; 0 means the player's setting
 * @param guided    true when the route was planned through blocks (a tunnel or a street): the terrain rules do not apply
 */
public record Shot(String id, MoveType type, Rail eye, Rail look, double seconds, Ease ease,
                   double fovFrom, double fovTo, double roll, double handheld,
                   double bank, double clearance, boolean guided) {

    public enum MoveType {
        ORBIT, DOLLY, CRANE, PUSH_IN, FLY_THROUGH, REVEAL, ESTABLISH,
        /** Climbs in a wide spiral around the subject. */
        SPIRAL,
        /** Skims low and fast over water or a valley floor. */
        SKIM,
        /** Follows a ridge line, looking out over the valley beside it. */
        RIDGE,
        /** Circles high above the subject, looking down. */
        BIRDS_EYE,
        /** Starts on the ground and tilts up to a peak or the sky. */
        TILT_UP,
        /** Faces the low sun at sunrise or sunset. */
        SUNSET,
        /** An orbit with the horizon tilted. */
        DUTCH,
        /** A long lens from far away. */
        TELEPHOTO,
        /** Passes the subject from one side to the other. */
        FLYBY,
        /** Walks down the streets of a village, planned through its blocks. */
        STREET,
        /** Travels through a mineshaft, stronghold or other passages underground, planned through its blocks. */
        TUNNEL,
        /** Follows an ambient mob. Needs the mob system. */
        TRACK,
        /** A flight from the end of one shot to the start of the next. Made by the profile director, never picked as a move. */
        TRANSIT
    }

    /** A shot without banking or terrain overrides. */
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

    /** True for shots under the ground. */
    public boolean underground() {
        return type == MoveType.TUNNEL;
    }
}
