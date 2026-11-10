package net.peercraft.client.mixin;

import net.minecraft.client.gui.components.AbstractWidget;
import org.spongepowered.asm.mixin.Mixin;

/** Retains tooltips when an existing button is wrapped by the shared theme. */
@Mixin(AbstractWidget.class)
public interface PeerCraftWidgetTooltipAccessor {
    //? if >=1.21.6 {
    /*@org.spongepowered.asm.mixin.gen.Accessor("tooltip")
    net.minecraft.client.gui.components.WidgetTooltipHolder peercraft$tooltip();*/
    //?}
}
