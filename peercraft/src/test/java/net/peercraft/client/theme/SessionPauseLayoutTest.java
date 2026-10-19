package net.peercraft.client.theme;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SessionPauseLayoutTest {
    @Test void actionsFitAndNeverIntersectEvenWhenMenuNeedsToMoveUp() {
        for (int width : new int[] {320, 426, 640, 1280}) {
            for (int height : new int[] {240, 300, 450, 720}) {
                for (int bottom : new int[] {180, height - 12, height + 8}) {
                    SessionPauseLayout row = SessionPauseLayout.below(width, height, bottom, true);
                    assertTrue(row.left >= 0);
                    assertTrue(row.handoffLeft() >= row.left + row.buttonWidth + 4);
                    assertTrue(row.handoffLeft() + row.buttonWidth <= width);
                    assertEquals(bottom - row.shiftUp + 4, row.top);
                    assertTrue(row.top + 20 <= height - 8);
                    assertTrue(row.buttonWidth >= 140);
                }
            }
        }
    }

    @Test void normalHeightKeepsVanillaButtonsInPlace() {
        SessionPauseLayout row = SessionPauseLayout.below(640, 450, 280, true);
        assertEquals(0, row.shiftUp);
        assertEquals(284, row.top);
        assertEquals(204, row.buttonWidth);
    }

    @Test void sessionAloneUsesTheOriginalCenteredWidth() {
        SessionPauseLayout row = SessionPauseLayout.below(320, 240, 220, false);
        assertFalse(row.withHandoff);
        assertEquals(204, row.buttonWidth);
        assertEquals(58, row.left);
        assertEquals(12, row.shiftUp);
        assertEquals(212, row.top);
    }
}
