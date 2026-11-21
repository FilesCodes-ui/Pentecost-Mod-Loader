package khi.pentecost.game;

import com.mojang.blaze3d.platform.NativeImage;
import khi.pentecost.boot.Log;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.EditWorldScreen;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.level.storage.LevelStorageException;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.LevelSummary;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

public final class PentecostWorldsScreen extends Screen {
    private record Hit(int x, int y, int w, int h, Runnable action) {}

    private enum Sort {
        RECENT("Recent"), NAME("Name"), SIZE("Size");

        final String label;

        Sort(String label) {
            this.label = label;
        }

        Sort next() {
            Sort[] all = values();
            return all[(ordinal() + 1) % all.length];
        }
    }

    private enum Mode {
        ALL("All"), SURVIVAL("Survival"), CREATIVE("Creative"), HARDCORE("Hardcore");

        final String label;

        Mode(String label) {
            this.label = label;
        }

        Mode next() {
            Mode[] all = values();
            return all[(ordinal() + 1) % all.length];
        }

        boolean allows(LevelSummary s) {
            return switch (this) {
                case ALL -> true;
                case HARDCORE -> s.isHardcore();
                case CREATIVE -> !s.isHardcore() && s.getGameMode() == net.minecraft.world.level.GameType.CREATIVE;
                case SURVIVAL -> !s.isHardcore() && s.getGameMode() == net.minecraft.world.level.GameType.SURVIVAL;
            };
        }
    }

    private static final int ROW_H = 36;
    private static Mode lastMode = Mode.ALL;
    private static final Map<String, Long> SIZES = new ConcurrentHashMap<>();
    private static Sort lastSort = Sort.RECENT;

    private final Screen parent;
    private final List<Hit> hits = new ArrayList<>();
    private volatile List<LevelSummary> all = List.of();
    private List<LevelSummary> shown = List.of();
    private volatile boolean loading = true;
    private volatile String loadError = "";
    private String search = "";
    private Sort sort = lastSort;
    private Mode mode = lastMode;

    private enum Dlg { NONE, MOVE, NEW, RENAME }

    private String currentFolder;
    private String selectedFolder;
    private List<Object> rows = List.of();
    private Dlg dlg = Dlg.NONE;
    private String dlgText = "";
    private String dlgWorld;
    private String dlgFolder;
    private int dlgScroll;
    private String selectedId;
    private int scroll;
    private String status = "";
    private long statusUntil;
    private String pendingDelete;
    private long lastClickAt;
    private String lastClickId = "";

    private int listX, listTop, listW, visibleRows;

    private final Map<String, ResourceLocation> icons = new HashMap<>();
    private final Set<String> noIcon = new HashSet<>();

    public PentecostWorldsScreen(Screen parent) {
        super(Component.literal("Worlds"));
        this.parent = parent;
    }

    private static final class Prefs {
        private static final Properties P = new Properties();
        private static boolean loaded;

        private static Path file() {
            return Minecraft.getInstance().gameDirectory.toPath().resolve("pentecost").resolve("world_prefs.properties");
        }

        private static synchronized void load() {
            if (loaded) {
                return;
            }
            loaded = true;
            Path f = file();
            if (Files.isRegularFile(f)) {
                try (InputStream in = Files.newInputStream(f)) {
                    P.load(in);
                } catch (IOException e) {
                    Log.warn("Could not read the pinned worlds: " + e);
                }
            }
        }

        static synchronized boolean pinned(String id) {
            load();
            return P.getProperty("pin." + id) != null;
        }

        static synchronized void setPinned(String id, boolean on) {
            load();
            if (on) {
                P.setProperty("pin." + id, "1");
            } else {
                P.remove("pin." + id);
            }
            save();
        }

        private static void save() {
            try {
                Files.createDirectories(file().getParent());
                try (OutputStream out = Files.newOutputStream(file())) {
                    P.store(out, "Pentecost world list");
                }
            } catch (IOException e) {
                Log.warn("Could not save the world list settings: " + e);
            }
        }

        static synchronized String folderOf(String id) {
            load();
            return P.getProperty("folder.of." + id);
        }

        static synchronized void setFolder(String id, String folder) {
            load();
            if (folder == null) {
                P.remove("folder.of." + id);
            } else {
                P.setProperty("folder.of." + id, folder);
            }
            save();
        }

        static synchronized List<String> folders() {
            load();
            List<String> out = new ArrayList<>();
            for (String k : P.stringPropertyNames()) {
                if (k.startsWith("folder.def.")) {
                    out.add(k.substring("folder.def.".length()));
                }
            }
            out.sort(String.CASE_INSENSITIVE_ORDER);
            return out;
        }

        static synchronized boolean addFolder(String name) {
            load();
            for (String f : folders()) {
                if (f.equalsIgnoreCase(name)) {
                    return false;
                }
            }
            P.setProperty("folder.def." + name, "1");
            save();
            return true;
        }

        static synchronized boolean renameFolder(String from, String to) {
            load();
            for (String f : folders()) {
                if (f.equalsIgnoreCase(to) && !f.equals(from)) {
                    return false;
                }
            }
            P.remove("folder.def." + from);
            P.setProperty("folder.def." + to, "1");
            for (String k : new ArrayList<>(P.stringPropertyNames())) {
                if (k.startsWith("folder.of.") && from.equals(P.getProperty(k))) {
                    P.setProperty(k, to);
                }
            }
            save();
            return true;
        }

