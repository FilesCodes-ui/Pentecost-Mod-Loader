package khi.pentecost.wardrobe;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import khi.pentecost.account.SkinApi;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

public final class WardrobeStore {
    public static final class Skin {
        public String id = "";
        public String name = "";
        public boolean slim;
        public String folder = "";
        public List<String> tags = new ArrayList<>();
        public long added;
        public String sha1 = "";
        public String from = "";
    }

    public static final class Outfit {
        public String id = "";
        public String name = "";
        public String skinId = "";
        public String capeId = "";
    }

    public static final class Rule {
        public String id = "";
        public String kind = "server";
        public String match = "";
        public String outfitId = "";
    }

    public static final class Past {
        public String sha1 = "";
        public boolean slim;
        public String capeId = "";
        public long at;
        public String name = "";
    }

    private static final class Data {
        List<Skin> skins = new ArrayList<>();
        List<String> folders = new ArrayList<>();
        List<Outfit> outfits = new ArrayList<>();
        List<Rule> rules = new ArrayList<>();
        List<Past> history = new ArrayList<>();
        String lastOutfit = "";
    }

    public static final int MAX_SKINS = 500;
    public static final int MAX_HISTORY = 10;
    private static final int MAX_TAGS = 8;
    private static final Pattern ID = Pattern.compile("[0-9a-f]{8}");
    private static final Pattern SHA1 = Pattern.compile("[0-9a-f]{40}");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path root;
    private Data data = new Data();

    private WardrobeStore(Path root) {
        this.root = root;
    }

