package khi.pentecost.game;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;

import java.util.ArrayList;
import java.util.List;

final class CommandsScreen extends PanelScreen {
    private enum Tab { TERMINAL("Terminal"), GROUPS("Groups"), SWITCHES("Switches"), LEVELS("Levels");
        final String label;
        Tab(String label) { this.label = label; }
    }

    private static final String[] CATEGORIES = {"Admin", "Build", "Chat", "Fun", "Items", "Locate", "Menus", "Move",
            "Ops", "Player", "Rules", "Server", "Timers", "World"};

    private static Tab remembered = Tab.TERMINAL;
    private static final List<String> HISTORY = new ArrayList<>();
    private static final List<String> BATCH = new ArrayList<>();

    private Tab tab = remembered;
    private int historyAt = -1;
    private final Field line = new Field("COMMAND   (use ; between commands, wait 2 to pause)", 240);
    private final Field name = new Field("GROUP NAME", 32);
    private final Field command = new Field("COMMAND NAME", 32);
    private final Field level = new Field("LEVEL (0 to 4, or default)", 7);

    CommandsScreen(Screen parent) {
        super("Commands", parent);
        select();
    }

    private void select() {
        fields.clear();
        switch (tab) {
            case TERMINAL -> {
                fields.add(line);
                fields.add(name);
            }
            case GROUPS -> fields.add(name);
            case LEVELS -> {
                fields.add(command);
                fields.add(level);
            }
            default -> { }
        }
        focus = fields.isEmpty() ? null : fields.get(0);
    }

    private void runLine() {
        String t = line.text.trim();
        if (t.isEmpty()) {
            return;
        }
        if (t.startsWith("/")) {
            t = t.substring(1);
        }
        if (HISTORY.isEmpty() || !HISTORY.get(HISTORY.size() - 1).equals(t)) {
            HISTORY.add(t);
            if (HISTORY.size() > 60) {
                HISTORY.remove(0);
            }
        }
        historyAt = -1;
        boolean several = t.contains(";") || t.toLowerCase(java.util.Locale.ROOT).startsWith("wait");
        send(several ? "run " + t : t);
        line.text = "";
    }

    private void addToBatch() {
        String t = line.text.trim();
        if (t.isEmpty()) {
            return;
        }
        for (String part : t.split(";")) {
            String p = part.trim();
            if (p.startsWith("/")) {
                p = p.substring(1).trim();
            }
            if (!p.isEmpty() && BATCH.size() < 100) {
                BATCH.add(p);
            }
        }
        line.text = "";
    }

    private String batchText() {
        return String.join(" ; ", BATCH);
    }

    @Override
    protected void draw(GuiGraphics g) {
        int colW = Math.min(500, vw - 40);
        int x0 = (vw - colW) / 2;
        int y = header(g, "COMMANDS", "Run, group and control commands", x0, colW);

        int tw = (colW - 9) / 4;
        for (int i = 0; i < Tab.values().length; i++) {
            Tab t = Tab.values()[i];
            int bx = x0 + i * (tw + 3);
            boolean on = t == tab;
            if (on) {
                g.fill(bx, y, bx + tw, y + 20, 0x30FFFFFF);
            }
            button(g, t.label, bx, y, tw, !on, () -> {
                tab = t;
                remembered = t;
                select();
            });
        }
        y += 28;

        switch (tab) {
            case TERMINAL -> y = terminal(g, x0, y, colW);
            case GROUPS -> y = groups(g, x0, y, colW);
            case SWITCHES -> y = switches(g, x0, y, colW);
            case LEVELS -> y = levels(g, x0, y, colW);
        }

        int logLines = Math.max(3, Math.min(9, (vh - y - 40) / 11));
        log(g, x0, vh - 36 - logLines * 11 - 8, colW, logLines);
        button(g, "Back", x0, vh - 28, 80, true, this::onClose);
        button(g, "Clear output", x0 + colW - 110, vh - 28, 110, true, CommandOutput::clear);
    }

    private int terminal(GuiGraphics g, int x0, int y, int colW) {
        field(g, line, x0, y, colW);
        y += 34;
        int w = (colW - 9) / 4;
        button(g, "Run", x0, y, w, true, this::runLine);
        button(g, "Add to batch", x0 + (w + 3), y, w, true, this::addToBatch);
        button(g, "Run batch", x0 + 2 * (w + 3), y, w, !BATCH.isEmpty(), () -> {
            send("run " + batchText());
        });
        button(g, "Clear batch", x0 + 3 * (w + 3), y, w, !BATCH.isEmpty(), BATCH::clear);
        y += 26;
        if (!BATCH.isEmpty()) {
            g.drawString(font, "BATCH (" + BATCH.size() + ")", x0, y, Ui.FAINT, false);
            y += 11;
            int shown = Math.min(BATCH.size(), 4);
            for (int i = 0; i < shown; i++) {
                String s = BATCH.get(i);
                while (font.width(s) > colW - 24 && s.length() > 4) {
                    s = s.substring(0, s.length() - 2);
                }
                g.drawString(font, (i + 1) + ". " + s, x0 + 4, y, Ui.SOFT, false);
                y += 10;
            }
            if (BATCH.size() > shown) {
                g.drawString(font, "and " + (BATCH.size() - shown) + " more", x0 + 4, y, Ui.FAINT, false);
                y += 10;
            }
            field(g, name, x0, y + 2, colW - 130);
            button(g, "Save as group", x0 + colW - 124, y + 13, 124, true, () -> {
                String n = name.text.trim();
                if (n.isEmpty() || n.contains(" ")) {
                    CommandOutput.offer("Give the group one word as a name.");
                    return;
                }
                send("group save " + n + " " + batchText());
            });
            y += 38;
        }
        return y;
    }

