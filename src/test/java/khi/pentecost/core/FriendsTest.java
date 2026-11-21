package khi.pentecost.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FriendsTest {
    @TempDir
    Path dir;

    @AfterEach
    void reset() {
        Friends.forget();
    }

    @Test
    void codeIsMadeOnceAndKept() {
        Friends.use(dir.resolve("friends.json"));
        String a = Friends.myCode();
        assertEquals(Wwls.CODE_LENGTH, a.length());
        Friends.forget();
        Friends.use(dir.resolve("friends.json"));
        assertEquals(a, Friends.myCode());
    }

    @Test
    void addFromSharedLine() {
        Friends.use(dir.resolve("friends.json"));
        String msg = Friends.add("Victor2812:ABCDEFGHJK");
        assertTrue(msg.contains("Victor2812"), msg);
        assertEquals("Victor2812", Friends.list().get(0).name());
        assertEquals("ABCDEFGHJK", Friends.list().get(0).code());
        assertTrue(Friends.add("abcdefghjk").contains("already"));
    }

    @Test
    void badCodesAndOwnCodeAreRefused() {
        Friends.use(dir.resolve("friends.json"));
        assertTrue(Friends.add("short").contains("letters"));
        assertTrue(Friends.add(Friends.myCode()).contains("own"));
        assertEquals(0, Friends.list().size());
    }

    @Test
    void namesAreCleaned() {
        assertEquals("Bob the 3rd", Friends.cleanName(" Bob <the> 3rd! "));
    }

    @Test
    void removeAndSurviveReload() {
        Friends.use(dir.resolve("friends.json"));
        Friends.add("A:ABCDEFGHJK");
        Friends.add("B:BCDEFGHJKL");
        Friends.remove("ABCDEFGHJK");
        Friends.forget();
        Friends.use(dir.resolve("friends.json"));
        assertEquals(1, Friends.list().size());
        assertEquals("B", Friends.list().get(0).name());
    }
}
