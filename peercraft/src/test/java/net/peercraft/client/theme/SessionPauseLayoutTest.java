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
    @Test void compactEntryDoesNotMoveNativeControls() {
        java.util.List<int[]> occupied = java.util.Arrays.asList(
                new int[] {218, 120, 204, 20}, new int[] {218, 144, 98, 20}, new int[] {324, 144, 98, 20});
        SessionPauseLayout entry = SessionPauseLayout.compact(640, 450, occupied);
        assertEquals(76, entry.buttonWidth);
        assertEquals(8, entry.top);
        assertEquals(556, entry.left);
        assertEquals(0, entry.shiftUp);
        assertEquals(218, occupied.get(0)[0]);
    }

    @Test void iconSitsBesideBackToGameWhenFree() {
        int[] back = {218, 120, 204, 20};
        java.util.List<int[]> occupied = java.util.Arrays.asList(back, new int[] {218, 144, 98, 20});
        SessionPauseLayout entry = SessionPauseLayout.icon(640, 450, occupied, back, 20);
        assertEquals(426, entry.left);
        assertEquals(120, entry.top);
        assertEquals(20, entry.buttonWidth);
    }

    @Test void iconFallsBackWithoutOverlappingAnything() {
        for (int width : new int[] {210, 320, 640}) {
            int[] back = {(width - 204) / 2, 120, 204, 20};
            java.util.List<int[]> occupied = java.util.Arrays.asList(back,
                    new int[] {back[0] + 208, 120, 98, 20}, new int[] {back[0] - 102, 120, 98, 20},
                    new int[] {width - 88, 8, 80, 20});
            SessionPauseLayout entry = SessionPauseLayout.icon(width, 240, occupied, back, 20);
            assertTrue(entry.left >= 0 && entry.left + 20 <= width);
            for (int[] box : occupied) assertFalse(entry.left < box[0] + box[2]
                    && entry.left + 20 > box[0] && entry.top < box[1] + box[3] && entry.top + 20 > box[1]);
        }
    }

    @Test void compactEntryAvoidsOtherModButtonsOnSmallScreens() {
        for (int width : new int[] {320, 426, 640}) {
            java.util.List<int[]> occupied = java.util.Arrays.asList(
                    new int[] {width - 88, 8, 80, 20}, new int[] {8, 8, 80, 20},
                    new int[] {(width - 204) / 2, 90, 204, 110});
            SessionPauseLayout entry = SessionPauseLayout.compact(width, 240, occupied);
            assertEquals(0, entry.shiftUp);
            assertTrue(entry.left >= 8 && entry.left + entry.buttonWidth <= width - 8);
            for (int[] box : occupied) assertFalse(entry.left < box[0] + box[2]
                    && entry.left + entry.buttonWidth > box[0] && entry.top < box[1] + box[3] && entry.top + 20 > box[1]);
        }
    }
}
