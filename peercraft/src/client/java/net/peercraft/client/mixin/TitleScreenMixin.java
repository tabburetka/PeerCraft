package net.peercraft.client.mixin;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
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

    /** Opens the user's mail client with the recipient/subject pre-filled; no in-game form, no mail credentials shipped in the mod. */
    private static final String FEEDBACK_MAILTO = "mailto:peercraft2@gmail.com?subject=PeerCraft%20feedback";
    private static final String DONATE_URL = "https://boosty.to/peercraft";
    private static final int FOOTER_BUTTON_WIDTH = 70;
    private static final int FOOTER_BUTTON_HEIGHT = 20;

    protected TitleScreenMixin(Component title) {
        super(title);
    }

    /**
     * Account and "join by code" used to be full-width buttons added here. Both now live as
     * small icon buttons inside {@link PeerCraftMultiplayerScreen} (next to Back and Add Server
     * respectively) instead, since every PeerCraft path already funnels through that screen via
     * the swapped multiplayer button below — no need to also compete for space on the title
     * screen's own menu.
     */
    @Inject(method = "init", at = @At("TAIL"))
    private void peercraft$addButtons(CallbackInfo ci) {
        peercraft$addFooterButtons();

        if (PeerCraftConfig.MODE_DISABLED.equals(PeerCraftConfig.mode())) {
            return;
        }

        peercraft$replaceMultiplayerButton();
    }

    /**
     * "Feedback"/"Donate" in the bottom-left corner, stacked above vanilla's version string (which is
     * plain drawn text at {@code (2, height - 10)}, not a widget, so there's nothing to collide
     * with there). Shown regardless of {@link PeerCraftConfig#mode()} — unlike the multiplayer
     * swap below, these aren't tied to P2P functionality being enabled.
     */
    private void peercraft$addFooterButtons() {
        int y = this.height - 10 - FOOTER_BUTTON_HEIGHT - 4;

        this.addRenderableWidget(Button.builder(Component.translatable("peercraft.gui.title.feedback_button"),
                        ConfirmLinkScreen.confirmLink(this, FEEDBACK_MAILTO))
                .bounds(2, y, FOOTER_BUTTON_WIDTH, FOOTER_BUTTON_HEIGHT)
                .tooltip(Tooltip.create(Component.translatable("peercraft.gui.title.feedback_tooltip")))
                .build());

        this.addRenderableWidget(Button.builder(Component.translatable("peercraft.gui.title.donate_button"),
                        ConfirmLinkScreen.confirmLink(this, DONATE_URL))
                .bounds(2 + FOOTER_BUTTON_WIDTH + 4, y, FOOTER_BUTTON_WIDTH, FOOTER_BUTTON_HEIGHT)
                .tooltip(Tooltip.create(Component.translatable("peercraft.gui.title.donate_tooltip")))
                .build());
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
                var tooltip = button.getTooltip();

                this.removeWidget(button);
                Button replacement = Button.builder(multiplayerLabel,
                                b -> this.minecraft.setScreen(new PeerCraftMultiplayerScreen(this)))
                        .bounds(x, y2, w, h)
                        .tooltip(tooltip)
                        .build();
                replacement.active = active;
                this.addRenderableWidget(replacement);
                return;
            }
        }
    }
}
