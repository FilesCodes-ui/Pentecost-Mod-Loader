package khi.pentecost.game;

import khi.pentecost.control.Settings;
import khi.pentecost.core.ChatHooks;
import net.minecraft.client.GuiMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The chat in Pentecost's look: plain letters, no boxes, hairlines, black and white, and the same easing as the rest
 * of Pentecost.
 * <p>
 * Look (setting chat.theme, "pentecost" or "vanilla"): each chat line gets a soft dark wash and a hairline down the
 * left edge, which joins into one line along the whole chat; the input box gets a wash and a hairline underneath.
 * <p>
 * Motion (setting chat.animation):
 * - when a line arrives, the chat glides up into place instead of jumping, and the hairline flashes and fades,
 * - a brighter spot drifts along the hairline, the same as under a name tag,
 * - when the chat input opens, its hairline grows out from the middle and the box fades in,
 * - the tab underline glides from one tab to the next.
 * <p>
 * Tabs (setting chat.tabs): All, Players and System, just above the input box. A tab shows only its lines;
 * a number beside a tab counts the lines that arrived there while you looked at another tab.
 * <p>
 * Every line on its way into the chat window also passes through here:
 * - links in plain text become clickable (the game asks before it opens one),
 * - coordinates like "120 64 -340" become clickable and copy to the clipboard,
 * - your name in someone else's message is bold and underlined, with a short sound,
 * - an optional time stamp in front of each line, in the faint gray.
 * Every setting is in the Settings screen (Interface, Chat) and in config/pentecost/settings.json.
 */
final class ChatStyle implements ChatHooks.Processor, ChatHooks.Painter {
    static final String THEME = "chat.theme";
    static final String ANIMATION = "chat.animation";
    static final String TABS = "chat.tabs";
    static final String TIMESTAMPS = "chat.timestamps";
    static final String LINKS = "chat.links";
    static final String COORDS = "chat.coords";
    static final String MENTIONS = "chat.mentions";
    static final String MENTION_SOUND = "chat.mentionSound";

    private static final Pattern URL = Pattern.compile("https?://[^\\s<>\"]+");
    private static final Pattern COORD = Pattern.compile(
            "(?<![\\w.\\-])(-?\\d{1,7})(?:,\\s*|\\s+)(-?\\d{1,3})(?:,\\s*|\\s+)(-?\\d{1,7})(?![\\w.\\-])");
    /** A line somebody said: an optional [tag] first, then "<name>" or "name: ". Everything else is the System tab. */
    private static final Pattern SAID = Pattern.compile("^(\\[[^\\]]{1,12}\\] )?(<|[^\\s:<>]{1,24}: )");
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("h:mm a");

    private static final String[] TAB_NAMES = {"All", "Players", "System"};
    private static final int INK = 0xF2F2F2;
    private static final int TAB_GAP = 14;
    private static final int TAB_TOP = 32;
    private static final int TAB_BOTTOM = 17;
    private static final int BLACK = 0x0A0A0B;
    /** How far the input box, the tabs and the lines lift when the chat opens, so they sit above the hotbar. */
    private static final int RAISE = 24;

    private enum Kind { LINK, COORD, MENTION }

    private record Hit(int start, int end, Kind kind, String value) {}

    // Motion and tab state. All of it is read and written on the render thread.
    private static int tab;
    private static final int[] UNREAD = new int[3];
    private static final int[] TAB_X1 = new int[3];
    private static final int[] TAB_X2 = new int[3];
    private static boolean tabsKnown;
    private static float underlineX;
    private static float underlineW;
    private static long lastTabFrame;
    private static long lastFrame;
    private static long lastNew;
    private static long lastInput;
    private static long openedAt;
    private static float rise;
    private static boolean pushed;
    private static int curMin;
    private static int curMax;
    private static int prevMin;
    private static int prevMax;
    private static float lift;
    private static float shade;
    private static boolean raised;
    private static boolean tabsShown;
    /** True while the chat lines are drawn inside a scissor that keeps them off the tabs and the suggestion list. */
    private static boolean clipped;
    /** Top edge of the command suggestion list: drawn this frame so far, and in the frame before. MAX_VALUE = none. */
    private static int listTopCur = Integer.MAX_VALUE;
    private static int listTopPrev = Integer.MAX_VALUE;
    private static int panelX1;
    private static int panelX2;
    private static java.lang.reflect.Field inputField;
    private static boolean inputFieldTried;