        static synchronized void deleteFolder(String name) {
            load();
            P.remove("folder.def." + name);
            for (String k : new ArrayList<>(P.stringPropertyNames())) {
                if (k.startsWith("folder.of.") && name.equals(P.getProperty(k))) {
                    P.remove(k);
                }
            }
            save();
        }
    }

    @Override
    protected void init() {
        super.init();
        reload();
    }

    private void reload() {
        loading = true;
        loadError = "";
        releaseIcons();
        try {
            LevelStorageSource src = minecraft.getLevelSource();
            src.loadLevelSummaries(src.findLevelCandidates()).thenAcceptAsync(list -> {
                all = list;
                loading = false;
                refilter();
                startSizes(list);
            }, minecraft).exceptionally(e -> {
                loadError = "Could not read the worlds folder.";
                loading = false;
                Log.warn("World list failed: " + e);
                return null;
            });
        } catch (LevelStorageException e) {
            loading = false;
            loadError = "Could not read the worlds folder.";
            Log.warn("World list failed: " + e);
        }
    }

    private void startSizes(List<LevelSummary> list) {
        Path base = minecraft.getLevelSource().getBaseDir();
        List<String> ids = new ArrayList<>();
        for (LevelSummary s : list) {
            ids.add(s.getLevelId());
        }
        Thread t = new Thread(() -> {
            for (String id : ids) {
                long total = 0;
                try (Stream<Path> walk = Files.walk(base.resolve(id))) {
                    total = walk.filter(Files::isRegularFile).mapToLong(p -> {
                        try {
                            return Files.size(p);
                        } catch (IOException e) {
                            return 0;
                        }
                    }).sum();
                } catch (IOException | RuntimeException e) {
                }
                SIZES.put(id, total);
            }
            if (sort == Sort.SIZE) {
                minecraft.execute(this::refilter);
            }
        }, "Pentecost world sizes");
        t.setDaemon(true);
        t.start();
    }

    private void refilter() {
        String q = search.toLowerCase(Locale.ROOT).trim();
        boolean searching = !q.isEmpty();
        java.util.Set<String> defs = new HashSet<>(Prefs.folders());
        if (currentFolder != null && !defs.contains(currentFolder)) {
            currentFolder = null;
        }
        List<LevelSummary> out = new ArrayList<>();
        for (LevelSummary s : all) {
            String f = Prefs.folderOf(s.getLevelId());
            boolean placed = f != null && defs.contains(f);
            boolean inView = searching || (currentFolder == null ? !placed : currentFolder.equals(f));
            boolean matches = !searching || s.getLevelName().toLowerCase(Locale.ROOT).contains(q)
                    || s.getLevelId().toLowerCase(Locale.ROOT).contains(q)
                    || (placed && f.toLowerCase(Locale.ROOT).contains(q));
            if (inView && mode.allows(s) && matches) {
                out.add(s);
            }
        }
        Comparator<LevelSummary> by = switch (sort) {
            case NAME -> Comparator.comparing(s -> s.getLevelName().toLowerCase(Locale.ROOT));
            case SIZE -> Comparator.comparingLong((LevelSummary s) -> SIZES.getOrDefault(s.getLevelId(), 0L)).reversed();
            default -> Comparator.comparingLong(LevelSummary::getLastPlayed).reversed();
        };
        out.sort(Comparator.comparing((LevelSummary s) -> !Prefs.pinned(s.getLevelId())).thenComparing(by));
        shown = out;
        List<Object> r = new ArrayList<>();
        if (!searching && currentFolder == null) {
            r.addAll(defs.stream().sorted(String.CASE_INSENSITIVE_ORDER).toList());
        }
        r.addAll(out);
        rows = r;
        if (selectedFolder != null && !r.contains(selectedFolder)) {
            selectedFolder = null;
        }
        if (selectedFolder == null && (selectedId == null || indexOf(selectedId) < 0)) {
            selectedId = null;
            if (!r.isEmpty()) {
                select(0);
            }
        }
        scroll = Math.max(0, Math.min(scroll, Math.max(0, r.size() - Math.max(1, visibleRows))));
    }

    private void select(int row) {
        Object o = rows.get(row);
        if (o instanceof String f) {
            selectedFolder = f;
            selectedId = null;
        } else {
            selectedId = ((LevelSummary) o).getLevelId();
            selectedFolder = null;
        }
    }

    private int rowIndex() {
        if (selectedFolder != null) {
            return rows.indexOf(selectedFolder);
        }
        int i = selectedId == null ? -1 : indexOf(selectedId);
        return i < 0 ? -1 : i + (rows.size() - shown.size());
    }

    private int countIn(String folder) {
        int n = 0;
        for (LevelSummary s : all) {
            if (folder.equals(Prefs.folderOf(s.getLevelId()))) {
                n++;
            }
        }
        return n;
    }

    private void back() {
        if (currentFolder != null) {
            currentFolder = null;
            scroll = 0;
            selectedFolder = null;
            refilter();
        } else {
            onClose();
        }
    }

    private void openFolderView(String name) {
        if (name == null) {
            return;
        }
        currentFolder = name;
        scroll = 0;
        selectedFolder = null;
        selectedId = null;
        refilter();
    }

    private void openDialog(Dlg d, String world, String folder) {
        if (d == Dlg.MOVE && world == null) {
            return;
        }
        dlg = d;
        dlgWorld = world;
        dlgFolder = folder;
        dlgScroll = 0;
        dlgText = d == Dlg.RENAME && folder != null ? folder : "";
    }

