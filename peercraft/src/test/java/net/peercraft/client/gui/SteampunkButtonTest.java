//? if =1.21.1 {
package net.peercraft.client.gui;

import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SteampunkButtonTest {
    @Test
    void preservesTheOriginalCallbackAndItsDisabledState() {
        AtomicInteger requests = new AtomicInteger();
        Button original = Button.builder(Component.literal("Sign in"), pressed -> {
            requests.incrementAndGet();
            pressed.active = false;
        }).build();
        Button themed = SteampunkSettingsTheme.decorate(original, 20, 30, 200, 24, true);

        themed.onPress();
        assertEquals(1, requests.get());
        assertFalse(original.active);
        assertFalse(themed.active);
        themed.onPress();
        assertEquals(1, requests.get(), "A pending sign-in must not submit another request");
    }

    @Test
    void acceptsAnotherPressAfterTheAsynchronousCallbackReenablesTheOriginal() {
        AtomicInteger requests = new AtomicInteger();
        Button original = Button.builder(Component.literal("Sign in"), pressed -> {
            requests.incrementAndGet();
            pressed.active = false;
        }).build();
        Button themed = SteampunkSettingsTheme.decorate(original, 20, 30, 200, 24, true);
        themed.onPress();

        original.active = true;
        original.setMessage(Component.literal("Try again"));
        themed.onPress();
        assertEquals(2, requests.get());
        assertEquals("Try again", themed.getMessage().getString());
    }

    @Test
    void doesNotInvokeAnInitiallyDisabledOrHiddenAction() {
        AtomicInteger requests = new AtomicInteger();
        Button original = Button.builder(Component.literal("Sign in"), pressed -> requests.incrementAndGet()).build();
        original.active = false;
        Button themed = SteampunkSettingsTheme.decorate(original, 20, 30, 200, 24, true);
        themed.onPress();
        assertEquals(0, requests.get());

        original.active = true;
        original.visible = false;
        themed.onPress();
        assertEquals(0, requests.get());
    }
}
//?}