    private int groups(GuiGraphics g, int x0, int y, int colW) {
        field(g, name, x0, y, colW);
        y += 34;
        int w = (colW - 9) / 4;
        button(g, "Run", x0, y, w, true, () -> withName(n -> send("group run " + n)));
        button(g, "Show", x0 + (w + 3), y, w, true, () -> withName(n -> send("group show " + n)));
        button(g, "Delete", x0 + 2 * (w + 3), y, w, true, () -> withName(n -> send("group delete " + n)));
        button(g, "List all", x0 + 3 * (w + 3), y, w, true, () -> send("group list"));
        y += 28;
        g.drawString(font, "Make groups on the Terminal tab: type commands, Add to batch, then Save as group.", x0, y, Ui.FAINT, false);
        return y + 14;
    }

    private void withName(java.util.function.Consumer<String> then) {
        String n = name.text.trim();
        if (n.isEmpty()) {
            CommandOutput.offer("Type the group name first.");
            return;
        }
        then.accept(n);
    }

    private int switches(GuiGraphics g, int x0, int y, int colW) {
        g.drawString(font, "TURN A WHOLE GROUP OF PENTECOST COMMANDS OFF OR ON", x0, y, Ui.FAINT, false);
        y += 13;
        int half = (colW - 6) / 2;
        for (int i = 0; i < CATEGORIES.length; i++) {
            final String c = CATEGORIES[i];
            int bx = x0 + (i % 2) * (half + 6);
            int by = y + (i / 2) * 22;
            g.drawString(font, c, bx + 2, by + 6, Ui.TEXT, false);
            button(g, "On", bx + half - 96, by, 46, true, () -> send("cmdgroup " + c.toLowerCase(java.util.Locale.ROOT) + " on"));
            button(g, "Off", bx + half - 48, by, 46, true, () -> send("cmdgroup " + c.toLowerCase(java.util.Locale.ROOT) + " off"));
        }
        y += ((CATEGORIES.length + 1) / 2) * 22 + 4;
        button(g, "Show what is changed", x0, y, 170, true, () -> send("cmdconfig"));
        return y + 26;
    }

    private int levels(GuiGraphics g, int x0, int y, int colW) {
        field(g, command, x0, y, colW / 2 - 3);
        field(g, level, x0 + colW / 2 + 3, y, colW / 2 - 3);
        y += 34;
        int cx = x0;
        for (String l : new String[]{"0", "1", "2", "3", "4", "default"}) {
            cx += chip(g, l, cx, y, () -> level.text = l) + 4;
        }
        y += 22;
        int w = (colW - 6) / 2;
        button(g, "Apply", x0, y, w, true, () -> {
            String c = command.text.trim().toLowerCase(java.util.Locale.ROOT);
            String l = level.text.trim().isEmpty() ? "default" : level.text.trim();
            if (c.isEmpty()) {
                CommandOutput.offer("Type a command name first, without the slash.");
                return;
            }
            send("cmdlevel " + c + " " + l);
        });
        button(g, "Show what is changed", x0 + w + 6, y, w, true, () -> send("cmdconfig"));
        y += 24;
        g.drawString(font, "Level 0 lets everyone use the command. default puts it back.", x0, y, Ui.FAINT, false);
        return y + 14;
    }

    @Override
    protected void onEnter(Field f) {
        if (f == line) {
            runLine();
        } else if (f == command || f == level) {
            send("cmdlevel " + command.text.trim().toLowerCase(java.util.Locale.ROOT) + " "
                    + (level.text.trim().isEmpty() ? "default" : level.text.trim()));
        }
    }

    @Override
    protected boolean onArrow(Field f, boolean up) {
        if (f != line || HISTORY.isEmpty()) {
            return false;
        }
        if (up) {
            historyAt = historyAt < 0 ? HISTORY.size() - 1 : Math.max(0, historyAt - 1);
        } else {
            historyAt = historyAt < 0 ? -1 : historyAt + 1;
            if (historyAt >= HISTORY.size()) {
                historyAt = -1;
            }
        }
        line.text = historyAt < 0 ? "" : HISTORY.get(historyAt);
        return true;
    }
}
