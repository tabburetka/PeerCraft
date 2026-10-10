package net.peercraft.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.*;
import net.minecraft.util.ScreenShotHelper;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.peercraft.client.account.*;
import net.peercraft.network.account.AccountClient;
import java.lang.reflect.*;
import java.io.File;
import java.util.UUID;

@Mod(modid="peercraft_progress_ui_probe", name="PeerCraft progress UI probe", version="1", clientSideOnly=true)
public class ProgressUiProbe {
    private int index;
    private long next;
    private boolean ready;
    @Mod.EventHandler public void init(FMLInitializationEvent event) { net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(this); }
    @SubscribeEvent public void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getMinecraft();
        try {
            if (!ready) {
                if (!(mc.currentScreen instanceof GuiMainMenu)) return;
                mc.gameSettings.language = "ru_ru"; mc.gameSettings.guiScale = 2;
                mc.refreshResources(); ready = true;
            }
            if (System.currentTimeMillis() < next) return;
            if (index > 0) {
                ScreenShotHelper.saveScreenshot(new File("/tmp/peercraft-progress-ui/game"), "progress-"+index+".png", mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
                System.out.println("PROGRESS_UI_FRAME " + index);
            }
            if (index == 6) { System.out.println("PROGRESS_UI_DONE"); mc.shutdown(); return; }
            GuiScreen screen;
            if (index < 4) {
                boolean binding = index >= 2;
                screen = new PeerCraftEmailScreen(new GuiMainMenu(), binding);
                if ((index & 1) != 0) {
                    Field field = screen.getClass().getDeclaredField("form"); field.setAccessible(true);
                    EmailRecoveryForm form = (EmailRecoveryForm) field.get(screen);
                    form.stage = EmailRecoveryForm.Stage.CODE; form.status = "peercraft.gui.email.code_sent";
                }
            } else if (index == 4) {
                screen = new PeerCraftConfirmScreen(accepted -> {}, PeerCraftLang.tr("peercraft.gui.transfer.confirm_title"),
                    PeerCraftLang.tr("peercraft.gui.transfer.confirm_message", "Старый мир", UUID.randomUUID(), "Игрок", "ABC123", UUID.randomUUID()),
                    PeerCraftLang.tr("peercraft.gui.transfer.confirm"), PeerCraftLang.tr("peercraft.gui.common.back"));
            } else screen = new PeerCraftProgressToolsScreen(new GuiMainMenu());
            mc.displayGuiScreen(screen);
            Field buttons = GuiScreen.class.getDeclaredField("buttonList"); buttons.setAccessible(true);
            for (GuiButton button : (java.util.List<GuiButton>) buttons.get(screen)) {
                if (button.y < 0 || button.y + button.height > screen.height)
                    throw new IllegalStateException("Button outside screen: " + button.displayString);
            }
            index++; next = System.currentTimeMillis() + 1200;
        } catch (Throwable failure) {
            failure.printStackTrace(); System.out.println("PROGRESS_UI_FAILED"); mc.shutdown();
        }
    }
}
