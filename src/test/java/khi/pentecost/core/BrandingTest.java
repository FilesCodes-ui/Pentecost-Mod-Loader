package khi.pentecost.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class BrandingTest {
    @Test
    void forgeTitleBecomesPentecost() {
        assertEquals("Pentecost 1.20.1", Branding.title("Minecraft* Forge 1.20.1"));
        assertEquals("Pentecost 1.20.1 - Singleplayer", Branding.title("Minecraft* Forge 1.20.1 - Singleplayer"));
        assertEquals("Pentecost 1.20.1", Branding.title("Minecraft 1.20.1"));
    }

    @Test
    void otherTitlesAreLeftAlone() {
        assertEquals("Something else", Branding.title("Something else"));
        assertNull(Branding.title(null));
    }
}