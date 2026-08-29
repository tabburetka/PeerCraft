package net.peercraft.client.mixin;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TranslatableComponent;
import net.peercraft.client.gui.PeerCraftMultiplayerScreen;
import net.peercraft.config.PeerCraftConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;

/**
 * Minecraft 1.16.5 backport of {@code src/main/.../TitleScreenMixin.java}. Same idea — swap
 * vanilla's "Multiplayer" button for one that opens {@link PeerCraftMultiplayerScreen} — but
 * with 1.16.5 widget API: {@code new Button(...)} instead of the fluent builder, {@code x}/{@code y}
 * public fields instead of {@code getX()/getY()}, {@code addButton(...)} instead of
 * {@code addRenderableWidget(...)}, and manual removal from {@code buttons}/{@code children}
 * since there's no {@code removeWidget}. Vanilla's Multiplayer button never carries a tooltip,
 * so the tooltip copy is dropped entirely (it only ever moved {@code null}).
 */
@Mixin(TitleScreen.class)
public abstract class TitleScreenMixin extends Screen {

    protected TitleScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void peercraft$addButtons(CallbackInfo ci) {
        if (PeerCraftConfig.MODE_DISABLED.equals(PeerCraftConfig.mode())) {
            return;
        }

        peercraft$replaceMultiplayerButton();
    }

    private void peercraft$replaceMultiplayerButton() {
        Component multiplayerLabel = new TranslatableComponent("menu.multiplayer");
        for (GuiEventListener child : new ArrayList<>(this.children())) {
            if (child instanceof Button && multiplayerLabel.equals(((Button) child).getMessage())) {
                Button button = (Button) child;
                int x = button.x;
                int y2 = button.y;
                int w = button.getWidth();
                int h = button.getHeight();
                boolean active = button.active;

                this.buttons.remove(button);
                this.children.remove(button);

                Button replacement = new Button(x, y2, w, h, multiplayerLabel,
                        b -> this.minecraft.setScreen(new PeerCraftMultiplayerScreen(this)));
                replacement.active = active;
                this.addButton(replacement);
                return;
            }
        }
    }
}
