package khi.pentecost.core;

/** Headless check for spreading room codes across several rendezvous scripts. */
public final class WwlsShardCheck {
    private static int failures;

    private WwlsShardCheck() {}

    private static void expect(boolean ok, String what) {
        if (!ok) {
            failures++;
            System.out.println("FAIL: " + what);
        }
    }

    public static void main(String[] args) {
        for (int n = 1; n <= 32; n++) {
            boolean[] seen = new boolean[n];
            for (int shard = 0; shard < n; shard++) {
                for (int i = 0; i < 200; i++) {
                    String code = Wwls.newCode(shard, n);
                    expect(code.length() == Wwls.CODE_LENGTH, "length with " + n + " scripts");
                    expect(Wwls.shardOf(code, n) == shard, "a code made for script " + shard + " of " + n + " maps back to it: " + code);
                    seen[shard] = true;
                }
            }
            for (int s = 0; s < n; s++) {
                expect(seen[s], "every script gets codes with " + n + " scripts");
            }
        }
        expect(Wwls.shardOf("", 3) == 0 && Wwls.shardOf(null, 3) == 0, "no code goes to the first script");
        expect(Wwls.shardOf("0ABC", 3) == 0, "an unknown first character goes to the first script");
        expect(Wwls.backendFor(Wwls.newCode()).startsWith("https://script.google.com/macros/s/"), "backend is a Google script address");
        if (failures > 0) {
            System.out.println(failures + " check(s) failed");
            System.exit(1);
        }
        System.out.println("WwlsShardCheck passed");
    }
}
