package khi.pentecost.boot;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public final class Splash {
    private static final int BG = 0x0A0A0B;
    private static final int TEXT = 0xF2F2F2;
    private static final int MUTED = 0x8A8A8A;
    private static final int FAINT = 0x3A3A3A;
    private static final int W = 640;
    private static final int H = 360;

    private static volatile javax.swing.JWindow window;
    private static volatile boolean dropped;
    private static volatile boolean queued;
    private static volatile javax.swing.Timer timer;

    private static final int ROW = 30;
    private static volatile String override;
    private static volatile java.util.List<khi.pentecost.install.MissingDeps.Offer> asking;
    private static volatile boolean[] picked;
    private static volatile long askStart;
    private static volatile long askTimeout;
    private static volatile java.util.concurrent.CompletableFuture<java.util.List<String>> answer;
    private static volatile int panelH;
    private static float panelShown;
    private static final java.util.List<int[]> rowHits = new java.util.ArrayList<>();
    private static int[] addHit;
    private static int[] skipHit;
    private static final java.awt.Font SMALL = new java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 12);

    public static void status(String text) {
        override = text;
    }

    public static boolean canAsk() {
        for (int i = 0; i < 20 && window == null; i++) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return window != null;
    }

    public static java.util.List<String> askToAdd(java.util.List<khi.pentecost.install.MissingDeps.Offer> offers, long timeoutMs) {
        if (window == null || offers.isEmpty()) {
            return null;
        }
        boolean[] p = new boolean[offers.size()];
        for (int i = 0; i < p.length; i++) {
            p[i] = offers.get(i).pick() != null;
        }
        java.util.concurrent.CompletableFuture<java.util.List<String>> f = new java.util.concurrent.CompletableFuture<>();
        picked = p;
        answer = f;
        askTimeout = timeoutMs;
        askStart = System.currentTimeMillis();
        asking = offers;
        panelH = 74 + Math.min(6, offers.size()) * ROW + 46;
        override = "Missing mods: add them below";
        try {
            return f.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            Log.info("Missing mods: no answer in time, asking again next start");
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (java.util.concurrent.ExecutionException e) {
            return null;
        } finally {
            asking = null;
            panelH = 0;
            override = null;
        }
    }

    private static boolean panelClick(int x, int y) {
        java.util.List<khi.pentecost.install.MissingDeps.Offer> offers = asking;
        java.util.concurrent.CompletableFuture<java.util.List<String>> f = answer;
        if (offers == null || f == null || y < H) {
            return false;
        }
        if (in(addHit, x, y)) {
            java.util.List<String> ids = new java.util.ArrayList<>();
            for (int i = 0; i < offers.size(); i++) {
                if (picked[i]) {
                    ids.add(offers.get(i).id());
                }
            }
            f.complete(ids);
        } else if (in(skipHit, x, y)) {
            f.complete(java.util.List.of());
        } else {
            for (int[] r : rowHits) {
                if (in(r, x, y) && offers.get(r[4]).pick() != null) {
                    picked[r[4]] = !picked[r[4]];
                }
            }
        }
        return true;
    }

    private static boolean in(int[] r, int x, int y) {
        return r != null && x >= r[0] && x < r[0] + r[2] && y >= r[1] && y < r[1] + r[3];
    }

    private static void drawPanel(Graphics2D g, int height) {
        java.util.List<khi.pentecost.install.MissingDeps.Offer> offers = asking;
        rowHits.clear();
        addHit = null;
        skipHit = null;
        if (height <= 0) {
            return;
        }
        fill(g, 0, H, W, H + height, BG);
        panelDither(g, System.currentTimeMillis(), height);
        fill(g, 40, H, W - 40, H + 1, FAINT);
        if (offers == null) {
            return;
        }
        int y = H + 18;
        pixelText(g, "MISSING MODS", 40, y, 2, TEXT);
        g.setFont(SMALL);
        g.setColor(new Color(MUTED));
        g.drawString("These mods are needed by mods in your pack. Pick which to add from Modrinth.", 40, y + 30);
        y += 44;
        int shown = Math.min(6, offers.size());
        for (int i = 0; i < shown; i++) {
            khi.pentecost.install.MissingDeps.Offer o = offers.get(i);
            boolean can = o.pick() != null;
            int box = y + 4;
            if (can) {
                fill(g, 40, box, 52, box + 12, picked[i] ? TEXT : FAINT);
                if (!picked[i]) {
                    fill(g, 41, box + 1, 51, box + 11, BG);
                }
            } else {
                fill(g, 40, box + 5, 52, box + 7, FAINT);
            }
            g.setColor(new Color(can ? TEXT : MUTED));
            g.drawString(o.title() + (can ? "   " + o.pick().filename() : "   not on Modrinth for this version"), 62, y + 13);
            g.setColor(new Color(MUTED));
            g.drawString("needed by " + String.join(", ", o.neededBy()), 62, y + 26);
            rowHits.add(new int[]{36, y, W - 76, ROW, i});
            y += ROW;
        }
        if (offers.size() > shown) {
            g.setColor(new Color(MUTED));
            g.drawString("and " + (offers.size() - shown) + " more", 62, y + 10);
        }
        int by = H + height - 34;
        int addW = 120;
        fill(g, 40, by, 40 + addW, by + 22, TEXT);
        pixelText(g, "ADD", 40 + (addW - pixelWidth("ADD", 1)) / 2, by + 8, 1, BG);
        addHit = new int[]{40, by, addW, 22};
        int sx = 40 + addW + 12;
        fill(g, sx, by, sx + addW, by + 22, FAINT);
        fill(g, sx + 1, by + 1, sx + addW - 1, by + 21, BG);
        pixelText(g, "SKIP", sx + (addW - pixelWidth("SKIP", 1)) / 2, by + 8, 1, MUTED);
        skipHit = new int[]{sx, by, addW, 22};
        long left = Math.max(0, askTimeout - (System.currentTimeMillis() - askStart));
        String t = (left / 1000) + " S";
        pixelText(g, t, W - 40 - pixelWidth(t, 1), by + 8, 1, MUTED);
    }
    private static final long STARTED = System.currentTimeMillis();

    private Splash() {}

    public static synchronized void open() {
        if (window != null || System.getenv("PENTECOST_NO_SPLASH") != null) {
            return;
        }
        try {
            if (GraphicsEnvironment.isHeadless()) {
                Log.info("No start window: Java runs without a screen here");
                return;
            }
            queued = true;
            javax.swing.SwingUtilities.invokeLater(Splash::build);
        } catch (Throwable t) {
            Log.warn("No start window: " + t);
        }
    }

    private static void build() {
        if (dropped) {
            return;
        }
        try {
            javax.swing.JWindow w = new javax.swing.JWindow();
            w.setFocusableWindowState(false);
            w.setAutoRequestFocus(false);
            w.setAlwaysOnTop(true);
            w.setSize(W, H);
            w.setLocationRelativeTo(null);
            w.setBackground(new Color(BG));
            javax.swing.JComponent pane = new javax.swing.JComponent() {
                @Override
                protected void paintComponent(Graphics g) {
                    Splash.draw((Graphics2D) g);
                }
            };
            pane.setDoubleBuffered(true);
            java.awt.Point[] grab = new java.awt.Point[1];
            java.awt.event.MouseAdapter drag = new java.awt.event.MouseAdapter() {
                @Override
                public void mousePressed(java.awt.event.MouseEvent e) {
                    if (panelClick(e.getX(), e.getY())) {
                        grab[0] = null;
                        return;
                    }
                    grab[0] = e.getPoint();
                }

                @Override
                public void mouseDragged(java.awt.event.MouseEvent e) {
                    if (grab[0] != null) {
                        java.awt.Point at = e.getLocationOnScreen();
                        w.setLocation(at.x - grab[0].x, at.y - grab[0].y);
                    }
                }

                @Override
                public void mouseReleased(java.awt.event.MouseEvent e) {
                    grab[0] = null;
                }
            };
            pane.addMouseListener(drag);
            pane.addMouseMotionListener(drag);
            w.setContentPane(pane);
            try {
                w.setOpacity(0f);
            } catch (RuntimeException ignored) {
            }
            w.setVisible(true);
            window = w;
            Log.info("Start window shown");
            javax.swing.Timer t = new javax.swing.Timer(16, e -> {
                javax.swing.JWindow cur = window;
                if (cur != null) {
                    float target = panelH > 0 ? 1f : 0f;
                    panelShown += (target - panelShown) * 0.18f;
                    if (Math.abs(target - panelShown) < 0.01f) {
                        panelShown = target;
                    }
                    int wantH = H + Math.round(Math.max(panelH, lastPanelH) * panelShown);
                    if (panelH > 0) {
                        lastPanelH = panelH;
                    }
                    if (cur.getHeight() != wantH) {
                        cur.setSize(W, wantH);
                    }
                    fadeTo(cur, Math.min(1f, (System.currentTimeMillis() - STARTED) / 250f));
                    cur.getContentPane().repaint();
                    java.awt.Toolkit.getDefaultToolkit().sync();
                }
                if (System.currentTimeMillis() - STARTED > 120_000 && asking == null) {
                    close();
                }
            });
            t.setCoalesce(true);
            t.start();
            timer = t;
        } catch (Throwable t) {
            Log.warn("No start window: " + t);
        }
    }

    private static void fill(Graphics2D g, int x0, int y0, int x1, int y1, int rgb) {
        g.setColor(new Color(rgb));
        g.fillRect(x0, y0, x1 - x0, y1 - y0);
    }

    private static int lastPanelH;

    private static void draw(Graphics2D g) {
        long now = System.currentTimeMillis();
        javax.swing.JWindow cur = window;
        if (cur != null && cur.getHeight() > H) {
            drawPanel(g, cur.getHeight() - H);
            g.setClip(0, 0, W, H);
        }
        fill(g, 0, 0, W, H, BG);
        dither(g, now);

        int px = 2;
        int markW = (LOGO_W + 5) * px + wordmarkWidth("PENTECOST", px);
        int x = (W - markW) / 2;
        int y = H * 42 / 100 - 14 * px;
        logo(g, x, y - px, px, TEXT, now);
        wordmark(g, "PENTECOST", x + (LOGO_W + 5) * px, y, px, TEXT);

        int ly = y + 14 * px + 18;
        fill(g, x, ly, x + markW, ly + 1, FAINT);
        double phase = ((now - STARTED) % 1800) / 1800.0;
        int seg = markW / 5;
        int sx = x + (int) ((markW - seg) * (0.5 - 0.5 * Math.cos(phase * Math.PI * 2)));
        fill(g, sx, ly, sx + seg, ly + 1, TEXT);

        String status = status();
        pixelText(g, status, (W - pixelWidth(status, 1)) / 2, ly + 10, 1, MUTED);

        Runtime rt = Runtime.getRuntime();
        double used = (rt.totalMemory() - rt.freeMemory()) / 1073741824.0;
        double max = rt.maxMemory() / 1073741824.0;
        double share = Math.min(1.0, used / Math.max(0.1, max));
        int bx = 40;
        int by = H - 40;
        pixelText(g, "RAM " + Math.round(share * 100) + "%", bx, by - 2, 1, MUTED);
        int lit = (int) Math.round(share * 16);
        for (int s = 0; s < 16; s++) {
            fill(g, bx + s * 9, by + 12, bx + s * 9 + 7, by + 20, s < lit ? TEXT : FAINT);
        }
        String time = ((now - STARTED) / 1000) + " S";
        pixelText(g, time, W - 40 - pixelWidth(time, 1), by, 1, MUTED);
    }

    private static final int[][] BAYER = {{0, 8, 2, 10}, {12, 4, 14, 6}, {3, 11, 1, 9}, {15, 7, 13, 5}};
    private static final int CELL = 3;
    private static java.awt.image.BufferedImage ditherImage;

    private static void dither(Graphics2D g, long now) {
        int cols = (W + CELL - 1) / CELL;
        int rows = (H + CELL - 1) / CELL;
        if (ditherImage == null) {
            ditherImage = new java.awt.image.BufferedImage(cols, rows, java.awt.image.BufferedImage.TYPE_INT_RGB);
        }
        int dot = mix(0xFFFFFF, BG, 16 / 255.0);
        double t = now * 0.0012;
        for (int y = 0; y < rows; y++) {
            for (int x = 0; x < cols; x++) {
                double v = Math.pow((double) x / cols, 2.2) * 0.40 + 0.05 * Math.sin(x * 0.5 + y * 0.3 - t);
                ditherImage.setRGB(x, y, v * 16 > BAYER[y & 3][x & 3] ? dot : BG);
            }
        }
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(ditherImage, 0, 0, cols * CELL, rows * CELL, null);
    }

    private static java.awt.image.BufferedImage panelImage;

    private static void panelDither(Graphics2D g, long now, int height) {
        int cols = (W + CELL - 1) / CELL;
        int rows = (height + CELL - 1) / CELL;
        if (rows <= 0) {
            return;
        }
        if (panelImage == null || panelImage.getHeight() < rows) {
            panelImage = new java.awt.image.BufferedImage(cols, rows, java.awt.image.BufferedImage.TYPE_INT_RGB);
        }
        int above = (H + CELL - 1) / CELL;
        int dot = mix(0xFFFFFF, BG, 16 / 255.0);
        double t = now * 0.0012;
        for (int y = 0; y < rows; y++) {
            int gy = above + y;
            double down = 1.0 - 0.9 * y / (double) Math.max(1, rows - 1);
            for (int x = 0; x < cols; x++) {
                double v = (Math.pow((double) x / cols, 2.2) * 0.40 + 0.05 * Math.sin(x * 0.5 + gy * 0.3 - t)) * down;
                panelImage.setRGB(x, y, v * 16 > BAYER[gy & 3][x & 3] ? dot : BG);
            }
        }
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(panelImage, 0, H, cols * CELL, H + rows * CELL, 0, 0, cols, rows, null);
    }

    static java.awt.image.BufferedImage preview() {
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(W, H, java.awt.image.BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        draw(g);
        g.dispose();
        return img;
    }

    private static String status() {
        String o = override;
        if (o != null) {
            return o.toUpperCase(Locale.ROOT);
        }
        try {
            Map<String, Double> marks = khi.pentecost.core.Timeline.marks();
            String last = null;
            for (String k : marks.keySet()) {
                last = k;
            }
            if (last == null) {
                return "STARTING PENTECOST";
            }
            return switch (last) {
                case "Loader ready, game starting" -> "STARTING THE GAME";
                case "Game object created" -> "BUILDING MODS";
                case "Forge mods built" -> "STARTING FABRIC AND QUILT MODS";
                default -> last.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9% ]", "");
            };
        } catch (Throwable t) {
            return "STARTING PENTECOST";
        }
    }

    private static final int LOGO_W = 16;
    private static final String[] BOAT = {
            ".....#......",
            ".....##.....",
            ".....#.#....",
            ".....#..#...",
            "..#..#...#..",
            ".##..#....#.",
            "#.#..#######",
            "###..#......",
            ".....#......",
            "############",
            ".##########."
    };

    private static void logo(Graphics2D g, int x, int y, int px, int color, long now) {
        double t = now / 1000.0;
        for (int r = 0; r < BOAT.length; r++) {
            String row = BOAT[r];
            int run = -1;
            for (int c = 0; c <= row.length(); c++) {
                boolean lit = c < row.length() && row.charAt(c) == '#';
                if (lit && run < 0) {
                    run = c;
                } else if (!lit && run >= 0) {
                    fill(g, x + (run + 2) * px, y + r * px, x + (c + 2) * px, y + (r + 1) * px, color);
                    run = -1;
                }
            }
        }
        for (int line = 0; line < 2; line++) {
            int base = 10 + line * 2;
            int shade = line == 0 ? color : mix(color, BG, 0.4);
            for (int c = 0; c < LOGO_W; c++) {
                double wave = Math.sin(c * 0.9 - t * 0.9 + line * 1.7);
                int dy = wave > 0.55 ? -1 : 0;
                fill(g, x + c * px, y + (base + dy) * px, x + (c + 1) * px, y + (base + dy + 1) * px, shade);
            }
        }
    }

    private static int mix(int a, int b, double share) {
        int r = (int) (((a >> 16) & 0xFF) * share + ((b >> 16) & 0xFF) * (1 - share));
        int gg = (int) (((a >> 8) & 0xFF) * share + ((b >> 8) & 0xFF) * (1 - share));
        int bl = (int) ((a & 0xFF) * share + (b & 0xFF) * (1 - share));
        return (r << 16) | (gg << 8) | bl;
    }

    private static int wordmarkWidth(String word, int px) {
        return Math.max(0, word.length() * 12 - 2 + 4) * px;
    }

    private static void wordmark(Graphics2D g, String word, int x, int y, int px, int color) {
        int cx = x;
        for (char ch : word.toUpperCase(Locale.ROOT).toCharArray()) {
            boolean[][] f = fine(ch);
            if (f != null) {
                for (int r = 0; r < 14; r++) {
                    int shift = (13 - r) / 3;
                    int run = -1;
                    for (int c = 0; c <= 10; c++) {
                        boolean lit = c < 10 && f[r][c];
                        if (lit && run < 0) {
                            run = c;
                        } else if (!lit && run >= 0) {
                            fill(g, cx + (run + shift) * px, y + r * px, cx + (c + shift) * px, y + (r + 1) * px, color);
                            run = -1;
                        }
                    }
                }
            }
            cx += 12 * px;
        }
    }

    private static int pixelWidth(String text, int px) {
        return Math.max(0, text.length() * 6 - 1) * px;
    }

    private static void pixelText(Graphics2D g, String text, int x, int y, int px, int color) {
        int cx = x;
        for (char ch : text.toUpperCase(Locale.ROOT).toCharArray()) {
            String[] glyph = GLYPHS.get(ch);
            if (glyph != null) {
                for (int r = 0; r < 7; r++) {
                    for (int c = 0; c < 5; c++) {
                        if (glyph[r].charAt(c) == '1') {
                            fill(g, cx + c * px, y + r * px, cx + (c + 1) * px, y + (r + 1) * px, color);
                        }
                    }
                }
            }
            cx += 6 * px;
        }
    }

    private static final Map<Character, boolean[][]> FINE = new HashMap<>();

    private static boolean[][] fine(char ch) {
        return FINE.computeIfAbsent(ch, c -> {
            String[] g = GLYPHS.get(c);
            if (g == null) {
                return null;
            }
            boolean[][] f = new boolean[14][10];
            for (int r = 0; r < 7; r++) {
                for (int col = 0; col < 5; col++) {
                    if (g[r].charAt(col) == '1') {
                        f[r * 2][col * 2] = f[r * 2][col * 2 + 1] = f[r * 2 + 1][col * 2] = f[r * 2 + 1][col * 2 + 1] = true;
                    }
                }
            }
            for (int r = 0; r < 7; r++) {
                for (int col = 0; col < 5; col++) {
                    if (on(g, r, col)) {
                        continue;
                    }
                    for (int dr = -1; dr <= 1; dr += 2) {
                        for (int dc = -1; dc <= 1; dc += 2) {
                            if (on(g, r + dr, col) && on(g, r, col + dc)) {
                                f[r * 2 + (dr > 0 ? 1 : 0)][col * 2 + (dc > 0 ? 1 : 0)] = true;
                            }
                        }
                    }
                }
            }
            return f;
        });
    }

    private static boolean on(String[] g, int r, int c) {
        return r >= 0 && r < 7 && c >= 0 && c < 5 && g[r].charAt(c) == '1';
    }

    private static final Map<Character, String[]> GLYPHS = new HashMap<>();
    static {
        GLYPHS.put('A', new String[]{"01110", "10001", "10001", "11111", "10001", "10001", "10001"});
        GLYPHS.put('C', new String[]{"01111", "10000", "10000", "10000", "10000", "10000", "01111"});
        GLYPHS.put('D', new String[]{"11110", "10001", "10001", "10001", "10001", "10001", "11110"});
        GLYPHS.put('E', new String[]{"11111", "10000", "10000", "11110", "10000", "10000", "11111"});
        GLYPHS.put('F', new String[]{"11111", "10000", "10000", "11110", "10000", "10000", "10000"});
        GLYPHS.put('I', new String[]{"11111", "00100", "00100", "00100", "00100", "00100", "11111"});
        GLYPHS.put('M', new String[]{"10001", "11011", "10101", "10101", "10001", "10001", "10001"});
        GLYPHS.put('N', new String[]{"10001", "11001", "10101", "10101", "10011", "10001", "10001"});
        GLYPHS.put('O', new String[]{"01110", "10001", "10001", "10001", "10001", "10001", "01110"});
        GLYPHS.put('P', new String[]{"11110", "10001", "10001", "11110", "10000", "10000", "10000"});
        GLYPHS.put('R', new String[]{"11110", "10001", "10001", "11110", "10100", "10010", "10001"});
        GLYPHS.put('S', new String[]{"01111", "10000", "10000", "01110", "00001", "00001", "11110"});
        GLYPHS.put('T', new String[]{"11111", "00100", "00100", "00100", "00100", "00100", "00100"});
        GLYPHS.put('U', new String[]{"10001", "10001", "10001", "10001", "10001", "10001", "01110"});
        GLYPHS.put('B', new String[]{"11110", "10001", "10001", "11110", "10001", "10001", "11110"});
        GLYPHS.put('G', new String[]{"01111", "10000", "10000", "10011", "10001", "10001", "01111"});
        GLYPHS.put('H', new String[]{"10001", "10001", "10001", "11111", "10001", "10001", "10001"});
        GLYPHS.put('J', new String[]{"00111", "00010", "00010", "00010", "00010", "10010", "01100"});
        GLYPHS.put('K', new String[]{"10001", "10010", "10100", "11000", "10100", "10010", "10001"});
        GLYPHS.put('L', new String[]{"10000", "10000", "10000", "10000", "10000", "10000", "11111"});
        GLYPHS.put('Q', new String[]{"01110", "10001", "10001", "10001", "10101", "10010", "01101"});
        GLYPHS.put('V', new String[]{"10001", "10001", "10001", "10001", "10001", "01010", "00100"});
        GLYPHS.put('W', new String[]{"10001", "10001", "10001", "10101", "10101", "10101", "01010"});
        GLYPHS.put('X', new String[]{"10001", "10001", "01010", "00100", "01010", "10001", "10001"});
        GLYPHS.put('Y', new String[]{"10001", "10001", "01010", "00100", "00100", "00100", "00100"});
        GLYPHS.put('Z', new String[]{"11111", "00001", "00010", "00100", "01000", "10000", "11111"});
        GLYPHS.put('0', new String[]{"01110", "10001", "10011", "10101", "11001", "10001", "01110"});
        GLYPHS.put('1', new String[]{"00100", "01100", "00100", "00100", "00100", "00100", "01110"});
        GLYPHS.put('2', new String[]{"01110", "10001", "00001", "00010", "00100", "01000", "11111"});
        GLYPHS.put('3', new String[]{"11110", "00001", "00001", "01110", "00001", "00001", "11110"});
        GLYPHS.put('4', new String[]{"00010", "00110", "01010", "10010", "11111", "00010", "00010"});
        GLYPHS.put('5', new String[]{"11111", "10000", "11110", "00001", "00001", "10001", "01110"});
        GLYPHS.put('6', new String[]{"00110", "01000", "10000", "11110", "10001", "10001", "01110"});
        GLYPHS.put('7', new String[]{"11111", "00001", "00010", "00100", "01000", "01000", "01000"});
        GLYPHS.put('8', new String[]{"01110", "10001", "10001", "01110", "10001", "10001", "01110"});
        GLYPHS.put('9', new String[]{"01110", "10001", "10001", "01111", "00001", "00010", "01100"});
        GLYPHS.put('%', new String[]{"11001", "11010", "00010", "00100", "01000", "01011", "10011"});
    }

    public static void closeNow() {
        dropped = true;
        if (!queued) {
            return;
        }
        Runnable gone = () -> {
            javax.swing.JWindow w = window;
            window = null;
            javax.swing.Timer t = timer;
            if (t != null) {
                t.stop();
            }
            if (w != null) {
                w.setVisible(false);
                w.dispose();
            }
        };
        try {
            if (javax.swing.SwingUtilities.isEventDispatchThread()) {
                gone.run();
            } else {
                javax.swing.SwingUtilities.invokeAndWait(gone);
            }
        } catch (Exception ignored) {
        }
    }

    public static void close() {
        javax.swing.JWindow w = window;
        if (w == null) {
            return;
        }
        window = null;
        javax.swing.SwingUtilities.invokeLater(() -> {
            javax.swing.Timer t = timer;
            if (t != null) {
                t.stop();
            }
            long from = System.currentTimeMillis();
            javax.swing.Timer out = new javax.swing.Timer(16, null);
            out.addActionListener(e -> {
                float a = 1f - (System.currentTimeMillis() - from) / 250f;
                if (a <= 0f) {
                    out.stop();
                    w.setVisible(false);
                    w.dispose();
                } else {
                    fadeTo(w, a);
                    w.getContentPane().repaint();
                }
            });
            out.start();
        });
    }

    private static void fadeTo(javax.swing.JWindow w, float a) {
        try {
            w.setOpacity(Math.max(0f, Math.min(1f, a)));
        } catch (RuntimeException ignored) {
        }
    }
}