    private ChatStyle() {}

    static void install() {
        ChatStyle style = new ChatStyle();
        ChatHooks.processor = style;
        ChatHooks.painter = style;
    }

    static boolean option(String key, boolean def) {
        String v = Settings.get(key);
        return v.isEmpty() ? def : Boolean.parseBoolean(v);
    }

    static void flip(String key, boolean def) {
        Settings.set(key, String.valueOf(!option(key, def)));
    }

    private static boolean pentecostLook() {
        return !"vanilla".equals(Settings.get(THEME));
    }

    private static int argb(int rgb, float alpha) {
        return (Math.round(Math.max(0.0F, Math.min(1.0F, alpha)) * 255.0F) << 24) | (rgb & 0xFFFFFF);
    }

    private static int category(String text) {
        return SAID.matcher(text).find() ? 1 : 2;
    }

    // ---- Motion: the chat window ----

    @Override
    public void begin(Object graphics) {
        long now = System.nanoTime();
        float dt = lastFrame == 0 ? 0.016F : Math.min(0.1F, (now - lastFrame) / 1e9F);
        lastFrame = now;
        prevMin = curMin;
        prevMax = curMax;
        curMin = Integer.MAX_VALUE;
        curMax = Integer.MIN_VALUE;
        pushed = false;
        settle((GuiGraphics) graphics, dt);
        clip((GuiGraphics) graphics);
        if (!option(ANIMATION, true)) {
            rise = 0.0F;
            return;
        }
        // The chat glides up to where the new line pushed it, the same easing the name tags use.
        rise -= rise * (1.0F - (float) Math.exp(-dt / 0.11F));
        if (rise < 0.05F) {
            rise = 0.0F;
        }
        if (rise > 0.0F) {
            GuiGraphics g = (GuiGraphics) graphics;
            g.pose().pushPose();
            g.pose().translate(0.0F, rise, 0.0F);
            pushed = true;
        }
    }

    @Override
    public void end(Object graphics) {
        if (pushed) {
            pushed = false;
            ((GuiGraphics) graphics).pose().popPose();
        }
        if (clipped) {
            clipped = false;
            ((GuiGraphics) graphics).disableScissor();
        }
    }

    /**
     * While the chat screen is open the lines may not draw below the tabs (or above-and-into the suggestion list), so a
     * line that glides in from below comes out from under that edge instead of over the tabs and the input box.
     * The scissor is set in screen coordinates before the chat's own translate and scale, and undone in end.
     */
    private static void clip(GuiGraphics g) {
        clipped = false;
        if (!pentecostLook() || !(Minecraft.getInstance().screen instanceof ChatScreen)) {
            return;
        }
        int bottom = g.guiHeight() - Math.round(lift) - TAB_TOP - 2;
        if (listTopPrev != Integer.MAX_VALUE) {
            bottom = Math.min(bottom, listTopPrev - 1);
        }
        if (bottom > 0) {
            g.enableScissor(0, 0, g.guiWidth(), bottom);
            clipped = true;
        }
    }

    // ---- Look: the backgrounds ----

