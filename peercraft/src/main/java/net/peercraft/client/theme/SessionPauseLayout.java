package net.peercraft.client.theme;

/** Shared pause-entry placement; Java 8 compatible for every client. */
public final class SessionPauseLayout {
    public final int left, top, buttonWidth, shiftUp;
    public final boolean withHandoff;

    private SessionPauseLayout(int left, int top, int buttonWidth, int shiftUp, boolean withHandoff) {
        this.left = left; this.top = top; this.buttonWidth = buttonWidth;
        this.shiftUp = shiftUp; this.withHandoff = withHandoff;
    }

    public static SessionPauseLayout below(int screenWidth, int screenHeight, int existingBottom, boolean handoff) {
        int rowWidth = Math.max(1, Math.min(handoff ? 412 : 204, screenWidth - 24));
        int shift = Math.max(0, existingBottom + 24 - (screenHeight - 8));
        return new SessionPauseLayout((screenWidth - rowWidth) / 2, existingBottom - shift + 4,
                handoff ? (rowWidth - 4) / 2 : rowWidth, shift, handoff);
    }

    /** Prefer an unused corner without moving or replacing any existing control. */
    public static SessionPauseLayout compact(int width, int height, java.util.List<int[]> occupied) {
        int buttonWidth = Math.min(76, Math.max(1, width - 16));
        for (int y = 8; y + 20 <= height - 8; y += 24) {
            for (int x : new int[] {width - buttonWidth - 8, 8}) {
                boolean free = true;
                for (int[] rect : occupied) {
                    if (x < rect[0] + rect[2] + 4 && x + buttonWidth + 4 > rect[0]
                            && y < rect[1] + rect[3] + 4 && y + 24 > rect[1]) { free = false; break; }
                }
                if (free) return new SessionPauseLayout(x, y, buttonWidth, 0, false);
            }
        }
        int bottom = 0;
        for (int[] rect : occupied) bottom = Math.max(bottom, rect[1] + rect[3]);
        SessionPauseLayout row = below(width, height, bottom, false);
        return new SessionPauseLayout((width - buttonWidth) / 2, row.top, buttonWidth, row.shiftUp, false);
    }

    /**
     * A square entry beside {@code anchor} (x, y, w, h — normally "Back to game"), falling back
     * to a free corner. Never moves or overlaps an existing control.
     */
    public static SessionPauseLayout icon(int width, int height, java.util.List<int[]> occupied, int[] anchor, int size) {
        if (anchor != null) {
            int y = anchor[1] + (anchor[3] - size) / 2;
            for (int x : new int[] {anchor[0] + anchor[2] + 4, anchor[0] - size - 4}) {
                if (x >= 4 && x + size <= width - 4 && y >= 4 && y + size <= height - 4 && free(occupied, x, y, size, 2)) {
                    return new SessionPauseLayout(x, y, size, 0, false);
                }
            }
        }
        for (int y = 8; y + size <= height - 8; y += size + 4) {
            for (int x : new int[] {width - size - 8, 8}) {
                if (free(occupied, x, y, size, 4)) return new SessionPauseLayout(x, y, size, 0, false);
            }
        }
        return new SessionPauseLayout(Math.max(0, width - size - 4), 4, size, 0, false);
    }

    private static boolean free(java.util.List<int[]> occupied, int x, int y, int size, int gap) {
        for (int[] rect : occupied) {
            if (x < rect[0] + rect[2] + gap && x + size + gap > rect[0]
                    && y < rect[1] + rect[3] + gap && y + size + gap > rect[1]) return false;
        }
        return true;
    }

    public int handoffLeft() { return left + buttonWidth + 4; }
}
