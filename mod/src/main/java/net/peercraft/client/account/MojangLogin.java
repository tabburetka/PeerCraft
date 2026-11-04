package net.peercraft.client.account;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.minecraft.MinecraftSessionService;
import net.peercraft.network.account.AccountClient;

/** Licensed sign-in: binds this Minecraft version's authlib session service to {@link AccountClient}. */
public final class MojangLogin {
    private MojangLogin() {
    }

    public static void start(GameProfile profile, String accessToken, MinecraftSessionService sessionService,
                             AccountClient.AuthCallback cb) {
        // GameProfile became a record in 1.21.9 — getName()/getId() are now name()/id().
        //? if <1.21.9 {
        String name = profile.getName();
        java.util.UUID id = profile.getId();
        //?} else {
        /*String name = profile.name();
        java.util.UUID id = profile.id();*/
        //?}
        //? if >=1.17 {
        AccountClient.INSTANCE.loginLicensed(name, serverId -> sessionService.joinServer(id, accessToken, serverId), cb);
        //?} else {
        /*// authlib 2.1.28 (1.16.5) joins by the whole GameProfile.
        AccountClient.INSTANCE.loginLicensed(name, serverId -> sessionService.joinServer(profile, accessToken, serverId), cb);*/
        //?}
    }
}
