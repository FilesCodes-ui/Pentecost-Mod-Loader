package khi.pentecost.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PentecostMainVersionTest {
    @Test
    void versionIsTheBuildVersion() {
        assertTrue(PentecostMain.VERSION.matches("\\d+\\.\\d+\\.\\d+"), "version was " + PentecostMain.VERSION);
        assertEquals(System.getProperty("pentecost.expectedVersion", PentecostMain.VERSION), PentecostMain.VERSION);
    }
}