//? file <1.12
package net.peercraft.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiPlayerInfo;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.peercraft.config.PeerCraftConfig;
import org.lwjgl.opengl.GL11;
import java.util.List;

/** Forge 1.7.10 draws Tab inside GuiIngameForge, before the separate overlay class existed. */
final class SessionPlayerListOverlay {
    private SessionPlayerListOverlay() { }

    @SuppressWarnings("unchecked")
    static void render(RenderGameOverlayEvent.Pre event) {
        if (event.type != RenderGameOverlayEvent.ElementType.PLAYER_LIST
                || PeerCraftConfig.MODE_DISABLED.equals(PeerCraftConfig.mode())) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null || mc.theWorld == null) return;
        List<GuiPlayerInfo> players = (List<GuiPlayerInfo>)mc.thePlayer.sendQueue.playerInfoList;
        int count = Math.max(players.size(), mc.thePlayer.sendQueue.currentServerMaxPlayers);
        if (count == 0) return;
        event.setCanceled(true);
        int columns = Math.max(1, (count + 19) / 20);
        int rows = (count + columns - 1) / columns;
        Scoreboard board = mc.theWorld.getScoreboard();
        ScoreObjective objective = board.func_96539_a(0);
        int needed = 150;
        for (GuiPlayerInfo player : players) {
            String name = ScorePlayerTeam.formatPlayerName(board.getPlayersTeam(player.name), player.name);
            int scoreWidth = objective == null ? 0 : mc.fontRenderer.getStringWidth(score(board, objective, player.name)) + 8;
            needed = Math.max(needed, mc.fontRenderer.getStringWidth(name) + scoreWidth + 64);
        }
        int columnWidth = Math.max(1, Math.min(needed, (event.resolution.getScaledWidth() - 20) / columns));
        int left = (event.resolution.getScaledWidth() - columns * columnWidth) / 2;
        Gui.drawRect(left - 1, 9, left + columns * columnWidth, 10 + rows * 9, Integer.MIN_VALUE);
        for (int index = 0; index < count; index++) {
            int x = left + index % columns * columnWidth;
            int y = 10 + index / columns * 9;
            Gui.drawRect(x, y, x + columnWidth - 1, y + 8, 553648127);
            if (index >= players.size()) continue;
            GuiPlayerInfo player = players.get(index);
            String name = ScorePlayerTeam.formatPlayerName(board.getPlayersTeam(player.name), player.name);
            String score = objective == null ? "" : score(board, objective, player.name);
            int scoreWidth = objective == null ? 0 : mc.fontRenderer.getStringWidth(score) + 8;
            mc.fontRenderer.drawStringWithShadow(mc.fontRenderer.trimStringToWidth(name, Math.max(1, columnWidth - 62 - scoreWidth)), x, y, 0xFFFFFF);
            if (scoreWidth > 0) mc.fontRenderer.drawStringWithShadow(score,
                    x + columnWidth - 62 - mc.fontRenderer.getStringWidth(score), y, 0xFFFF55);
            int ping = player.responseTime;
            String value = ping < 0 ? "—" : PeerCraftLang.tr("peercraft.session.ping", ping > 9999 ? "9999+" : Integer.toString(ping));
            int color = ping < 0 ? 0xA99D89 : ping < 150 ? 0x98C69B : 0xE8AF50;
            mc.fontRenderer.drawStringWithShadow(value, x + columnWidth - mc.fontRenderer.getStringWidth(value) - 2, y, color);
        }
        GL11.glColor4f(1, 1, 1, 1);
        mc.getTextureManager().bindTexture(new net.minecraft.util.ResourceLocation("textures/gui/icons.png"));
    }

    private static String score(Scoreboard board, ScoreObjective objective, String player) {
        return Integer.toString(board.func_96529_a(player, objective).getScorePoints());
    }
}
