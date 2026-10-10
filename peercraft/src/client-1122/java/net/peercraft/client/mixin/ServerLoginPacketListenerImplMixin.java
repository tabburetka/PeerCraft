package net.peercraft.client.mixin;

import com.mojang.authlib.GameProfile;
import net.minecraft.network.NetworkManager;
import net.minecraft.server.network.NetHandlerLoginServer;
import net.peercraft.network.p2p.PlayerIdentityRegistry;
import net.peercraft.network.p2p.LocalPlayerIdentity;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

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


    @Shadow private GameProfile loginGameProfile;

    @Inject(method = "processLoginStart", at = @At("RETURN"))
    private void peercraft$localIdentity(net.minecraft.network.login.client.CPacketLoginStart packet, CallbackInfo ci) {
        UUID id = LocalPlayerIdentity.current();
        if (this.networkManager.isLocalChannel() && id != null) {
            this.loginGameProfile = new GameProfile(id, this.loginGameProfile.getName());
        }
    }

    private UUID resolveAccountIdForThisConnection() {
        return PlayerIdentityRegistry.INSTANCE.get(this.networkManager.getRemoteAddress());
    }

    /** Forge changes the encoder protocol synchronously, while vanilla queues LoginSuccess.
     * Keep the handshake behind that queued write on the same channel event loop. */
    @org.spongepowered.asm.mixin.injection.Redirect(method = {"tryAcceptPlayer", "update"},
            at = @At(value = "INVOKE", remap = false,
                    target = "Lnet/minecraftforge/fml/common/network/internal/FMLNetworkHandler;fmlServerHandshake(Lnet/minecraft/server/management/PlayerList;Lnet/minecraft/network/NetworkManager;Lnet/minecraft/entity/player/EntityPlayerMP;)V"))
    private void peercraft$orderedForgeHandshake(net.minecraft.server.management.PlayerList players,
            NetworkManager manager, net.minecraft.entity.player.EntityPlayerMP player) {
        if (!manager.isLocalChannel() && resolveAccountIdForThisConnection() != null) {
            manager.channel().eventLoop().execute(() -> {
                if (manager.isChannelOpen())
                    net.minecraftforge.fml.common.network.internal.FMLNetworkHandler.fmlServerHandshake(players, manager, player);
            });
        } else {
            net.minecraftforge.fml.common.network.internal.FMLNetworkHandler.fmlServerHandshake(players, manager, player);
        }
    }

    @org.spongepowered.asm.mixin.injection.Inject(method = "processLoginStart", at = @At("HEAD"), cancellable = true)
    private void peercraft$handoffAdmission(net.minecraft.network.login.client.CPacketLoginStart packet,
            org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        if (net.peercraft.network.p2p.P2PBridge.INSTANCE.handoffAdmissionClosed() && !this.networkManager.isLocalChannel()) {
            this.networkManager.closeChannel(new net.minecraft.util.text.TextComponentString("PeerCraft: host handoff in progress")); ci.cancel();
        }
    }
}
