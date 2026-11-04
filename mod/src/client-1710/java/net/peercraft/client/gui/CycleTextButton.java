package net.peercraft.client.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 1.7.10 stand-in for {@code CycleButton<T>} (which only arrived in 1.17) — byte-identical to
 * the {@code src/client-1122} twin (it's a pure factory over {@link IdButton}, no vanilla API).
 * Builds an {@link IdButton} that steps through a fixed list of values on each click, rewriting
 * its own label, and reports the new value through a callback.
 */
final class CycleTextButton {

    private CycleTextButton() {
    }

    static <T> IdButton create(int x, int y, int width, int height,
                               List<T> values, T initial,
                               Function<T, String> labelFor,
                               Consumer<T> onChange) {
        List<T> snapshot = new ArrayList<T>(values);
        int startIdx = Math.max(0, snapshot.indexOf(initial));
        int[] idx = {startIdx};
        IdButton[] self = new IdButton[1];
        self[0] = IdButton.builder(labelFor.apply(snapshot.get(startIdx)), new Runnable() {
            @Override
            public void run() {
                idx[0] = (idx[0] + 1) % snapshot.size();
                T value = snapshot.get(idx[0]);
                self[0].displayString = labelFor.apply(value);
                onChange.accept(value);
            }
        }).bounds(x, y, width, height).build();
        return self[0];
    }
}
