package khi.pentecost.control;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class MemoryPlanTest {
    private static Function<String, String> settings(String... kv) {
        Map<String, String> m = new java.util.HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return k -> m.getOrDefault(k, "");
    }

    @Test
    void tiers() {
        assertArrayEquals(new int[]{8, 2}, MemoryPlan.tier(32));
        assertArrayEquals(new int[]{6, 2}, MemoryPlan.tier(16));
        assertArrayEquals(new int[]{5, 2}, MemoryPlan.tier(12));
        assertArrayEquals(new int[]{4, 1}, MemoryPlan.tier(8));
        assertArrayEquals(new int[]{3, 0}, MemoryPlan.tier(6));
    }

    @Test
    void backupAlwaysLeavesTwoGigabytesFree() {
        assertEquals(0, MemoryPlan.fitReserve(8, 6, 4));
        assertEquals(4, MemoryPlan.fitReserve(16, 6, 4));
        assertEquals(2, MemoryPlan.fitReserve(10, 6, 4));
        assertEquals(1, MemoryPlan.fitReserve(10, 7, 5));
        assertEquals(0, MemoryPlan.fitReserve(4, 3, 2));
    }

    @Test
    void sizesAreRead() {
        assertEquals(8192, MemoryPlan.megabytes("8G"));
        assertEquals(8192, MemoryPlan.megabytes("8g"));
        assertEquals(6144, MemoryPlan.megabytes("6144m"));
        assertEquals(1024, MemoryPlan.megabytes("1048576k"));
        assertEquals(0, MemoryPlan.megabytes("lots"));
        assertEquals(0, MemoryPlan.megabytes(""));
    }

    @Test
    void untouchedSettingsKeepTheRunningMemory() {
        List<String> running = List.of("-Xms8G", "-Xmx8G", "-Xmn4G", "-XX:-DontCompileHugeMethods");
        MemoryPlan.Plan p = MemoryPlan.plan(32, running, settings());
        assertEquals(new MemoryPlan.Plan(8, 0, 4, "g1"), p);
        assertEquals(MemoryPlan.effective(running), MemoryPlan.effective(MemoryPlan.apply(running, p, List.of())));
        assertFalse(MemoryPlan.anySet(settings()));
    }

    @Test
    void choosingMemoryAddsTheDefaultBackup() {
        List<String> running = List.of("-Xmx2G");
        MemoryPlan.Plan p = MemoryPlan.plan(32, running, settings(Settings.JAVA_MEMORY, "8"));
        assertEquals(new MemoryPlan.Plan(8, 2, 4, "g1"), p);
        assertEquals(List.of("-Xms8G", "-Xmx10G", "-Xmn4G"), MemoryPlan.flags(p));
    }

    @Test
    void backupOnlyMovesTheCeiling() {
        List<String> running = List.of("-Xms8G", "-Xmx8G", "-Xmn4G");
        MemoryPlan.Plan p = MemoryPlan.plan(32, running, settings(Settings.JAVA_RESERVE, "3"));
        assertEquals(new MemoryPlan.Plan(8, 3, 4, "g1"), p);
        assertEquals(List.of("-Xms8G", "-Xmx11G", "-Xmn4G"), MemoryPlan.flags(p));
    }

    @Test
    void noBackupMeansAFixedHeap() {
        MemoryPlan.Plan p = MemoryPlan.plan(32, List.of(), settings(Settings.JAVA_MEMORY, "6", Settings.JAVA_RESERVE, "0"));
        assertEquals(List.of("-Xms6G", "-Xmx6G", "-Xmn3G"), MemoryPlan.flags(p));
    }

    @Test
    void zgcUsesTheWorkingSizeAsASoftLimit() {
        MemoryPlan.Plan p = MemoryPlan.plan(32, List.of("-Xms8G", "-Xmx8G"),
                settings(Settings.JAVA_GC, "zgc", Settings.JAVA_RESERVE, "2"));
        assertEquals(List.of("-Xms8G", "-Xmx10G", "-XX:+UseZGC", "-XX:+ZGenerational", "-XX:SoftMaxHeapSize=8G"), MemoryPlan.flags(p));
    }

    @Test
    void choosingOnlyTheCollectorDoesNotMoveTheMemory() {
        List<String> running = List.of("-Xms5G", "-Xmx5G", "-Xmn2G");
        MemoryPlan.Plan p = MemoryPlan.plan(32, running, settings(Settings.JAVA_GC, "parallel"));
        assertEquals(new MemoryPlan.Plan(5, 0, 2, "parallel"), p);
    }

    @Test
    void memoryIsLimitedByThePc() {
        MemoryPlan.Plan p = MemoryPlan.plan(8, List.of(), settings(Settings.JAVA_MEMORY, "30", Settings.JAVA_RESERVE, "8"));
        assertEquals(6, p.targetGb());
        assertEquals(0, p.reserveGb());
    }

    @Test
    void anExplicitYoungAreaStaysBelowTheMemory() {
        MemoryPlan.Plan p = MemoryPlan.plan(32, List.of(), settings(Settings.JAVA_MEMORY, "4", Settings.JAVA_YOUNG, "9"));
        assertEquals(3, p.youngGb());
    }

    @Test
    void applyReplacesMemoryFlagsAndKeepsTheRest() {
        List<String> running = List.of("-Dpentecost.home=x", "-Xmx2G", "-XX:+UseZGC", "-XX:-DontCompileHugeMethods");
        MemoryPlan.Plan p = new MemoryPlan.Plan(6, 2, 3, "g1");
        List<String> out = MemoryPlan.apply(running, p, List.of("-XX:+UseStringDeduplication"));
        assertEquals(List.of("-Dpentecost.home=x", "-XX:-DontCompileHugeMethods", "-Xms6G", "-Xmx8G", "-Xmn3G",
                "-XX:+UseStringDeduplication"), out);
    }

    @Test
    void extraFlagsMayChangeTheMemoryAndWin() {
        MemoryPlan.Plan p = new MemoryPlan.Plan(6, 2, 3, "g1");
        List<String> out = MemoryPlan.apply(List.of(), p, List.of("-Xmx12G"));
        assertEquals(12288, MemoryPlan.effective(out).maxMb());
    }
}
