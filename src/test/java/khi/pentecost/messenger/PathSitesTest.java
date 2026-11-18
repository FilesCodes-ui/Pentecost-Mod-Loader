package khi.pentecost.messenger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PathSitesTest {
    @AfterEach
    void clean() {
        PathHooks.forgetSites();
    }

    @Test
    void aSiteThatReadsEarlyGoesBackToTheServer() {
        String early = "test.Goal.canUse";
        String waits = "test.Goal.start";
        for (int i = 0; i < PathHooks.LEARN_AFTER; i++) {
            assertTrue(PathHooks.siteAsync(early));
            PathHooks.tried(early);
            PathHooks.forcedAt(early);
            assertTrue(PathHooks.siteAsync(waits));
            PathHooks.tried(waits);
        }
        int async = 0;
        for (int i = 0; i < PathHooks.PROBE_EVERY * 4; i++) {
            if (PathHooks.siteAsync(early)) {
                async++;
            }
        }
        assertTrue(async >= 3 && async <= 5, "only probes go to a worker, got " + async);
        assertTrue(PathHooks.siteAsync(waits));
        assertEquals(1, PathHooks.sitesOnServer());
    }

    @Test
    void aSiteThatStopsReadingEarlyIsLearnedAgain() {
        String s = "test.Goal.tick";
        for (int i = 0; i < PathHooks.LEARN_AFTER; i++) {
            PathHooks.tried(s);
            PathHooks.forcedAt(s);
        }
        assertFalse(PathHooks.siteAsync(s) && PathHooks.siteAsync(s) && PathHooks.siteAsync(s));
        for (int i = 0; i < PathHooks.LEARN_AFTER * 3; i++) {
            PathHooks.tried(s);
        }
        assertTrue(PathHooks.siteAsync(s));
        assertEquals(0, PathHooks.sitesOnServer());
    }
}
