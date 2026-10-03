package net.peercraft.client.gui;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiComponent;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.peercraft.client.theme.SteampunkPalette;

class SteampunkButton extends Button {
    private boolean primary;
    SteampunkButton(int x, int y, int width, int height, Component label, OnPress callback) {
        super(x, y, width, height, label, callback);
    }
    SteampunkButton primary() { this.primary = true; return this; }
    @Override public void renderButton(PoseStack pose, int mouseX, int mouseY, float partialTick) {
        int edge = !this.active ? SteampunkPalette.BORDER : this.isFocused() ? SteampunkPalette.ACCENT
                : this.isHovered() ? SteampunkPalette.BORDER_HOVER : SteampunkPalette.BORDER;
        GuiComponent.fill(pose, this.x, this.y, this.x + this.width, this.y + this.height, edge);
        GuiComponent.fill(pose, this.x + 1, this.y + 1, this.x + this.width - 1, this.y + this.height - 1,
                !this.active ? SteampunkPalette.DISABLED : primary ? (this.isHovered() ? SteampunkPalette.PRIMARY_HOVER : SteampunkPalette.PRIMARY) : this.isHovered() ? SteampunkPalette.CONTROL_HOVER : SteampunkPalette.CONTROL);
        Minecraft mc = Minecraft.getInstance();
        String label = mc.font.plainSubstrByWidth(this.getMessage().getString(), Math.max(0, this.width - 12));
        if (this.active && primary) {
            mc.font.draw(pose, label, this.x + (this.width - mc.font.width(label)) / 2,
                    this.y + (this.height - mc.font.lineHeight) / 2, SteampunkPalette.CONTROL);
        } else {
        GuiComponent.drawCenteredString(pose, mc.font, label, this.x + this.width / 2,
                this.y + (this.height - mc.font.lineHeight) / 2, this.active ? (primary ? SteampunkPalette.CONTROL : SteampunkPalette.TEXT) : SteampunkPalette.MUTED);
        }
    }
}
