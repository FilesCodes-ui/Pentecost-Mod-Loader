package khi.pentecost.game;

import khi.pentecost.control.Settings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.GameRules;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class ServerScreen extends PanelScreen {
    private enum Section {
        OVERVIEW("Overview"), PLAYERS("Players"), OPERATORS("Operators"), ACCESS("Access"), WORLD("World"),
        RULES("Game rules"), CHAT("Chat"), SERVER("Server"), PERFORMANCE("Performance");

        final String label;

        Section(String label) {
            this.label = label;
        }
    }

    private record B(String label, Runnable run, int level) {
        B(String label, Runnable run) {
            this(label, run, 2);
        }
    }

    private static final String[][] RULES = {
            {"keepInventory", "Keep inventory"}, {"mobGriefing", "Mob griefing"},
            {"doDaylightCycle", "Day and night"}, {"doWeatherCycle", "Weather changes"},
            {"doFireTick", "Fire spreads"}, {"doMobSpawning", "Mobs spawn"},
            {"doInsomnia", "Phantoms"}, {"naturalRegeneration", "Natural healing"},
            {"fallDamage", "Fall damage"}, {"fireDamage", "Fire damage"},
            {"drowningDamage", "Drowning damage"}, {"doImmediateRespawn", "Instant respawn"},
            {"commandBlockOutput", "Command block chat"}, {"announceAdvancements", "Advancement chat"},
            {"showDeathMessages", "Death messages"}, {"doEntityDrops", "Entities drop items"}};

    private static Section remembered = Section.OVERVIEW;

    private Section section = remembered;
    private boolean refocus = true;
    private final Field player = new Field("PLAYER", 16);
    private final Field time = new Field("TIME (30m, 2h, 7d)", 12);
    private final Field reason = new Field("REASON (optional)", 60);
    private final Field minutes = new Field("AUTOSAVE MINUTES", 4);
    private final Field stop = new Field("STOP IN (30s, 5m, 1h)", 8);
    private final Field message = new Field("MESSAGE", 200);
    private final Field days = new Field("DAY LENGTH IN REAL MINUTES", 6);
    private final Field border = new Field("WORLD BORDER SIZE (blocks)", 8);

    private final Map<String, Boolean> ruleCache = new HashMap<>();
    private long ruleAt;
    private String armedKey = "";
    private long armedUntil;

    ServerScreen(Screen parent) {
        super("Server", parent);
    }

    static boolean available(Minecraft mc) {
        if (mc == null || mc.player == null || mc.level == null || !mc.player.hasPermissions(2)) {
            return false;
        }
        return mc.getCurrentServer() != null || mc.getSingleplayerServer() == null || WwlsScreen.hosting()
                || mc.getConnection() != null && mc.getConnection().getOnlinePlayers().size() > 1;
    }

    private String who() {
        return player.text.trim();
    }

    private boolean needPlayer() {
        if (who().isEmpty()) {
            CommandOutput.offer("Type a player name first, or tap a name under the field.");
            return false;
        }
        return true;
    }

    private Runnable cmd(String command) {
        return () -> send(command);
    }

    private Runnable onPlayer(String prefix) {
        return () -> {
            if (needPlayer()) {
                send(prefix + " " + who());
            }
        };
    }

    private Runnable twice(String key, String warning, Runnable run) {
        return () -> {
            long now = System.currentTimeMillis();
            if (armedKey.equals(key) && now < armedUntil) {
                armedKey = "";
                run.run();
            } else {
                armedKey = key;
                armedUntil = now + 5000;
                CommandOutput.offer(warning + " Press the button again within 5 seconds to do it.");
            }
        };
    }

    private int row(GuiGraphics g, int x, int y, int w, B... buttons) {
        int n = buttons.length;
        int bw = (w - (n - 1) * 3) / n;
        for (int i = 0; i < n; i++) {
            B b = buttons[i];
            boolean allowed = minecraft.player != null && minecraft.player.hasPermissions(b.level());
            button(g, b.label(), x + i * (bw + 3), y, bw, allowed, b.run());
        }
        return y + 24;
    }

    private int label(GuiGraphics g, String text, int x, int y) {
        g.drawString(font, text, x, y, Ui.FAINT, false);
        return y + 11;
    }

    private int names(GuiGraphics g, int x, int y, int w) {
        List<String> online = new ArrayList<>();
        if (minecraft.getConnection() != null) {
            for (PlayerInfo p : minecraft.getConnection().getOnlinePlayers()) {
                online.add(p.getProfile().getName());
            }
        }
        if (online.isEmpty()) {
            return y;
        }
        g.drawString(font, "ONLINE", x, y + 2, Ui.FAINT, false);
        int cx = x + 52;
        int cy = y - 1;
        for (String n : online) {
            int cw = font.width(n) + 10;
            if (cx + cw > x + w) {
                cx = x + 52;
                cy += 17;
            }
            cx += chip(g, n, cx, cy, () -> player.text = n) + 4;
        }
        return cy + 21;
    }

    private void refreshRules() {
        long now = System.currentTimeMillis();
        if (now - ruleAt < 500) {
            return;
        }
        ruleAt = now;
        ruleCache.clear();
        MinecraftServer server = minecraft.getSingleplayerServer();
        if (server == null) {
            return;
        }
        GameRules rules = server.getGameRules();
        GameRules.visitGameRuleTypes(new GameRules.GameRuleTypeVisitor() {
            @Override
            public void visitBoolean(GameRules.Key<GameRules.BooleanValue> key, GameRules.Type<GameRules.BooleanValue> type) {
                ruleCache.put(key.getId(), rules.getBoolean(key));
            }
        });
    }

    @Override
    protected void draw(GuiGraphics g) {
        int colW = Math.min(640, vw - 40);
        int x0 = (vw - colW) / 2;
        String sub = minecraft.getSingleplayerServer() != null ? "World open to friends" : "Operator tools";
        int top = header(g, "SERVER", sub, x0, colW);

        int side = 104;
        int sy = top;
        for (Section s : Section.values()) {
            boolean on = s == section;
            if (on) {
                g.fill(x0, sy, x0 + side, sy + 20, 0x30FFFFFF);
            }
            button(g, s.label, x0, sy, side, !on, () -> {
                section = s;
                remembered = s;
                refocus = true;
                armedKey = "";
            });
            sy += 23;
        }

        int cx = x0 + side + 10;
        int cw = colW - side - 10;
        fields.clear();
        switch (section) {
            case PLAYERS, OPERATORS -> fields.add(player);
            case ACCESS -> fields.addAll(List.of(player, time, reason));
            case WORLD -> fields.addAll(List.of(days, border));
            case CHAT -> fields.add(message);
            case SERVER -> fields.addAll(List.of(minutes, stop));
            default -> { }
        }
        if (refocus) {
            focus = fields.isEmpty() ? null : fields.get(0);
            refocus = false;
        }
        if (focus != null && !fields.contains(focus)) {
            focus = null;
        }

        switch (section) {
            case OVERVIEW -> overview(g, cx, top, cw);
            case PLAYERS -> players(g, cx, top, cw);
            case OPERATORS -> operators(g, cx, top, cw);
            case ACCESS -> access(g, cx, top, cw);
            case WORLD -> world(g, cx, top, cw);
            case RULES -> rules(g, cx, top, cw);
            case CHAT -> chat(g, cx, top, cw);
            case SERVER -> server(g, cx, top, cw);
            case PERFORMANCE -> performance(g, cx, top, cw);
        }

        log(g, x0, vh - 36 - 5 * 11 - 8, colW, 5);
        button(g, "Back", x0, vh - 28, 80, true, this::onClose);
        button(g, "Clear output", x0 + colW - 110, vh - 28, 110, true, CommandOutput::clear);
    }

    private void overview(GuiGraphics g, int x, int y, int w) {
        int online = minecraft.getConnection() == null ? 1 : minecraft.getConnection().getOnlinePlayers().size();
        String where = minecraft.getSingleplayerServer() != null
                ? minecraft.getSingleplayerServer().getWorldData().getLevelName()
                : minecraft.getCurrentServer() != null ? minecraft.getCurrentServer().name : "";
        Runtime rt = Runtime.getRuntime();
        long used = (rt.totalMemory() - rt.freeMemory()) >> 20;
        g.drawString(font, where, x, y, Ui.TEXT, false);
        y += 12;
        g.drawString(font, online + " online   " + minecraft.getFps() + " fps   " + used + " MB used by this game", x, y, Ui.MUTED, false);
        y += 18;
        y = label(g, "HOW IS IT RUNNING", x, y);
        y = row(g, x, y, w, new B("Speed (TPS)", cmd("tps"), 0), new B("Memory", cmd("mem"), 0),
                new B("Uptime", cmd("uptime"), 0), new B("World info", cmd("worldinfo"), 0));
        y = row(g, x, y, w, new B("Entities", cmd("entities")), new B("Loaded chunks", cmd("chunks")),
                new B("Who is online", cmd("online"), 0), new B("Free slots", cmd("slots"), 0));
        y = row(g, x, y, w, new B("Server summary", cmd("panel")), new B("Staff online", cmd("staff"), 0),
                new B("Game rules", cmd("rules"), 0), new B("Everyone's modes", cmd("gamemodes")));
        y += 6;
        y = label(g, "CLEAN UP", x, y);
        row(g, x, y, w, new B("Clear dropped items", twice("lag", "This removes every dropped item and XP orb.", cmd("clearlag")), 3),
                new B("Remove farm animals", twice("passive", "This removes every farm animal without a name that is not tamed.", cmd("killpassive")), 3),
                new B("Free memory", cmd("gc"), 3));
    }

    private void players(GuiGraphics g, int x, int y, int w) {
        field(g, player, x, y, w);
        y += 34;
        y = names(g, x, y, w);
        y = row(g, x, y, w, new B("Heal", onPlayer("heal")), new B("Feed", onPlayer("feed")),
                new B("Cure effects", onPlayer("cure")), new B("Put out fire", onPlayer("extinguish")));
        y = row(g, x, y, w, new B("Fly", onPlayer("fly")), new B("God mode", onPlayer("god")),
                new B("Freeze", onPlayer("freeze")), new B("Unfreeze", onPlayer("unfreeze")));
        y = row(g, x, y, w, new B("Bring here", () -> { if (needPlayer()) send("tp " + who() + " @s"); }),
                new B("Go to them", () -> { if (needPlayer()) send("tp @s " + who()); }),
                new B("Burn", onPlayer("burn")), new B("Kick", () -> { if (needPlayer()) send("kick " + who()); }, 3));
        y = label(g, "GAME MODE", x, y + 2);
        y = row(g, x, y, w, new B("Survival", () -> { if (needPlayer()) send("gamemode survival " + who()); }),
                new B("Creative", () -> { if (needPlayer()) send("gamemode creative " + who()); }),
                new B("Adventure", () -> { if (needPlayer()) send("gamemode adventure " + who()); }),
                new B("Spectator", () -> { if (needPlayer()) send("gamemode spectator " + who()); }));
        y = label(g, "EVERYONE", x, y + 2);
        y = row(g, x, y, w, new B("Heal all", cmd("healall")), new B("Feed all", cmd("feedall")),
                new B("Bring all here", cmd("tpall")), new B("Fireworks", cmd("celebrate")));
        row(g, x, y, w, new B("All survival", cmd("gmall survival")), new B("All creative", cmd("gmall creative")),
                new B("All adventure", cmd("gmall adventure")), new B("All spectator", cmd("gmall spectator")));
    }

    private void operators(GuiGraphics g, int x, int y, int w) {
        field(g, player, x, y, w);
        y += 34;
        y = names(g, x, y, w);
        y = row(g, x, y, w, new B("Promote", onPlayer("promote"), 3), new B("Demote", onPlayer("demote"), 3),
                new B("Check level", onPlayer("opcheck")));
        int lw = (w - 12) / 5;
        for (int lv = 0; lv <= 4; lv++) {
            final int level = lv;
            button(g, lv == 0 ? "Remove" : "Level " + lv, x + lv * (lw + 3), y, lw, minecraft.player.hasPermissions(3),
                    () -> { if (needPlayer()) send("oplevel " + who() + " " + level); });
        }
        y += 24;
        y = row(g, x, y, w, new B("List operators", cmd("ops")), new B("My level", cmd("mylevel"), 0),
                new B("Reload list", cmd("opreload"), 3), new B("Operator kit", cmd("opkit")));
        y = row(g, x, y, w, new B("Join when full: on", () -> { if (needPlayer()) send("opbypass " + who() + " on"); }, 3),
                new B("Join when full: off", () -> { if (needPlayer()) send("opbypass " + who() + " off"); }, 3),
                new B("Remove all operators", twice("deopall", "This removes every operator except you.", cmd("deopall confirm")), 4));
        var waiting = WwlsScreen.waitingList();
        if (!waiting.isEmpty()) {
            y = label(g, "WAITING TO JOIN", x, y + 4);
            for (var r : waiting) {
                g.drawString(font, r.name(), x + 4, y + 6, Ui.TEXT, false);
                button(g, "Let in", x + w - 150, y, 70, true, () -> WwlsScreen.answerFor(r.id(), true));
                button(g, "Turn away", x + w - 76, y, 76, true, () -> WwlsScreen.answerFor(r.id(), false));
                y += 22;
            }
        }
    }

    private void access(GuiGraphics g, int x, int y, int w) {
        field(g, player, x, y, w / 2 - 3);
        field(g, time, x + w / 2 + 3, y, w / 2 - 3);
        y += 34;
        field(g, reason, x, y, w);
        y += 34;
        y = names(g, x, y, w);
        y = label(g, "WHITELIST", x, y);
        y = row(g, x, y, w, new B("Add", () -> { if (needPlayer()) send("wl add " + who()); }, 3),
                new B("Remove", () -> { if (needPlayer()) send("wl remove " + who()); }, 3),
                new B("Add everyone online", cmd("wl all"), 3), new B("Show list", cmd("wl list"), 3));
        y = row(g, x, y, w, new B("Turn on", cmd("wl on"), 3), new B("Turn off", cmd("wl off"), 3),
                new B("Reload from disk", cmd("wl reload"), 3));
        y = label(g, "BANS AND KICKS", x, y + 2);
        y = row(g, x, y, w, new B("Ban for a while", () -> {
            if (needPlayer()) {
                String t = time.text.trim().isEmpty() ? "1h" : time.text.trim();
                send("tempban " + who() + " " + t + (reason.text.isBlank() ? "" : " " + reason.text.trim()));
            }
        }, 3), new B("Pardon", onPlayer("pardon"), 3), new B("Show bans", cmd("bans"), 3),
                new B("Kick", () -> {
                    if (needPlayer()) {
                        send("kick " + who() + (reason.text.isBlank() ? "" : " " + reason.text.trim()));
                    }
                }, 3));
        row(g, x, y, w, new B("Kick everyone but operators", twice("kickall", "This kicks every player who is not an operator.",
                () -> send("kickall" + (reason.text.isBlank() ? "" : " " + reason.text.trim()))), 3),
                new B("Maintenance on", cmd("maintenance on"), 3), new B("Maintenance off", cmd("maintenance off"), 3));
    }

    private void world(GuiGraphics g, int x, int y, int w) {
        y = label(g, "TIME", x, y);
        y = row(g, x, y, w, new B("Day", cmd("time set day")), new B("Noon", cmd("time set noon")),
                new B("Night", cmd("time set night")), new B("Midnight", cmd("time set midnight")),
                new B("Skip the night", cmd("skip")));
        y = label(g, "WEATHER AND DIFFICULTY", x, y + 2);
        y = row(g, x, y, w, new B("Clear", cmd("sun")), new B("Rain", cmd("rain")), new B("Storm", cmd("storm")),
                new B("Peaceful", cmd("diff peaceful")), new B("Easy", cmd("diff easy")),
                new B("Normal", cmd("diff normal")), new B("Hard", cmd("diff hard")));
        y = row(g, x, y, w, new B("Players can fight", cmd("pvp on")), new B("No fighting", cmd("pvp off")),
                new B("World spawn here", cmd("setspawn")), new B("Jail here", cmd("setjail")));
        field(g, days, x, y, w / 2 - 3);
        int bw = (w / 2 - 6) / 3;
        int bx = x + w / 2 + 3;
        button(g, "Set", bx, y + 11, bw, true, () -> {
            if (days.text.isBlank()) {
                CommandOutput.offer("Type how many real minutes a day should last. About 11 is normal.");
            } else {
                send("daylength " + days.text.trim());
            }
        });
        button(g, "Normal", bx + bw + 3, y + 11, bw, true, cmd("daylength normal"));
        button(g, "Freeze", bx + 2 * (bw + 3), y + 11, bw, true, cmd("daylength freeze"));
        y += 36;
        field(g, border, x, y, w / 2 - 3);
        button(g, "Set", bx, y + 11, bw, true, () -> {
            if (border.text.isBlank()) {
                CommandOutput.offer("Type the border size in blocks.");
            } else {
                send("worldborder set " + border.text.trim());
            }
        });
        button(g, "Centre here", bx + bw + 3, y + 11, bw, true, cmd("bordercenter"));
        button(g, "Widest", bx + 2 * (bw + 3), y + 11, bw, true, cmd("borderoff"));
    }

    private void rules(GuiGraphics g, int x, int y, int w) {
        refreshRules();
        boolean known = !ruleCache.isEmpty();
        g.drawString(font, known ? "GAME RULES (the current setting is lit)" : "GAME RULES", x, y, Ui.FAINT, false);
        y += 13;
        int half = (w - 8) / 2;
        for (int i = 0; i < RULES.length; i++) {
            final String rule = RULES[i][0];
            int bx = x + (i % 2) * (half + 8);
            int by = y + (i / 2) * 22;
            Boolean now = ruleCache.get(rule);
            String name = RULES[i][1];
            while (font.width(name) > half - 92 && name.length() > 4) {
                name = name.substring(0, name.length() - 2);
            }
            g.drawString(font, name, bx + 2, by + 6, Ui.SOFT, false);
            if (Boolean.TRUE.equals(now)) {
                g.fill(bx + half - 86, by, bx + half - 44, by + 20, 0x30FFFFFF);
            } else if (Boolean.FALSE.equals(now)) {
                g.fill(bx + half - 42, by, bx + half, by + 20, 0x30FFFFFF);
            }
            button(g, "On", bx + half - 86, by, 42, true, () -> send("gr " + rule + " true"));
            button(g, "Off", bx + half - 42, by, 42, true, () -> send("gr " + rule + " false"));
        }
        y += ((RULES.length + 1) / 2) * 22 + 6;
        y = row(g, x, y, w, new B("Show changed rules", cmd("grdiff")), new B("List presets", cmd("presets")),
                new B("Show all rules", cmd("gr")));
        row(g, x, y, w, new B("Reset every rule", twice("grreset", "This puts every game rule back to its normal value.", cmd("grresetall")), 3));
    }

    private void chat(GuiGraphics g, int x, int y, int w) {
        field(g, message, x, y, w);
        y += 34;
        y = row(g, x, y, w, new B("Announce", () -> typed("announce")), new B("Big title", () -> typed("titleall")),
                new B("Above hotbar", () -> typed("actionbar")), new B("Staff chat", () -> typed("sc")));
        y = row(g, x, y, w, new B("Set message of the day", () -> typed("setmotd"), 3), new B("Show message of the day", cmd("motd"), 0),
                new B("Clear everyone's chat", twice("clearchat", "This pushes the chat off everyone's screen.", cmd("clearchat"))));
        g.drawString(font, "Announce shows in chat and above the hotbar. Staff chat is only seen by operators.", x, y + 4, Ui.FAINT, false);
    }

    private void typed(String command) {
        if (message.text.isBlank()) {
            CommandOutput.offer("Type the message first.");
            return;
        }
        send(command + " " + message.text.trim());
    }

    private void server(GuiGraphics g, int x, int y, int w) {
        field(g, minutes, x, y, w / 2 - 3);
        int bw = (w / 2 - 3) / 2 - 2;
        int bx = x + w / 2 + 3;
        button(g, "Set autosave", bx, y + 11, bw, minecraft.player.hasPermissions(3), () -> {
            String m = minutes.text.trim();
            send("autosave " + (m.isEmpty() ? "now" : m));
        });
        button(g, "Autosave off", bx + bw + 4, y + 11, bw, minecraft.player.hasPermissions(3), cmd("autosave off"));
        y += 38;
        y = row(g, x, y, w, new B("Save now", cmd("savenow"), 3), new B("Pause saving", cmd("saveoff"), 3),
                new B("Resume saving", cmd("saveon"), 3));
        y = row(g, x, y, w, new B("Maintenance on", cmd("maintenance on"), 3), new B("Maintenance off", cmd("maintenance off"), 3),
                new B("Server summary", cmd("panel")));
        y = label(g, "NEW PLAYERS START IN", x, y + 2);
        y = row(g, x, y, w, new B("Survival", cmd("defaultgm survival"), 3), new B("Creative", cmd("defaultgm creative"), 3),
                new B("Adventure", cmd("defaultgm adventure"), 3), new B("Spectator", cmd("defaultgm spectator"), 3));
        field(g, stop, x, y + 2, w / 2 - 3);
        boolean can = minecraft.player.hasPermissions(4);
        button(g, "Stop server", bx, y + 13, bw, can, () -> send("stopin " + (stop.text.isBlank() ? "30s" : stop.text.trim())));
        button(g, "Cancel stop", bx + bw + 4, y + 13, bw, can, cmd("stopcancel"));
    }

    private void performance(GuiGraphics g, int x, int y, int w) {
        var o = minecraft.options;
        g.drawString(font, "THIS GAME'S SETTINGS. THE SAME ONES AS SETTINGS > SERVER.", x, y, Ui.FAINT, false);
        y += 14;
        int half = (w - 10) / 2;
        slider(g, "Simulation distance", x, y, half, 5, 32, 1, o.simulationDistance().get(), " chunks",
                v -> o.simulationDistance().set(v), o::save);
        slider(g, "Render distance", x + half + 10, y, half, 2, 32, 1, o.renderDistance().get(), " chunks",
                v -> o.renderDistance().set(v), o::save);
        y += 32;
        slider(g, "Entity distance", x, y, half, 50, 500, 10, (int) Math.round(o.entityDistanceScaling().get() * 100), "%",
                v -> o.entityDistanceScaling().set(v / 100.0), o::save);
        y += 36;
        toggle(g, "Spread chunk saving (restart)", x, y, half, Settings.on(Settings.SPREAD_UNLOADS),
                () -> Settings.toggle(Settings.SPREAD_UNLOADS));
        toggle(g, "Far mob throttle", x + half + 10, y, half, Settings.on(Settings.FAR_MOBS),
                () -> Settings.toggle(Settings.FAR_MOBS));
        y += 26;
        slider(g, "Far distance", x, y, half, 24, 96, 8, Settings.num(Settings.FAR_RANGE, 48), " blocks",
                v -> Settings.set(Settings.FAR_RANGE, String.valueOf(v)), () -> { });
        slider(g, "Far thinking rate (ticks)", x + half + 10, y, half, 2, 8, 1, Settings.num(Settings.FAR_EVERY, 3), " ticks",
                v -> Settings.set(Settings.FAR_EVERY, String.valueOf(v)), () -> { });
        y += 38;
        button(g, "Open or join a world (WWLS)", x, y, half, true, () -> minecraft.setScreen(new WwlsScreen(this)));
        button(g, "Friends who cannot connect (playit.gg)", x + half + 10, y, half, true, () -> minecraft.setScreen(new PlayitScreen(this)));
    }

    @Override
    protected void onEnter(Field f) {
        if (section == Section.OPERATORS && needPlayer()) {
            send("opcheck " + who());
        } else if (section == Section.CHAT) {
            typed("announce");
        }
    }
}