    /**
     * The chat's backgrounds. Lines are drawn at x -4 and the input box at x 2 and 12 high; anything else is the game's.
     * A line gets a dark wash that is solid behind the text and fades out to the right, so it reads on snow and sand
     * as well as in caves, and never looks like a box. The hairline down the left edge has a dark edge of its own,
     * so it shows on bright ground too. The input box is as wide as the chat, not the whole screen.
     */
    @Override
    public boolean fill(Object graphics, int x1, int y1, int x2, int y2, int color) {
        if (!pentecostLook()) {
            return false;
        }
        GuiGraphics g = (GuiGraphics) graphics;
        boolean animate = option(ANIMATION, true);
        long now = System.nanoTime();
        if (x1 == 2 && y2 - y1 == 12 && x2 - x1 > 40) {
            if (now - lastInput > 300_000_000L) {
                openedAt = now; // the input was closed a moment ago, so it opens now
            }
            lastInput = now;
            float t = animate ? Math.min(1.0F, (now - openedAt) / 280e6F) : 1.0F;
            float e = 1.0F - (1.0F - t) * (1.0F - t) * (1.0F - t);
            int right = x2;
            try {
                ChatComponent chat = Minecraft.getInstance().gui.getChat();
                right = Math.min(x2, Math.max(x1 + 160, (int) ((chat.getWidth() + 8) * chat.getScale())));
            } catch (RuntimeException ex) {
                // the box stays as wide as the game made it
            }
            wash(g, x1, y1, right, y2, 0.82F * e, 0.3F);
            float half = (right - x1) / 2.0F * e;
            int mid = (x1 + right) / 2;
            g.fill(Math.round(mid - half), y2 - 1, Math.round(mid + half), y2, argb(0xFFFFFF, 0.55F));
            return true;
        }
        if (x1 == -4 && x2 - x1 > 8) {
            // The game fades a line by changing the alpha of this color; follow it, so lines fade the same way.
            float k = Math.min(1.0F, (color >>> 24) / 128.0F);
            curMin = Math.min(curMin, y1);
            curMax = Math.max(curMax, y2);
            wash(g, x1, y1, x2, y2, 0.74F * k, 0.55F);
            float flash = animate ? (float) Math.exp(-(now - lastNew) / 450e6) : 0.0F;
            g.fill(x1 + 1, y1, x1 + 2, y2, argb(0x000000, 0.4F * k));
            g.fill(x1, y1, x1 + 1, y2, argb(0xFFFFFF, (0.6F + 0.4F * flash) * k));
            if (animate && prevMax > prevMin) {
                // A brighter spot drifts up and down the hairline, as it does under a name tag.
                double t = (now / 1e9) % 3600.0;
                float spot = prevMin + (prevMax - prevMin) * (0.5F + 0.5F * (float) Math.sin(t * 1.3));
                int a = Math.max(y1, Math.round(spot - 5.0F));
                int b = Math.min(y2, Math.round(spot + 5.0F));
                if (b > a) {
                    g.fill(x1, a, x1 + 1, b, argb(0xFFFFFF, k));
                }
            }
            return true;
        }
        return false;
    }

    /** A dark wash that is solid up to the given share of the width, then fades out to nothing. */
    private static void wash(GuiGraphics g, int x1, int y1, int x2, int y2, float alpha, float solid) {
        int w = x2 - x1;
        int strips = Math.max(1, Math.min(24, w / 4));
        for (int i = 0; i < strips; i++) {
            float t = (i + 0.5F) / strips;
            float fade = t <= solid ? 1.0F : 1.0F - smooth((t - solid) / (1.0F - solid));
            float a = alpha * fade;
            if (a < 0.01F) {
                continue;
            }
            g.fill(x1 + w * i / strips, y1, x1 + w * (i + 1) / strips, y2, argb(BLACK, a));
        }
    }

    private static float smooth(float x) {
        float c = Math.max(0.0F, Math.min(1.0F, x));
        return c * c * (3.0F - 2.0F * c);
    }
    // ---- Lift, shade and command suggestions ----

    /**
     * Eases the lift and the shade toward where the chat is (open or closed) and draws the shade behind the chat. The
     * shade is the same dark wash and dither as the pause screen's, lighter, and it fades in and out with the chat.
     */
    private static void settle(GuiGraphics g, float dt) {
        Minecraft mc = Minecraft.getInstance();
        boolean open = mc.screen instanceof ChatScreen && pentecostLook();
        float k = option(ANIMATION, true) ? 1.0F - (float) Math.exp(-dt / 0.09F) : 1.0F;
        float liftTo = open && raised ? RAISE : 0.0F;
        // The screen's layout jumps when the chat opens, so the lines jump with it. Only opacity eases, never position.
        lift = liftTo;
        shade += ((open ? 1.0F : 0.0F) - shade) * k;
        if (!open && shade < 0.01F) {
            shade = 0.0F;
        }
        if (shade > 0.01F) {
            int w = g.guiWidth();
            int h = g.guiHeight();
            g.fill(0, 0, w, h, argb(BLACK, 0.3F * shade));
            g.setColor(1.0F, 1.0F, 1.0F, shade * 0.9F);
            Ui.dither(g, w, h);
            g.setColor(1.0F, 1.0F, 1.0F, 1.0F);
        }
    }

