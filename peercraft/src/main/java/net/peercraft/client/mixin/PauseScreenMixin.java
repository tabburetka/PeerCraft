package net.peercraft.client.mixin;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.peercraft.client.gui.HandoffPlayerPickerScreen;
import net.peercraft.client.gui.PeerCraftUi;
import net.peercraft.config.PeerCraftConfig;
import net.peercraft.network.p2p.P2PBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * While hosting a world over the rendezvous server, add a "Hand off hosting…" button to the
 * pause screen, opening {@link HandoffPlayerPickerScreen}.
 *
 * <p>This used to try to repurpose the vanilla "Open to LAN" button's slot — but vanilla's own
 * {@code PauseScreen.createPauseMenu()} only puts that button there while
 * {@code IntegratedServer.isPublished()} is still false; the instant it flips true (which is
 * exactly the state {@code isHostingViaRendezvous()} requires), vanilla swaps that grid cell
 * for the player-reporting button instead. So a button search keyed on "Open to LAN" never
 * found anything to replace — confirmed by decompiling {@code PauseScreen} across the version
 * matrix (1.21.x and 26.x alike): the branch is symmetric everywhere, an already-published
 * server never has the vanilla button in the grid at all. Adding our own standalone button
 * below whatever vanilla actually built avoids depending on that branch entirely.
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

        // Vanilla's own grid is already laid out by this point (createPauseMenu() ran at
        // init() HEAD) — drop our button just below the lowest widget it built, whatever that
        // grid happens to contain on this version/state.
        int maxBottom = 0;
        for (GuiEventListener child : this.children()) {
            if (child instanceof AbstractWidget widget) {
                maxBottom = Math.max(maxBottom, widget.getY() + widget.getHeight());
            }
        }

        int width = 204;
        int x = this.width / 2 - width / 2;
        int y = maxBottom + 4;

        Button handoffButton = Button.builder(Component.translatable("peercraft.handoff.menu.button"),
                        b -> PeerCraftUi.setScreen(this.minecraft, new HandoffPlayerPickerScreen((PauseScreen) (Object) this)))
                .bounds(x, y, width, 20)
                .build();
        handoffButton.active = !P2PBridge.INSTANCE.handoffInProgress();
        this.addRenderableWidget(handoffButton);
    }
}
