package net.peercraft.client.mixin;

import net.peercraft.client.skin.SkinProfileSupport;
import com.mojang.authlib.GameProfile;
import net.minecraft.client.resources.SkinManager;
import net.peercraft.network.skin.OfficialSkinProfiles;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(SkinManager.class)
public abstract class OfficialSkinMixin {
    @Inject(method = "func_152790_a", at = @At("HEAD"), cancellable = true)
    private void peercraft$official(GameProfile profile, SkinManager.SkinAvailableCallback callback, boolean secure, CallbackInfo ci) {
        if (!SkinProfileSupport.missing(profile)) return;
        java.util.concurrent.CompletableFuture<java.util.Optional<OfficialSkinProfiles.Texture>> lookup = OfficialSkinProfiles.lookup(profile.getId());
        if (lookup.isDone() && !lookup.getNow(java.util.Optional.empty()).isPresent()) return;
        ci.cancel();
        lookup.thenAccept(texture -> ((SkinManager) (Object) this).func_152790_a(
                texture.isPresent() ? SkinProfileSupport.withTexture(profile, texture.get()) : profile, callback, secure));
    }
}
