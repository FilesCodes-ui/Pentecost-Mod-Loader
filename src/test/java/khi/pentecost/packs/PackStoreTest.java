package khi.pentecost.packs;

import khi.pentecost.packs.PackStore.Kind;
import khi.pentecost.packs.PackStore.Pick;
import khi.pentecost.packs.PackStore.Results;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackStoreTest {
    private static final String SHA1 = "0123456789abcdef0123456789abcdef01234567";
    private static final String SHA512 = "a".repeat(128);

    private static String version(String id, String type, String game, String file, String url, String sha1) {
        return "{\"id\":\"" + id + "\",\"version_number\":\"1." + id + "\",\"version_type\":\"" + type + "\","
                + "\"game_versions\":[\"" + game + "\"],\"files\":[{\"primary\":true,\"filename\":\"" + file + "\",\"url\":\"" + url
                + "\",\"size\":1000,\"hashes\":{\"sha1\":\"" + sha1 + "\",\"sha512\":\"" + SHA512 + "\"}}]}";
    }

    private static final String CDN = "https://cdn.modrinth.com/data/AAAAAAAA/versions/BBBBBBBB/pack.zip";

    @Test
    void searchUrlFiltersByTypeAndGameVersion() {
        String url = PackStore.searchUrl(Kind.RESOURCE, "faithful pack", "downloads", 40, "1.20.1");
        assertTrue(url.startsWith("https://api.modrinth.com/v2/search?query=faithful+pack&facets="));
        assertTrue(url.contains("project_type%3Aresourcepack"));
        assertTrue(url.contains("versions%3A1.20.1"));
        assertTrue(url.contains("&index=downloads"));
        assertTrue(url.endsWith("&limit=20&offset=40"));
        assertFalse(url.contains("iris"));
    }

    @Test
    void shaderSearchOnlyOffersFormatsOculusReads() {
        String url = PackStore.searchUrl(Kind.SHADER, "", "nonsense", -5, "1.20.1");
        assertTrue(url.contains("project_type%3Ashader"));
        assertTrue(url.contains("categories%3Airis"));
        assertTrue(url.contains("categories%3Aoptifine"));
        assertTrue(url.contains("&index=relevance"));
        assertTrue(url.endsWith("&offset=0"));
    }

    @Test
    void searchTextCannotBreakOutOfItsParameter() {
        String url = PackStore.searchUrl(Kind.RESOURCE, "a&index=newest#x", "relevance", 0, "1.20.1");
        assertTrue(url.contains("query=a%26index%3Dnewest%23x&"));
    }

    @Test
    void aBadGameVersionIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> PackStore.searchUrl(Kind.RESOURCE, "", "relevance", 0, "1.20.1\"],[\"x"));
    }

    @Test
    void readsSearchHitsAndDropsBadOnes() throws IOException {
        String json = "{\"total_hits\":57,\"hits\":["
                + "{\"project_id\":\"AANobbMI\",\"title\":\"Good \\u00a7cPack\",\"author\":\"someone\",\"description\":\"Line one\\nline two\",\"downloads\":1200},"
                + "{\"project_id\":\"../../etc\",\"title\":\"Bad\"},"
                + "\"not an object\"]}";
        Results r = PackStore.parseSearch(json);
        assertEquals(57, r.total());
        assertEquals(1, r.projects().size());
        assertEquals("Good cPack", r.projects().get(0).title().replace("  ", " "));
        assertEquals("Line one line two", r.projects().get(0).summary());
        assertEquals(1200, r.projects().get(0).downloads());
    }

    @Test
    void unreadableAnswersGiveAReadableError() {
        assertThrows(IOException.class, () -> PackStore.parseSearch("<html>"));
        assertThrows(IOException.class, () -> PackStore.parseSearch("[]"));
        assertThrows(IOException.class, () -> PackStore.parsePick("{}", "1.20.1"));
    }

    @Test
    void picksTheNewestReleaseForThisGame() throws IOException {
        String json = "[" + version("BETAAAAA", "beta", "1.20.1", "beta.zip", CDN, SHA1) + ","
                + version("RELEASE1", "release", "1.20.1", "new.zip", CDN, SHA1) + ","
                + version("OLDGAME1", "release", "1.19.2", "old.zip", CDN, SHA1) + ","
                + version("RELEASE2", "release", "1.20.1", "older.zip", CDN, SHA1) + "]";
        Pick p = PackStore.parsePick(json, "1.20.1");
        assertNotNull(p);
        assertEquals("RELEASE1", p.versionId());
        assertEquals("new.zip", p.filename());
        assertEquals(SHA512, p.sha512());
    }

    @Test
    void fallsBackToABetaWhenThereIsNoRelease() throws IOException {
        Pick p = PackStore.parsePick("[" + version("BETAAAAA", "beta", "1.20.1", "beta.zip", CDN, SHA1) + "]", "1.20.1");
        assertEquals("beta.zip", p.filename());
    }

    @Test
    void noVersionForThisGameMeansNoPick() throws IOException {
        assertNull(PackStore.parsePick("[" + version("OLDGAME1", "release", "1.19.2", "old.zip", CDN, SHA1) + "]", "1.20.1"));
    }

    @Test
    void refusesFilesItWillNotDownload() throws IOException {
        for (String[] bad : new String[][] {
                {"pack.jar", CDN, SHA1},
                {"../pack.zip", CDN, SHA1},
                {"sub/pack.zip", CDN, SHA1},
                {"C:pack.zip", CDN, SHA1},
                {"pack.zip", "http://cdn.modrinth.com/pack.zip", SHA1},
                {"pack.zip", "https://evilmodrinth.com/pack.zip", SHA1},
                {"pack.zip", "https://modrinth.com.evil.net/pack.zip", SHA1},
                {"pack.zip", "https://cdn.modrinth.com@evil.net/pack.zip", SHA1},
                {"pack.zip", CDN, "short"}}) {
            assertNull(PackStore.parsePick("[" + version("RELEASE1", "release", "1.20.1", bad[0], bad[1], bad[2]) + "]", "1.20.1"), bad[0] + " " + bad[1]);
        }
    }

    @Test
    void aBadFileInTheNewestVersionFallsBackToTheNextOne() throws IOException {
        String json = "[" + version("RELEASE1", "release", "1.20.1", "bad.jar", CDN, SHA1) + ","
                + version("RELEASE2", "release", "1.20.1", "good.zip", CDN, SHA1) + "]";
        assertEquals("good.zip", PackStore.parsePick(json, "1.20.1").filename());
    }

    @Test
    void hostRule() {
        assertTrue(PackStore.trustedUrl("https://cdn.modrinth.com/x.zip"));
        assertTrue(PackStore.trustedUrl("https://modrinth.com/x.zip"));
        assertFalse(PackStore.trustedUrl("https://notmodrinth.com/x.zip"));
        assertFalse(PackStore.trustedUrl("ftp://cdn.modrinth.com/x.zip"));
        assertFalse(PackStore.trustedUrl("not a url"));
        assertFalse(PackStore.trustedUrl(null));
    }

    @Test
    void installRefusesABadPickBeforeTouchingTheNetwork(@TempDir Path dir) {
        Pick bad = new Pick("RELEASE1", "1", "../x.zip", CDN, SHA1, "", 100);
        assertThrows(IOException.class, () -> PackStore.install(bad, dir));
        Pick huge = new Pick("RELEASE1", "1", "x.zip", CDN, SHA1, "", PackStore.MAX_BYTES + 1);
        assertThrows(IOException.class, () -> PackStore.install(huge, dir));
        assertThrows(IOException.class, () -> PackStore.install(null, dir));
    }

    @Test
    void verifyCatchesAWrongZipAndAWrongHash(@TempDir Path dir) throws IOException {
        Path notZip = dir.resolve("a.zip");
        Files.writeString(notZip, "this is not a zip");
        Pick p = new Pick("RELEASE1", "1", "a.zip", CDN, SHA1, "", 17);
        assertThrows(IOException.class, () -> PackStore.verify(notZip, p));

        Path zip = dir.resolve("b.zip");
        try (java.util.zip.ZipOutputStream z = new java.util.zip.ZipOutputStream(Files.newOutputStream(zip))) {
            z.putNextEntry(new java.util.zip.ZipEntry("pack.mcmeta"));
            z.write("{}".getBytes());
            z.closeEntry();
        }
        PackStore.verify(zip, new Pick("RELEASE1", "1", "b.zip", CDN, SHA1, "", 100));
        assertThrows(IOException.class, () -> PackStore.verify(zip, new Pick("RELEASE1", "1", "b.zip", CDN, SHA1, SHA512, 100)));
    }

    @Test
    void listsZipsAndFoldersOnly(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("B Pack.zip"), "x");
        Files.writeString(dir.resolve("notes.txt"), "x");
        Files.writeString(dir.resolve(".hidden.zip"), "x");
        Files.writeString(dir.resolve("half.zip.part"), "x");
        Files.createDirectory(dir.resolve("a folder pack"));
        List<PackStore.Local> list = PackStore.listLocal(dir, true);
        assertEquals(List.of("a folder pack", "B Pack.zip"), list.stream().map(PackStore.Local::name).toList());
        assertTrue(list.get(0).folder());
        assertNull(list.get(0).sha1());
        assertEquals(40, list.get(1).sha1().length());
        assertNull(PackStore.listLocal(dir, false).get(1).sha1());
        assertEquals(List.of(), PackStore.listLocal(dir.resolve("missing"), true));
    }

    @Test
    void deleteStaysInsideTheFolder(@TempDir Path dir) throws IOException {
        Path outside = Files.writeString(dir.resolve("outside.txt"), "keep");
        Path packs = Files.createDirectory(dir.resolve("packs"));
        Files.writeString(packs.resolve("one.zip"), "x");
        Files.createDirectories(packs.resolve("folder pack").resolve("assets"));
        Files.writeString(packs.resolve("folder pack").resolve("assets").resolve("a.png"), "x");

        for (String bad : new String[] {"../outside.txt", "..", "sub/one.zip", "", ".hidden", "C:x"}) {
            assertThrows(IOException.class, () -> PackStore.delete(packs, bad), bad);
        }
        assertTrue(Files.exists(outside));

        PackStore.delete(packs, "one.zip");
        PackStore.delete(packs, "folder pack");
        assertFalse(Files.exists(packs.resolve("one.zip")));
        assertFalse(Files.exists(packs.resolve("folder pack")));
        assertTrue(Files.exists(outside));
    }

    @Test
    void iconsAreFittedToSixtyFourSquare() throws Exception {
        java.awt.image.BufferedImage wide = new java.awt.image.BufferedImage(128, 64, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < 128; x++) {
            for (int y = 0; y < 64; y++) {
                wide.setRGB(x, y, 0xFFFF0000);
            }
        }
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(wide, "png", b);
        byte[] icon = PackStore.toIcon(b.toByteArray());
        org.junit.jupiter.api.Assertions.assertNotNull(icon);
        java.awt.image.BufferedImage back = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(icon));
        org.junit.jupiter.api.Assertions.assertEquals(64, back.getWidth());
        org.junit.jupiter.api.Assertions.assertEquals(64, back.getHeight());
        // Fitted and centred: a 2 to 1 image fills the middle rows only.
        org.junit.jupiter.api.Assertions.assertEquals(0, back.getRGB(32, 2) >>> 24);
        org.junit.jupiter.api.Assertions.assertEquals(0xFF, back.getRGB(32, 32) >>> 24);
        org.junit.jupiter.api.Assertions.assertNull(PackStore.toIcon(new byte[] {1, 2, 3}));
    }

    @Test
    void searchKeepsOnlyModrinthIcons() throws Exception {
        String json = "{\"total_hits\":2,\"hits\":["
                + "{\"project_id\":\"AANobbMI\",\"title\":\"A\",\"author\":\"x\",\"description\":\"d\",\"downloads\":1,\"icon_url\":\"https://cdn.modrinth.com/data/AANobbMI/icon.png\"},"
                + "{\"project_id\":\"BBNobbMI\",\"title\":\"B\",\"author\":\"x\",\"description\":\"d\",\"downloads\":1,\"icon_url\":\"http://evil.example/icon.png\"}]}";
        PackStore.Results r = PackStore.parseSearch(json);
        org.junit.jupiter.api.Assertions.assertEquals("https://cdn.modrinth.com/data/AANobbMI/icon.png", r.projects().get(0).icon());
        org.junit.jupiter.api.Assertions.assertEquals("", r.projects().get(1).icon());
    }

    @Test
    void localIconReadsPackPngAndStaysInTheFolder(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(img, "png", b);
        java.nio.file.Path zip = dir.resolve("p.zip");
        try (java.util.zip.ZipOutputStream z = new java.util.zip.ZipOutputStream(java.nio.file.Files.newOutputStream(zip))) {
            z.putNextEntry(new java.util.zip.ZipEntry("pack.png"));
            z.write(b.toByteArray());
            z.closeEntry();
        }
        org.junit.jupiter.api.Assertions.assertNotNull(PackStore.localIcon(dir, "p.zip"));
        org.junit.jupiter.api.Assertions.assertNull(PackStore.localIcon(dir, "../p.zip"));
        org.junit.jupiter.api.Assertions.assertNull(PackStore.localIcon(dir, "missing.zip"));
    }
}
