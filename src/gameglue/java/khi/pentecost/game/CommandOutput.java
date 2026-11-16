package khi.pentecost.game;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * The last lines that came into the chat, plus the commands the panels sent. The panels show these as their output.
 * Lines are kept in memory only, and the list is capped.
 */
final class CommandOutput {
    private static final int MAX = 300;
    private static final Deque<String> LINES = new ArrayDeque<>();
    private static int version;

    private CommandOutput() {}

    /** A line of chat, or a command a panel sent. */
    static synchronized void offer(String line) {
        if (line == null) {
            return;
        }
        String s = line.strip();
        if (s.isEmpty()) {
            return;
        }
        LINES.addLast(s);
        while (LINES.size() > MAX) {
            LINES.removeFirst();
        }
        version++;
    }

    /** The last n lines, oldest first. */
    static synchronized List<String> last(int n) {
        List<String> all = new ArrayList<>(LINES);
        return all.subList(Math.max(0, all.size() - n), all.size());
    }

    static synchronized int version() {
        return version;
    }

    static synchronized void clear() {
        LINES.clear();
        version++;
    }
}
