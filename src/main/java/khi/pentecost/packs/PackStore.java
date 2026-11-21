package khi.pentecost.packs;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import khi.pentecost.install.Http;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import javax.imageio.ImageIO;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

public final class PackStore {
    public enum Kind {
        RESOURCE("resourcepack", "Resource packs"),
        SHADER("shader", "Shader packs");

        public final String type;
        public final String title;

        Kind(String type, String title) {
            this.type = type;
            this.title = title;
        }
    }

    public record Project(String id, String title, String author, String summary, long downloads, String icon) {}

    public record Results(List<Project> projects, int total) {}

    public record Pick(String versionId, String versionNumber, String filename, String url, String sha1, String sha512, long size) {}

    public record Local(String name, boolean folder, long size, String sha1) {}

    public record Known(String projectId, String title, String versionId, String versionNumber, Pick latest, String icon) {
        public boolean outdated() {
            return latest != null && !latest.versionId().equals(versionId);
        }
    }

    public static final int PAGE = 20;
    static final long MAX_BYTES = 512L * 1024 * 1024;

    private static final String API = "https://api.modrinth.com/v2";
    private static final Pattern GAME = Pattern.compile("[0-9A-Za-z.\\-]{1,24}");
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9]{6,12}");
    private static final Pattern SHA1 = Pattern.compile("[0-9a-fA-F]{40}");
    private static final Pattern SHA512 = Pattern.compile("[0-9a-fA-F]{128}");
    private static final Set<String> SORTS = Set.of("relevance", "downloads", "follows", "newest", "updated");
    private static final Map<String, String> HASHES = new ConcurrentHashMap<>();

    private PackStore() {}

    public static String searchUrl(Kind kind, String query, String sort, int offset, String game) {
        require(GAME, game, "game version");
        String facets = "[[\"project_type:" + kind.type + "\"],[\"versions:" + game + "\"]"
                + (kind == Kind.SHADER ? ",[\"categories:iris\",\"categories:optifine\"]" : "") + "]";
        String q = query == null ? "" : query.strip();
        if (q.length() > 80) {
            q = q.substring(0, 80);
        }
        return API + "/search?query=" + enc(q) + "&facets=" + enc(facets)
                + "&index=" + (SORTS.contains(sort) ? sort : "relevance")
                + "&limit=" + PAGE + "&offset=" + Math.max(0, offset);
    }

    public static Results parseSearch(String json) throws IOException {
        JsonObject root = object(json);
        List<Project> out = new ArrayList<>();
        JsonArray hits = root.has("hits") && root.get("hits").isJsonArray() ? root.getAsJsonArray("hits") : new JsonArray();
        for (JsonElement e : hits) {
            if (!e.isJsonObject()) {
                continue;
            }
            JsonObject h = e.getAsJsonObject();
            String id = str(h, "project_id");
            if (!ID.matcher(id).matches()) {
                continue;
            }
            out.add(new Project(id, clean(str(h, "title")), clean(str(h, "author")), clean(str(h, "description")), num(h, "downloads"),
                    trustedUrl(str(h, "icon_url")) ? str(h, "icon_url") : ""));
        }
        return new Results(List.copyOf(out), (int) Math.min(Integer.MAX_VALUE, num(root, "total_hits")));
    }

    public static Pick parsePick(String versionsJson, String game) throws IOException {
        JsonArray versions = array(versionsJson);
        List<JsonObject> releases = new ArrayList<>();
        List<JsonObject> others = new ArrayList<>();
        for (JsonElement e : versions) {
            if (!e.isJsonObject()) {
                continue;
            }
            JsonObject v = e.getAsJsonObject();
            if (!lists(v, "game_versions", game)) {
                continue;
            }
            ("release".equals(str(v, "version_type")) ? releases : others).add(v);
        }
        for (JsonObject v : concat(releases, others)) {
            Pick p = pickOf(v);
            if (p != null) {
                return p;
            }
        }
        return null;
    }

    static Pick pickOf(JsonObject version) {
        String versionId = str(version, "id");
        if (!ID.matcher(versionId).matches() || !version.has("files") || !version.get("files").isJsonArray()) {
            return null;
        }
        JsonObject file = null;
        for (JsonElement e : version.getAsJsonArray("files")) {
            if (!e.isJsonObject()) {
                continue;
            }
            JsonObject f = e.getAsJsonObject();
            if (file == null || (f.has("primary") && f.get("primary").isJsonPrimitive() && f.get("primary").getAsBoolean())) {
                file = f;
            }
        }
        if (file == null) {
            return null;
        }
        String name = str(file, "filename");
        String url = str(file, "url");
        long size = num(file, "size");
        JsonObject hashes = file.has("hashes") && file.get("hashes").isJsonObject() ? file.getAsJsonObject("hashes") : new JsonObject();
        String sha1 = str(hashes, "sha1");
        String sha512 = str(hashes, "sha512");
        if (!safeName(name) || !trustedUrl(url) || !SHA1.matcher(sha1).matches() || size <= 0 || size > MAX_BYTES) {
            return null;
        }
        return new Pick(versionId, clean(str(version, "version_number")), name, url, sha1.toLowerCase(Locale.ROOT),
                SHA512.matcher(sha512).matches() ? sha512.toLowerCase(Locale.ROOT) : "", size);
    }

    public static boolean safeName(String name) {
        if (name == null || name.isEmpty() || name.length() > 120 || name.startsWith(".") || name.contains("..")
                || !name.toLowerCase(Locale.ROOT).endsWith(".zip")) {
            return false;
        }
        for (char c : name.toCharArray()) {
            if (c < 32 || "/\\:<>\"|?*".indexOf(c) >= 0) {
                return false;
            }
        }
        return true;
    }

    public static boolean trustedUrl(String url) {
        try {
            URI u = URI.create(url);
            String host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
            return "https".equalsIgnoreCase(u.getScheme()) && u.getUserInfo() == null
                    && (host.equals("modrinth.com") || host.endsWith(".modrinth.com"));
        } catch (IllegalArgumentException | NullPointerException e) {
            return false;
        }
    }

    public static Results search(Kind kind, String query, String sort, int offset, String game) throws IOException {
        return parseSearch(Http.text(searchUrl(kind, query, sort, offset, game)));
    }

    public static Pick latest(String projectId, String game) throws IOException {
        require(ID, projectId, "project");
        require(GAME, game, "game version");
        Pick p = parsePick(Http.text(API + "/project/" + projectId + "/version?game_versions=" + enc("[\"" + game + "\"]")), game);
        if (p == null) {
            throw new IOException("No version of this pack is made for Minecraft " + game);
        }
        return p;
    }

    public static Map<String, Known> identify(Collection<String> sha1s, String game) throws IOException {
        List<String> hashes = sha1s.stream().filter(h -> h != null && SHA1.matcher(h).matches()).map(h -> h.toLowerCase(Locale.ROOT))
                .distinct().limit(200).toList();
        if (hashes.isEmpty()) {
            return Map.of();
        }
        JsonObject body = new JsonObject();
        JsonArray list = new JsonArray();
        hashes.forEach(list::add);
        body.add("hashes", list);
        body.addProperty("algorithm", "sha1");
        JsonObject found = object(Http.postJson(API + "/version_files", body.toString()));

        Set<String> projects = new LinkedHashSet<>();
        for (String h : hashes) {
            if (found.has(h) && found.get(h).isJsonObject()) {
                String pid = str(found.getAsJsonObject(h), "project_id");
                if (ID.matcher(pid).matches()) {
                    projects.add(pid);
                }
            }
        }
        Map<String, String> icons = new HashMap<>();
        Map<String, String> titles = titles(projects, icons);
        Map<String, Pick> newest = new HashMap<>();
        for (String pid : projects) {
            try {
                newest.put(pid, latest(pid, game));
            } catch (IOException e) {
            }
        }
        Map<String, Known> out = new HashMap<>();
        for (String h : hashes) {
            if (!found.has(h) || !found.get(h).isJsonObject()) {
                continue;
            }
            JsonObject v = found.getAsJsonObject(h);
            String pid = str(v, "project_id");
            if (!projects.contains(pid)) {
                continue;
            }
            out.put(h, new Known(pid, titles.getOrDefault(pid, "Modrinth pack"), str(v, "id"), clean(str(v, "version_number")), newest.get(pid),
                    icons.getOrDefault(pid, "")));
        }
        return out;
    }

    private static Map<String, String> titles(Set<String> projects, Map<String, String> icons) {
        Map<String, String> out = new HashMap<>();
        if (projects.isEmpty()) {
            return out;
        }
        try {
            JsonArray ids = new JsonArray();
            projects.forEach(ids::add);
            for (JsonElement e : array(Http.text(API + "/projects?ids=" + enc(ids.toString())))) {
                if (e.isJsonObject()) {
                    out.put(str(e.getAsJsonObject(), "id"), clean(str(e.getAsJsonObject(), "title")));
                    String icon = str(e.getAsJsonObject(), "icon_url");
                    if (trustedUrl(icon)) {
                        icons.put(str(e.getAsJsonObject(), "id"), icon);
                    }
                }
            }
        } catch (IOException e) {
        }
        return out;
    }

    static final int ICON = 64;
    private static final int MAX_ICON_BYTES = 1_000_000;
    private static final HttpClient ICON_HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    public static byte[] icon(String url) {
        if (!trustedUrl(url)) {
            return null;
        }
        try {
            HttpResponse<InputStream> r = ICON_HTTP.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20))
                    .header("User-Agent", Http.USER_AGENT).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = r.body()) {
                if (r.statusCode() != 200) {
                    return null;
                }
                byte[] b = in.readNBytes(MAX_ICON_BYTES + 1);
                return b.length > MAX_ICON_BYTES ? null : toIcon(b);
            }
        } catch (IOException | RuntimeException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    public static byte[] localIcon(Path folder, String name) {
        try {
            Path root = folder.toAbsolutePath().normalize();
            Path target = root.resolve(name).normalize();
            if (!root.equals(target.getParent())) {
                return null;
            }
            if (Files.isDirectory(target)) {
                Path png = target.resolve("pack.png");
                return Files.isRegularFile(png) && Files.size(png) <= MAX_ICON_BYTES ? toIcon(Files.readAllBytes(png)) : null;
            }
            try (ZipFile z = new ZipFile(target.toFile())) {
                var e = z.getEntry("pack.png");
                if (e == null || e.getSize() > MAX_ICON_BYTES) {
                    return null;
                }
                try (InputStream in = z.getInputStream(e)) {
                    return toIcon(in.readNBytes(MAX_ICON_BYTES));
                }
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    static byte[] toIcon(byte[] data) {
        try {
            BufferedImage src = ImageIO.read(new ByteArrayInputStream(data));
            if (src == null || src.getWidth() <= 0 || src.getHeight() <= 0 || src.getWidth() > 4096 || src.getHeight() > 4096) {
                return null;
            }
            BufferedImage out = new BufferedImage(ICON, ICON, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = out.createGraphics();
            boolean pixel = src.getWidth() <= ICON && src.getHeight() <= ICON;
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, pixel ? RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR
                    : RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            double s = Math.min((double) ICON / src.getWidth(), (double) ICON / src.getHeight());
            int w = Math.max(1, (int) Math.round(src.getWidth() * s));
            int h = Math.max(1, (int) Math.round(src.getHeight() * s));
            g.drawImage(src, (ICON - w) / 2, (ICON - h) / 2, w, h, null);
            g.dispose();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            return ImageIO.write(out, "png", bytes) ? bytes.toByteArray() : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    public static List<Local> listLocal(Path folder, boolean hash) throws IOException {
        if (!Files.isDirectory(folder)) {
            return List.of();
        }
        List<Local> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(folder)) {
            for (Path p : (Iterable<Path>) s.sorted(Comparator.comparing(x -> x.getFileName().toString().toLowerCase(Locale.ROOT)))::iterator) {
                String name = p.getFileName().toString();
                if (name.startsWith(".")) {
                    continue;
                }
                if (Files.isDirectory(p)) {
                    out.add(new Local(name, true, 0, null));
                } else if (name.toLowerCase(Locale.ROOT).endsWith(".zip") && Files.isRegularFile(p)) {
                    out.add(new Local(name, false, Files.size(p), hash ? sha1Of(p) : null));
                }
            }
        }
        return List.copyOf(out);
    }

    private static String sha1Of(Path p) throws IOException {
        String key = p.toAbsolutePath() + "|" + Files.size(p) + "|" + Files.getLastModifiedTime(p).toMillis();
        String known = HASHES.get(key);
        if (known != null) {
            return known;
        }
        String h = Http.sha1(p);
        HASHES.put(key, h);
        return h;
    }

    public static Path install(Pick pick, Path folder) throws IOException {
        if (pick == null || !safeName(pick.filename()) || !trustedUrl(pick.url()) || !SHA1.matcher(pick.sha1()).matches()
                || pick.size() <= 0 || pick.size() > MAX_BYTES) {
            throw new IOException("Modrinth gave a file entry that Pentecost will not download");
        }
        Files.createDirectories(folder);
        Path root = folder.toAbsolutePath().normalize();
        Path dest = root.resolve(pick.filename()).normalize();
        if (!root.equals(dest.getParent())) {
            throw new IOException("The file name points outside the pack folder");
        }
        if (Files.exists(dest)) {
            if (Http.sha1(dest).equalsIgnoreCase(pick.sha1())) {
                return dest;
            }
            String base = pick.filename().substring(0, pick.filename().length() - 4);
            dest = root.resolve(base + "-" + pick.sha1().substring(0, 6) + ".zip");
        }
        Http.download(pick.url(), dest, pick.sha1());
        try {
            verify(dest, pick);
        } catch (IOException e) {
            Files.deleteIfExists(dest);
            throw e;
        }
        return dest;
    }

    static void verify(Path file, Pick pick) throws IOException {
        if (Files.size(file) > MAX_BYTES) {
            throw new IOException("The pack is larger than Pentecost allows");
        }
        if (!pick.sha512().isEmpty() && !sha512(file).equalsIgnoreCase(pick.sha512())) {
            throw new IOException("The download did not match its checksum");
        }
        try (ZipFile zip = new ZipFile(file.toFile())) {
            if (zip.size() == 0) {
                throw new IOException("The pack is empty");
            }
        } catch (ZipException e) {
            throw new IOException("The download is not a valid zip pack");
        }
    }

    private static String sha512(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-512");
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static void delete(Path folder, String name) throws IOException {
        if (name == null || name.isEmpty() || name.startsWith(".") || name.contains("/") || name.contains("\\") || name.contains(":")) {
            throw new IOException("That is not a pack name");
        }
        Path root = folder.toAbsolutePath().normalize();
        Path target = root.resolve(name).normalize();
        if (!root.equals(target.getParent())) {
            throw new IOException("That pack is outside the pack folder");
        }
        if (Files.isSymbolicLink(target) || !Files.isDirectory(target)) {
            Files.delete(target);
            return;
        }
        List<Path> all;
        try (Stream<Path> walk = Files.walk(target)) {
            all = walk.sorted(Comparator.reverseOrder()).toList();
        }
        for (Path p : all) {
            if (!p.normalize().startsWith(target)) {
                throw new IOException("A file in the pack points outside it");
            }
            Files.delete(p);
        }
    }

    private static void require(Pattern pattern, String value, String what) {
        if (value == null || !pattern.matcher(value).matches()) {
            throw new IllegalArgumentException("Not a valid " + what + ": " + value);
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static JsonObject object(String json) throws IOException {
        try {
            JsonElement e = JsonParser.parseString(json);
            if (e.isJsonObject()) {
                return e.getAsJsonObject();
            }
        } catch (JsonParseException | IllegalStateException | NullPointerException e) {
        }
        throw new IOException("Modrinth sent an answer Pentecost could not read");
    }

    private static JsonArray array(String json) throws IOException {
        try {
            JsonElement e = JsonParser.parseString(json);
            if (e.isJsonArray()) {
                return e.getAsJsonArray();
            }
        } catch (JsonParseException | IllegalStateException | NullPointerException e) {
        }
        throw new IOException("Modrinth sent an answer Pentecost could not read");
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : "";
    }

    private static long num(JsonObject o, String key) {
        JsonElement e = o.get(key);
        try {
            return e != null && e.isJsonPrimitive() ? e.getAsLong() : 0;
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private static boolean lists(JsonObject o, String key, String value) {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonArray()) {
            return false;
        }
        for (JsonElement x : e.getAsJsonArray()) {
            if (x.isJsonPrimitive() && x.getAsString().equals(value)) {
                return true;
            }
        }
        return false;
    }

    static String clean(String s) {
        String t = s.replaceAll("[\\p{Cntrl}\u00A7]", " ").replaceAll("\\s+", " ").strip();
        return t.length() > 300 ? t.substring(0, 300) : t;
    }

    private static List<JsonObject> concat(List<JsonObject> a, List<JsonObject> b) {
        List<JsonObject> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }
}
