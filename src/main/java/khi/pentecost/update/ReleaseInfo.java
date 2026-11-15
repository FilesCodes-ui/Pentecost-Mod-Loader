package khi.pentecost.update;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The newest Pentecost release on GitHub, reduced to what an update needs: its version, its notes and the installer file
 * with the SHA-256 GitHub lists for it. Only files from Pentecost's own releases are accepted.
 */
public record ReleaseInfo(String tag, String version, String notes, String assetName, String url, String sha256, long size) {
    public static final String REPO = "FilesCodes-ui/Pentecost-Mod-Loader";
    public static final String LATEST_API = "https://api.github.com/repos/" + REPO + "/releases/latest";
    private static final String DOWNLOADS = "https://github.com/" + REPO + "/releases/download/";
    private static final Pattern INSTALLER = Pattern.compile("^Pentecost-Installer-[0-9][0-9A-Za-z.\\-]*\\.exe$");

    /** Reads the answer of GitHub's "latest release" call. Throws when the release has no installer, or no checksum to check it by. */
    public static ReleaseInfo parse(String json) throws IOException {
        JsonObject o;
        try {
            o = JsonParser.parseString(json).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IOException("GitHub answered with something unreadable");
        }
        if (!o.has("tag_name")) {
            throw new IOException("GitHub's answer has no release");
        }
        String tag = o.get("tag_name").getAsString();
        String notes = o.has("body") && !o.get("body").isJsonNull() ? o.get("body").getAsString() : "";
        if (o.has("assets")) {
            for (JsonElement a : o.getAsJsonArray("assets")) {
                JsonObject asset = a.getAsJsonObject();
                String name = asset.get("name").getAsString();
                if (!INSTALLER.matcher(name).matches()) {
                    continue;
                }
                String url = asset.get("browser_download_url").getAsString();
                if (!url.startsWith(DOWNLOADS)) {
                    throw new IOException("The installer is not hosted on Pentecost's releases");
                }
                String digest = asset.has("digest") && !asset.get("digest").isJsonNull() ? asset.get("digest").getAsString() : "";
                Matcher m = Pattern.compile("^sha256:([0-9a-fA-F]{64})$").matcher(digest);
                if (!m.matches()) {
                    throw new IOException("GitHub lists no checksum for the installer, so it cannot be checked");
                }
                long size = asset.has("size") ? asset.get("size").getAsLong() : -1;
                return new ReleaseInfo(tag, clean(tag), notes, name, url, m.group(1).toLowerCase(java.util.Locale.ROOT), size);
            }
        }
        throw new IOException("The newest release has no installer");
    }

    /** "v0.3.1" gives "0.3.1". */
    public static String clean(String tag) {
        String s = tag.trim();
        return s.startsWith("v") || s.startsWith("V") ? s.substring(1) : s;
    }

    /** True when latest is a higher version than current. Compares the dotted numbers; a version with a suffix like -beta is older than the plain one. */
    public static boolean newer(String latest, String current) {
        int[] a = numbers(clean(latest));
        int[] b = numbers(clean(current));
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length ? a[i] : 0;
            int y = i < b.length ? b[i] : 0;
            if (x != y) {
                return x > y;
            }
        }
        boolean latestPre = clean(latest).contains("-");
        boolean currentPre = clean(current).contains("-");
        return currentPre && !latestPre;
    }

    private static int[] numbers(String v) {
        String core = v.split("[-+]", 2)[0];
        String[] parts = core.split("\\.");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                out[i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException e) {
                out[i] = 0;
            }
        }
        return out;
    }
}
