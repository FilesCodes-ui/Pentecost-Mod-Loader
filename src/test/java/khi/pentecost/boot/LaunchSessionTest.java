package khi.pentecost.boot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LaunchSessionTest {
    private static final String FAKE_TOKEN = "eyJfake.token.value";

    @Test
    void readsASessionFromTypicalLauncherArguments() {
        String[] args = {"launch", "--forge", "--username", "Alex", "--version", "pentecost", "--gameDir", "C:\\g",
                "--assetsDir", "C:\\a", "--uuid", "069a79f444e94726a5befca90e38aaf5", "--accessToken", FAKE_TOKEN,
                "--userType", "msa", "--versionType", "release"};
        LaunchSession s = LaunchSession.parse(args);
        assertTrue(s.real());
        assertEquals("Alex", s.gameName());
        assertEquals("069a79f444e94726a5befca90e38aaf5", s.gameUuid());
        assertEquals(FAKE_TOKEN, s.gameToken());
        assertEquals("msa", s.gameType());
    }

    @Test
    void noArgumentsMeansTheDevSession() {
        LaunchSession s = LaunchSession.parse(new String[]{"launch", "--forge"});
        assertFalse(s.real());
        assertEquals("Dev", s.gameName());
        assertEquals("00000000-0000-0000-0000-000000000000", s.gameUuid());
        assertEquals("0", s.gameToken());
        assertEquals("legacy", s.gameType());
    }

    @Test
    void thePlaceholderTokenIsNotASession() {
        assertFalse(LaunchSession.parse(new String[]{"--username", "Player", "--uuid", "x", "--accessToken", "0"}).real());
        assertFalse(LaunchSession.parse(new String[]{"--username", "Player", "--uuid", "x", "--accessToken", " "}).real());
    }

    @Test
    void anIncompleteSessionIsNotUsed() {
        assertFalse(LaunchSession.parse(new String[]{"--username", "Alex", "--accessToken", FAKE_TOKEN}).real());
        assertFalse(LaunchSession.parse(new String[]{"--uuid", "u", "--accessToken", FAKE_TOKEN}).real());
        // A flag at the very end has no value and must not throw.
        assertFalse(LaunchSession.parse(new String[]{"--username"}).real());
    }

    @Test
    void userTypeDefaultsToMsa() {
        LaunchSession s = LaunchSession.parse(new String[]{"--username", "Alex", "--uuid", "u", "--accessToken", FAKE_TOKEN});
        assertEquals("msa", s.gameType());
    }

    @Test
    void theTokenNeverShowsInText() {
        LaunchSession s = LaunchSession.parse(new String[]{"--username", "Alex", "--uuid", "u", "--accessToken", FAKE_TOKEN});
        assertFalse(s.toString().contains(FAKE_TOKEN));
        assertTrue(s.toString().contains("Alex"));
    }
}
