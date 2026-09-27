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
 * Forge 1.7.10 backport of {@code src/main/.../ServerLoginPacketListenerImplMixin.java} (twin of
 * the {@code src/client-1122} mixin). This is the load-bearing mixin the 1.7.10 port keeps a
 * Mixin loader for: it forces an unlicensed joiner's in-world UUID to be their stable PeerCraft
 * account id, so two joiners who typed the same nickname don't collide on one
 * {@code playerdata/<uuid>.dat}. Anonymous joiners fall through to vanilla's body.
 *
 * <p>1.7.10 deltas vs the 1.12.2 twin (all confirmed against the RFG-decompiled
 * {@code stable_12} source — these MCP names carry no readable mapping, so the SRG name is
 * used verbatim and the refmap maps it 1:1):
 * <ul>
 *   <li>Class is still {@link NetHandlerLoginServer} ({@code net.minecraft.server.network}).</li>
 *   <li>The offline-profile method is {@code func_152506_a(GameProfile)} (no {@code getOfflineProfile}
 *       mapping on 1.7.10). Body is the same {@code UUID.nameUUIDFromBytes("OfflinePlayer:"+name)}.</li>
 *   <li>The {@link NetworkManager} field is {@code field_147333_a} (no {@code networkManager}
 *       mapping); its accessor {@code getSocketAddress()} does have a readable name.</li>
 * </ul>
 *
 * <p>Runtime application is not proven by compilation — check the log for
 * "Mixing ServerLoginPacketListenerImplMixin into ...NetHandlerLoginServer".
 */
@Mixin(NetHandlerLoginServer.class)
public abstract class ServerLoginPacketListenerImplMixin {

    @Shadow
    @Final
    public NetworkManager field_147333_a;

    @Inject(method = "func_152506_a", at = @At("HEAD"), cancellable = true)
    private void peercraft$injectAccountUuid(GameProfile original, CallbackInfoReturnable<GameProfile> cir) {
        UUID accountId = resolveAccountIdForThisConnection();
        if (accountId != null) {
            cir.setReturnValue(new GameProfile(accountId, original.getName()));
        }
    }


    @Shadow private GameProfile field_147337_i;

    @Inject(method = "processLoginStart", at = @At("RETURN"))
    private void peercraft$localIdentity(net.minecraft.network.login.client.C00PacketLoginStart packet, CallbackInfo ci) {
        UUID id = LocalPlayerIdentity.current();
        if (this.field_147333_a.isLocalChannel() && id != null) {
            this.field_147337_i = new GameProfile(id, this.field_147337_i.getName());
        }
    }

    private UUID resolveAccountIdForThisConnection() {
        return PlayerIdentityRegistry.INSTANCE.get(this.field_147333_a.getSocketAddress());
    }
}
