package khi.pentecost.messenger;

/** Where a job runs. */
public enum Lane {
    /** The game thread, the one that draws. Jobs run when the game pumps this lane once a frame. */
    GAME,
    /** The server thread of the world being played. Jobs run when the server pumps this lane at the start of a tick. */
    SERVER,
    /** A small pool of worker threads for work that only reads a snapshot or does math. */
    WORKER,
    /** Virtual threads for work that waits on disk or network. */
    IO
}
