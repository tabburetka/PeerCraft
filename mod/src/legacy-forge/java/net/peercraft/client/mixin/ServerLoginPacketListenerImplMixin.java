package net.peercraft.client.mixin;

import net.peercraft.client.skin.SkinProfileSupport;

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
 //? if >=1.12 {
 * Forge 1.12.2 backport of {@code src/main/.../ServerLoginPacketListenerImplMixin.java}
 * (cf. 1.16.5 twin). 1.12.2: the class is {@link NetHandlerLoginServer} and the offline
 * profile is built by {@code getOfflineProfile(GameProfile)} (called from
 * {@code processLoginSuccess()} when the profile isn't complete — i.e. the integrated server
 * has online-mode OFF because the host allowed unlicensed players). When
 * {@link PlayerIdentityRegistry} knows this connection's PeerCraft accountId, return a profile
 * carrying that stable id so two joiners with the same nickname don't share one
 //?} else {
//~  * Forge 1.7.10 backport of {@code src/main/.../ServerLoginPacketListenerImplMixin.java} (twin of
//~  * the {@code src/client-1122} mixin). This is the load-bearing mixin the 1.7.10 port keeps a
//~  * Mixin loader for: it forces an unlicensed joiner's in-world UUID to be their stable PeerCraft
//~  * account id, so two joiners who typed the same nickname don't collide on one
 //?}
 * {@code playerdata/<uuid>.dat}. Anonymous joiners fall through to vanilla's body.
 //? if <1.12 {
//~  *
//~  * <p>1.7.10 deltas vs the 1.12.2 twin (all confirmed against the RFG-decompiled
//~  * {@code stable_12} source — these MCP names carry no readable mapping, so the SRG name is
//~  * used verbatim and the refmap maps it 1:1):
//~  * <ul>
//~  *   <li>Class is still {@link NetHandlerLoginServer} ({@code net.minecraft.server.network}).</li>
//~  *   <li>The offline-profile method is {@code func_152506_a(GameProfile)} (no {@code getOfflineProfile}
//~  *       mapping on 1.7.10). Body is the same {@code UUID.nameUUIDFromBytes("OfflinePlayer:"+name)}.</li>
//~  *   <li>The {@link NetworkManager} field is {@code field_147333_a} (no {@code networkManager}
//~  *       mapping); its accessor {@code getSocketAddress()} does have a readable name.</li>
//~  * </ul>
//~  *
//~  * <p>Runtime application is not proven by compilation — check the log for
//~  * "Mixing ServerLoginPacketListenerImplMixin into ...NetHandlerLoginServer".
 //?}
 */
@Mixin(NetHandlerLoginServer.class)
public abstract class ServerLoginPacketListenerImplMixin {

    @Shadow
    @Final
    //? if >=1.12 {
    public NetworkManager networkManager;
    //?} else {
//~     public NetworkManager field_147333_a;
    //?}

    //? if >=1.12 {
    @Inject(method = "getOfflineProfile", at = @At("HEAD"), cancellable = true)
    //?} else {
//~     @Inject(method = "func_152506_a", at = @At("HEAD"), cancellable = true)
    //?}
    private void peercraft$injectAccountUuid(GameProfile original, CallbackInfoReturnable<GameProfile> cir) {
        UUID accountId = resolveAccountIdForThisConnection();
        if (accountId != null) {
            cir.setReturnValue(new GameProfile(accountId, original.getName()));
        }
    }


    //? if >=1.12 {
    @Shadow private GameProfile loginGameProfile;
    //?} else {
//~     @Shadow private GameProfile field_147337_i;
    //?}

    @Inject(method = "processLoginStart", at = @At("RETURN"))
    //? if >=1.12 {
    private void peercraft$localIdentity(net.minecraft.network.login.client.CPacketLoginStart packet, CallbackInfo ci) {
    //?} else {
//~     private void peercraft$localIdentity(net.minecraft.network.login.client.C00PacketLoginStart packet, CallbackInfo ci) {
    //?}
        UUID id = LocalPlayerIdentity.current();
        //? if >=1.12 {
        if (this.networkManager.isLocalChannel() && id != null) {
            this.loginGameProfile = SkinProfileSupport.copy(this.loginGameProfile, id);
        //?} else {
//~         if (this.field_147333_a.isLocalChannel() && id != null) {
//~             this.field_147337_i = SkinProfileSupport.copy(this.field_147337_i, id);
        //?}
        }
    }

    private UUID resolveAccountIdForThisConnection() {
        //? if >=1.12 {
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
        //?} else {
//~         return PlayerIdentityRegistry.INSTANCE.get(this.field_147333_a.getSocketAddress());
        //?}
    }

    @org.spongepowered.asm.mixin.injection.Inject(method = "processLoginStart", at = @At("HEAD"), cancellable = true)
    //? if >=1.12 {
    private void peercraft$handoffAdmission(net.minecraft.network.login.client.CPacketLoginStart packet,
    //?} else {
//~     private void peercraft$handoffAdmission(net.minecraft.network.login.client.C00PacketLoginStart packet,
    //?}
            org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        //? if >=1.12 {
        if (net.peercraft.network.p2p.P2PBridge.INSTANCE.handoffAdmissionClosed() && !this.networkManager.isLocalChannel()) {
            this.networkManager.closeChannel(new net.minecraft.util.text.TextComponentString("PeerCraft: host handoff in progress")); ci.cancel();
        //?} else {
//~         if (net.peercraft.network.p2p.P2PBridge.INSTANCE.handoffAdmissionClosed() && !this.field_147333_a.isLocalChannel()) {
//~             this.field_147333_a.closeChannel(new net.minecraft.util.ChatComponentText("PeerCraft: host handoff in progress")); ci.cancel();
        //?}
        }
    }
}