    private void assign(String worldId, String folder) {
        Prefs.setFolder(worldId, folder);
        say(folder == null ? "Moved to the main list." : "Moved to " + folder + ".");
        dlg = Dlg.NONE;
        refilter();
    }

    private void confirmDialog() {
        String name = dlgText.trim();
        if (name.isEmpty()) {
            return;
        }
        if (dlg == Dlg.NEW) {
            if (!Prefs.addFolder(name)) {
                say("A folder with that name already exists.");
                return;
            }
            if (dlgWorld != null) {
                Prefs.setFolder(dlgWorld, name);
                say("Moved to " + name + ".");
            } else {
                say("Folder created.");
            }
        } else if (dlg == Dlg.RENAME) {
            if (!name.equals(dlgFolder) && !Prefs.renameFolder(dlgFolder, name)) {
                say("A folder with that name already exists.");
                return;
            }
            if (dlgFolder.equals(currentFolder)) {
                currentFolder = name;
            }
            if (dlgFolder.equals(selectedFolder)) {
                selectedFolder = name;
            }
        }
        dlg = Dlg.NONE;
        refilter();
    }

    private void deleteFolder(String name) {
        if (name == null) {
            return;
        }
        Prefs.deleteFolder(name);
        if (name.equals(currentFolder)) {
            currentFolder = null;
        }
        selectedFolder = null;
        say("Folder removed. Its worlds are back in the main list.");
        refilter();
    }

    private int indexOf(String id) {
        for (int i = 0; i < shown.size(); i++) {
            if (shown.get(i).getLevelId().equals(id)) {
                return i;
            }
        }
        return -1;
    }

    private LevelSummary selected() {
        int i = selectedId == null ? -1 : indexOf(selectedId);
        return i < 0 ? null : shown.get(i);
    }

    private LevelSummary lastPlayed() {
        LevelSummary best = null;
        for (LevelSummary s : all) {
            if (s.isLocked() || s.isDisabled() || s.requiresManualConversion()) {
                continue;
            }
            if (best == null || s.getLastPlayed() > best.getLastPlayed()) {
                best = s;
            }
        }
        return best;
    }

    private void say(String text) {
        status = text;
        statusUntil = System.currentTimeMillis() + 4500;
    }

    private void play(LevelSummary s) {
        if (s == null) {
            return;
        }
        if (s.isLocked()) {
            say("This world is open in another game.");
        } else if (s.isDisabled() || s.requiresManualConversion()) {
            say("This world can't be opened: " + s.getInfo().getString());
        } else if (!GuardUi.protect(this, s)) {
            minecraft.createWorldOpenFlows().loadLevel(this, s.getLevelId());
        }
    }

    private void edit(LevelSummary s) {
        if (s == null) {
            return;
        }
        try {
            LevelStorageSource.LevelStorageAccess access = minecraft.getLevelSource().createAccess(s.getLevelId());
            minecraft.setScreen(new EditWorldScreen(changed -> {
                try {
                    access.close();
                } catch (IOException e) {
                    Log.warn("Could not close the world after editing: " + e);
                }
                minecraft.setScreen(this);
            }, access));
        } catch (IOException e) {
            say("Could not open this world for editing.");
        }
    }

    private void backup(LevelSummary s) {
        if (s == null) {
            return;
        }
        EditWorldScreen.makeBackupAndShowToast(minecraft.getLevelSource(), s.getLevelId());
        say("Backed up " + s.getLevelName() + ".");
    }

    private void openFolder(LevelSummary s) {
        if (s != null) {
            Util.getPlatform().openFile(minecraft.getLevelSource().getBaseDir().resolve(s.getLevelId()).toFile());
        }
    }

    private void togglePin(LevelSummary s) {
        if (s == null) {
            return;
        }
        boolean now = !Prefs.pinned(s.getLevelId());
        Prefs.setPinned(s.getLevelId(), now);
        say(now ? "Pinned. It can't be deleted until you unpin it." : "Unpinned.");
        refilter();
    }

    private void askDelete(LevelSummary s) {
        if (s == null) {
            return;
        }
        if (Prefs.pinned(s.getLevelId())) {
            say("Pinned worlds can't be deleted. Unpin it first.");
            return;
        }
        pendingDelete = s.getLevelId();
    }

    private void delete(String id, boolean backupFirst) {
        pendingDelete = null;
        if (id == null || Prefs.pinned(id)) {
            return;
        }
        if (backupFirst) {
            EditWorldScreen.makeBackupAndShowToast(minecraft.getLevelSource(), id);
        }
        try (LevelStorageSource.LevelStorageAccess a = minecraft.getLevelSource().createAccess(id)) {
            a.deleteLevel();
            say("Deleted.");
        } catch (IOException e) {
            say("Could not delete this world.");
            Log.warn("Delete failed: " + e);
        }
        SIZES.remove(id);
        Prefs.setFolder(id, null);
        reload();
    }

    private volatile boolean busy;

