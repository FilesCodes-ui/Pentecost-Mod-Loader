package khi.pentecost.account;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkinApiTest {
    private static String profile(String texturesJson) {
        String value = Base64.getEncoder().encodeToString(texturesJson.getBytes(StandardCharsets.UTF_8));
        return "{\"id\":\"abc\",\"name\":\"Steve\",\"properties\":[{\"name\":\"textures\",\"value\":\"" + value + "\"}]}";
    }

    private static byte[] png(int w, int h) {
        byte[] b = new byte[40];
        byte[] sig = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        System.arraycopy(sig, 0, b, 0, 8);
        b[19] = (byte) w;
        b[23] = (byte) h;
        return b;
    }

    @Test
    void readsSkinModelAndCape() throws IOException {
        SkinApi.Textures t = SkinApi.parseTextures(profile("{\"textures\":{\"SKIN\":{\"url\":\"https://textures.minecraft.net/texture/s\","
                + "\"metadata\":{\"model\":\"slim\"}},\"CAPE\":{\"url\":\"https://textures.minecraft.net/texture/c\"}}}"));
        assertEquals("https://textures.minecraft.net/texture/s", t.skinUrl());
        assertTrue(t.slim());
        assertEquals("https://textures.minecraft.net/texture/c", t.capeUrl());
    }

    @Test
    void classicSkinWithoutCape() throws IOException {
        SkinApi.Textures t = SkinApi.parseTextures(profile("{\"textures\":{\"SKIN\":{\"url\":\"https://x/s\"}}}"));
        assertFalse(t.slim());
        assertEquals("", t.capeUrl());
    }

    @Test
    void refusesUnreadableProfiles() {
        assertThrows(IOException.class, () -> SkinApi.parseTextures("nope"));
        assertThrows(IOException.class, () -> SkinApi.parseTextures("{\"properties\":[]}"));
    }

    @Test
    void readsIds() throws IOException {
        assertEquals("069a79f444e94726a5befca90e38aaf5", SkinApi.parseId("{\"id\":\"069A79F444E94726A5BEFCA90E38AAF5\",\"name\":\"Notch\"}"));
        assertThrows(IOException.class, () -> SkinApi.parseId("{\"id\":\"short\"}"));
        assertThrows(IOException.class, () -> SkinApi.parseId("[]"));
    }

    @Test
    void checksNames() {
        assertTrue(SkinApi.isName("Notch"));
        assertTrue(SkinApi.isName("a_b_1"));
        assertFalse(SkinApi.isName("ab"));
        assertFalse(SkinApi.isName("has space"));
        assertFalse(SkinApi.isName("https://x.com/a.png"));
        assertFalse(SkinApi.isName(null));
    }

    @Test
    void checksSkinSizes() throws IOException {
        SkinApi.checkSkin(png(64, 64));
        SkinApi.checkSkin(png(64, 32));
        assertThrows(IOException.class, () -> SkinApi.checkSkin(png(128, 128)));
        assertThrows(IOException.class, () -> SkinApi.checkSkin(new byte[100]));
        assertNull(SkinApi.pngSize(new byte[10]));
        assertArrayEquals(new int[] {64, 32}, SkinApi.pngSize(png(64, 32)));
    }

    @Test
    void buildsTheUpload() {
        byte[] body = SkinApi.multipart("B", true, new byte[] {1, 2, 3});
        String text = new String(body, StandardCharsets.ISO_8859_1);
        assertTrue(text.startsWith("--B\r\n"));
        assertTrue(text.contains("name=\"variant\"\r\n\r\nslim\r\n"));
        assertTrue(text.contains("name=\"file\"; filename=\"skin.png\""));
        assertTrue(text.endsWith("\r\n--B--\r\n"));
        assertTrue(new String(SkinApi.multipart("B", false, new byte[0]), StandardCharsets.ISO_8859_1).contains("classic"));
    }

    @Test
    void describesFailures() {
        assertTrue(SkinApi.describe(429, "").contains("Wait"));
        assertTrue(SkinApi.describe(401, "").contains("launcher"));
        assertTrue(SkinApi.describe(400, "{\"errorMessage\":\"Invalid skin\"}").contains("Invalid skin"));
        assertEquals("Mojang answered 500", SkinApi.describe(500, ""));
    }
}
