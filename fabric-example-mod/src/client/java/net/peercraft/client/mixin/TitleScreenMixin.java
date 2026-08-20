package net.peercraft.client.mixin;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.peercraft.client.gui.PeerCraftAccountScreen;
import net.peercraft.client.gui.PeerCraftJoinScreen;
import net.peercraft.config.PeerCraftConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TitleScreen.class)
public abstract class TitleScreenMixin extends Screen {

    protected TitleScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void peercraft$addButtons(CallbackInfo ci) {
        String mode = PeerCraftConfig.mode();
        if (PeerCraftConfig.MODE_DISABLED.equals(mode)) {
            return;
        }

        int y = this.height - 28;
        if (!PeerCraftConfig.MODE_HOST.equals(mode)) {
            this.addRenderableWidget(Button.builder(Component.literal("PeerCraft: Join"),
                            b -> this.minecraft.setScreen(new PeerCraftJoinScreen(this)))
                    .bounds(this.width / 2 - 100, y, 200, 20)
                    .build());
            y -= 24;
        }

        // Account/friends button — shown in every non-disabled mode (including host), unlike
        // Join above: a host can still want an account for the friends system even though
        // they don't need to manually join a room by code.
        this.addRenderableWidget(Button.builder(Component.literal("PeerCraft: Аккаунт"),
                        b -> this.minecraft.setScreen(new PeerCraftAccountScreen(this)))
                .bounds(this.width / 2 - 100, y, 200, 20)
                .build());
    }
}