    private void duplicate(LevelSummary s) {
        if (s == null || busy) {
            return;
        }
        if (s.isLocked()) {
            say("This world is open in another game.");
            return;
        }
        busy = true;
        say("Copying " + s.getLevelName() + "...");
        Path base = minecraft.getLevelSource().getBaseDir();
        String id = s.getLevelId();
        String name = s.getLevelName() + " copy";
        Thread t = new Thread(() -> {
            try {
                String fresh = net.minecraft.FileUtil.findAvailableName(base, id + " copy", "");
                Path from = base.resolve(id);
                Path to = base.resolve(fresh);
                try (Stream<Path> walk = Files.walk(from)) {
                    for (Path src : (Iterable<Path>) walk::iterator) {
                        Path rel = from.relativize(src);
                        if (rel.toString().equals("session.lock")) {
                            continue;
                        }
                        Path dest = to.resolve(rel.toString());
                        if (Files.isDirectory(src)) {
                            Files.createDirectories(dest);
                        } else {
                            Files.createDirectories(dest.getParent());
                            Files.copy(src, dest);
                        }
                    }
                }
                try (LevelStorageSource.LevelStorageAccess a = minecraft.getLevelSource().createAccess(fresh)) {
                    a.renameLevel(name);
                }
                String inFolder = Prefs.folderOf(id);
                if (inFolder != null) {
                    Prefs.setFolder(fresh, inFolder);
                }
                minecraft.execute(() -> {
                    say("Copied as " + name + ".");
                    reload();
                });
            } catch (IOException | RuntimeException e) {
                Log.warn("Copy failed: " + e);
                minecraft.execute(() -> say("Could not copy this world."));
            } finally {
                busy = false;
            }
        }, "Pentecost world copy");
        t.setDaemon(true);
        t.start();
    }

    private void export(LevelSummary s) {
        if (s == null || busy) {
            return;
        }
        busy = true;
        say("Exporting " + s.getLevelName() + "...");
        Path from = minecraft.getLevelSource().getBaseDir().resolve(s.getLevelId());
        Path dir = minecraft.gameDirectory.toPath().resolve("pentecost").resolve("exports");
        String stamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
        Path zip = dir.resolve(s.getLevelId().replaceAll("[^A-Za-z0-9._-]", "_") + "_" + stamp + ".zip");
        Thread t = new Thread(() -> {
            try {
                Files.createDirectories(dir);
                try (java.util.zip.ZipOutputStream out = new java.util.zip.ZipOutputStream(Files.newOutputStream(zip));
                     Stream<Path> walk = Files.walk(from)) {
                    for (Path src : (Iterable<Path>) walk::iterator) {
                        if (!Files.isRegularFile(src) || src.getFileName().toString().equals("session.lock")) {
                            continue;
                        }
                        String entry = from.getFileName() + "/" + from.relativize(src).toString().replace('\\', '/');
                        out.putNextEntry(new java.util.zip.ZipEntry(entry));
                        Files.copy(src, out);
                        out.closeEntry();
                    }
                }
                minecraft.execute(() -> {
                    say("Exported to " + zip.getFileName() + ".");
                    Util.getPlatform().openFile(dir.toFile());
                });
            } catch (IOException | RuntimeException e) {
                Log.warn("Export failed: " + e);
                minecraft.execute(() -> say("Could not export this world."));
            } finally {
                busy = false;
            }
        }, "Pentecost world export");
        t.setDaemon(true);
        t.start();
    }

    private void newWorld() {
        CreateWorldScreen.openFresh(minecraft, this);
    }

    private void move(int by) {
        if (rows.isEmpty()) {
            return;
        }
        int i = Math.max(0, Math.min(rows.size() - 1, Math.max(0, rowIndex()) + by));
        select(i);
        if (i < scroll) {
            scroll = i;
        } else if (visibleRows > 0 && i >= scroll + visibleRows) {
            scroll = i - visibleRows + 1;
        }
    }

    private String totals() {
        long sum = 0;
        int known = 0;
        for (LevelSummary w : all) {
            Long b = SIZES.get(w.getLevelId());
            if (b != null) {
                sum += b;
                known++;
            }
        }
        int n = all.size();
        String text = n + (n == 1 ? " world" : " worlds");
        if (known == n) {
            text += "   " + size(sum) + " on disk";
        }
        return text + "   F5 refreshes";
    }

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

    private static String size(long bytes) {
        if (bytes < 1024 * 1024) {
            return Math.max(1, bytes / 1024) + " KB";
        }
        if (bytes < 1024L * 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f MB", bytes / 1048576.0);
        }
        return String.format(Locale.ROOT, "%.2f GB", bytes / 1073741824.0);
    }

    private static String ago(long ms) {
        if (ms <= 0) {
            return "Never played";
        }
        long s = Math.max(0, (System.currentTimeMillis() - ms) / 1000);
        if (s < 60) {
            return "Just now";
        }
        if (s < 3600) {
            return (s / 60) + " min ago";
        }
        if (s < 86400) {
            return (s / 3600) + " h ago";
        }
        if (s < 86400L * 14) {
            return (s / 86400) + " d ago";
        }
        return Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate().toString();
    }

    private String details(LevelSummary s) {
        if (s.isLocked()) {
            return "Open in another game";
        }
        if (s.isDisabled() || s.requiresManualConversion()) {
            return s.getInfo().getString();
        }
        String mode = s.getGameMode().getName();
        StringBuilder b = new StringBuilder(Character.toUpperCase(mode.charAt(0)) + mode.substring(1));
        if (s.isHardcore()) {
            b.append("  -  Hardcore");
        }
        if (s.hasCheats()) {
            b.append("  -  Cheats");
        }
        if (s.askToOpenWorld()) {
            b.append("  -  Other version");
        }
        b.append("  -  ").append(ago(s.getLastPlayed()));
        if (s.getLastPlayed() > 0) {
            b.append("  (").append(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                    .format(Instant.ofEpochMilli(s.getLastPlayed()).atZone(ZoneId.systemDefault()))).append(")");
        }
        return b.toString();
    }

