package net.peercraft.client.mixin;

import com.mojang.authlib.GameProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.server.integrated.IntegratedServer;
import net.peercraft.client.handoff.WorldArchiver;
import net.peercraft.network.p2p.LocalPlayerIdentity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.io.IOException;

/** Migrate before any world regions are loaded. */
@Mixin(IntegratedServer.class)
public abstract class PlayerDataMigrationMixin {
    @Inject(method = "startServer", at = @At("HEAD"))
    private void peercraft$preparePlayerData(CallbackInfoReturnable<Boolean> cir) throws IOException {
        IntegratedServer server = (IntegratedServer) (Object) this;
        GameProfile profile = Minecraft.getMinecraft().getSession().func_148256_e();
        LocalPlayerIdentity.prepare(WorldArchiver.worldDir(server), profile.getId(), profile.getName());
    }
}
