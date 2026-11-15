package khi.pentecost.game;

import khi.pentecost.boot.Log;
import khi.pentecost.control.Settings;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.PresetEditor;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState.SelectedGameMode;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState.WorldTypeEntry;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.Difficulty;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Pentecost's Create World screen. It does not make worlds itself: it edits the game's own creation state
 * (the same one the game's screen uses) and asks the game's screen to create, so every rule, warning and
 * data pack behaves as in the game. Game rules, data packs and experiments stay in the game's own screen,
 * one button away.
 */
public final class PentecostCreateWorldScreen extends Screen {
    private record Hit(int x, int y, int w, int h, Runnable action) {}

    /** The game's screen that is about to be shown on purpose, so it is not swapped for this one again. */
    static volatile Screen showVanilla;

    private static Method create;

    private final CreateWorldScreen inner;
    private final List<Hit> hits = new ArrayList<>();
    private int focus; // 0 name, 1 seed
    private String status = "";
    private long statusUntil;

    public PentecostCreateWorldScreen(CreateWorldScreen inner) {
        super(Component.literal("New world"));
        this.inner = inner;
        restore();
    }

    private WorldCreationUiState state() {
        return inner.getUiState();
    }

    @Override
    protected void init() {
        super.init();
        // The game's screen needs to know the window to do its work (it opens warnings and loads packs through it).
        inner.init(minecraft, width, height);
    }

    private void say(String text) {
        status = text;
        statusUntil = System.currentTimeMillis() + 4500;
    }

    // ---------------------------------------------------------------- actions

    private void create() {
        if (state().getName().trim().isEmpty()) {
            say("Give the world a name first.");
            return;
        }
        remember();
        rememberSeed(state().getSeed());
        try {
            if (create == null) {
                create = CreateWorldScreen.class.getDeclaredMethod("onCreate");
                create.setAccessible(true);
            }
            create.invoke(inner);
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.error("Could not create the world: " + e);
            say("Could not start creating the world. Try the game's own options.");
        }
    }

    private void openVanilla() {
        showVanilla = inner;
        minecraft.setScreen(inner);
    }

    private void cycleMode() {
        WorldCreationUiState s = state();
        if (s.isDebug()) {
            return;
        }
        SelectedGameMode[] order = {SelectedGameMode.SURVIVAL, SelectedGameMode.HARDCORE, SelectedGameMode.CREATIVE};
        int i = 0;
        for (int k = 0; k < order.length; k++) {
            if (order[k] == s.getGameMode()) {
                i = k;
            }
        }
        s.setGameMode(order[(i + 1) % order.length]);
    }

    private void cycleDifficulty() {
        WorldCreationUiState s = state();
        if (s.isHardcore()) {
            return;
        }
        Difficulty[] all = Difficulty.values();
        s.setDifficulty(all[(s.getDifficulty().ordinal() + 1) % all.length]);
    }

    private void cycleType() {
        WorldCreationUiState s = state();
        List<WorldTypeEntry> list = s.getNormalPresetList();
        if (list.isEmpty()) {
            return;
        }
        int i = list.indexOf(s.getWorldType());
        s.setWorldType(list.get((i + 1) % list.size()));
    }

    private void customize() {
        PresetEditor editor = state().getPresetEditor();
        if (editor != null) {
            Screen next = editor.createEditScreen(inner, state().getSettings());
            if (next != null) {
                minecraft.setScreen(next);
            }
        }
    }

    private void randomSeed() {
        state().setSeed(Long.toString(new Random().nextLong()));
        focus = 1;
    }

    // ---------------------------------------------------------------- remembered choices

    private static final String REMEMBER = "create.remember";
    private static final String[] REMEMBERED = {"create.mode", "create.difficulty", "create.type", "create.cheats",
            "create.structures", "create.bonus"};

    private static boolean remembering() {
        String v = Settings.get(REMEMBER);
        return v.isEmpty() || Boolean.parseBoolean(v);
    }

    private static List<String> recentSeeds() {
        List<String> out = new ArrayList<>();
        for (String part : Settings.get("create.seeds").split("\\|")) {
            if (!part.isBlank()) {
                out.add(part);
            }
        }
        return out;
    }

    private static void rememberSeed(String seed) {
        String s = seed.trim();
        if (s.isEmpty() || s.contains("|")) {
            return;
        }
        List<String> list = recentSeeds();
        list.remove(s);
        list.add(0, s);
        while (list.size() > 6) {
            list.remove(list.size() - 1);
        }
        Settings.set("create.seeds", String.join("|", list));
    }

    private void copySeed() {
        String seed = state().getSeed();
        if (seed.isEmpty()) {
            say("There is no seed to copy yet.");
            return;
        }
        minecraft.keyboardHandler.setClipboard(seed);
        say("Seed copied.");
    }

    private static String withoutLastWord(String v) {
        String t = v.stripTrailing();
        int i = t.lastIndexOf(' ');
        return i < 0 ? "" : t.substring(0, i + 1);
    }

    /** Saves the choices made on this screen, so the next new world starts from them. Hardcore is not kept. */
    private void remember() {
        if (!remembering()) {
            return;
        }
        WorldCreationUiState s = state();
        if (s.isDebug()) {
            return;
        }
        SelectedGameMode mode = s.getGameMode() == SelectedGameMode.HARDCORE ? SelectedGameMode.SURVIVAL : s.getGameMode();
        Settings.set("create.mode", mode.name());
        if (!s.isHardcore()) {
            Settings.set("create.difficulty", s.getDifficulty().name());
            Settings.set("create.cheats", String.valueOf(s.isAllowCheats()));
            Settings.set("create.bonus", String.valueOf(s.isBonusChest()));
        }
        Settings.set("create.type", s.getWorldType().describePreset().getString());
        Settings.set("create.structures", String.valueOf(s.isGenerateStructures()));
    }

    /** Starts a fresh world from the last choices. A world made from an existing one keeps its own. */
    private void restore() {
        if (!remembering()) {
            return;
        }
        WorldCreationUiState s = state();
        if (!s.getName().equals(net.minecraft.client.resources.language.I18n.get("selectWorld.newWorld"))) {
            return;
        }
        try {
            String mode = Settings.get("create.mode");
            if (!mode.isEmpty()) {
                s.setGameMode(SelectedGameMode.valueOf(mode));
            }
            String type = Settings.get("create.type");
            if (!type.isEmpty()) {
                for (WorldTypeEntry entry : s.getNormalPresetList()) {
                    if (entry.describePreset().getString().equals(type)) {
                        s.setWorldType(entry);
                        break;
                    }
                }
            }
            if (!s.isHardcore() && !s.isDebug()) {
                String difficulty = Settings.get("create.difficulty");
                if (!difficulty.isEmpty()) {
                    s.setDifficulty(Difficulty.valueOf(difficulty));
                }
                String cheats = Settings.get("create.cheats");
                if (!cheats.isEmpty()) {
                    s.setAllowCheats(Boolean.parseBoolean(cheats));
                }
                String bonus = Settings.get("create.bonus");
                if (!bonus.isEmpty()) {
                    s.setBonusChest(Boolean.parseBoolean(bonus));
                }
                String structures = Settings.get("create.structures");
                if (!structures.isEmpty()) {
                    s.setGenerateStructures(Boolean.parseBoolean(structures));
                }
            }
        } catch (RuntimeException e) {
            Log.warn("Could not restore the last world choices: " + e);
        }
    }

    private void forget() {
        for (String key : REMEMBERED) {
            Settings.set(key, "");
        }
        Settings.set("create.seeds", "");
        say("Forgot your last choices and seeds.");
    }

    /** The last seeds used, as small buttons under the seed field. */
    private void drawRecent(GuiGraphics g, List<String> seeds, int x0, int y, int colW, int mx, int my) {
        g.drawString(font, "Recent", x0, y + 2, Ui.FAINT, false);
        int x = x0 + font.width("Recent") + 8;
        for (String seed : seeds) {
            String text = fit(seed, 80);
            int w = font.width(text) + 10;
            if (x + w > x0 + colW) {
                break;
            }
            boolean hover = Ui.over(mx, my, x, y, w, 12);
            if (hover) {
                g.fill(x, y, x + w, y + 12, Ui.TEXT);
            } else {
                Ui.outline(g, x, y, x + w, y + 12, 0x44FFFFFF);
            }
            g.drawString(font, text, x + 5, y + 2, hover ? Ui.BLACK : Ui.SOFT, false);
            hits.add(new Hit(x, y, w, 12, () -> {
                state().setSeed(seed);
                focus = 1;
            }));
            x += w + 4;
        }
    }

    // ---------------------------------------------------------------- drawing

    private String fit(String s, int w) {
        if (font.width(s) <= w) {
            return s;
        }
        int n = s.length();
        while (n > 1 && font.width(s.substring(0, n)) + font.width("...") > w) {
            n--;
        }
        return s.substring(0, n) + "...";
    }

    private void centered(GuiGraphics g, String text, int cx, int y, int color) {
        g.drawString(font, text, cx - font.width(text) / 2, y, color, false);
    }

    private void button(GuiGraphics g, String label, int x, int y, int w, int mx, int my, boolean enabled, Runnable action) {
        boolean hover = enabled && Ui.over(mx, my, x, y, w, 20);
        if (hover) {
            g.fill(x, y, x + w, y + 20, Ui.TEXT);
        } else {
            Ui.outline(g, x, y, x + w, y + 20, enabled ? 0x66FFFFFF : 0x24FFFFFF);
        }
        int color = !enabled ? Ui.FAINT : hover ? Ui.BLACK : Ui.TEXT;
        g.drawString(font, fit(label, w - 12), x + (w - font.width(fit(label, w - 12))) / 2, y + 6, color, false);
        if (enabled) {
            hits.add(new Hit(x, y, w, 20, action));
        }
    }

    private void field(GuiGraphics g, String label, String value, String placeholder, int x, int y, int w, boolean focused, int idx, int mx, int my) {
        g.drawString(font, label, x, y, Ui.MUTED, false);
        Ui.outline(g, x, y + 11, x + w, y + 31, focused ? 0xCCFFFFFF : 0x55FFFFFF);
        if (value.isEmpty()) {
            g.drawString(font, placeholder, x + 8, y + 17, Ui.FAINT, false);
        } else {
            g.drawString(font, fit(value, w - 16), x + 8, y + 17, Ui.TEXT, false);
        }
        if (focused && (System.currentTimeMillis() / 500) % 2 == 0) {
            int caret = x + 8 + (value.isEmpty() ? 0 : Math.min(w - 14, font.width(value)));
            g.fill(caret, y + 15, caret + 1, y + 27, Ui.TEXT);
        }
        hits.add(new Hit(x, y + 11, w, 20, () -> focus = idx));
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, minecraft.level == null ? 0xFF0A0A0B : 0xD80A0A0B);
        Ui.dither(g, width, height);
        float ui = Ui.scale(minecraft);
        int vw = (int) (width / ui);
        int vh = (int) (height / ui);
        int mx = (int) (mouseX / ui);
        int my = (int) (mouseY / ui);
        hits.clear();
        g.pose().pushPose();
        g.pose().scale(ui, ui, 1);

        WorldCreationUiState s = state();
        int cx = vw / 2;
        int colW = Math.min(440, vw - 40);
        int x0 = cx - colW / 2;

        int y = Math.max(14, vh / 20);
        String word = "NEW WORLD";
        int total = (Ui.LOGO_W + 5) * 2 + Ui.wordmarkWidth(word, 2);
        int hx = cx - total / 2;
        Ui.logo(g, hx, y - 2, 2, Ui.TEXT);
        Ui.wordmark(g, word, hx + (Ui.LOGO_W + 5) * 2, y, 2, Ui.TEXT);
        y += 34;

        field(g, "World name", s.getName(), "New World", x0, y, colW, focus == 0, 0, mx, my);
        y += 38;
        g.drawString(font, "Saved as " + fit(s.getTargetFolder(), colW - 70), x0, y - 4, Ui.FAINT, false);
        y += 12;
        int rndW = 96;
        int cpyW = 52;
        field(g, "Seed (empty for a random one)", s.getSeed(), "Random", x0, y, colW - rndW - cpyW - 12, focus == 1, 1, mx, my);
        button(g, "Copy", x0 + colW - rndW - cpyW - 6, y + 11, cpyW, mx, my, !s.getSeed().isEmpty(), this::copySeed);
        button(g, "Random seed", x0 + colW - rndW, y + 11, rndW, mx, my, true, this::randomSeed);
        int chipsY = y + 34;
        y += 46;
        List<String> recent = recentSeeds();
        if (vh >= 330 && !recent.isEmpty()) {
            drawRecent(g, recent, x0, chipsY, colW, mx, my);
            y += 14;
        }

        int gap = 6;
        int half = (colW - gap) / 2;
        boolean debug = s.isDebug();
        String mode = s.getGameMode().displayName.getString();
        button(g, "Mode: " + mode, x0, y, half, mx, my, !debug, this::cycleMode);
        button(g, "Difficulty: " + (s.isHardcore() ? "Hard" : s.getDifficulty().getDisplayName().getString()), x0 + half + gap, y, half, mx, my,
                !s.isHardcore(), this::cycleDifficulty);
        y += 24;
        centered(g, fit(s.getGameMode().getInfo().getString(), colW), cx, y, Ui.MUTED);
        y += 16;
        button(g, "World type: " + s.getWorldType().describePreset().getString(), x0, y, half, mx, my, true, this::cycleType);
        button(g, "Customize", x0 + half + gap, y, half, mx, my, s.getPresetEditor() != null, this::customize);
        y += 24;
        boolean cheatsLocked = s.isHardcore() || debug;
        button(g, "Cheats: " + (s.isAllowCheats() ? "On" : "Off"), x0, y, half, mx, my, !cheatsLocked,
                () -> s.setAllowCheats(!s.isAllowCheats()));
        button(g, "Structures: " + (s.isGenerateStructures() ? "On" : "Off"), x0 + half + gap, y, half, mx, my, !debug,
                () -> s.setGenerateStructures(!s.isGenerateStructures()));
        y += 24;
        button(g, "Bonus chest: " + (s.isBonusChest() ? "On" : "Off"), x0, y, half, mx, my, !s.isHardcore() && !debug,
                () -> s.setBonusChest(!s.isBonusChest()));
        button(g, "Game rules, packs...", x0 + half + gap, y, half, mx, my, true, this::openVanilla);
        y += 24;
        if (vh >= 360) {
            button(g, "Remember choices: " + (remembering() ? "On" : "Off"), x0, y, half, mx, my, true,
                    () -> Settings.set(REMEMBER, String.valueOf(!remembering())));
            button(g, "Forget choices", x0 + half + gap, y, half, mx, my, true, this::forget);
            y += 30;
        } else {
            y += 6;
        }

        if (System.currentTimeMillis() < statusUntil && !status.isEmpty()) {
            centered(g, status, cx, y, Ui.SOFT);
        }

        int by = vh - 36;
        int third = (colW - 12) / 3;
        button(g, "Back", x0, by, third, mx, my, true, this::onClose);
        button(g, "Create world", x0 + third + 6, by, third * 2 + 6, mx, my, !s.getName().trim().isEmpty(), this::create);
        centered(g, "Tab switches field   Enter creates   Ctrl+C copies", cx, by - 16, Ui.FAINT);
        g.pose().popPose();
    }

    // ---------------------------------------------------------------- input

    @Override
    public boolean charTyped(char c, int modifiers) {
        if (Character.isISOControl(c)) {
            return super.charTyped(c, modifiers);
        }
        WorldCreationUiState s = state();
        if (focus == 0 && s.getName().length() < 64) {
            s.setName(s.getName() + c);
        } else if (focus == 1 && s.getSeed().length() < 64) {
            s.setSeed(s.getSeed() + c);
        }
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        WorldCreationUiState s = state();
        switch (key) {
            case 256 -> {
                onClose();
                return true;
            }
            case 257, 335 -> {
                create();
                return true;
            }
            case 258 -> {
                focus = 1 - focus;
                return true;
            }
            case 259 -> {
                String v = focus == 0 ? s.getName() : s.getSeed();
                if (!v.isEmpty()) {
                    v = Screen.hasControlDown() ? withoutLastWord(v) : v.substring(0, v.length() - 1);
                    if (focus == 0) {
                        s.setName(v);
                    } else {
                        s.setSeed(v);
                    }
                }
                return true;
            }
            case 67 -> {
                if (Screen.hasControlDown()) {
                    if (focus == 1) {
                        copySeed();
                    } else if (!s.getName().isEmpty()) {
                        minecraft.keyboardHandler.setClipboard(s.getName());
                        say("Name copied.");
                    }
                    return true;
                }
            }
            case 86 -> {
                if (Screen.hasControlDown()) {
                    String clip = minecraft.keyboardHandler.getClipboard();
                    if (focus == 0) {
                        s.setName((s.getName() + clip).trim());
                    } else {
                        s.setSeed((s.getSeed() + clip).trim());
                    }
                    return true;
                }
            }
            default -> {
            }
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        float ui = Ui.scale(minecraft);
        double mx = mouseX / ui;
        double my = mouseY / ui;
        if (button == 0) {
            for (int i = hits.size() - 1; i >= 0; i--) {
                Hit h = hits.get(i);
                if (mx >= h.x() && mx < h.x() + h.w() && my >= h.y() && my < h.y() + h.h()) {
                    minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
                    h.action().run();
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public void onClose() {
        // The game's own way out: it also removes the temporary data pack folder it may have made.
        inner.onClose();
    }
}
