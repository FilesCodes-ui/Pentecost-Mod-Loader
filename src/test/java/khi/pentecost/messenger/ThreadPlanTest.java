package khi.pentecost.messenger;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThreadPlanTest {
    private static Map<String, Long> costs(Object... kv) {
        Map<String, Long> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], ((Number) kv[i + 1]).longValue());
        }
        return m;
    }

    @Test
    void addonSharesThreadWithParent() {
        var p = ThreadPlan.plan(costs("create", 500, "copycats", 50, "naturalist", 400, "xaero", 300),
                Map.of(), List.of(new ThreadPlan.Edge("copycats", "create", 120, 0)), 3, 0);
        assertEquals(p.threadOf().get("create"), p.threadOf().get("copycats"));
        assertEquals(0, p.crossCalls());
    }

    @Test
    void mixinEditJoinsEvenWithFewCalls() {
        var p = ThreadPlan.plan(costs("a", 100, "b", 100), Map.of(), List.of(new ThreadPlan.Edge("a", "b", 1, 2)), 2, 0);
        assertEquals(p.threadOf().get("a"), p.threadOf().get("b"));
    }

    @Test
    void declaredDependencyJoins() {
        var p = ThreadPlan.plan(costs("a", 100, "b", 100), Map.of("a", Set.of("b")), List.of(), 2, 0);
        assertEquals(p.threadOf().get("a"), p.threadOf().get("b"));
    }

    @Test
    void libraryUsedByManyBelongsToNoThread() {
        var p = ThreadPlan.plan(costs("lib", 10, "a", 100, "b", 100, "c", 100),
                Map.of(), List.of(new ThreadPlan.Edge("a", "lib", 50, 0), new ThreadPlan.Edge("b", "lib", 50, 0),
                        new ThreadPlan.Edge("c", "lib", 50, 0)), 3, 0);
        assertTrue(p.libraries().contains("lib"));
        assertFalse(p.threadOf().containsKey("lib"));
        assertNotEquals(p.threadOf().get("a"), p.threadOf().get("b"));
    }

    @Test
    void fewCallsCrossThreadsAndAreCounted() {
        var p = ThreadPlan.plan(costs("a", 100, "b", 100), Map.of(), List.of(new ThreadPlan.Edge("a", "b", 3, 0)), 2, 0);
        assertNotEquals(p.threadOf().get("a"), p.threadOf().get("b"));
        assertEquals(3, p.crossCalls());
    }

    @Test
    void packedLibrariesAndFabricModulesAreShared() {
        var p = ThreadPlan.plan(costs("lib_x-1.0.jar", 50, "fabric_api_base", 10, "org_jetbrains_kotlin_kotlin-reflect", 900, "create", 100),
                Map.of(), List.of(), 2, 0);
        assertEquals(Set.of("lib_x-1.0.jar", "fabric_api_base", "org_jetbrains_kotlin_kotlin-reflect"), p.libraries());
        assertEquals(Set.of("create"), p.threadOf().keySet());
    }

    @Test
    void loadIsSpreadHeaviestFirst() {
        var p = ThreadPlan.plan(costs("big", 900, "m1", 300, "m2", 300, "m3", 300), Map.of(), List.of(), 2, 0);
        int big = p.threadOf().get("big");
        assertTrue(p.threadOf().get("m1") != big && p.threadOf().get("m2") != big && p.threadOf().get("m3") != big);
    }
}
