package khi.pentecost.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModImpactTest {
    private static final ModImpact.Owners OWNERS = new ModImpact.Owners() {
        @Override
        public String owner(String c, String m, String d) {
            if (c.startsWith("slow.") || c.startsWith(Busy.class.getName())) {
                return "slowmod";
            }
            if (c.startsWith("kotlin.")) {
                return "kotlin";
            }
            if (c.startsWith("other.")) {
                return "othermod";
            }
            return null;
        }

        @Override
        public boolean passThrough(String mod) {
            return mod.equals("kotlin");
        }
    };

    @AfterEach
    void clean() {
        ModImpact.stop(Map.of(), null);
    }

    private static String[] f(String c) {
        return new String[]{c, "m", "()V"};
    }

    @Test
    void theFirstModFromTheTopPays() {
        assertEquals("slowmod", ModImpact.charge(List.of(f("net.minecraft.A"), f("slow.B"), f("other.C")), OWNERS));
        assertEquals("othermod", ModImpact.charge(List.of(f("other.C"), f("slow.B")), OWNERS));
        assertEquals(ModImpact.GAME, ModImpact.charge(List.of(f("net.minecraft.A"), f("java.lang.Thread")), OWNERS));
    }

    @Test
    void librariesPassTheirCostOn() {
        assertEquals("slowmod", ModImpact.charge(List.of(f("kotlin.collections.X"), f("slow.B")), OWNERS));
        assertEquals("kotlin", ModImpact.charge(List.of(f("kotlin.collections.X"), f("net.minecraft.A")), OWNERS));
    }

    @Test
    void detailFindsHotMethodAndCaller() {
        String[] d = ModImpact.detail(List.of(f("net.minecraft.A"), f("slow.B"), f("slow.C"), f("net.minecraft.Mob"), f("java.lang.Thread")), OWNERS, "slowmod");
        assertEquals("B.m", d[0]);
        assertEquals("Mob.m", d[1]);
        String[] end = ModImpact.detail(List.<String[]>of(f("slow.B")), OWNERS, "slowmod");
        assertEquals("(thread start)", end[1]);
    }

    @Test
    void hiddenAndLambdaClassesBelongToTheirOuterClass() {
        assertEquals("a/b/Foo", ModOwners.internal("a.b.Foo$$Lambda/0x000001234"));
        assertEquals("a/b/Foo", ModOwners.internal("a.b.Foo$$Lambda$12/0x00"));
        assertEquals("a/b/Foo$Inner", ModOwners.internal("a.b.Foo$Inner"));
        assertEquals("fabric_api", ModOwners.group("fabric_rendering_v1"));
        assertEquals("fabric_api", ModOwners.group("fabric-api"));
        assertEquals("create", ModOwners.group("create"));
    }

    static final class Busy {
        static volatile double sink;

        static void spin(long until) {
            double x = 0;
            while (System.nanoTime() < until) {
                for (int i = 0; i < 10_000; i++) {
                    x += Math.sqrt(i + x);
                }
            }
            sink = x;
        }
    }

    @Test
    void theFlightRecorderSeesTheBusyMod() throws Exception {
        ModImpact.start(() -> OWNERS, "test-render", "test-server");
        long end = System.nanoTime() + 2_500_000_000L;
        AtomicBoolean done = new AtomicBoolean();
        Thread render = new Thread(() -> {
            while (ModImpact.preparing()) {
                Thread.onSpinWait();
            }
            Busy.spin(end);
            done.set(true);
        }, "test-render");
        render.start();
        while (!done.get()) {
            ModImpact.frame();
            Thread.sleep(5);
        }
        render.join();
        Thread.sleep(1500);
        assertEquals("", ModImpact.error());
        List<ModImpact.Row> rows = ModImpact.stop(Map.of(), null);
        ModImpact.Row slow = rows.stream().filter(r -> r.mod().equals("slowmod")).findFirst().orElse(null);
        assertNotNull(slow, "rows: " + rows);
        assertTrue(slow.msPerFrame() > 0, "rows: " + rows);
    }

    @Test
    void flagsNeedEnoughPlayAndAMatchingVersion(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("impact.json");
        java.nio.file.Files.writeString(file, "{\"slowmod\":{\"version\":\"1.0\",\"msPerFrame\":12.5,\"msPerTick\":0,\"at\":1}}");
        ModImpact.forgetFlags();
        assertNotNull(ModImpact.flag(file, "slowmod", "1.0"));
        assertNull(ModImpact.flag(file, "slowmod", "2.0"));
        assertNull(ModImpact.flag(file, "othermod", "1.0"));
        ModImpact.start(() -> OWNERS, "nobody", "nobody");
        Thread.sleep(50);
        ModImpact.stop(Map.of("slowmod", "1.0"), file);
        ModImpact.forgetFlags();
        assertNotNull(ModImpact.flag(file, "slowmod", "1.0"));
        java.nio.file.Files.writeString(file, "not json");
        ModImpact.forgetFlags();
        assertFalse(ModImpact.loadFlags(file).containsKey("slowmod"));
        ModImpact.forgetFlags();
    }
}