    private ResourceLocation icon(LevelSummary s) {
        String id = s.getLevelId();
        ResourceLocation have = icons.get(id);
        if (have != null || noIcon.contains(id)) {
            return have;
        }
        Path p = s.getIcon();
        if (p != null && Files.isRegularFile(p)) {
            try (InputStream in = Files.newInputStream(p)) {
                NativeImage img = NativeImage.read(in);
                if (img.getWidth() == 64 && img.getHeight() == 64) {
                    ResourceLocation rl = new ResourceLocation("pentecost", "worlds/" + Integer.toHexString(id.hashCode()) + "_" + icons.size());
                    minecraft.getTextureManager().register(rl, new DynamicTexture(img));
                    icons.put(id, rl);
                    return rl;
                }
                img.close();
            } catch (IOException | RuntimeException e) {
            }
        }
        noIcon.add(id);
        return null;
    }

    private void releaseIcons() {
        if (minecraft != null) {
            for (ResourceLocation rl : icons.values()) {
                minecraft.getTextureManager().release(rl);
            }
        }
        icons.clear();
        noIcon.clear();
    }

    @Override
    public void removed() {
        releaseIcons();
        super.removed();
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
        g.drawString(font, label, x + (w - font.width(label)) / 2, y + 6, color, false);
        if (enabled) {
            hits.add(new Hit(x, y, w, 20, action));
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, Ui.back(minecraft));
        Ui.dither(g, width, height);
        float ui = Ui.scale(minecraft);
        int vw = (int) (width / ui);
        int vh = (int) (height / ui);
        int mx = (int) (mouseX / ui);
        int my = (int) (mouseY / ui);
        hits.clear();
        g.pose().pushPose();
        g.pose().scale(ui, ui, 1);

        int cx = vw / 2;
        int colW = Math.min(480, vw - 40);
        int x0 = cx - colW / 2;
        LevelSummary sel = selected();
        boolean modal = pendingDelete != null || dlg != Dlg.NONE;

        int y = Math.max(14, vh / 20);
        String word = "WORLDS";
        int total = (Ui.LOGO_W + 5) * 2 + Ui.wordmarkWidth(word, 2);
        int hx = cx - total / 2;
        Ui.logo(g, hx, y - 2, 2, Ui.TEXT);
        Ui.wordmark(g, word, hx + (Ui.LOGO_W + 5) * 2, y, 2, Ui.TEXT);
        y += 32;

        int newW = 86, sortW = 92, modeW = 92, gap = 6;
        int searchW = colW - newW - sortW - modeW - gap * 3;
        Ui.outline(g, x0, y, x0 + searchW, y + 20, 0x66FFFFFF);
        if (search.isEmpty()) {
            g.drawString(font, "Type to search", x0 + 8, y + 6, Ui.FAINT, false);
        } else {
            g.drawString(font, fit(search, searchW - 16), x0 + 8, y + 6, Ui.TEXT, false);
        }
        if (!modal && (System.currentTimeMillis() / 500) % 2 == 0) {
            int caret = x0 + 8 + (search.isEmpty() ? 0 : Math.min(searchW - 14, font.width(search)));
            g.fill(caret, y + 4, caret + 1, y + 16, Ui.TEXT);
        }
        button(g, "Sort: " + sort.label, x0 + searchW + gap, y, sortW, mx, my, !modal, () -> {
            sort = sort.next();
            lastSort = sort;
            scroll = 0;
            refilter();
        });
        button(g, "Show: " + mode.label, x0 + searchW + gap + sortW + gap, y, modeW, mx, my, !modal, () -> {
            mode = mode.next();
            lastMode = mode;
            scroll = 0;
            refilter();
        });
        button(g, "New world", x0 + searchW + gap + sortW + gap + modeW + gap, y, newW, mx, my, !modal, this::newWorld);
        y += 28;

        long totalBytes = 0;
        boolean sizesKnown = !all.isEmpty();
        for (LevelSummary w : all) {
            Long b = SIZES.get(w.getLevelId());
            if (b == null) {
                sizesKnown = false;
            } else {
                totalBytes += b;
            }
        }
        String count = loading ? "Reading worlds..." : search.isEmpty() && mode == Mode.ALL
                ? all.size() + (all.size() == 1 ? " world" : " worlds") + (sizesKnown ? "  -  " + size(totalBytes) : "")
                : shown.size() + " of " + all.size();
        if (currentFolder != null && search.isEmpty()) {
            count = "Worlds / " + currentFolder + "  -  " + shown.size() + (shown.size() == 1 ? " world" : " worlds");
        } else if (search.isEmpty() && mode == Mode.ALL && currentFolder == null && !loading) {
            int nf = Prefs.folders().size();
            if (nf > 0) {
                count = count + "  -  " + nf + (nf == 1 ? " folder" : " folders");
            }
        }
        g.drawString(font, count, x0, y, Ui.MUTED, false);
        String hint = "Enter play   Ctrl+F pin   Ctrl+M move   Ctrl+G new folder   Ctrl+D copy";
        if (font.width(hint) + font.width(count) + 20 < colW) {
            g.drawString(font, hint, x0 + colW - font.width(hint), y, Ui.FAINT, false);
        }
        y += 14;
        g.fill(x0, y, x0 + colW, y + 1, Ui.LINE);
        y += 6;

        int bottomBlock = 82;
        int listBottom = vh - bottomBlock;
        visibleRows = Math.max(1, (listBottom - y) / ROW_H);
        listX = x0;
        listTop = y;
        listW = colW;
        scroll = Math.max(0, Math.min(scroll, Math.max(0, rows.size() - visibleRows)));

        if (!loadError.isEmpty()) {
            centered(g, loadError, cx, y + 20, Ui.SOFT);
        } else if (!loading && rows.isEmpty()) {
            centered(g, all.isEmpty() ? "No worlds yet. Create one to begin."
                    : currentFolder != null && search.isEmpty() ? "This folder is empty. Use Move on a world to put it here."
                    : "No worlds match \"" + search + "\".", cx, y + 20, Ui.SOFT);
        }
        for (int i = 0; i < visibleRows && scroll + i < rows.size(); i++) {
            Object row = rows.get(scroll + i);
            int ry = y + i * ROW_H;
            boolean isSel = row instanceof String f ? f.equals(selectedFolder)
                    : selectedFolder == null && ((LevelSummary) row).getLevelId().equals(selectedId);
            boolean hover = !modal && Ui.over(mx, my, x0, ry, colW, ROW_H - 4);
            if (isSel) {
                g.fill(x0, ry, x0 + colW, ry + ROW_H - 4, Ui.HOVER);
                Ui.outline(g, x0, ry, x0 + colW, ry + ROW_H - 4, 0x99FFFFFF);
            } else if (hover) {
                g.fill(x0, ry, x0 + colW, ry + ROW_H - 4, Ui.HOVER);
            }
            if (row instanceof String folder) {
                Ui.outline(g, x0 + 4, ry + 2, x0 + 16, ry + 8, 0x99FFFFFF);
                Ui.outline(g, x0 + 4, ry + 6, x0 + 32, ry + 28, 0x99FFFFFF);
                int inside = countIn(folder);
                g.drawString(font, fit(folder, colW - 120), x0 + 40, ry + 4, Ui.TEXT, false);
                g.drawString(font, inside + (inside == 1 ? " world" : " worlds"), x0 + 40, ry + 17, isSel ? Ui.SOFT : Ui.MUTED, false);
                g.drawString(font, "FOLDER", x0 + colW - 8 - font.width("FOLDER"), ry + 4, Ui.MUTED, false);
                continue;
            }
            LevelSummary s = (LevelSummary) row;
            ResourceLocation rl = icon(s);
            if (rl != null) {
                g.blit(rl, x0 + 4, ry + 2, 28, 28, 0f, 0f, 64, 64, 64, 64);
            } else {
                Ui.outline(g, x0 + 4, ry + 2, x0 + 32, ry + 30, 0x33FFFFFF);
                String letter = s.getLevelName().isEmpty() ? "?" : s.getLevelName().substring(0, 1).toUpperCase(Locale.ROOT);
                g.drawString(font, letter, x0 + 18 - font.width(letter) / 2, ry + 12, Ui.FAINT, false);
            }
            boolean pinned = Prefs.pinned(s.getLevelId());
            String right = SIZES.containsKey(s.getLevelId()) ? size(SIZES.get(s.getLevelId())) : "";
            int rightW = Math.max(font.width(right), pinned ? font.width("PINNED") : 0) + 10;
            String name = fit(s.getLevelName(), colW - 44 - rightW);
            g.drawString(font, name, x0 + 40, ry + 4, Ui.TEXT, false);
            String id = s.getLevelId();
            int idX = x0 + 40 + font.width(name) + 8;
            int idRoom = x0 + colW - 8 - rightW - idX;
            if (!id.equals(s.getLevelName()) && idRoom > 36) {
                g.drawString(font, fit("(" + id + ")", idRoom), idX, ry + 4, Ui.FAINT, false);
            }
            String second = details(s);
            String inFolder = Prefs.folderOf(id);
            if (!search.isEmpty() && inFolder != null) {
                second = "In " + inFolder + "  -  " + second;
            }
            g.drawString(font, fit(second, colW - 44 - rightW), x0 + 40, ry + 17, isSel ? Ui.SOFT : Ui.MUTED, false);
            if (!right.isEmpty()) {
                g.drawString(font, right, x0 + colW - 8 - font.width(right), ry + 4, Ui.MUTED, false);
            }
            if (pinned) {
                g.drawString(font, "PINNED", x0 + colW - 8 - font.width("PINNED"), ry + 17, Ui.TEXT, false);
            }
        }
        if (rows.size() > visibleRows) {
            int trackH = visibleRows * ROW_H - 4;
            int barH = Math.max(14, trackH * visibleRows / rows.size());
            int barY = y + (trackH - barH) * scroll / Math.max(1, rows.size() - visibleRows);
            g.fill(x0 + colW + 4, y, x0 + colW + 5, y + trackH, Ui.LINE);
            g.fill(x0 + colW + 3, barY, x0 + colW + 6, barY + barH, Ui.MUTED);
        }

        int sy = vh - bottomBlock + 6;
        if (System.currentTimeMillis() < statusUntil && !status.isEmpty()) {
            centered(g, status, cx, sy, Ui.SOFT);
        } else if (!loading && loadError.isEmpty() && !all.isEmpty()) {
            centered(g, totals(), cx, sy, Ui.FAINT);
        }

        int ay = vh - 62;
        boolean has = sel != null && !modal;
        int ag = 4;
        final String selFolder = selectedFolder;
        if (selFolder != null) {
            int n = 3;
            int bw = (colW - ag * (n - 1)) / n;
            String[] names = {"Open", "Rename", "Delete folder"};
            Runnable[] acts = {() -> openFolderView(selFolder), () -> openDialog(Dlg.RENAME, null, selFolder), () -> deleteFolder(selFolder)};
            for (int i = 0; i < n; i++) {
                button(g, names[i], x0 + i * (bw + ag), ay, bw, mx, my, !modal, acts[i]);
            }
        } else {
            int n = 9;
            int bw = (colW - ag * (n - 1)) / n;
            String[] names = {"Play", "Edit", "Backup", "Copy", "Export", "Files", sel != null && Prefs.pinned(sel.getLevelId()) ? "Unpin" : "Pin",
                    "Move", "Delete"};
            final String selId = sel == null ? null : sel.getLevelId();
            Runnable[] acts = {
                    () -> play(sel), () -> edit(sel), () -> backup(sel), () -> duplicate(sel), () -> export(sel),
                    () -> openFolder(sel), () -> togglePin(sel), () -> openDialog(Dlg.MOVE, selId, null), () -> askDelete(sel)};
            for (int i = 0; i < n; i++) {
                button(g, names[i], x0 + i * (bw + ag), ay, bw, mx, my, has, acts[i]);
            }
        }
        int by = vh - 36;
        int third = (colW - 12) / 3;
        button(g, currentFolder != null ? "Back to worlds" : "Back", x0, by, third, mx, my, !modal, this::back);
        button(g, "New folder", x0 + third + 6, by, third, mx, my, !modal, () -> openDialog(Dlg.NEW, null, null));
        LevelSummary cont = lastPlayed();
        String contLabel = cont == null ? "Continue" : "Continue: " + fit(cont.getLevelName(), third - font.width("Continue: ") - 14);
        button(g, contLabel, x0 + (third + 6) * 2, by, third, mx, my, cont != null && !modal, () -> play(cont));

        if (pendingDelete != null) {
            LevelSummary d = null;
            for (LevelSummary s : all) {
                if (s.getLevelId().equals(pendingDelete)) {
                    d = s;
                }
            }
            hits.clear();
            g.fill(0, 0, vw, vh, 0xB0000000);
            int bwid = Math.min(340, vw - 40), bx = cx - bwid / 2, byy = vh / 2 - 52;
            g.fill(bx, byy, bx + bwid, byy + 104, Ui.BG);
            Ui.outline(g, bx, byy, bx + bwid, byy + 104, 0xCCFFFFFF);
            centered(g, "DELETE THIS WORLD?", cx, byy + 10, Ui.TEXT);
            centered(g, fit(d == null ? pendingDelete : d.getLevelName(), bwid - 24), cx, byy + 26, Ui.SOFT);
            centered(g, "This can't be undone.", cx, byy + 40, Ui.MUTED);
            int btnW = (bwid - 24 - 8) / 3;
            int cy = byy + 70;
            String id = pendingDelete;
            button(g, "Cancel", bx + 12, cy, btnW, mx, my, true, () -> pendingDelete = null);
            button(g, "Back up first", bx + 12 + btnW + 4, cy, btnW, mx, my, true, () -> delete(id, true));
            button(g, "Delete", bx + 12 + (btnW + 4) * 2, cy, btnW, mx, my, true, () -> delete(id, false));
        }
        if (dlg != Dlg.NONE) {
            drawDialog(g, vw, vh, cx, mx, my);
        }
        g.pose().popPose();
    }

