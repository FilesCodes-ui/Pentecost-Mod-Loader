package khi.pentecost.core;

public final class WwlsCheck {
    private static int failures;

    private WwlsCheck() {}

    private static void expect(boolean ok, String what) {
        if (!ok) {
            failures++;
            System.out.println("FAIL: " + what);
        }
    }

    public static void main(String[] args) {
        expect(Wwls.validAddress("8.8.8.8:25565"), "public address");
        expect(Wwls.validAddress("203.0.113.9:1024"), "lowest port");
        expect(Wwls.validAddress("203.0.113.9:65535"), "highest port");

        String[] bad = {"10.0.0.5:25565", "127.0.0.1:25565", "192.168.1.20:25565", "172.16.0.1:25565", "172.31.255.255:25565",
                "169.254.10.10:25565", "100.64.0.1:25565", "100.127.255.255:25565", "0.0.0.0:25565", "224.0.0.1:25565",
                "255.255.255.255:25565", "192.0.0.8:25565", "198.18.0.1:25565"};
        for (String a : bad) {
            expect(!Wwls.validAddress(a), "refused " + a);
        }
        expect(Wwls.validAddress("172.32.0.1:25565"), "172.32 is public");
        expect(Wwls.validAddress("100.128.0.1:25565"), "100.128 is public");

        String[] odd = {"8.8.8.8:80", "8.8.8.8:1023", "8.8.8.8:65536", "8.8.8.8:0", "8.8.8.8", "8.8.8.8:", "8.8.8.8:+25565",
                "evil.example.com:25565", "localhost:25565", "[::1]:25565", "8.8.8.8:25565/extra", "8.8.8.8:25565 ", "256.1.1.1:25565",
                "1.2.3:25565", "", "8.8.8.8:2556500"};
        for (String a : odd) {
            expect(!Wwls.validAddress(a), "refused " + a);
        }
        expect(!Wwls.validAddress(null), "refused null");

        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < 2000; i++) {
            String c = Wwls.newCode();
            expect(c.length() == Wwls.CODE_LENGTH, "code length");
            expect(c.matches("[A-HJ-NP-Z2-9]{" + Wwls.CODE_LENGTH + "}"), "code alphabet " + c);
            expect(seen.add(c), "code repeated " + c);
        }
        expect(Wwls.cleanCode("abcd-efgh 23").equals("ABCDEFGH23"), "cleanCode");

        if (failures > 0) {
            System.out.println(failures + " check(s) failed");
            System.exit(1);
        }
        System.out.println("WwlsCheck passed");
    }
}
