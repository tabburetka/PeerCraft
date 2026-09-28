package net.peercraft.client.mixin;

import com.mojang.authlib.GameProfile;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.play.server.SPacketEntityStatus;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.PlayerList;
import net.minecraft.server.management.UserListOps;
import net.peercraft.client.handoff.HandoffOwnerPolicy;
import net.peercraft.client.handoff.WorldArchiver;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.IOException;
import java.util.UUID;

/** 1.12.2 bases integrated-server commands on the host's name, and sends every player OP UI. */
@Mixin(PlayerList.class)
public abstract class HandoffLegacyPermissionsMixin {
    @Shadow @Final private MinecraftServer server;
    @Shadow @Final private UserListOps ops;
    @Shadow private boolean commandsAllowedForAll;
    @Unique private boolean peercraft$ownerLoaded;
    @Unique private UUID peercraft$originalOwner;

    @Unique
    private UUID peercraft$owner() {
        if (!peercraft$ownerLoaded) {
            try { peercraft$originalOwner = HandoffOwnerPolicy.read(WorldArchiver.worldDir(server)); }
            catch (IOException invalid) { throw new IllegalStateException("Invalid handoff owner record", invalid); }
            peercraft$ownerLoaded = true;
        }
        return peercraft$originalOwner;
    }

    @Redirect(method = "canSendCommands", at = @At(value = "INVOKE", target = "Ljava/lang/String;equalsIgnoreCase(Ljava/lang/String;)Z"))
    private boolean peercraft$ownerById(String serverOwner, String playerName, GameProfile profile) {
        UUID owner = peercraft$owner();
        return owner == null ? serverOwner.equalsIgnoreCase(playerName) : owner.equals(profile.getId());
    }

    @Inject(method = "updatePermissionLevel", at = @At("TAIL"))
    private void peercraft$correctClientPermission(EntityPlayerMP player, CallbackInfo ci) {
        UUID owner = peercraft$owner();
        if (owner == null || player.connection == null) return;
        GameProfile profile = player.getGameProfile();
        int level = commandsAllowedForAll ? 4 : ops.getEntry(profile) != null ? ops.getPermissionLevel(profile)
                : owner.equals(profile.getId()) && server.worlds[0].getWorldInfo().areCommandsAllowed() ? 4 : 0;
        player.connection.sendPacket(new SPacketEntityStatus(player, (byte) (24 + Math.max(0, Math.min(4, level)))));
    }
}
