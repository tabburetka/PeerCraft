package net.peercraft.uitest;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.Minecraft;
import net.peercraft.network.rendezvous.*;
import net.peercraft.network.account.AccountClient;
import net.peercraft.network.admission.*;
import static net.peercraft.network.admission.AdmissionProtocol.*;
import java.net.*;
import java.util.*;

/** Local real server + authenticated synthetic guests; production UI, no mock request state. */
public final class AdmissionLivePreview implements ClientModInitializer {
    private volatile boolean prepared;
    private boolean world,ready;
    private int ticks;
    private static final int PORT=51193;
    private byte[] exchange(DatagramSocket socket,byte[] bytes)throws Exception{
        socket.send(new DatagramPacket(bytes,bytes.length,InetAddress.getLoopbackAddress(),PORT));DatagramPacket response=new DatagramPacket(new byte[2048],2048);socket.receive(response);return Arrays.copyOf(response.getData(),response.getLength());
    }
    @Override public void onInitializeClient(){
        net.peercraft.config.TelemetryConsent.choose(false);
        Thread control=new Thread(()->{try(DatagramSocket host=new DatagramSocket();DatagramSocket guest=new DatagramSocket()){
            host.setSoTimeout(2000);guest.setSoTimeout(2000);
            byte[] hostAuth=exchange(host,AccountProtocol.encodeAccountRegister("LocalHost"+System.nanoTime()%100000,new byte[16],new byte[32]));
            var h=AccountProtocol.decodeAuthOk(hostAuth,hostAuth.length);
            // Decode variable-sized AUTH_OK with its actual received length below for all remaining messages.
            var current=new AccountClient.AccountSession(h.accountId(),h.sessionToken(),h.rememberToken(),h.licensed(),h.friendCode(),h.displayName());
            var field=AccountClient.class.getDeclaredField("currentSession");field.setAccessible(true);field.set(AccountClient.INSTANCE,current);
            byte[] register=registration(RendezvousProtocol.encodeRegisterWithAccount(8,1,h.accountId(),h.sessionToken(),false,true,"Local admission review","26.2"),true);
            byte[] response=exchange(host,register);String room=RendezvousProtocol.decodeRoomCreated(response,response.length).code();
            AdmissionClient.INSTANCE.host(room);
            for(String name:new String[]{"Alex","Wanderer"}){
                byte[] auth=exchange(guest,AccountProtocol.encodeAccountRegister(name+System.nanoTime()%100000,new byte[16],new byte[32]));var g=AccountProtocol.decodeAuthOk(auth,auth.length);
                byte[] reply=exchange(guest,encode(message(REQUEST,UUID.randomUUID(),room,g.sessionToken(),0)));
                if(decode(reply,reply.length).status!=PENDING)throw new IllegalStateException("Expected pending request");
            }
            prepared=true;
            while(true){Thread.sleep(10_000);exchange(host,register);}
        }catch(Exception failure){failure.printStackTrace();System.out.println("ADMISSION_LIVE_FAILED");}},"Admission-local-fixture");control.setDaemon(true);control.start();
        ClientTickEvents.END_CLIENT_TICK.register(mc->{
            if(prepared && !world && mc.gui.screen() instanceof TitleScreen && mc.gui.overlay()==null){
                world=true;
                var settings=new net.minecraft.world.level.LevelSettings("PeerCraft — настоящие заявки",net.minecraft.world.level.GameType.CREATIVE,
                    new net.minecraft.world.level.LevelSettings.DifficultySettings(net.minecraft.world.Difficulty.PEACEFUL,false,false),true,net.minecraft.world.level.WorldDataConfiguration.DEFAULT);
                mc.createWorldOpenFlows().createFreshLevel("admission-live-"+System.currentTimeMillis(),settings,new net.minecraft.world.level.levelgen.WorldOptions(123,false,false),
                    r->r.lookupOrThrow(net.minecraft.core.registries.Registries.WORLD_PRESET).getOrThrow(net.minecraft.world.level.levelgen.presets.WorldPresets.FLAT).value().createWorldDimensions(),new TitleScreen());
            }
            if(world && mc.level!=null && mc.player!=null && mc.getConnection().hasClientLoaded()){
                if(!ready){ready=true;mc.gui.setScreen(new net.peercraft.client.gui.JoinRequestsScreen(new net.minecraft.client.gui.screens.PauseScreen(true)));System.out.println("ADMISSION_LIVE_READY");}
                ticks++;
                if(ticks==20)capture(mc,"host");
                if(ticks==30){
                    var panel=mc.gui.screen();
                    var allow=panel.children().stream().filter(c->c instanceof net.minecraft.client.gui.components.Button b && b.getMessage().getString().equals(net.minecraft.network.chat.Component.translatable("peercraft.admission.allow").getString())).map(c->(net.minecraft.client.gui.components.Button)c).findFirst().orElseThrow();
                    allow.onPress(new net.minecraft.client.input.KeyEvent(257,0,0));
                }
                if(ticks==70){if(AdmissionClient.INSTANCE.entries().size()!=1)throw new IllegalStateException("Decision was not acknowledged by real server");capture(mc,"allowed");System.out.println("ADMISSION_LIVE_DECISION_PASSED");}
                if(ticks==80){mc.gui.setScreen(new net.minecraft.client.gui.screens.PauseScreen(true));}
                if(ticks==100){capture(mc,"pause");}
                if(ticks==110){mc.gui.setScreen(null);AdmissionClient.INSTANCE.noticeKey="peercraft.admission.new_request";AdmissionClient.INSTANCE.toastUntil=System.currentTimeMillis()+5000;}
                if(ticks==130){capture(mc,"hud");if(mc.gui.screen()!=null)throw new IllegalStateException("HUD stole screen");}
                if(ticks==230){capture(mc,"hud-expired");mc.gui.setScreen(new net.peercraft.client.gui.JoinRequestsScreen(new net.minecraft.client.gui.screens.PauseScreen(true)));System.out.println("ADMISSION_LIVE_FINISHED");}
            }
        });
    }
    private void capture(Minecraft mc,String name){net.minecraft.client.Screenshot.grab(mc.gameDirectory,"admission-"+name+".png",mc.gameRenderer.mainRenderTarget(),1,m->System.out.println("ADMISSION_CAPTURED "+name));}
}