    @Override
    public int chatHeight(int height) {
        return height - Math.round(lift);
    }

    @Override
    public void screenInit(Object screen) {
        Screen s = (Screen) screen;
        raised = false;
        int full = Minecraft.getInstance().getWindow().getGuiScaledHeight();
        listTopCur = Integer.MAX_VALUE;
        listTopPrev = Integer.MAX_VALUE;
        if (pentecostLook() && full >= 160) {
            s.height = full - RAISE; // everything the screen lays out from the bottom, the input box and the suggestions, sits higher
            raised = true;
        }
    }

    /** The command suggestions: a dark wash per row with a hairline on the left, like the chat lines. */
    @Override
    public boolean suggestFill(Object graphics, int x1, int y1, int x2, int y2, int color) {
        if (!pentecostLook()) {
            return false;
        }
        GuiGraphics g = (GuiGraphics) graphics;
        int w = x2 - x1;
        int h = y2 - y1;
        if (w == 1 && h == 1) {
            g.fill(x1, y1, x2, y2, argb(0xFFFFFF, 0.5F)); // the dots that say there are more above or below
            return true;
        }
        if (w > 8) {
            listTopCur = Math.min(listTopCur, y1);
        }
        g.fill(x1, y1, x2, y2, argb(BLACK, 0.96F));
        if (h == 12 && w > 8) {
            panelX1 = x1;
            panelX2 = x2;
            g.fill(x1, y1, x1 + 1, y2, argb(0xFFFFFF, 0.6F));
        }
        return true;
    }

    /** The chosen suggestion is white on a light strip, the others are gray. */
    @Override
    public int suggestText(Object graphics, Object fontObject, String text, int x, int y, int color) {
        if (!pentecostLook()) {
            return Integer.MIN_VALUE;
        }
        GuiGraphics g = (GuiGraphics) graphics;
        boolean chosen = color == -256;
        if (chosen && panelX2 > panelX1) {
            g.fill(panelX1 + 1, y - 2, panelX2, y + 10, argb(0xFFFFFF, 0.14F));
        }
        int ink = chosen ? 0xFFFFFFFF : color == -5592406 ? 0xFFA8A8A8 : color;
        return g.drawString((Font) fontObject, text, x, y, ink);
    }

