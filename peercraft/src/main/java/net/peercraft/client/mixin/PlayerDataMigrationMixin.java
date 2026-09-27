package net.peercraft.client.mixin;

import com.mojang.authlib.GameProfile;
import net.minecraft.client.server.IntegratedServer;
import net.peercraft.client.handoff.WorldArchiver;
import net.peercraft.network.p2p.LocalPlayerIdentity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.io.IOException;

/** World storage is locked, but no regions/stats have been opened yet. Fail closed on migration errors. */
@Mixin(IntegratedServer.class)
public abstract class PlayerDataMigrationMixin {
    @Inject(method = "initServer", at = @At("HEAD"))
    private void peercraft$preparePlayerData(CallbackInfoReturnable<Boolean> cir) throws IOException {
        IntegratedServer server = (IntegratedServer) (Object) this;
        GameProfile profile = server.getSingleplayerProfile();
        if (profile == null) throw new IOException("Missing singleplayer profile during migration");
        //? if <1.21.9
        LocalPlayerIdentity.prepare(WorldArchiver.worldDir(server), profile.getId());
        //? if >=1.21.9
        /*LocalPlayerIdentity.prepare(WorldArchiver.worldDir(server), profile.id());*/
        // 26.x persists this profile's UUID as singleplayer_uuid. Keep it equal to
        // the file actually played, so removing PeerCraft still loads the current host.
        //? if <1.21.9 {
        if (!LocalPlayerIdentity.current().equals(profile.getId())) {
            server.setSingleplayerProfile(new GameProfile(LocalPlayerIdentity.current(), profile.getName()));
        }
        //?} else {
        /*if (!LocalPlayerIdentity.current().equals(profile.id())) {
            server.setSingleplayerProfile(new GameProfile(LocalPlayerIdentity.current(), profile.name()));
        }
        *///?}
    }
}
