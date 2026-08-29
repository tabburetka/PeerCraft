package net.peercraft.client.mixin;

import com.mojang.authlib.GameProfile;
import net.minecraft.network.Connection;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
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
 * Minecraft 1.16.5 backport of {@code src/main/.../ServerLoginPacketListenerImplMixin.java}.
 *
 * <p>1.16.5 has no {@code net.minecraft.core.UUIDUtil} — the offline {@link GameProfile} is
 * built by {@code ServerLoginPacketListenerImpl.createFakeProfile(GameProfile)} (called from
 * {@code handleAcceptedLogin()} only when the incoming profile isn't complete, i.e. only when
 * the integrated server has authentication OFF because the host allowed unlicensed players —
 * same guard the modern redirect relied on). So instead of a {@code @Redirect} on the missing
 * {@code UUIDUtil} call, this injects at the head of {@code createFakeProfile} and, when
 * {@link PlayerIdentityRegistry} knows this connection's PeerCraft accountId, returns a profile
 * carrying that stable id instead of the nickname-derived offline hash — preventing two joiners
 * with the same nickname from sharing one {@code playerdata/<uuid>.dat} file. Anonymous joiners
 * fall through to vanilla's own {@code createFakeProfile} body unchanged.
 */
@Mixin(ServerLoginPacketListenerImpl.class)
public abstract class ServerLoginPacketListenerImplMixin {

    @Shadow
    @Final
    public Connection connection;

    @Inject(method = "createFakeProfile", at = @At("HEAD"), cancellable = true)
    private void peercraft$injectAccountUuid(GameProfile original, CallbackInfoReturnable<GameProfile> cir) {
        UUID accountId = resolveAccountIdForThisConnection();
        if (accountId != null) {
            cir.setReturnValue(new GameProfile(accountId, original.getName()));
        }
    }

    private UUID resolveAccountIdForThisConnection() {
        SocketAddress address = this.connection.getRemoteAddress();
        if (!(address instanceof InetSocketAddress)) {
            return null;
        }
        return PlayerIdentityRegistry.INSTANCE.get(((InetSocketAddress) address).getPort());
    }
}
