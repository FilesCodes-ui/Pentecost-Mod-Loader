package khi.pentecost.adapter.forge;

import java.io.IOException;

public final class FabricBridgeCheck {
    private static int failures;

    private FabricBridgeCheck() {}

    private static boolean ok(String name) {
        try {
            FabricBridge.safeJarName(name);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static void expect(boolean ok, String what) {
        if (!ok) {
            failures++;
            System.out.println("FAIL: " + what);
        }
    }

    public static void main(String[] args) {
        expect(ok("forgified-fabric-api-0.92.2+1.5.4+1.20.1.jar"), "a normal file name");
        expect(ok("fabric_api-1.0.0.jar"), "underscores");
        String[] bad = {"..\\..\\evil.jar", "../evil.jar", "sub/evil.jar", "sub\\evil.jar", "C:\\evil.jar", "/etc/evil.jar",
                "evil.exe", "evil.jar.exe", "evil", "", ".jar", "a..b.jar", "evil.jar ", " evil.jar", "evil\u0000.jar",
                "x".repeat(200) + ".jar"};
        for (String n : bad) {
            expect(!ok(n), "refused " + n.replace("\u0000", "<NUL>"));
        }
        expect(!ok(null), "refused null");
        if (failures > 0) {
            System.out.println(failures + " check(s) failed");
            System.exit(1);
        }
        System.out.println("FabricBridgeCheck passed");
    }
}
