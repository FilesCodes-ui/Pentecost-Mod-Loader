package khi.pentecost.update;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReleaseInfoTest {
    private static final String HASH = "a".repeat(64);

    private static String release(String assetName, String url, String digest) {
        return "{\"tag_name\":\"v0.3.1\",\"body\":\"Fixes things\",\"assets\":["
                + "{\"name\":\"menu-world-1.zip\",\"browser_download_url\":\"" + url + "\",\"size\":5,\"digest\":\"sha256:" + HASH + "\"},"
                + "{\"name\":\"" + assetName + "\",\"browser_download_url\":\"" + url + "\",\"size\":65670349,\"digest\":" + digest + "}]}";
    }

    private static final String GOOD_URL = "https://github.com/FilesCodes-ui/Pentecost-Mod-Loader/releases/download/v0.3.1/Pentecost-Installer-0.3.1.exe";

    @Test
    void readsTheInstallerAndItsChecksum() throws IOException {
        ReleaseInfo r = ReleaseInfo.parse(release("Pentecost-Installer-0.3.1.exe", GOOD_URL, "\"sha256:" + HASH.toUpperCase() + "\""));
        assertEquals("0.3.1", r.version());
        assertEquals("Pentecost-Installer-0.3.1.exe", r.assetName());
        assertEquals(HASH, r.sha256());
        assertEquals(65670349L, r.size());
        assertEquals("Fixes things", r.notes());
    }

    @Test
    void refusesAnInstallerWithoutAChecksum() {
        assertThrows(IOException.class, () -> ReleaseInfo.parse(release("Pentecost-Installer-0.3.1.exe", GOOD_URL, "null")));
    }

    @Test
    void refusesFilesFromOtherPlaces() {
        assertThrows(IOException.class, () -> ReleaseInfo.parse(release("Pentecost-Installer-0.3.1.exe",
                "https://example.com/Pentecost-Installer-0.3.1.exe", "\"sha256:" + HASH + "\"")));
    }

    @Test
    void ignoresOtherAssetNamesAndBadJson() {
        assertThrows(IOException.class, () -> ReleaseInfo.parse(release("setup.exe", GOOD_URL, "\"sha256:" + HASH + "\"")));
        assertThrows(IOException.class, () -> ReleaseInfo.parse("nope"));
        assertThrows(IOException.class, () -> ReleaseInfo.parse("{}"));
    }

    @Test
    void comparesVersions() {
        assertTrue(ReleaseInfo.newer("v0.3.1", "0.3.0"));
        assertTrue(ReleaseInfo.newer("0.10.0", "0.9.9"));
        assertTrue(ReleaseInfo.newer("1.0", "0.9.9"));
        assertFalse(ReleaseInfo.newer("0.3.0", "0.3.0"));
        assertFalse(ReleaseInfo.newer("0.2.9", "0.3.0"));
        assertFalse(ReleaseInfo.newer("0.3.1-beta", "0.3.1"));
        assertTrue(ReleaseInfo.newer("0.3.1", "0.3.1-beta"));
    }
}
