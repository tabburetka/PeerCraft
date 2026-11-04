package net.peercraft.uitest;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraft.client.Minecraft;
import net.peercraft.client.gui.JoinRequestsScreen;
@Mod(modid="peercraft_admission_probe",name="Admission verification",version="1")
public final class AdmissionUiProbe {
    private int ticks;private boolean started,done;
    @Mod.EventHandler public void init(FMLInitializationEvent event){cpw.mods.fml.common.FMLCommonHandler.instance().bus().register(this);net.peercraft.config.TelemetryConsent.choose(false);}
    @SubscribeEvent public void tick(TickEvent.ClientTickEvent event){
        if(event.phase!=TickEvent.Phase.END||done)return;Minecraft mc=Minecraft.getMinecraft();
        try{
            if(!started){if(!(mc.currentScreen instanceof net.minecraft.client.gui.GuiMainMenu))return;started=true;open(mc,0);}
            ticks++;
            if(ticks==20||ticks==50||ticks==80||ticks==120||ticks==150||ticks==180)capture(mc,"admission-"+ticks);
            if(ticks==30||ticks==130)open(mc,1);
            if(ticks==60||ticks==160)open(mc,2);
            if(ticks==100){mc.resize(640,480);open(mc,0);}
            if(ticks==190){done=true;System.out.println("ADMISSION_UI_1710_PASSED");mc.shutdown();}
        }catch(Throwable failure){failure.printStackTrace();done=true;System.out.println("ADMISSION_UI_1710_FAILED");mc.shutdown();}
    }
    private void open(Minecraft mc,int value)throws Exception{JoinRequestsScreen screen=(value==1 ? new net.peercraft.client.gui.JoinRequestWaitingScreen(new net.minecraft.client.gui.GuiMainMenu()) : new JoinRequestsScreen(new net.minecraft.client.gui.GuiMainMenu()));java.lang.reflect.Field page=JoinRequestsScreen.class.getDeclaredField("page");page.setAccessible(true);page.setInt(screen,value);mc.displayGuiScreen(screen);}
    private void capture(Minecraft mc,String name){
        net.minecraft.util.ScreenShotHelper.saveScreenshot(mc.mcDataDir,name+".png",mc.displayWidth,mc.displayHeight,mc.getFramebuffer());
    }
}
