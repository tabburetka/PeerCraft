package net.peercraft.client.mixin;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.Connection;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import net.peercraft.network.p2p.PlayerIdentityRegistry;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.UUID;

/**
 * Phase 5 (save-data isolation): when a host allows unlicensed players (PeerCraftHostOptions
 * .allowUnlicensedPlayers — see ShareToLanScreenMixin/OpenToLanMixin), the IntegratedServer's
 * authentication is off, and vanilla assigns every joiner a UUID derived purely from their
 * nickname ({@code UUIDUtil.createOfflineProfile}, called from {@code handleHello} below,
 * exactly the call this mixin redirects). Two joiners sharing a nickname would then collide
 * into the exact same UUID — and therefore the exact same {@code playerdata/<uuid>.dat} file,
 * silently overwriting each other's inventory.
 *
 * This redirect swaps that offline hash for the joiner's real PeerCraft accountId whenever
 * one is known — see {@link PlayerIdentityRegistry} for how {@code P2PBridge} populates the
 * local-port -> accountId mapping this reads (the loopback trick: a HostConnection's outgoing
 * socket's local port is exactly the remote port the server sees on the matching inbound
 * connection). Anonymous joiners (no PeerCraft account, or the registry simply has no entry
 * for this connection yet) fall through to vanilla's own behavior unchanged — this is purely
 * additive, never a regression.
 *
 * Doesn't need its own online-mode check: {@code createOfflineProfile} is called from {@code
 * handleHello} ONLY inside the branch where {@code !server.usesAuthentication()} already —
 * when the host disallows unlicensed players (authentication on), this call site — and thus
 * this redirect — is simply never reached at all; vanilla's real Mojang handshake runs
 * instead and assigns the genuine UUID on its own.
 */
@Mixin(ServerLoginPacketListenerImpl.class)
public abstract class ServerLoginPacketListenerImplMixin {

    @Shadow
    @Final
    Connection connection;

    @Redirect(method = "handleHello", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/core/UUIDUtil;createOfflineProfile(Ljava/lang/String;)Lcom/mojang/authlib/GameProfile;"))
    private GameProfile peercraft$injectAccountUuid(String username) {
        UUID accountId = resolveAccountIdForThisConnection();
        return accountId != null ? new GameProfile(accountId, username) : UUIDUtil.createOfflineProfile(username);
    }

    private UUID resolveAccountIdForThisConnection() {
        SocketAddress address = this.connection.getRemoteAddress();
        if (!(address instanceof InetSocketAddress inetAddress)) {
            return null;
        }
        return PlayerIdentityRegistry.INSTANCE.get(inetAddress.getPort());
    }
}