    /** True while the chat input holds a command, when the suggestion list sits where the tabs are. */
    private static boolean typingCommand(Minecraft mc) {
        if (!(mc.screen instanceof ChatScreen)) {
            return false;
        }
        try {
            if (inputField == null && !inputFieldTried) {
                inputFieldTried = true;
                java.lang.reflect.Field f = ChatScreen.class.getDeclaredField("input");
                f.setAccessible(true);
                inputField = f;
            }
            if (inputField != null) {
                return ((EditBox) inputField.get(mc.screen)).getValue().startsWith("/");
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            inputField = null;
        }
        return false;
    }

    // ---- Tabs ----

    @Override
    public boolean show(Object message) {
        if (tab == 0 || !option(TABS, true)) {
            return true;
        }
        return category(((GuiMessage) message).content().getString()) == tab;
    }

    @Override
    public void screen(Object graphics, int mouseX, int mouseY) {
        boolean listUp = listTopCur != Integer.MAX_VALUE;
        listTopPrev = listTopCur;
        listTopCur = Integer.MAX_VALUE;
        if (!option(TABS, true)) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        GuiGraphics g = (GuiGraphics) graphics;
        Font font = mc.font;
        int h = mc.screen != null ? mc.screen.height : g.guiHeight(); // the chat screen's own height, already lifted
        tabsShown = false;
        if (listUp || typingCommand(mc)) {
            return;
        }
        tabsShown = true;
        long now = System.nanoTime();
        float dt = lastTabFrame == 0 ? 0.016F : Math.min(0.1F, (now - lastTabFrame) / 1e9F);
        lastTabFrame = now;
        float open = pentecostLook() && option(ANIMATION, true) ? Math.min(1.0F, (now - openedAt) / 280e6F) : 1.0F;

        String[] labels = new String[3];
        int total = 0;
        for (int i = 0; i < 3; i++) {
            labels[i] = TAB_NAMES[i] + (UNREAD[i] > 0 ? " " + Math.min(UNREAD[i], 99) : "");
            total += font.width(labels[i]) + (i > 0 ? TAB_GAP : 0);
        }
        // The tabs sit just above the input box, at its left end, where the chat lines stop.
        int x = 8;
        int top = h - TAB_TOP;
        int bottom = h - TAB_BOTTOM;
        wash(g, x - 8, top, x + total + 16, bottom, 0.74F * open, 0.8F);
        for (int i = 0; i < 3; i++) {
            int lw = font.width(labels[i]);
            TAB_X1[i] = x - TAB_GAP / 2;
            TAB_X2[i] = x + lw + TAB_GAP / 2;
            boolean hover = mouseY >= top && mouseY < bottom && mouseX >= TAB_X1[i] && mouseX < TAB_X2[i];
            int rgb = i == tab ? INK : hover ? 0xFFFFFF : 0xB8B8B8;
            g.drawString(font, labels[i], x, h - TAB_TOP + 4, argb(rgb, open), false);
            if (i == tab) {
                if (!tabsKnown) {
                    underlineX = x;
                    underlineW = lw;
                    tabsKnown = true;
                }
                float ease = 1.0F - (float) Math.exp(-dt / 0.09F);
                underlineX += (x - underlineX) * ease;
                underlineW += (lw - underlineW) * ease;
            }
            x += lw + TAB_GAP;
        }
        g.fill(Math.round(underlineX), bottom - 3, Math.round(underlineX + underlineW), bottom - 2, argb(0xFFFFFF, 0.9F * open));
    }

    @Override
    public boolean click(double x, double y, int button) {
        if (button != 0 || !option(TABS, true) || !tabsKnown || !tabsShown) {
            return false;
        }
        Minecraft mc = Minecraft.getInstance();
        int h = mc.screen != null ? mc.screen.height : mc.getWindow().getGuiScaledHeight();
        if (y < h - TAB_TOP || y >= h - TAB_BOTTOM) {
            return false;
        }
        for (int i = 0; i < 3; i++) {
            if (x >= TAB_X1[i] && x < TAB_X2[i]) {
                if (i != tab) {
                    tab = i;
                    UNREAD[i] = 0;
                    mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
                    mc.gui.getChat().rescaleChat(); // draws the window again, asking show() about every line
                }
                return true;
            }
        }
        return false;
    }
    // ---- Every line on its way in ----

    @Override
    public Object process(Object object) {
        Component line = (Component) object;
        String full = line.getString();
        CommandOutput.offer(full);
        Minecraft mc = Minecraft.getInstance();

        int cat = category(full);
        boolean tabs = option(TABS, true);
        boolean visible = !tabs || tab == 0 || tab == cat;
        if (tabs && !visible) {
            UNREAD[cat] = Math.min(99, UNREAD[cat] + 1);
        }

        boolean links = option(LINKS, true);
        boolean coords = option(COORDS, true);
        boolean mentions = option(MENTIONS, true);
        boolean stamps = option(TIMESTAMPS, false);
        Component result = line;
        if (links || coords || mentions || stamps) {
            result = rewrite(line, full, mc, links, coords, mentions, stamps);
        }

        if (visible && option(ANIMATION, true)) {
            rise = Math.min(rise + lineHeight(mc, result), 3.0F * 9.0F);
            lastNew = System.nanoTime();
        }
        return result;
    }

    /** How far this line pushes the chat up: its number of rows times the row height. */
    private static float lineHeight(Minecraft mc, Component line) {
        try {
            ChatComponent chat = mc.gui.getChat();
            double scale = chat.getScale();
            int rows = Math.max(1, mc.font.split(line, Mth.floor(chat.getWidth() / scale)).size());
            return (float) (rows * 9.0 * (mc.options.chatLineSpacing().get() + 1.0) * scale);
        } catch (RuntimeException e) {
            return 9.0F;
        }
    }

    private Component rewrite(Component line, String full, Minecraft mc, boolean links, boolean coords,
                              boolean mentions, boolean stamps) {
        String me = mc.player != null ? mc.player.getGameProfile().getName() : mc.getUser().getName();

        // A chat line looks like "<name> text" or "name: text". Only the text part can mention you.
        int bodyStart = -1;
        int angle = full.indexOf("> ");
        if (full.startsWith("<") && angle > 0 && angle < 80) {
            bodyStart = angle + 2;
        } else {
            int colon = full.indexOf(": ");
            if (colon > 0 && colon < 50) {
                bodyStart = colon + 2;
            }
        }
        Pattern mention = mentions && bodyStart >= 0 && !me.isEmpty()
                ? Pattern.compile("(?<!\\w)@?" + Pattern.quote(me) + "(?!\\w)", Pattern.CASE_INSENSITIVE)
                : null;

        MutableComponent out = Component.empty();
        if (stamps) {
            out.append(Component.literal("[" + LocalTime.now().format(CLOCK) + "] ")
                    .withStyle(Style.EMPTY.withColor(0x636363)));
        }
        final int[] offset = {0};
        final boolean[] mentioned = {false};
        final int body = bodyStart;
        line.visit((style, text) -> {
            append(out, style, text, offset[0], body, links, coords, mention, mentioned);
            offset[0] += text.length();
            return Optional.empty();
        }, Style.EMPTY);

        if (mentioned[0] && option(MENTION_SOUND, true)) {
            mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.2F));
        }
        return out;
    }

    /** Adds one piece of the line, split wherever a link, coordinates or a mention sit inside it. */
    private static void append(MutableComponent out, Style style, String text, int offset, int body,
                               boolean links, boolean coords, Pattern mention, boolean[] mentioned) {
        List<Hit> hits = new ArrayList<>();
        if (links && style.getClickEvent() == null) {
            Matcher m = URL.matcher(text);
            while (m.find()) {
                int end = m.end();
                while (end > m.start() + 8 && ".,;:!?)'\"".indexOf(text.charAt(end - 1)) >= 0) {
                    end--;
                }
                hits.add(new Hit(m.start(), end, Kind.LINK, text.substring(m.start(), end)));
            }
        }
        if (coords && style.getClickEvent() == null) {
            Matcher m = COORD.matcher(text);
            while (m.find()) {
                try {
                    int x = Integer.parseInt(m.group(1));
                    int y = Integer.parseInt(m.group(2));
                    int z = Integer.parseInt(m.group(3));
                    if (y >= -64 && y <= 320 && (Math.abs(x) >= 10 || Math.abs(z) >= 10)) {
                        hits.add(new Hit(m.start(), m.end(), Kind.COORD, x + " " + y + " " + z));
                    }
                } catch (NumberFormatException e) {
                    // too big to be a coordinate
                }
            }
        }
        if (mention != null) {
            Matcher m = mention.matcher(text);
            while (m.find()) {
                if (offset + m.start() >= body) {
                    hits.add(new Hit(m.start(), m.end(), Kind.MENTION, ""));
                }
            }
        }
        if (hits.isEmpty()) {
            out.append(Component.literal(text).withStyle(style));
            return;
        }
        hits.sort((a, b) -> Integer.compare(a.start(), b.start()));
        int at = 0;
        for (Hit h : hits) {
            if (h.start() < at) {
                continue; // overlaps one already used
            }
            if (h.start() > at) {
                out.append(Component.literal(text.substring(at, h.start())).withStyle(style));
            }
            String piece = text.substring(h.start(), h.end());
            Style s = switch (h.kind()) {
                case LINK -> style.withUnderlined(true)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, h.value()))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("Open link")));
                case COORD -> style.withUnderlined(true)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, h.value()))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("Click to copy")));
                case MENTION -> {
                    mentioned[0] = true;
                    yield style.withBold(true).withUnderlined(true);
                }
            };
            out.append(Component.literal(piece).withStyle(s));
            at = h.end();
        }
        if (at < text.length()) {
            out.append(Component.literal(text.substring(at)).withStyle(style));
        }
    }
}
