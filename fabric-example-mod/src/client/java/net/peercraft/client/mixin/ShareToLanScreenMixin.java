package net.peercraft.client.mixin;

import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.ShareToLanScreen;
import net.minecraft.network.chat.Component;
import net.peercraft.client.PeerCraftHostOptions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ShareToLanScreen.class)
public abstract class ShareToLanScreenMixin extends Screen {

    protected ShareToLanScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void peercraft$addInternetCheckbox(CallbackInfo ci) {
        this.addRenderableWidget(Checkbox.builder(Component.literal("PeerCraft: через интернет"), this.font)
                .pos(this.width / 2 - 155, this.height - 54)
                .selected(PeerCraftHostOptions.internetPlayRequested)
                .onValueChange((checkbox, value) -> PeerCraftHostOptions.internetPlayRequested = value)
                .build());
    }
}