    private void drawDialog(GuiGraphics g, int vw, int vh, int cx, int mx, int my) {
        hits.clear();
        g.fill(0, 0, vw, vh, 0xB0000000);
        int bwid = Math.min(340, vw - 40), bx = cx - bwid / 2;
        if (dlg == Dlg.MOVE) {
            List<String> names = Prefs.folders();
            int visible = Math.min(6, names.size());
            dlgScroll = Math.max(0, Math.min(dlgScroll, Math.max(0, names.size() - visible)));
            int bh = 44 + (1 + visible) * 24 + 34;
            int by = vh / 2 - bh / 2;
            g.fill(bx, by, bx + bwid, by + bh, Ui.BG);
            Ui.outline(g, bx, by, bx + bwid, by + bh, 0xCCFFFFFF);
            centered(g, "MOVE TO FOLDER", cx, by + 10, Ui.TEXT);
            String world = dlgWorld;
            String now = Prefs.folderOf(world);
            int yy = by + 30;
            button(g, (now == null ? "> " : "") + "Main list (no folder)", bx + 14, yy, bwid - 28, mx, my, true, () -> assign(world, null));
            yy += 24;
            for (int i = 0; i < visible; i++) {
                String f = names.get(dlgScroll + i);
                button(g, (f.equals(now) ? "> " : "") + f, bx + 14, yy, bwid - 28, mx, my, true, () -> assign(world, f));
                yy += 24;
            }
            if (names.size() > visible) {
                centered(g, "Scroll for more folders", cx, yy - 2, Ui.FAINT);
            }
            int half = (bwid - 28 - 6) / 2;
            int btn = by + bh - 30;
            button(g, "New folder", bx + 14, btn, half, mx, my, true, () -> openDialog(Dlg.NEW, world, null));
            button(g, "Cancel", bx + 14 + half + 6, btn, half, mx, my, true, () -> dlg = Dlg.NONE);
        } else {
            int bh = 116;
            int by = vh / 2 - bh / 2;
            g.fill(bx, by, bx + bwid, by + bh, Ui.BG);
            Ui.outline(g, bx, by, bx + bwid, by + bh, 0xCCFFFFFF);
            centered(g, dlg == Dlg.NEW ? "NEW FOLDER" : "RENAME FOLDER", cx, by + 10, Ui.TEXT);
            g.drawString(font, "Name", bx + 14, by + 28, Ui.MUTED, false);
            Ui.outline(g, bx + 14, by + 39, bx + bwid - 14, by + 59, 0xCCFFFFFF);
            g.drawString(font, fit(dlgText, bwid - 44), bx + 22, by + 45, Ui.TEXT, false);
            if ((System.currentTimeMillis() / 500) % 2 == 0) {
                int caret = bx + 22 + Math.min(bwid - 46, font.width(dlgText));
                g.fill(caret, by + 43, caret + 1, by + 55, Ui.TEXT);
            }
            int half = (bwid - 28 - 6) / 2;
            int btn = by + bh - 30;
            button(g, "Cancel", bx + 14, btn, half, mx, my, true, () -> dlg = Dlg.NONE);
            button(g, dlg == Dlg.NEW ? "Create" : "Save", bx + 14 + half + 6, btn, half, mx, my, !dlgText.trim().isEmpty(), this::confirmDialog);
        }
    }

