package net.peercraft.client.mixin;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.peercraft.client.gui.PeerCraftInGameMenuScreen;
import net.peercraft.client.gui.PeerCraftUi;
import net.peercraft.client.gui.FriendRequestNotifier;
import net.peercraft.config.PeerCraftConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * One small "PC" entry beside "Back to game" that opens the in-game PeerCraft menu (handoff,
 * session monitor, join requests, friends). Never moves vanilla or other mods' buttons.
 */
@Mixin(PauseScreen.class)
public abstract class PauseScreenMixin extends Screen {

    protected PauseScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void peercraft$addMenuButton(CallbackInfo ci) {
        if (PeerCraftConfig.MODE_DISABLED.equals(PeerCraftConfig.mode())) return;
        List<int[]> occupied = new ArrayList<>();
        int[] anchor = null;
        for (GuiEventListener child : children()) {
            if (child instanceof AbstractWidget) {
                AbstractWidget widget = (AbstractWidget) child;
                int[] rect = {widget.getX(), widget.getY(), widget.getWidth(), widget.getHeight()};
                occupied.add(rect);
                // "Back to game" is the topmost full-width button of the vanilla menu.
                if (widget.getWidth() >= 150 && (anchor == null || rect[1] < anchor[1])) anchor = rect;
            }
        }
        // F3+Esc pause shows no menu at all — stay out of it too.
        if (occupied.isEmpty()) return;
        net.peercraft.client.theme.SessionPauseLayout layout =
                net.peercraft.client.theme.SessionPauseLayout.icon(width, height, occupied, anchor, 20);
        int pending = FriendRequestNotifier.pendingCount();
        Component label = pending > 0
                ? Component.literal("PC").withStyle(ChatFormatting.YELLOW)
                : Component.literal("PC");
        Component tooltip = pending > 0
                ? Component.translatable("peercraft.ingame.button_tooltip_pending", pending)
                : Component.translatable("peercraft.ingame.button_tooltip");
        addRenderableWidget(Button.builder(label,
                        b -> PeerCraftUi.setScreen(minecraft, new PeerCraftInGameMenuScreen(this)))
                .bounds(layout.left, layout.top, 20, 20)
                .tooltip(Tooltip.create(tooltip))
                .build());
    }
}