    public static WardrobeStore open(Path root) throws IOException {
        WardrobeStore store = new WardrobeStore(root);
        Files.createDirectories(root.resolve("skins"));
        Files.createDirectories(root.resolve("history"));
        Path index = root.resolve("index.json");
        if (Files.isRegularFile(index)) {
            try {
                Data read = GSON.fromJson(Files.readString(index, StandardCharsets.UTF_8), Data.class);
                if (read != null) {
                    store.data = read;
                    store.tidy();
                }
            } catch (JsonParseException e) {
                Files.move(index, root.resolve("index.broken-" + System.currentTimeMillis() + ".json"), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        return store;
    }

    private void tidy() {
        data.skins.removeIf(s -> s == null || !ID.matcher(s.id).matches() || !Files.isRegularFile(root.resolve("skins").resolve(s.id + ".png")));
        data.skins.forEach(s -> {
            s.name = clean(s.name, 40);
            s.folder = clean(s.folder == null ? "" : s.folder, 30);
            s.from = clean(s.from == null ? "" : s.from, 60);
            s.sha1 = s.sha1 == null ? "" : s.sha1;
            if (s.tags == null) {
                s.tags = new ArrayList<>();
            }
        });
        Set<String> ids = new HashSet<>();
        data.skins.forEach(s -> ids.add(s.id));
        data.outfits.removeIf(o -> o == null || !ID.matcher(o.id).matches() || !ids.contains(o.skinId));
        Set<String> outfitIds = new HashSet<>();
        data.outfits.forEach(o -> outfitIds.add(o.id));
        data.rules.removeIf(r -> r == null || !ID.matcher(r.id).matches() || !outfitIds.contains(r.outfitId)
                || !("server".equals(r.kind) || "world".equals(r.kind)) || r.match == null || r.match.isBlank());
        data.history.removeIf(p -> p == null || !SHA1.matcher(p.sha1).matches() || !Files.isRegularFile(root.resolve("history").resolve(p.sha1 + ".png")));
        if (data.folders == null) {
            data.folders = new ArrayList<>();
        }
        if (data.lastOutfit == null) {
            data.lastOutfit = "";
        }
    }

    private synchronized void save() throws IOException {
        Path tmp = root.resolve("index.json.tmp");
        Files.writeString(tmp, GSON.toJson(data), StandardCharsets.UTF_8);
        Files.move(tmp, root.resolve("index.json"), StandardCopyOption.REPLACE_EXISTING);
    }

    public Path root() {
        return root;
    }

    public synchronized List<Skin> skins() {
        return List.copyOf(data.skins);
    }

    public synchronized Skin skin(String id) {
        return data.skins.stream().filter(s -> s.id.equals(id)).findFirst().orElse(null);
    }

    public synchronized Skin addSkin(byte[] png, String name, String from) throws IOException {
        SkinImage.Report report = SkinImage.check(png);
        String sha = sha1(png);
        for (Skin s : data.skins) {
            if (s.sha1.equals(sha)) {
                return s;
            }
        }
        if (data.skins.size() >= MAX_SKINS) {
            throw new IOException("The wardrobe holds " + MAX_SKINS + " skins. Delete some first.");
        }
        Skin s = new Skin();
        s.id = newId(data.skins.stream().map(x -> x.id).toList());
        s.name = clean(name, 40).isEmpty() ? "Skin " + (data.skins.size() + 1) : clean(name, 40);
        s.slim = report.slim();
        s.added = System.currentTimeMillis();
        s.sha1 = sha;
        s.from = clean(from, 60);
        Files.write(root.resolve("skins").resolve(s.id + ".png"), png);
        data.skins.add(s);
        save();
        return s;
    }

    public byte[] bytes(Skin s) throws IOException {
        if (!ID.matcher(s.id).matches()) {
            throw new IOException("That skin has no file");
        }
        return Files.readAllBytes(root.resolve("skins").resolve(s.id + ".png"));
    }

    public synchronized void rename(String id, String name) throws IOException {
        Skin s = require(id);
        String n = clean(name, 40);
        if (!n.isEmpty()) {
            s.name = n;
            save();
        }
    }

    public synchronized void setSlim(String id, boolean slim) throws IOException {
        require(id).slim = slim;
        save();
    }

    public synchronized void setFolder(String id, String folder) throws IOException {
        Skin s = require(id);
        String f = clean(folder, 30);
        s.folder = f;
        if (!f.isEmpty() && !data.folders.contains(f)) {
            data.folders.add(f);
        }
        save();
    }

    public synchronized void addTag(String id, String tag) throws IOException {
        Skin s = require(id);
        String t = clean(tag, 20).toLowerCase(Locale.ROOT);
        if (!t.isEmpty() && !s.tags.contains(t) && s.tags.size() < MAX_TAGS) {
            s.tags.add(t);
            save();
        }
    }

    public synchronized void removeTag(String id, String tag) throws IOException {
        if (require(id).tags.remove(tag)) {
            save();
        }
    }

    public synchronized void replaceImage(String id, byte[] png) throws IOException {
        Skin s = require(id);
        SkinImage.Report report = SkinImage.check(png);
        Files.write(root.resolve("skins").resolve(s.id + ".png"), png);
        s.sha1 = sha1(png);
        s.slim = report.slim();
        save();
    }

    public synchronized void deleteSkin(String id) throws IOException {
        Skin s = require(id);
        data.skins.remove(s);
        Files.deleteIfExists(root.resolve("skins").resolve(s.id + ".png"));
        Set<String> gone = new HashSet<>();
        data.outfits.removeIf(o -> {
            boolean hit = o.skinId.equals(id);
            if (hit) {
                gone.add(o.id);
            }
            return hit;
        });
        data.rules.removeIf(r -> gone.contains(r.outfitId));
        if (gone.contains(data.lastOutfit)) {
            data.lastOutfit = "";
        }
        save();
    }

    public synchronized List<Skin> find(String text, String folder) {
        String q = text == null ? "" : text.strip().toLowerCase(Locale.ROOT);
        List<Skin> out = new ArrayList<>();
        for (Skin s : data.skins) {
            if (!folder.isEmpty() && !folder.equals(s.folder)) {
                continue;
            }
            if (q.isEmpty() || s.name.toLowerCase(Locale.ROOT).contains(q) || s.from.toLowerCase(Locale.ROOT).contains(q)
                    || s.tags.stream().anyMatch(t -> t.contains(q))) {
                out.add(s);
            }
        }
        out.sort(Comparator.comparingLong((Skin s) -> s.added).reversed());
        return out;
    }

    public synchronized List<String> folders() {
        List<String> out = new ArrayList<>(data.folders);
        Collections.sort(out, String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    public synchronized void addFolder(String name) throws IOException {
        String f = clean(name, 30);
        if (!f.isEmpty() && !data.folders.contains(f) && data.folders.size() < 50) {
            data.folders.add(f);
            save();
        }
    }

    public synchronized void removeFolder(String name) throws IOException {
        if (data.folders.remove(name)) {
            data.skins.stream().filter(s -> s.folder.equals(name)).forEach(s -> s.folder = "");
            save();
        }
    }

    public synchronized List<Outfit> outfits() {
        return List.copyOf(data.outfits);
    }

    public synchronized Outfit outfit(String id) {
        return data.outfits.stream().filter(o -> o.id.equals(id)).findFirst().orElse(null);
    }

    public synchronized Outfit addOutfit(String name, String skinId, String capeId) throws IOException {
        require(skinId);
        if (data.outfits.size() >= 100) {
            throw new IOException("The wardrobe holds 100 outfits. Delete some first.");
        }
        Outfit o = new Outfit();
        o.id = newId(data.outfits.stream().map(x -> x.id).toList());
        o.name = clean(name, 40).isEmpty() ? "Outfit " + (data.outfits.size() + 1) : clean(name, 40);
        o.skinId = skinId;
        o.capeId = capeId == null ? "" : clean(capeId, 64);
        data.outfits.add(o);
        save();
        return o;
    }

    public synchronized void deleteOutfit(String id) throws IOException {
        if (data.outfits.removeIf(o -> o.id.equals(id))) {
            data.rules.removeIf(r -> r.outfitId.equals(id));
            if (data.lastOutfit.equals(id)) {
                data.lastOutfit = "";
            }
            save();
        }
    }

    public synchronized List<Rule> rules() {
        return List.copyOf(data.rules);
    }

    public synchronized Rule addRule(String kind, String match, String outfitId) throws IOException {
        if (!("server".equals(kind) || "world".equals(kind))) {
            throw new IOException("A rule is for a server or a world");
        }
        if (outfit(outfitId) == null) {
            throw new IOException("Pick an outfit for the rule");
        }
        String m = clean(match, 60);
        if (m.isEmpty()) {
            throw new IOException("Type part of the server address or the world name");
        }
        if (data.rules.size() >= 100) {
            throw new IOException("The wardrobe holds 100 rules. Delete some first.");
        }
        Rule r = new Rule();
        r.id = newId(data.rules.stream().map(x -> x.id).toList());
        r.kind = kind;
        r.match = m;
        r.outfitId = outfitId;
        data.rules.add(r);
        save();
        return r;
    }

    public synchronized void deleteRule(String id) throws IOException {
        if (data.rules.removeIf(r -> r.id.equals(id))) {
            save();
        }
    }

    public synchronized Rule match(String kind, String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String t = text.toLowerCase(Locale.ROOT);
        Rule best = null;
        for (Rule r : data.rules) {
            if (!r.kind.equals(kind)) {
                continue;
            }
            String m = r.match.toLowerCase(Locale.ROOT);
            boolean hit = "world".equals(kind) ? t.equals(m) : t.contains(m);
            if (hit && (best == null || r.match.length() > best.match.length())) {
                best = r;
            }
        }
        return best;
    }

    public synchronized String lastOutfit() {
        return data.lastOutfit;
    }

    public synchronized void setLastOutfit(String id) throws IOException {
        data.lastOutfit = id == null ? "" : id;
        save();
    }

    public synchronized List<Past> history() {
        return List.copyOf(data.history);
    }

    public synchronized void remember(byte[] png, boolean slim, String capeId, String name) throws IOException {
        SkinApi.checkSkin(png);
        String sha = sha1(png);
        Path file = root.resolve("history").resolve(sha + ".png");
        if (!Files.exists(file)) {
            Files.write(file, png);
        }
        data.history.removeIf(p -> p.sha1.equals(sha));
        Past p = new Past();
        p.sha1 = sha;
        p.slim = slim;
        p.capeId = capeId == null ? "" : capeId;
        p.at = System.currentTimeMillis();
        p.name = clean(name, 40);
        data.history.add(0, p);
        while (data.history.size() > MAX_HISTORY) {
            Past old = data.history.remove(data.history.size() - 1);
            Files.deleteIfExists(root.resolve("history").resolve(old.sha1 + ".png"));
        }
        save();
    }

    public byte[] historyBytes(Past p) throws IOException {
        if (!SHA1.matcher(p.sha1).matches()) {
            throw new IOException("That entry has no file");
        }
        return Files.readAllBytes(root.resolve("history").resolve(p.sha1 + ".png"));
    }

    public synchronized void exportTo(Path zip) throws IOException {
        Data copy = new Data();
        copy.skins = data.skins;
        copy.folders = data.folders;
        copy.outfits = data.outfits;
        copy.rules = data.rules;
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            out.putNextEntry(new ZipEntry("index.json"));
            out.write(GSON.toJson(copy).getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            for (Skin s : data.skins) {
                out.putNextEntry(new ZipEntry("skins/" + s.id + ".png"));
                out.write(Files.readAllBytes(root.resolve("skins").resolve(s.id + ".png")));
                out.closeEntry();
            }
        }
    }

    public synchronized int importFrom(Path zip) throws IOException {
        if (Files.size(zip) > 200L * 1024 * 1024) {
            throw new IOException("That file is too big to be a wardrobe");
        }
        Data in;
        Map<String, byte[]> files = new HashMap<>();
        try (ZipFile z = new ZipFile(zip.toFile())) {
            if (z.size() > MAX_SKINS + 5) {
                throw new IOException("That file holds too many entries to be a wardrobe");
            }
            ZipEntry index = z.getEntry("index.json");
            if (index == null || index.getSize() > 4_000_000) {
                throw new IOException("That file is not a Pentecost wardrobe");
            }
            try (InputStream s = z.getInputStream(index)) {
                in = GSON.fromJson(new String(s.readNBytes(4_000_001), StandardCharsets.UTF_8), Data.class);
            } catch (JsonParseException e) {
                throw new IOException("That file is not a Pentecost wardrobe");
            }
            if (in == null || in.skins == null) {
                throw new IOException("That file is not a Pentecost wardrobe");
            }
            for (Skin s : in.skins) {
                if (s == null || s.id == null || !ID.matcher(s.id).matches()) {
                    continue;
                }
                ZipEntry e = z.getEntry("skins/" + s.id + ".png");
                if (e != null && e.getSize() > 0 && e.getSize() <= 1_000_000) {
                    try (InputStream s2 = z.getInputStream(e)) {
                        files.put(s.id, s2.readNBytes(1_000_001));
                    }
                }
            }
        }
        int added = 0;
        Map<String, String> newIds = new HashMap<>();
        for (Skin s : in.skins) {
            byte[] png = s == null ? null : files.get(s.id);
            if (png == null || png.length > 1_000_000) {
                continue;
            }
            int before = data.skins.size();
            Skin made;
            try {
                made = addSkin(png, s.name, s.from);
            } catch (IOException e) {
                continue;
            }
            if (data.skins.size() > before) {
                added++;
                made.slim = s.slim;
                made.folder = clean(s.folder == null ? "" : s.folder, 30);
                if (!made.folder.isEmpty() && !data.folders.contains(made.folder)) {
                    data.folders.add(made.folder);
                }
                if (s.tags != null) {
                    s.tags.stream().map(t -> clean(t, 20).toLowerCase(Locale.ROOT)).filter(t -> !t.isEmpty()).distinct()
                            .limit(MAX_TAGS).forEach(made.tags::add);
                }
            }
            newIds.put(s.id, made.id);
        }
        Map<String, String> newOutfits = new HashMap<>();
        if (in.outfits != null) {
            for (Outfit o : in.outfits) {
                if (o == null || o.id == null || !newIds.containsKey(o.skinId)) {
                    continue;
                }
                String skinId = newIds.get(o.skinId);
                String name = clean(o.name, 40);
                String cape = o.capeId == null ? "" : clean(o.capeId, 64);
                Outfit same = data.outfits.stream()
                        .filter(x -> x.skinId.equals(skinId) && x.name.equals(name) && x.capeId.equals(cape)).findFirst().orElse(null);
                if (same != null) {
                    newOutfits.put(o.id, same.id);
                } else if (data.outfits.size() < 100) {
                    newOutfits.put(o.id, addOutfit(name, skinId, cape).id);
                }
            }
        }
        if (in.rules != null) {
            for (Rule r : in.rules) {
                if (r == null || r.outfitId == null || !newOutfits.containsKey(r.outfitId) || data.rules.size() >= 100) {
                    continue;
                }
                String outfitId = newOutfits.get(r.outfitId);
                String match = clean(r.match, 60);
                if (data.rules.stream().anyMatch(x -> x.outfitId.equals(outfitId) && x.kind.equals(r.kind) && x.match.equals(match))) {
                    continue;
                }
                try {
                    addRule(r.kind, match, outfitId);
                } catch (IOException e) {
                }
            }
        }
        save();
        return added;
    }

    private Skin require(String id) throws IOException {
        Skin s = skin(id);
        if (s == null) {
            throw new IOException("That skin is not in the wardrobe any more");
        }
        return s;
    }

    private static String newId(List<String> taken) {
        String id;
        do {
            byte[] b = new byte[4];
            RANDOM.nextBytes(b);
            id = HexFormat.of().formatHex(b);
        } while (taken.contains(id));
        return id;
    }

    static String clean(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.replaceAll("[\\p{Cntrl}§]", " ").replaceAll("\\s+", " ").strip();
        return t.length() > max ? t.substring(0, max).strip() : t;
    }

    public static String sha1(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public synchronized int sweep() throws IOException {
        Set<String> keep = new HashSet<>();
        data.skins.forEach(s -> keep.add(s.id + ".png"));
        int removed = 0;
        try (Stream<Path> s = Files.list(root.resolve("skins"))) {
            for (Path p : (Iterable<Path>) s::iterator) {
                if (!keep.contains(p.getFileName().toString())) {
                    Files.deleteIfExists(p);
                    removed++;
                }
            }
        }
        return removed;
    }
}
