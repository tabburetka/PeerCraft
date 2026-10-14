package net.peercraft.client.mixin;

//? if <26.2
import net.minecraft.client.Minecraft;
//? if >=26.2
/*import net.minecraft.client.gui.Gui;*/
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.peercraft.client.gui.PeerCraftMultiplayerScreen;
import net.peercraft.config.PeerCraftConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Route vanilla multiplayer destinations, including Disconnect, through the PeerCraft UI. */
//? if <26.2
@Mixin(Minecraft.class)
//? if >=26.2
/*@Mixin(Gui.class)*/
public abstract class MultiplayerScreenRoutingMixin {
    @ModifyVariable(method = "setScreen", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private Screen peercraft$routeMultiplayer(Screen destination) {
        // Exact matching avoids rewrapping PeerCraft and preserves other mods' screens.
        if (!PeerCraftConfig.MODE_DISABLED.equals(PeerCraftConfig.mode())
                && destination != null && destination.getClass() == JoinMultiplayerScreen.class) {
            return new PeerCraftMultiplayerScreen(new TitleScreen());
        }
        return destination;
    }
}
