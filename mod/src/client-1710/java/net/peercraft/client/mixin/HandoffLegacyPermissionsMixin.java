package net.peercraft.client.mixin;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.ServerConfigurationManager;
import net.peercraft.client.handoff.HandoffOwnerPolicy;
import net.peercraft.client.handoff.WorldArchiver;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.io.IOException;
import java.util.UUID;

/** Preserve the original owner's command authority instead of promoting the new local host. */
@Mixin(ServerConfigurationManager.class)
public abstract class HandoffLegacyPermissionsMixin {
    @Shadow @Final private MinecraftServer mcServer;
    @Unique private boolean peercraft$ownerLoaded;
    @Unique private UUID peercraft$originalOwner;

    @Unique
    private UUID peercraft$owner() {
        if (!peercraft$ownerLoaded) {
            try { peercraft$originalOwner = HandoffOwnerPolicy.read(WorldArchiver.worldDir(mcServer)); }
            catch (IOException invalid) { throw new IllegalStateException("Invalid handoff owner record", invalid); }
            peercraft$ownerLoaded = true;
        }
        return peercraft$originalOwner;
    }

    @Redirect(method = "func_152596_g", at = @At(value = "INVOKE", target = "Ljava/lang/String;equalsIgnoreCase(Ljava/lang/String;)Z"))
    private boolean peercraft$ownerById(String serverOwner, String playerName, GameProfile profile) {
        UUID owner = peercraft$owner();
        return owner == null ? serverOwner.equalsIgnoreCase(playerName) : owner.equals(profile.getId());
    }
}