    @Override
    public boolean charTyped(char c, int modifiers) {
        if (dlg != Dlg.NONE) {
            if (dlg != Dlg.MOVE && !Character.isISOControl(c) && dlgText.length() < 32) {
                dlgText += c;
            }
            return true;
        }
        if (pendingDelete == null && !Character.isISOControl(c) && search.length() < 40) {
            search += c;
            scroll = 0;
            refilter();
            return true;
        }
        return super.charTyped(c, modifiers);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (dlg != Dlg.NONE) {
            switch (key) {
                case 256 -> dlg = Dlg.NONE;
                case 257, 335 -> {
                    if (dlg != Dlg.MOVE) {
                        confirmDialog();
                    }
                }
                case 259 -> {
                    if (dlg != Dlg.MOVE && !dlgText.isEmpty()) {
                        dlgText = dlgText.substring(0, dlgText.length() - 1);
                    }
                }
                case 86 -> {
                    if (Screen.hasControlDown() && dlg != Dlg.MOVE) {
                        dlgText = (dlgText + minecraft.keyboardHandler.getClipboard()).trim();
                    }
                }
                default -> {
                }
            }
            return true;
        }
        if (pendingDelete != null) {
            if (key == 256) {
                pendingDelete = null;
            }
            return true;
        }
        boolean ctrl = Screen.hasControlDown();
        LevelSummary sel = selected();
        switch (key) {
            case 256 -> {
                if (!search.isEmpty()) {
                    search = "";
                    refilter();
                } else {
                    back();
                }
                return true;
            }
            case 259 -> {
                if (!search.isEmpty()) {
                    search = ctrl ? "" : search.substring(0, search.length() - 1);
                    refilter();
                }
                return true;
            }
            case 257, 335 -> {
                if (selectedFolder != null) {
                    openFolderView(selectedFolder);
                } else {
                    play(sel);
                }
                return true;
            }
            case 268 -> {
                move(-rows.size());
                return true;
            }
            case 269 -> {
                move(rows.size());
                return true;
            }
            case 294 -> {
                reload();
                say("Refreshed.");
                return true;
            }
            case 265 -> {
                move(-1);
                return true;
            }
            case 264 -> {
                move(1);
                return true;
            }
            case 266 -> {
                move(-visibleRows);
                return true;
            }
            case 267 -> {
                move(visibleRows);
                return true;
            }
            case 261 -> {
                if (selectedFolder != null) {
                    deleteFolder(selectedFolder);
                } else {
                    askDelete(sel);
                }
                return true;
            }
            default -> {
            }
        }
        if (ctrl) {
            switch (key) {
                case 70 -> togglePin(sel);
                case 66 -> backup(sel);
                case 79 -> openFolder(sel);
                case 68 -> duplicate(sel);
                case 88 -> export(sel);
                case 77 -> openDialog(Dlg.MOVE, sel == null ? null : sel.getLevelId(), null);
                case 71 -> openDialog(Dlg.NEW, null, null);
                case 69 -> edit(sel);
                case 78 -> newWorld();
                case 86 -> {
                    search = (search + minecraft.keyboardHandler.getClipboard()).trim();
                    refilter();
                }
                default -> {
                    return super.keyPressed(key, scan, mods);
                }
            }
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (dlg == Dlg.MOVE) {
            dlgScroll = Math.max(0, dlgScroll - (int) Math.signum(delta));
            return true;
        }
        if (pendingDelete != null || dlg != Dlg.NONE) {
            return true;
        }
        scroll = Math.max(0, Math.min(Math.max(0, rows.size() - visibleRows), scroll - (int) Math.signum(delta) * 2));
        return true;
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
            if (pendingDelete == null && dlg == Dlg.NONE && mx >= listX && mx < listX + listW && my >= listTop) {
                int row = (int) ((my - listTop) / ROW_H);
                int idx = scroll + row;
                if (row < visibleRows && idx < rows.size()) {
                    Object o = rows.get(idx);
                    String key = o instanceof String f ? "folder:" + f : ((LevelSummary) o).getLevelId();
                    long now = System.currentTimeMillis();
                    boolean second = key.equals(lastClickId) && now - lastClickAt < 350;
                    select(idx);
                    lastClickId = key;
                    lastClickAt = now;
                    if (second) {
                        if (o instanceof String f) {
                            openFolderView(f);
                        } else {
                            play((LevelSummary) o);
                        }
                    }
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }
}
