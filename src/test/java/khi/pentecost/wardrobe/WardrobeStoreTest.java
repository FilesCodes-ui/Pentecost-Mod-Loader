package khi.pentecost.wardrobe;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WardrobeStoreTest {
    /** A skin: opaque everywhere, except what the options say. */
    private static byte[] skin(int height, boolean slim, int seed) throws IOException {
        BufferedImage img = new BufferedImage(64, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < 64; x++) {
                img.setRGB(x, y, 0xFF000000 | ((seed * 40 + x * 3 + y) & 0xFFFFFF));
            }
        }
        // The hat area is mostly clear, like a real skin.
        for (int y = 0; y < 16; y++) {
            for (int x = 32; x < 64; x++) {
                img.setRGB(x, y, 0);
            }
        }
        if (slim) {
            for (int y = 20; y < 32; y++) {
                img.setRGB(54, y, 0);
                img.setRGB(55, y, 0);
            }
            for (int y = 52; y < 64; y++) {
                img.setRGB(46, y, 0);
                img.setRGB(47, y, 0);
            }
        }
        return SkinImage.toPng(img);
    }

    @Test
    void detectsTheArmModel() throws IOException {
        assertTrue(SkinImage.check(skin(64, true, 1)).slim());
        assertFalse(SkinImage.check(skin(64, false, 1)).slim());
        assertFalse(SkinImage.check(skin(32, false, 1)).slim());
        assertTrue(SkinImage.check(skin(32, false, 1)).legacy());
    }

    @Test
    void reportsWhatIsWrong() throws IOException {
        SkinImage.Report ok = SkinImage.check(skin(64, false, 1));
        assertTrue(ok.notes().isEmpty(), ok.notes().toString());

        BufferedImage hole = SkinImage.read(skin(64, false, 1));
        hole.setRGB(10, 10, 0x00FFFFFF);
        assertTrue(SkinImage.check(SkinImage.toPng(hole)).notes().stream().anyMatch(n -> n.contains("face")));

        BufferedImage solidHat = SkinImage.read(skin(64, false, 1));
        for (int y = 0; y < 16; y++) {
            for (int x = 32; x < 64; x++) {
                solidHat.setRGB(x, y, 0xFF123456);
            }
        }
        assertTrue(SkinImage.check(SkinImage.toPng(solidHat)).notes().stream().anyMatch(n -> n.contains("hat layer")));

        assertTrue(SkinImage.check(skin(32, false, 1)).notes().stream().anyMatch(n -> n.contains("Old 64 by 32")));
        BufferedImage empty = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        assertTrue(SkinImage.check(SkinImage.toPng(empty)).notes().get(0).contains("see-through"));
    }

    @Test
    void notASkinIsRefused() {
        assertThrows(IOException.class, () -> SkinImage.check(new byte[] {1, 2, 3}));
        assertThrows(IOException.class, () -> SkinImage.check(Files.readAllBytes(Path.of("build.gradle"))));
    }

    @Test
    void upgradingAnOldSkinMirrorsTheRightLimbsOntoTheLeft() throws IOException {
        BufferedImage old = SkinImage.read(skin(32, false, 3));
        BufferedImage up = SkinImage.read(SkinImage.upgradeLegacy(skin(32, false, 3)));
        assertEquals(64, up.getHeight());
        // Right leg front pixel (4,20) lands mirrored on the left leg front (20..23, 52..): x 23 is the mirror of x 4 in that block.
        assertEquals(old.getRGB(4, 20), up.getRGB(23, 52));
        assertEquals(old.getRGB(7, 31), up.getRGB(20, 63));
        // The original part is untouched.
        assertEquals(old.getRGB(10, 10), up.getRGB(10, 10));
        byte[] modern = skin(64, false, 3);
        assertSame(modern, SkinImage.upgradeLegacy(modern));
    }

    @Test
    void sameImageIsKeptOnce(@TempDir Path dir) throws IOException {
        WardrobeStore w = WardrobeStore.open(dir);
        WardrobeStore.Skin a = w.addSkin(skin(64, false, 1), "  Steve§c ", "file.png");
        WardrobeStore.Skin again = w.addSkin(skin(64, false, 1), "other", "x");
        assertSame(a, again);
        assertEquals(1, w.skins().size());
        assertEquals("Steve c", a.name);
        WardrobeStore.Skin b = w.addSkin(skin(64, true, 2), "", "Notch");
        assertEquals("Skin 2", b.name);
        assertTrue(b.slim);
        assertEquals(2, w.skins().size());
    }

    @Test
    void everythingSurvivesAReopen(@TempDir Path dir) throws IOException {
        WardrobeStore w = WardrobeStore.open(dir);
        WardrobeStore.Skin a = w.addSkin(skin(64, false, 1), "One", "f");
        w.setFolder(a.id, "Knights");
        w.addTag(a.id, " Red ");
        w.addTag(a.id, "red");
        WardrobeStore.Outfit o = w.addOutfit("Day", a.id, "cape123");
        w.addRule("server", "hypixel", o.id);
        w.setLastOutfit(o.id);

        WardrobeStore r = WardrobeStore.open(dir);
        assertEquals(1, r.skins().size());
        assertEquals("Knights", r.skins().get(0).folder);
        assertEquals(List.of("red"), r.skins().get(0).tags);
        assertEquals(List.of("Knights"), r.folders());
        assertEquals("cape123", r.outfits().get(0).capeId);
        assertEquals(o.id, r.rules().get(0).outfitId);
        assertEquals(o.id, r.lastOutfit());
        assertEquals(a.sha1, WardrobeStore.sha1(r.bytes(r.skins().get(0))));
    }

    @Test
    void deletingASkinTakesItsOutfitsAndRulesWithIt(@TempDir Path dir) throws IOException {
        WardrobeStore w = WardrobeStore.open(dir);
        WardrobeStore.Skin a = w.addSkin(skin(64, false, 1), "One", "");
        WardrobeStore.Skin b = w.addSkin(skin(64, false, 2), "Two", "");
        WardrobeStore.Outfit oa = w.addOutfit("A", a.id, "");
        WardrobeStore.Outfit ob = w.addOutfit("B", b.id, "");
        w.addRule("world", "Home", oa.id);
        w.addRule("world", "Away", ob.id);
        w.deleteSkin(a.id);
        assertEquals(1, w.skins().size());
        assertEquals(List.of(ob.id), w.outfits().stream().map(x -> x.id).toList());
        assertEquals(1, w.rules().size());
        assertFalse(Files.exists(dir.resolve("skins").resolve(a.id + ".png")));
    }

    @Test
    void rulesPickTheMostSpecificMatch(@TempDir Path dir) throws IOException {
        WardrobeStore w = WardrobeStore.open(dir);
        WardrobeStore.Skin a = w.addSkin(skin(64, false, 1), "One", "");
        WardrobeStore.Outfit o1 = w.addOutfit("Any", a.id, "");
        WardrobeStore.Outfit o2 = w.addOutfit("Mini", a.id, "");
        w.addRule("server", "example.com", o1.id);
        w.addRule("server", "play.example.com", o2.id);
        w.addRule("world", "Creative", o1.id);
        assertEquals(o2.id, w.match("server", "PLAY.Example.com:25565").outfitId);
        assertEquals(o1.id, w.match("server", "mc.example.com").outfitId);
        assertNull(w.match("server", "other.net"));
        assertEquals(o1.id, w.match("world", "creative").outfitId);
        assertNull(w.match("world", "Creative 2"));
        assertNull(w.match("server", ""));
        assertThrows(IOException.class, () -> w.addRule("server", "  ", o1.id));
        assertThrows(IOException.class, () -> w.addRule("planet", "x", o1.id));
        assertThrows(IOException.class, () -> w.addRule("server", "x", "nothing"));
    }

    @Test
    void historyKeepsTenAndOneCopyOfEach(@TempDir Path dir) throws IOException {
        WardrobeStore w = WardrobeStore.open(dir);
        for (int i = 0; i < 12; i++) {
            w.remember(skin(64, false, i), false, "", "Skin " + i);
        }
        assertEquals(10, w.history().size());
        assertEquals("Skin 11", w.history().get(0).name);
        w.remember(skin(64, false, 5), true, "c", "Skin 5 again");
        assertEquals(10, w.history().size());
        assertEquals("Skin 5 again", w.history().get(0).name);
        long files;
        try (var s = Files.list(dir.resolve("history"))) {
            files = s.count();
        }
        assertEquals(10, files);
        assertNotNull(w.historyBytes(w.history().get(0)));
    }

    @Test
    void searchAndFolders(@TempDir Path dir) throws IOException {
        WardrobeStore w = WardrobeStore.open(dir);
        WardrobeStore.Skin a = w.addSkin(skin(64, false, 1), "Red Knight", "");
        WardrobeStore.Skin b = w.addSkin(skin(64, false, 2), "Blue Mage", "Notch");
        w.addTag(b.id, "wizard");
        w.setFolder(a.id, "Armor");
        assertEquals(List.of(a.id), w.find("knight", "").stream().map(s -> s.id).toList());
        assertEquals(List.of(b.id), w.find("WIZ", "").stream().map(s -> s.id).toList());
        assertEquals(List.of(b.id), w.find("notch", "").stream().map(s -> s.id).toList());
        assertEquals(List.of(a.id), w.find("", "Armor").stream().map(s -> s.id).toList());
        w.removeFolder("Armor");
        assertEquals("", w.skin(a.id).folder);
        assertEquals(List.of(), w.folders());
    }

    @Test
    void exportThenImportCarriesOutfitsAndRules(@TempDir Path dir) throws IOException {
        WardrobeStore from = WardrobeStore.open(dir.resolve("a"));
        WardrobeStore.Skin s = from.addSkin(skin(64, true, 7), "Mage", "");
        from.setFolder(s.id, "Magic");
        from.addTag(s.id, "blue");
        WardrobeStore.Outfit o = from.addOutfit("Robe", s.id, "capeX");
        from.addRule("server", "spells.net", o.id);
        Path zip = dir.resolve("wardrobe.zip");
        from.exportTo(zip);

        WardrobeStore to = WardrobeStore.open(dir.resolve("b"));
        to.addSkin(skin(64, false, 9), "Local", "");
        assertEquals(1, to.importFrom(zip));
        assertEquals(0, to.importFrom(zip));
        assertEquals(2, to.skins().size());
        assertEquals(1, to.outfits().size());
        assertEquals(1, to.rules().size());
        WardrobeStore.Skin got = to.find("mage", "").get(0);
        assertTrue(got.slim);
        assertEquals("Magic", got.folder);
        assertEquals(List.of("blue"), got.tags);
        assertEquals(1, to.outfits().stream().filter(x -> x.skinId.equals(got.id)).count());
        assertNotNull(to.match("server", "spells.net"));
    }

    @Test
    void aBadImportFileIsRefused(@TempDir Path dir) throws IOException {
        WardrobeStore w = WardrobeStore.open(dir);
        Path junk = Files.writeString(dir.resolve("junk.zip"), "not a zip");
        assertThrows(IOException.class, () -> w.importFrom(junk));
    }

    @Test
    void aDamagedIndexIsKeptAsideNotLost(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("index.json"), "{ this is not json");
        WardrobeStore w = WardrobeStore.open(dir);
        assertEquals(0, w.skins().size());
        try (var s = Files.list(dir)) {
            assertTrue(s.anyMatch(p -> p.getFileName().toString().startsWith("index.broken-")));
        }
    }

    @Test
    void aHandEditedIndexCannotPointOutsideTheFolder(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("skins"));
        Files.writeString(dir.resolve("index.json"),
                "{\"skins\":[{\"id\":\"../../x\",\"name\":\"evil\"},{\"id\":\"zzzzzzzz\",\"name\":\"nofile\"}],"
                        + "\"history\":[{\"sha1\":\"../../etc/passwd\"}]}");
        WardrobeStore w = WardrobeStore.open(dir);
        assertEquals(0, w.skins().size());
        assertEquals(0, w.history().size());
    }

    @Test
    void theWardrobeHasALimit(@TempDir Path dir) throws IOException {
        WardrobeStore w = WardrobeStore.open(dir);
        WardrobeStore.Skin s = w.addSkin(skin(64, false, 1), "One", "");
        w.addTag(s.id, "a");
        assertThrows(IOException.class, () -> w.rename("nothing", "x"));
        for (int i = 0; i < 20; i++) {
            w.addTag(s.id, "tag" + i);
        }
        assertEquals(8, w.skin(s.id).tags.size());
    }
}
