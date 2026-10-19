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

/** Adds the session and eligible handoff actions in one non-overlapping row. */
@Mixin(PauseScreen.class)
public abstract class PauseScreenMixin extends Screen {

    protected PauseScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void peercraft$addSessionButtons(CallbackInfo ci) {
        if (PeerCraftConfig.MODE_DISABLED.equals(PeerCraftConfig.mode())) return;
        boolean handoff = PeerCraftConfig.handoff() && P2PBridge.INSTANCE.isHostingViaRendezvous();
        int bottom = 0;
        for (GuiEventListener child : children()) {
            if (child instanceof AbstractWidget) {
                AbstractWidget widget = (AbstractWidget) child;
                bottom = Math.max(bottom, widget.y + widget.getHeight());
            }
        }
        net.peercraft.client.theme.SessionPauseLayout layout =
                net.peercraft.client.theme.SessionPauseLayout.below(width, height, bottom, handoff);
        if (layout.shiftUp > 0) for (GuiEventListener child : children()) {
            if (child instanceof AbstractWidget) {
                AbstractWidget widget = (AbstractWidget) child;
                widget.y -= layout.shiftUp;
            }
        }
        addButton(new Button(layout.left, layout.top, layout.buttonWidth, 20,
                new TranslatableComponent("peercraft.session.title"),
                b -> PeerCraftUi.setScreen(minecraft, new net.peercraft.client.gui.PeerCraftSessionScreen(this))));
        if (handoff) {
            Button button = new Button(layout.handoffLeft(), layout.top, layout.buttonWidth, 20,
                    new TranslatableComponent("peercraft.handoff.menu.button"),
                    b -> PeerCraftUi.setScreen(minecraft, new HandoffPlayerPickerScreen(this)));
            button.active = !P2PBridge.INSTANCE.handoffInProgress();
            addButton(button);
        }
    }
}
