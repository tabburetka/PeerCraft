package net.peercraft.client.theme;

/** One shared row below existing pause controls; Java 8 compatible for every client. */
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

    public int handoffLeft() { return left + buttonWidth + 4; }
}
