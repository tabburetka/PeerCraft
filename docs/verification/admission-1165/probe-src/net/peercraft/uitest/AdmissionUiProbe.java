package net.peercraft.uitest;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.peercraft.client.gui.JoinRequestsScreen;
public final class AdmissionUiProbe implements ClientModInitializer {
    private int ticks;private boolean started,done;
    public void onInitializeClient(){net.peercraft.config.TelemetryConsent.choose(false);ClientTickEvents.END_CLIENT_TICK.register(mc->{
        if(done)return;
        try{
            if(!started){if(!(mc.screen instanceof net.minecraft.client.gui.screens.TitleScreen)||mc.getOverlay()!=null)return;started=true;open(mc,0);}
            ticks++;
            if(ticks==20||ticks==50||ticks==80||ticks==120||ticks==150||ticks==180)capture(mc,"admission-"+ticks);
            if(ticks==30||ticks==130)open(mc,1);
            if(ticks==60||ticks==160)open(mc,2);
            if(ticks==100){org.lwjgl.glfw.GLFW.glfwSetWindowSize(mc.getWindow().getWindow(),640,480);open(mc,0);}
            if(ticks==190){done=true;System.out.println("ADMISSION_UI_1165_PASSED");mc.stop();}
        }catch(Throwable failure){failure.printStackTrace();done=true;System.out.println("ADMISSION_UI_1165_FAILED");mc.stop();}
    });}
    private void open(Minecraft mc,int page)throws Exception{JoinRequestsScreen screen=(page==1 ? new net.peercraft.client.gui.JoinRequestWaitingScreen(new net.minecraft.client.gui.screens.TitleScreen()) : new JoinRequestsScreen(new net.minecraft.client.gui.screens.TitleScreen()));varPage(screen,page);mc.setScreen(screen);}
    private void varPage(JoinRequestsScreen screen,int value)throws Exception{java.lang.reflect.Field page=JoinRequestsScreen.class.getDeclaredField("page");page.setAccessible(true);page.setInt(screen,value);}
    private void capture(Minecraft mc,String name){
        for(net.minecraft.client.gui.components.events.GuiEventListener c:mc.screen.children())if(c instanceof net.minecraft.client.gui.components.Button){net.minecraft.client.gui.components.Button b=(net.minecraft.client.gui.components.Button)c;if(b.y<0||b.y+b.getHeight()>mc.screen.height)throw new IllegalStateException("Button outside screen");}
        net.minecraft.client.Screenshot.grab(mc.gameDirectory,name+".png",mc.getWindow().getWidth(),mc.getWindow().getHeight(),mc.getMainRenderTarget(),m->{});
    }
}
