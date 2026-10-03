package net.peercraft.client.mixin;

import net.minecraft.client.gui.components.AbstractWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Adjusts the real input bounds together with the compact LAN control rendering. */
@Mixin(AbstractWidget.class)
public interface PeerCraftWidgetSizeAccessor {
    @Accessor("height") void peercraft$setHeight(int height);
}
