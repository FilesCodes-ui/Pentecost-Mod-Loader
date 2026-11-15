package khi.pentecost.account;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class AccountInfoTest {
    private static final String PROFILE = """
            {"id":"069a79f444e94726a5befca90e38aaf5","name":"Notch",
             "skins":[{"id":"a","state":"INACTIVE","url":"http://x/old","variant":"CLASSIC"},
                      {"id":"b","state":"ACTIVE","url":"http://x/new","variant":"SLIM"}],
             "capes":[{"id":"c1","state":"INACTIVE","alias":"Migrator"},{"id":"c2","state":"ACTIVE","alias":"Mojang Office"}]}""";
    private static final String NAMECHANGE = "{\"changedAt\":\"2020-05-01T10:00:00Z\",\"createdAt\":\"2011-03-04T12:00:00Z\",\"nameChangeAllowed\":false}";
    private static final String ENTITLEMENTS = "{\"items\":[{\"name\":\"product_minecraft\"},{\"name\":\"game_minecraft\"}],\"keyId\":\"1\"}";
    private static final String ATTRIBUTES = """
            {"privileges":{"onlineChat":{"enabled":true},"multiplayerServer":{"enabled":true},
                           "multiplayerRealms":{"enabled":false},"telemetry":{"enabled":true}},
             "profanityFilterPreferences":{"profanityFilterOn":true},
             "banStatus":{"bannedScopes":{}}}""";

    private static String jwt(long exp) {
        Base64.Encoder e = Base64.getUrlEncoder().withoutPadding();
        return e.encodeToString("{\"alg\":\"HS256\"}".getBytes(StandardCharsets.UTF_8)) + "."
                + e.encodeToString(("{\"exp\":" + exp + "}").getBytes(StandardCharsets.UTF_8)) + ".sig";
    }

    @Test
    void readsEverything() {
        AccountInfo a = AccountInfo.parse(PROFILE, NAMECHANGE, ENTITLEMENTS, ATTRIBUTES, jwt(1_800_000_000L));
        assertEquals("Notch", a.name);
        assertEquals("SLIM", a.skinVariant);
        assertEquals("http://x/new", a.skinUrl);
        assertEquals(2, a.capes.size());
        assertEquals("Mojang Office", a.activeCape().alias());
        assertEquals(Instant.parse("2011-03-04T12:00:00Z"), a.createdAt);
        assertEquals(Boolean.FALSE, a.nameChangeAllowed);
        assertEquals(Boolean.TRUE, a.ownsJava);
        assertEquals(Boolean.TRUE, a.onlineChat);
        assertEquals(Boolean.FALSE, a.realms);
        assertEquals(Boolean.TRUE, a.profanityFilter);
        assertFalse(a.banned);
        assertEquals(Instant.ofEpochSecond(1_800_000_000L), a.tokenExpires);
        assertTrue(a.problems.isEmpty());
    }

    @Test
    void missingAnswersLeaveFieldsEmptyAndSayWhy() {
        AccountInfo a = AccountInfo.parse(PROFILE, null, "{\"error\":\"UNAUTHORIZED\"}", "not json", "not-a-jwt");
        assertEquals("Notch", a.name);
        assertNull(a.createdAt);
        assertNull(a.nameChangeAllowed);
        assertNull(a.ownsJava);
        assertNull(a.onlineChat);
        assertNull(a.tokenExpires);
        assertEquals(3, a.problems.size());
    }

    @Test
    void emptyProfileDoesNotThrow() {
        AccountInfo a = AccountInfo.parse(null, null, null, null, null);
        assertEquals("", a.name);
        assertEquals(4, a.problems.size());
        assertNull(a.activeCape());
    }

    @Test
    void banScopesAreSeen() {
        AccountInfo a = AccountInfo.parse(PROFILE, NAMECHANGE, ENTITLEMENTS,
                "{\"privileges\":{},\"banStatus\":{\"bannedScopes\":{\"MULTIPLAYER\":{\"banId\":\"x\"}}}}", null);
        assertTrue(a.banned);
        assertNull(a.onlineChat);
    }

    @Test
    void formatting() {
        assertEquals("069a79f4-44e9-4726-a5be-fca90e38aaf5", AccountInfo.dashed("069a79f444e94726a5befca90e38aaf5"));
        assertEquals("short", AccountInfo.dashed("short"));
        Instant now = Instant.parse("2026-11-14T00:00:00Z");
        assertEquals("today", AccountInfo.age(now.minusSeconds(3600), now));
        assertEquals("10 days", AccountInfo.age(now.minusSeconds(10L * 86400), now));
        assertEquals("5 months", AccountInfo.age(now.minusSeconds(160L * 86400), now));
        assertEquals("15 years, 8 months", AccountInfo.age(Instant.parse("2011-03-04T12:00:00Z"), now));
        assertEquals("in 2 h 5 min", AccountInfo.until(now.plusSeconds(2 * 3600 + 5 * 60), now));
        assertEquals("in 9 min", AccountInfo.until(now.plusSeconds(9 * 60), now));
        assertEquals("ended", AccountInfo.until(now.minusSeconds(5), now));
        assertEquals("unknown", AccountInfo.until(null, now));
    }
}
