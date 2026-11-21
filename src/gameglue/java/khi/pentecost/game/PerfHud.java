package khi.pentecost.game;

import com.mojang.blaze3d.platform.InputConstants;
import khi.pentecost.boot.PentecostMain;
import khi.pentecost.core.PartProbe;
import khi.pentecost.core.PentecostRuntime;
import khi.pentecost.core.Timeline;
import khi.pentecost.mods.LoaderKind;
import khi.pentecost.mods.ModInfo;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public final class PerfHud {
    private static final int KEY_F6 = 295;
    private static boolean shown = "1".equals(System.getenv("PENTECOST_TEST_HUD"));
    private static boolean keyWasDown;
    private static boolean exportTested;
    private static long lastFrame;
    private static double frameMs;
    private static double workMs;

    public static double workMs() {
        return workMs;
    }
    private static final double[] HIST = new double[240];
    private static int histAt;

    private static int panelW;
    private static long smallerSince;
    private static double lastScale;

    private record Section(String title, List<String[]> rows) {}

    private PerfHud() {}

    public static void frame() {
        Minecraft mc = Minecraft.getInstance();
        long now = System.nanoTime();
        if (lastFrame != 0) {
            double instant = (now - lastFrame) / 1_000_000.0;
            frameMs = frameMs * 0.9 + instant * 0.1;
            double work = Math.max(0, instant - khi.pentecost.core.ClientHooks.takeSleep() / 1_000_000.0);
            workMs = workMs * 0.9 + work * 0.1;
            khi.pentecost.core.LodBridge.frameMs = frameMs;
            khi.pentecost.core.LodBridge.frameLimit = mc.options.framerateLimit().get() >= 260 ? 0 : mc.options.framerateLimit().get();
            HIST[histAt++ % HIST.length] = instant;
            if (PartProbe.on) {
                PerfDips.record(instant, work);
            }
            Telemetry.frame(mc, instant, work);
        }
        lastFrame = now;
        if (mc.level != null) {
            Timeline.mark("In the world");
            if (!exportTested && "1".equals(System.getenv("PENTECOST_TEST_EXPORT"))) {
                exportTested = true;
                try {
                    khi.pentecost.boot.Log.info("Test: pack exported to " + khi.pentecost.control.PackExport.export());
                } catch (java.io.IOException | RuntimeException e) {
                    khi.pentecost.boot.Log.error("Test: pack export failed: " + e);
                }
            }
        }
        boolean down = InputConstants.isKeyDown(mc.getWindow().getWindow(), KEY_F6);
        if (down && !keyWasDown && mc.screen == null) {
            shown = !shown;
            if (shown) {
                PerfDips.reset();
            }
        }
        keyWasDown = down;
        float e = Ui.ease("pentecost:f6", shown && !mc.options.hideGui ? 1f : 0f, 12f);
        boolean probing = e > 0.001f || Telemetry.on();
        khi.pentecost.core.CostMeter.setEnabled(probing);
        PartProbe.frame(probing);
        if (probing) {
            khi.pentecost.core.GenWatch.ensureRunning();
        }
        if (e <= 0.001f) {
            DebugView.f6Left = -1;
            DebugView.render(0f);
            return;
        }

        List<Section> sections = sections(mc);
        if (mc.options.renderDebug) {
            sections.removeIf(s -> !s.title().equals("FRAME") && !s.title().equals("CHUNKS"));
        }

        Font font = mc.font;
        double scale = mc.getWindow().getGuiScale();
        if (scale != lastScale) {
            lastScale = scale;
            panelW = 0;
        }
        float ui = scale > 2 ? (float) (2.0 / scale) : 1f;
        int vw = (int) (mc.getWindow().getGuiScaledWidth() / ui);
        int vh = (int) (mc.getWindow().getGuiScaledHeight() / ui);

        int want = 176;
        for (Section s : sections) {
            int labelW = font.width(s.title()) + 10;
            int valueW = 0;
            int keyW = 0;
            for (String[] r : s.rows()) {
                keyW = Math.max(keyW, font.width(r[0]));
                valueW = Math.max(valueW, font.width(r[1]));
            }
            want = Math.max(want, Math.max(labelW, keyW + 14 + valueW + 12));
        }
        long wallNow = System.currentTimeMillis();
        if (want >= panelW) {
            panelW = want;
            smallerSince = 0;
        } else if (smallerSince == 0) {
            smallerSince = wallNow;
        } else if (wallNow - smallerSince > 3000) {
            panelW = want;
            smallerSince = 0;
        }
        int w = panelW;
        int bigH = 30;
        int sparkH = 22;
        int top = 8;
        int headerH = 15 + bigH + 2 + sparkH + 6;
        int bottomLimit = vh - 6;

        List<List<Section>> columns = new ArrayList<>();
        List<Section> col = new ArrayList<>();
        columns.add(col);
        int y = top + headerH;
        for (Section s : sections) {
            int sh = sectionHeight(s);
            if (y + sh > bottomLimit && !col.isEmpty()) {
                col = new ArrayList<>();
                columns.add(col);
                y = top + 15;
            }
            col.add(s);
            y += sh;
        }
        int gap = 6;
        int totalW = columns.size() * w + (columns.size() - 1) * gap;
        float slide = 1f - (1f - e) * (1f - e);
        int offset = Math.round((1f - slide) * (totalW + 16));
        int right = vw - 8 + offset;
        int alpha = Math.round(e * 255);

        int lowest = 0;
        for (int c = 0; c < columns.size(); c++) {
            int cy = c == 0 ? top + headerH : top + 15;
            for (Section s : columns.get(c)) {
                cy += sectionHeight(s);
            }
            lowest = Math.max(lowest, cy);
        }
        DebugView.f6Left = (vw - 8 - totalW) * ui;
        DebugView.render((lowest + 4) * ui * slide);

        GuiGraphics g = GuiBatch.on(new GuiGraphics(mc, mc.renderBuffers().bufferSource()));
        g.pose().pushPose();
        g.pose().translate(0, 0, 210);
        g.pose().scale(ui, ui, 1);
        int back = fade(0xB00A0A0B, alpha);
        int backLight = fade(0xC01A1A1C, alpha);
        int x = right - w;
        String title = "PENTECOST " + PentecostMain.VERSION;
        int tw = font.width(title) + 8;
        g.fill(right - tw, top, right, top + 11, fade(0xFFF2F2F2, alpha));
        text(g, font, title, right - tw + 4, top + 2, fade(0xFF0A0A0B, alpha));
        int cy = top + 15;
        String fps = String.valueOf(mc.getFps());
        g.fill(x, cy, x + w, cy + bigH, back);
        Ui.text(g, font, fps, x + 6, cy + 3, fade(0xFFF2F2F2, alpha), 3f);
        int afterBig = x + 10 + font.width(fps) * 3;
        text(g, font, "fps", afterBig, cy + bigH - 12, fade(0xFF636363, alpha));
        String ms = String.format("%.1f ms", frameMs);
        text(g, font, ms, x + w - 6 - font.width(ms), cy + bigH - 12, fade(0xFF9A9A9A, alpha));
        int sy = cy + bigH + 2;
        g.fill(x, sy, x + w, sy + sparkH, back);
        int samples = (w - 4) / 2;
        for (int i = 0; i < samples; i++) {
            double v = HIST[Math.floorMod(histAt - samples + i, HIST.length)];
            if (v <= 0) {
                continue;
            }
            int bh = (int) Math.min(sparkH - 2, v / 33.3 * (sparkH - 2));
            int bx = x + 2 + i * 2;
            g.fill(bx, sy + sparkH - 1 - Math.max(1, bh), bx + 1, sy + sparkH - 1,
                    fade(v > 25 ? 0xFFF2F2F2 : 0xFF9A9A9A, alpha));
        }
        int line60 = sy + sparkH - 1 - (int) (16.7 / 33.3 * (sparkH - 2));
        for (int dx = x + 2; dx < x + w - 2; dx += 6) {
            g.fill(dx, line60, dx + 2, line60 + 1, fade(0x66FFFFFF, alpha));
        }

        for (int c = 0; c < columns.size(); c++) {
            int cx = right - w - c * (w + gap);
            int ry = c == 0 ? top + headerH : top + 15;
            for (Section s : columns.get(c)) {
                ry = drawSection(g, font, s, cx, ry, w, back, backLight, alpha);
            }
        }
        g.pose().popPose();
        g.flush();
    }

    private static int sectionHeight(Section s) {
        return 11 + s.rows().size() * 10 + 5;
    }

    private static int drawSection(GuiGraphics g, Font font, Section s, int x, int y, int w, int back, int backLight,
                                   int alpha) {
        g.fill(x, y, x + w, y + 11, back);
        g.fill(x, y, x + 2, y + 11, fade(0xFFF2F2F2, alpha));
        text(g, font, s.title(), x + 6, y + 2, fade(0xFFF2F2F2, alpha));
        int keyW = 0;
        for (String[] r : s.rows()) {
            keyW = Math.max(keyW, font.width(r[0]));
        }
        int ry = y + 11;
        int i = 0;
        for (String[] r : s.rows()) {
            g.fill(x, ry, x + w, ry + 10, i++ % 2 == 0 ? back : backLight);
            boolean sub = r[0].startsWith("  ");
            int labelColor = sub ? 0xFF6E6E6E : 0xFF9A9A9A;
            int valueColor = sub ? 0xFFC8C8C8 : 0xFFF2F2F2;
            text(g, font, r[0], x + 6, ry + 1, fade(labelColor, alpha));
            text(g, font, r[1], x + 6 + keyW + 14, ry + 1, fade(valueColor, alpha));
            ry += 10;
        }
        return ry + 5;
    }

    static List<String[]> telemetryRows(Minecraft mc) {
        List<String[]> out = new ArrayList<>();
        for (Section s : sections(mc)) {
            for (String[] r : s.rows()) {
                out.add(new String[]{s.title(), r[0].strip(), r[1]});
            }
        }
        return out;
    }

    private static List<Section> sections(Minecraft mc) {
        List<Section> out = new ArrayList<>();

        List<String[]> frame = new ArrayList<>();
        frame.add(new String[]{"Work per frame", ms(workMs)});
        frame.add(new String[]{"Slowest 1% of frames", ms(slowest(0.01))});
        frame.add(new String[]{"Waiting on GPU", ms(khi.pentecost.core.FrameProbe.gpuWaitMs())});
        double render = PartProbe.frameMs(PartProbe.RENDER);
        double ticks = PartProbe.frameMs(PartProbe.CLIENT_TICK);
        frame.add(new String[]{"Game ticks", ms(ticks)});
        frame.add(new String[]{"Drawing, all of it", ms(render)});
        frame.add(new String[]{"Everything else", ms(Math.max(0, workMs - render - ticks))});
        double world = PartProbe.frameMs(PartProbe.WORLD);
        frame.add(new String[]{"World draw", ms(world)});
        double parts = 0;
        int[] order = {PartProbe.TERRAIN, PartProbe.CULL, PartProbe.UPLOAD, PartProbe.ENTITIES,
                PartProbe.BLOCK_ENTITIES, PartProbe.PARTICLES, PartProbe.SKY, PartProbe.SHADOW};
        String[] names = {"Terrain", "Chunk culling", "Chunk uploads", "Entities", "Block entities", "Particles",
                "Sky and weather", "Shadow pass"};
        for (int i = 0; i < order.length; i++) {
            double v = PartProbe.frameMs(order[i]);
            parts += v;
            frame.add(new String[]{"  " + names[i], ms(v)});
        }
        frame.add(new String[]{"  Portal shadows skipped", khi.pentecost.core.PortalShadow.skipped + " of " + khi.pentecost.core.PortalShadow.total});
        frame.add(new String[]{"  Other world work", ms(Math.max(0, world - parts))});
        frame.add(new String[]{"Held item draw", ms(khi.pentecost.core.FrameProbe.handMs())});
        frame.add(new String[]{"HUD and overlays", ms(PartProbe.frameMs(PartProbe.HUD))});
        frame.add(new String[]{"Culling scan, render thread", EntityCulling.scanText()});
        frame.add(new String[]{"Culling tests, worker thread", EntityCulling.workText()});
        out.add(new Section("FRAME", frame));
        out.add(new Section("SLOW FRAMES", PerfDips.rows()));

        if (mc.getSingleplayerServer() != null) {
            List<String[]> chunks = new ArrayList<>();
            chunks.add(new String[]{"Chunks ready", String.format("%.0f per s", PartProbe.perSecond(PartProbe.FINISHED))});
            chunks.add(new String[]{"New chunks made", String.format("%.0f per s", PartProbe.perSecond(PartProbe.NOISE))});
            chunks.add(new String[]{"  Terrain shape", each(PartProbe.NOISE)});
            chunks.add(new String[]{"  Surface", each(PartProbe.SURFACE)});
            chunks.add(new String[]{"  Caves", each(PartProbe.CAVES)});
            chunks.add(new String[]{"  Trees, ores, plants", each(PartProbe.FEATURES)});
            chunks.add(new String[]{"Light updates", String.format("%.0f per s, %.2f ms each",
                    PartProbe.perSecond(PartProbe.LIGHT), PartProbe.msEach(PartProbe.LIGHT))});
            int[] q = ChunkQueues.read(mc);
            chunks.add(new String[]{"Server chunk tasks", q[0] < 0 ? "-" : q[0] + " waiting"});
            chunks.add(new String[]{"  Server time on them", String.format("%.0f ms per s",
                    PartProbe.perSecond(PartProbe.CHUNK_TASKS) * PartProbe.msEach(PartProbe.CHUNK_TASKS))});
            chunks.add(new String[]{"  Extra time at tick start", String.format("%.1f ms, %d tasks",
                    ChunkDrain.lastMs, ChunkDrain.lastTasks)});
            chunks.add(new String[]{"Server tick", String.format("%.1f ms", PartProbe.msEach(PartProbe.SERVER_TICK))});
            double[] la = khi.pentecost.game.pregen.Pregen.lookaheadNumbers();
            if (la[0] == 0) {
                chunks.add(new String[]{"Lookahead", "off"});
            } else if (la[1] == 0) {
                chunks.add(new String[]{"Lookahead", "on, waiting for you to move fast"});
            } else {
                chunks.add(new String[]{"Lookahead", String.format("%.0f being made, %.0f per s", la[2], la[3])});
                chunks.add(new String[]{"  Path ready ahead", String.format("%.0f s at %.0f blocks/s", la[4], la[5])});
                chunks.add(new String[]{"  Taken back after turns", String.format("%.0f", la[6])});
            }
            chunks.add(new String[]{"Light queue", q[1] < 0 ? "-" : q[1] + " waiting"});
            out.add(new Section("CHUNKS", chunks));
        }

        List<String[]> game = new ArrayList<>();
        Runtime rt = Runtime.getRuntime();
        long used = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
        long max = rt.maxMemory() / (1024 * 1024);
        int forge = 0;
        int fabric = 0;
        for (ModInfo m : PentecostRuntime.mods) {
            String id = m.id();
            if (m.loader() == LoaderKind.LIBRARY || id.startsWith("fabric-") || id.startsWith("fabric_")) {
                continue;
            }
            if (m.loader() == LoaderKind.FORGE) {
                forge++;
            } else {
                fabric++;
            }
        }
        game.add(new String[]{"Memory", used + " / " + max + " MB"});
        game.add(new String[]{"Garbage collection", gcLine()});
        game.add(new String[]{"Mods", forge + " Forge   " + fabric + " Fabric and Quilt"});
        if (khi.pentecost.core.ModImpact.running()) {
            game.add(new String[]{"Mod impact", String.format("measuring %.0f s", khi.pentecost.core.ModImpact.seconds())});
        }
        out.add(new Section("GAME", game));

        List<String[]> cost = khi.pentecost.core.CostMeter.rows(6);
        if (!cost.isEmpty()) {
            out.add(new Section("MOD HANDLER TIME PER TICK", cost));
        }

        khi.pentecost.messenger.Messenger.Stats mstat = khi.pentecost.messenger.Messenger.get().stats();
        if (mstat.on()) {
            List<String[]> rows = new ArrayList<>();
            rows.add(new String[]{"Messenger", (mstat.routerAlive() ? "on" : "STOPPED") + ", " + mstat.workers() + " workers"});
            rows.add(new String[]{"Jobs moved", mstat.routed() + ""});
            rows.add(new String[]{"Wait for a thread", String.format("%.0f us", mstat.avgWaitMicros())});
            for (khi.pentecost.messenger.Messenger.LaneStats l : mstat.lanes()) {
                if (l.sent() > 0) {
                    rows.add(new String[]{"  " + l.lane().name().toLowerCase(), l.done() + " done  " + l.waiting() + " waiting"
                            + (l.failed() > 0 ? "  " + l.failed() + " failed" : "")});
                }
            }
            long pw = khi.pentecost.messenger.PathHooks.onWorkers.sum();
            if (pw > 0) {
                long pf = khi.pentecost.messenger.PathHooks.forced.sum();
                rows.add(new String[]{"Paths on workers", pw + "  (" + (pf * 100 / pw) + "% read early)"});
                rows.add(new String[]{"Path work moved", String.format("%.0f ms", khi.pentecost.messenger.PathHooks.workerNanos.sum() / 1e6)});
            }
            if (khi.pentecost.messenger.PathHooks.sitesOnServer() > 0) {
                rows.add(new String[]{"Path askers on server", khi.pentecost.messenger.PathHooks.sitesOnServer() + " (read early)"});
            }
            if (khi.pentecost.control.Settings.on(khi.pentecost.control.Settings.MOB_THREADS)) {
                rows.add(new String[]{"Mobs on mod threads", MobThreads.lastMobs + " per tick  (" + MobThreads.threadsUsed
                        + (MobThreads.threadsUsed == 1 ? " thread, " : " threads, ") + MobThreads.lastChanges + " changes)"});
                rows.add(new String[]{"Mod thread time", String.format("%.2f ms per tick", MobThreads.lastMs)});
                if (!MobThreads.SENT_HOME.isEmpty()) {
                    rows.add(new String[]{"Mods sent home", String.join(", ", MobThreads.SENT_HOME)});
                }
            }
            if (khi.pentecost.control.Settings.on(khi.pentecost.control.Settings.MOD_TICKS)) {
                rows.add(new String[]{"Mod tick work", khi.pentecost.messenger.TickSplit.lastHandlers + " handlers  ("
                        + khi.pentecost.messenger.TickSplit.lastGroups
                        + (khi.pentecost.messenger.TickSplit.lastGroups == 1 ? " thread, " : " threads, ")
                        + String.format("%.2f ms)", khi.pentecost.messenger.TickSplit.lastMs)});
                if (!khi.pentecost.messenger.TickSplit.SENT_HOME.isEmpty()) {
                    rows.add(new String[]{"Handlers sent home", String.join(", ", khi.pentecost.messenger.TickSplit.SENT_HOME)});
                }
            }
            if (khi.pentecost.control.Settings.on(khi.pentecost.control.Settings.CULL_ENTITIES)) {
                rows.add(new String[]{"Mobs behind walls", khi.pentecost.core.CullHooks.count() + " not drawn"});
            }
            if (mstat.fallbacks() > 0) {
                rows.add(new String[]{"Fell back", mstat.fallbacks() + " (" + mstat.timeouts() + " too slow)"});
            }
            if (!mstat.quarantined().isEmpty()) {
                rows.add(new String[]{"Quarantined", String.join(", ", mstat.quarantined())});
            }
            out.add(new Section("THREADS", rows));
        }

        List<String[]> launch = new ArrayList<>();
        for (Map.Entry<String, Double> mark : Timeline.marks().entrySet()) {
            launch.add(new String[]{mark.getKey(), String.format("%.1f s", mark.getValue())});
        }
        if (!launch.isEmpty()) {
            out.add(new Section("LAUNCH", launch));
        }
        return out;
    }

    private static long gcLastTime = -1;
    private static long gcLastCount;
    private static long gcWindowStart;
    private static String gcText = "-";

    private static String gcLine() {
        long now = System.currentTimeMillis();
        if (now - gcWindowStart < 2000 && gcLastTime >= 0) {
            return gcText;
        }
        long time = 0;
        long count = 0;
        for (java.lang.management.GarbageCollectorMXBean b : java.lang.management.ManagementFactory.getGarbageCollectorMXBeans()) {
            time += Math.max(0, b.getCollectionTime());
            count += Math.max(0, b.getCollectionCount());
        }
        if (gcLastTime >= 0) {
            double seconds = Math.max(0.5, (now - gcWindowStart) / 1000.0);
            long dt = time - gcLastTime;
            long dc = count - gcLastCount;
            gcText = String.format("%.0f ms per s, %d in %.0f s", dt / seconds, dc, seconds);
        }
        gcLastTime = time;
        gcLastCount = count;
        gcWindowStart = now;
        return gcText;
    }

    private static String ms(double v) {
        return String.format("%.1f ms", v);
    }

    private static String each(int part) {
        return String.format("%.1f ms each", PartProbe.msEach(part));
    }

    private static double slowest(double share) {
        double[] copy = Arrays.stream(HIST).filter(v -> v > 0).sorted().toArray();
        if (copy.length == 0) {
            return 0;
        }
        int i = Math.min(copy.length - 1, (int) Math.floor(copy.length * (1 - share)));
        return copy[i];
    }

    private static int fade(int color, int alpha) {
        return (((color >>> 24) * alpha / 255) << 24) | (color & 0xFFFFFF);
    }

    private static void text(GuiGraphics g, Font font, String s, int x, int y, int color) {
        if ((color >>> 24) >= 4) {
            g.drawString(font, s, x, y, color, false);
        }
    }
}
