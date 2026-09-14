package net.peercraft.client.mixin;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TranslatableComponent;
import net.peercraft.client.gui.HandoffPlayerPickerScreen;
import net.peercraft.client.gui.PeerCraftUi;
import net.peercraft.config.PeerCraftConfig;
import net.peercraft.network.p2p.P2PBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Minecraft 1.16.5 backport of {@code src/main/.../client/mixin/PauseScreenMixin.java}. Same
 * hook ({@code PauseScreen.init()} TAIL) and layout approach (drop below whatever vanilla's own
 * grid built). Deltas: no Java 16 {@code instanceof} pattern variable; {@code AbstractWidget} has
 * a public {@code y} field instead of {@code getY()}; {@code addRenderableWidget} -&gt;
 * {@code addButton}; no {@code Button.builder} fluent API -&gt; {@code new Button(...)}.
 */
@Mixin(PauseScreen.class)
public abstract class PauseScreenMixin extends Screen {

    protected PauseScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void peercraft$addHandoffButton(CallbackInfo ci) {
        if (PeerCraftConfig.MODE_DISABLED.equals(PeerCraftConfig.mode()) || !PeerCraftConfig.handoff()) {
            return;
        }
        if (!P2PBridge.INSTANCE.isHostingViaRendezvous()) {
            return;
        }

        int maxBottom = 0;
        for (GuiEventListener child : this.children()) {
            if (child instanceof AbstractWidget) {
                AbstractWidget widget = (AbstractWidget) child;
                maxBottom = Math.max(maxBottom, widget.y + widget.getHeight());
            }
        }

        int width = 204;
        int x = this.width / 2 - width / 2;
        int y = maxBottom + 4;

        Button handoffButton = new Button(x, y, width, 20,
                new TranslatableComponent("peercraft.handoff.menu.button"),
                b -> PeerCraftUi.setScreen(this.minecraft, new HandoffPlayerPickerScreen((PauseScreen) (Object) this)));
        handoffButton.active = !P2PBridge.INSTANCE.handoffInProgress();
        this.addButton(handoffButton);
    }
}
