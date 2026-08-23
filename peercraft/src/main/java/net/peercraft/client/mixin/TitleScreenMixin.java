package net.peercraft.client.mixin;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.peercraft.client.gui.PeerCraftMultiplayerScreen;
import net.peercraft.config.PeerCraftConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;

@Mixin(TitleScreen.class)
public abstract class TitleScreenMixin extends Screen {

    protected TitleScreenMixin(Component title) {
        super(title);
    }

    /**
     * Account and "join by code" used to be full-width buttons added here. Both now live as
     * small icon buttons inside {@link PeerCraftMultiplayerScreen} (next to Back and Add Server
     * respectively) instead, since every PeerCraft path already funnels through that screen via
     * the swapped multiplayer button below — no need to also compete for space on the title
     * screen's own menu. "Feedback"/"Donate" moved there too, stacked in the bottom-left corner.
     */
    @Inject(method = "init", at = @At("TAIL"))
    private void peercraft$addButtons(CallbackInfo ci) {
        if (PeerCraftConfig.MODE_DISABLED.equals(PeerCraftConfig.mode())) {
            return;
        }

        peercraft$replaceMultiplayerButton();
    }

    /**
     * Swaps vanilla's "Multiplayer" button for one that opens {@link PeerCraftMultiplayerScreen}
     * (vanilla's join screen plus a Friends/Find Players tab bar) instead of the plain
     * {@code JoinMultiplayerScreen}/{@code SafetyScreen}. Found by message rather than by
     * mixing into the private {@code createNormalMenuOptions} lambda — that method's button is
     * a local variable with no field to shadow, and redirecting inside a synthetic lambda body
     * is brittle across compiler versions. Bounds/tooltip/active state are copied across so the
     * swap is visually invisible; the vanilla safety warning screen is intentionally skipped
     * since PeerCraft already gates multiplayer behind its own account/friends flow.
     */
    private void peercraft$replaceMultiplayerButton() {
        Component multiplayerLabel = Component.translatable("menu.multiplayer");
        for (GuiEventListener child : new ArrayList<>(this.children())) {
            if (child instanceof Button button && multiplayerLabel.equals(button.getMessage())) {
                int x = button.getX();
                int y2 = button.getY();
                int w = button.getWidth();
                int h = button.getHeight();
                boolean active = button.active;
                // AbstractWidget.getTooltip() was removed in 1.21.6 with no getter replacement —
                // vanilla's own Multiplayer button has never actually set one, so this only ever
                // copied null across; dropping the copy on newer versions is a no-op in practice.
                //? if <1.21.6 {
                var tooltip = button.getTooltip();
                //?}

                this.removeWidget(button);
                Button.Builder replacementBuilder = Button.builder(multiplayerLabel,
                                b -> this.minecraft.setScreen(new PeerCraftMultiplayerScreen(this)))
                        .bounds(x, y2, w, h);
                //? if <1.21.6 {
                replacementBuilder.tooltip(tooltip);
                //?}
                Button replacement = replacementBuilder.build();
                replacement.active = active;
                this.addRenderableWidget(replacement);
                return;
            }
        }
    }
}
