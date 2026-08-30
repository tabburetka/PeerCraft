package net.peercraft.client.mixin;

import com.mojang.authlib.GameProfile;
import net.minecraft.network.NetworkManager;
import net.minecraft.server.network.NetHandlerLoginServer;
import net.peercraft.network.p2p.PlayerIdentityRegistry;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.UUID;

/**
 * Forge 1.12.2 backport of {@code src/main/.../ServerLoginPacketListenerImplMixin.java}
 * (cf. 1.16.5 twin). 1.12.2: the class is {@link NetHandlerLoginServer} and the offline
 * profile is built by {@code getOfflineProfile(GameProfile)} (called from
 * {@code processLoginSuccess()} when the profile isn't complete — i.e. the integrated server
 * has online-mode OFF because the host allowed unlicensed players). When
 * {@link PlayerIdentityRegistry} knows this connection's PeerCraft accountId, return a profile
 * carrying that stable id so two joiners with the same nickname don't share one
 * {@code playerdata/<uuid>.dat}. Anonymous joiners fall through to vanilla's body.
 */
@Mixin(NetHandlerLoginServer.class)
public abstract class ServerLoginPacketListenerImplMixin {

    @Shadow
    @Final
    public NetworkManager networkManager;

    @Inject(method = "getOfflineProfile", at = @At("HEAD"), cancellable = true)
    private void peercraft$injectAccountUuid(GameProfile original, CallbackInfoReturnable<GameProfile> cir) {
        UUID accountId = resolveAccountIdForThisConnection();
        if (accountId != null) {
            cir.setReturnValue(new GameProfile(accountId, original.getName()));
        }
    }

    private UUID resolveAccountIdForThisConnection() {
        SocketAddress address = this.networkManager.getRemoteAddress();
        if (!(address instanceof InetSocketAddress)) {
            return null;
        }
        return PlayerIdentityRegistry.INSTANCE.get(((InetSocketAddress) address).getPort());
    }
}
