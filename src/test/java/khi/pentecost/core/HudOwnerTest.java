package khi.pentecost.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HudOwnerTest {
    public static final class FakeGui {
        public int leftHeight = 39;
        public int rightHeight = 39;
    }

    @AfterEach
    void off() {
        HudOwner.vanillaBars = false;
    }

    @Test
    void foodMovesTheRightCounterInItsOwnSlotOnly() {
        HudOwner.vanillaBars = true;
        FakeGui gui = new FakeGui();
        assertEquals(39, gui.rightHeight);
        assertTrue(HudOwner.skip(gui, "renderFood"));
        assertEquals(49, gui.rightHeight);
        assertEquals(39, gui.leftHeight);
    }

    @Test
    void whenForgeDrawsTheBarsNothingIsTouched() {
        FakeGui gui = new FakeGui();
        assertFalse(HudOwner.skip(gui, "renderFood"));
        assertEquals(39, gui.rightHeight);
    }
}
