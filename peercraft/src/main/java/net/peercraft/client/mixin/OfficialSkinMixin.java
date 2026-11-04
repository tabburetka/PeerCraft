package net.peercraft.client.mixin;

import net.peercraft.client.skin.SkinProfileSupport;

import com.mojang.authlib.GameProfile;
import net.minecraft.client.resources.SkinManager;
import net.peercraft.network.skin.OfficialSkinProfiles;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.concurrent.CompletableFuture;
//? if <1.21.9
import net.minecraft.client.resources.PlayerSkin;
//? if >=1.21.9
/*import net.minecraft.world.entity.player.PlayerSkin;*/

@Mixin(SkinManager.class)
public abstract class OfficialSkinMixin {
    //? if <1.21.4 {
    @Inject(method = "getOrLoad", at = @At("RETURN"), cancellable = true)
    private void peercraft$official(GameProfile profile, CallbackInfoReturnable<CompletableFuture<PlayerSkin>> cir) {
        if (!SkinProfileSupport.missing(profile)) return;
        CompletableFuture<PlayerSkin> fallback = cir.getReturnValue();
        cir.setReturnValue(OfficialSkinProfiles.lookup(SkinProfileSupport.id(profile)).thenCompose(texture ->
                texture.isPresent() ? ((SkinManager) (Object) this).getOrLoad(SkinProfileSupport.withTexture(profile, texture.get())) : fallback));
    }
    //?} else {
    /*//? if <1.21.9
    @Inject(method = "getOrLoad", at = @At("RETURN"), cancellable = true)
    //? if >=1.21.9
    @Inject(method = "get", at = @At("RETURN"), cancellable = true)
    private void peercraft$official(GameProfile profile, CallbackInfoReturnable<CompletableFuture<java.util.Optional<PlayerSkin>>> cir) {
        if (!SkinProfileSupport.missing(profile)) return;
        CompletableFuture<java.util.Optional<PlayerSkin>> fallback = cir.getReturnValue();
        cir.setReturnValue(OfficialSkinProfiles.lookup(SkinProfileSupport.id(profile)).thenCompose(texture ->
                texture.isPresent() ?
                //? if <1.21.9
                ((SkinManager) (Object) this).getOrLoad(SkinProfileSupport.withTexture(profile, texture.get())) : fallback));
                //? if >=1.21.9
                ((SkinManager) (Object) this).get(SkinProfileSupport.withTexture(profile, texture.get())) : fallback));
    }
    *///?}
}
