package net.peercraft.client.mixin;

//? if >=1.12 {
import net.minecraft.server.management.PlayerList;
//?} else {
//~ import net.minecraft.server.management.ServerConfigurationManager;
//?}
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Disable the name-based embedded-player shortcut only while reading player data. */
//? if >=1.12 {
@Mixin(PlayerList.class)
//?} else {
//~ @Mixin(ServerConfigurationManager.class)
//?}
public abstract class PlayerDataLoadMixin {
    @Redirect(method = "readPlayerDataFromFile", at = @At(value = "INVOKE", target = "Ljava/lang/String;equals(Ljava/lang/Object;)Z"))
    private boolean peercraft$loadByUuid(String name, Object owner) { return false; }

    // Forge adds this method, so it has no MCP/SRG mapping.
    @Redirect(method = "getPlayerNBT", remap = false,
            at = @At(value = "INVOKE", target = "Ljava/lang/String;equals(Ljava/lang/Object;)Z", remap = false))
    private boolean peercraft$inspectByUuid(String name, Object owner) { return false; }
}
