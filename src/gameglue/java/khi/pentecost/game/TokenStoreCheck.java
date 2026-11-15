package khi.pentecost.game;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Headless check for TokenStore. It needs no game and writes only to a temporary folder. */
public final class TokenStoreCheck {
    private static int failures;

    private TokenStoreCheck() {}

    private static void expect(boolean ok, String what) {
        if (!ok) {
            failures++;
            System.out.println("FAIL: " + what);
        }
    }

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("pentecost-token-check");
        try {
            Path file = dir.resolve("pentecost-account.json");
            String secret = "M.C509_BAY.0.U.-Check-Refresh-Token-1234567890abcdef";

            // Saved, then read back whole.
            TokenStore.save(file, "Steve", "069a79f444e94726a5befca90e38aaf5", secret);
            String onDisk = Files.readString(file, StandardCharsets.UTF_8);
            boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
            if (windows) {
                expect(!onDisk.contains(secret), "the token must not appear in the file on Windows");
                expect(!onDisk.contains("Check-Refresh"), "no piece of the token in the file on Windows");
                expect(onDisk.contains("dpapi"), "file says it is protected");
            }
            TokenStore.Saved back = TokenStore.load(file);
            expect(back != null && back.refresh().equals(secret), "token comes back unchanged");
            expect(back != null && back.name().equals("Steve"), "name comes back");
            expect(back != null && back.uuid().equals("069a79f444e94726a5befca90e38aaf5"), "uuid comes back");
            expect(!Files.exists(dir.resolve("pentecost-account.json.tmp")), "no temporary file left behind");

            // An old plain text file is read once and rewritten protected.
            Files.writeString(file, "{\"refresh\":\"" + secret + "\",\"name\":\"Alex\",\"uuid\":\"abc\"}", StandardCharsets.UTF_8);
            TokenStore.Saved old = TokenStore.load(file);
            expect(old != null && old.refresh().equals(secret) && old.name().equals("Alex"), "old file still signs in");
            String migrated = Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
            if (windows) {
                expect(!migrated.contains(secret), "old plain text file was replaced by a protected one");
            }
            expect(!migrated.contains("\"refresh\""), "old plain field is gone");

            // A damaged protected file is removed, not trusted.
            if (windows) {
                Files.writeString(file, "{\"v\":2,\"name\":\"Bad\",\"uuid\":\"x\",\"protection\":\"dpapi\",\"token\":\"AAAA\"}",
                        StandardCharsets.UTF_8);
                expect(TokenStore.load(file) == null, "a token that cannot be decrypted is refused");
                expect(!Files.exists(file), "and the unusable file is removed");
            }
        } finally {
            try (var walk = Files.walk(dir)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
        if (failures > 0) {
            System.out.println(failures + " check(s) failed");
            System.exit(1);
        }
        System.out.println("TokenStoreCheck passed");
    }
}
