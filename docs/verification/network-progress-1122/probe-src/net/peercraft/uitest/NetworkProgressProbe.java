package net.peercraft.uitest;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.multiplayer.GuiConnecting;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.*;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.world.*;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.peercraft.network.account.AccountClient;
import net.peercraft.network.p2p.P2PBridge;

/** Isolated native two-process test. Coordination files carry only public IDs/status. */
@Mod(modid="peercraft_network_progress_probe",name="PeerCraft network progress probe",version="1",clientSideOnly=true)
public class NetworkProgressProbe {
    private final boolean host = "host".equals(System.getProperty("peercraft.probe.role"));
    private final Path coordination = Paths.get(System.getProperty("peercraft.probe.coordination"));
    private final int port = Integer.getInteger("peercraft.probe.accountPort");
    private CompletableFuture<AccountClient.AccountSession> auth;
    private CompletableFuture<Void> work;
    private UUID own, guest;
    private IntegratedServer server;
    private int stage;
    private long deadline = System.currentTimeMillis()+180000;
    private volatile Throwable networkFailure;
    private boolean finished;
    @Mod.EventHandler public void init(FMLInitializationEvent event) { org.apache.logging.log4j.core.config.Configurator.setLevel("net.minecraft.network.NetworkManager", org.apache.logging.log4j.Level.DEBUG); net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(this); }
    @SubscribeEvent public void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || finished) return;
        Minecraft mc=Minecraft.getMinecraft();
        try {
            if (networkFailure!=null) throw new IllegalStateException("Network failed",networkFailure);
            if (System.currentTimeMillis()>deadline) throw new IllegalStateException("Timed out role="+host+" stage="+stage);
            if (host && server != null) installPacketDiagnostics();
            if(stage==0) {
                if(!(mc.currentScreen instanceof GuiMainMenu))return;
                if(auth==null) {
                    AccountClient.INSTANCE.connect("127.0.0.1",port); auth=new CompletableFuture<>();
                    AccountClient.INSTANCE.registerUnlicensed("Developer","LocalNetworkProbePassword".toCharArray(),new AccountClient.AuthCallback(){
                        public void onSuccess(AccountClient.AccountSession session){auth.complete(session);}
                        public void onFailed(String reason){auth.completeExceptionally(new IllegalStateException(reason));}
                    });
                }
                if(!auth.isDone())return;
                own=auth.get().accountId(); write(host?"host-id":"guest-id",own.toString());
                if(host){
                    WorldSettings settings=new WorldSettings(123,GameType.CREATIVE,false,false,WorldType.FLAT);settings.enableCommands();
                    stage=1;mc.launchIntegratedServer("network-progress","Network progress",settings);
                }else{ P2PBridge.INSTANCE.startProxy(0);stage=20; }
            }else if(host) hostTick(mc);else guestTick(mc);
        }catch(Throwable failure){
            failure.printStackTrace();System.out.println("NETWORK_PROGRESS_PROBE_FAILED role="+(host?"host":"guest")+" stage="+stage);
            try{write("failed",failure.toString());}catch(Exception ignored){}
            finished=true;P2PBridge.INSTANCE.stop();mc.shutdown();
        }
    }
    private void installPacketDiagnostics() throws Exception {
        java.lang.reflect.Field field = net.minecraft.network.NetworkSystem.class.getDeclaredField("networkManagers");
        field.setAccessible(true);
        java.util.List<net.minecraft.network.NetworkManager> managers = (java.util.List<net.minecraft.network.NetworkManager>) field.get(server.getNetworkSystem());
        synchronized (managers) {
            for (net.minecraft.network.NetworkManager manager : managers) {
                io.netty.channel.Channel channel = manager.channel();
                if (channel == null || !channel.isOpen() || channel.pipeline().get("probe-packet-diagnostics") != null) continue;
                channel.pipeline().addBefore("packet_handler", "probe-packet-diagnostics", new io.netty.channel.ChannelDuplexHandler() {
                    @Override public void write(io.netty.channel.ChannelHandlerContext context, Object message, io.netty.channel.ChannelPromise promise) throws Exception {
                        promise.addListener(completed -> {
                            if (!completed.isSuccess()) {
                                System.err.println("NETWORK_PROBE_PACKET_FAILED class=" + message.getClass().getName());
                                if (completed.cause() != null) completed.cause().printStackTrace();
                            }
                        });
                        super.write(context, message, promise);
                    }
                    @Override public void exceptionCaught(io.netty.channel.ChannelHandlerContext context, Throwable cause) throws Exception {
                        System.err.println("NETWORK_PROBE_CHANNEL_EXCEPTION"); cause.printStackTrace();
                        super.exceptionCaught(context, cause);
                    }
                });
            }
        }
    }
    private void hostTick(Minecraft mc)throws Exception {
        if(stage==1){
            if(mc.player==null||mc.getIntegratedServer()==null)return;
            server=mc.getIntegratedServer();require(own.equals(mc.player.getUniqueID()),"Host UUID mismatch");
            String nativePort=server.shareToLAN(GameType.CREATIVE,true);require(nativePort!=null,"LAN publish failed");server.setOnlineMode(false);
            P2PBridge.INSTANCE.startHostViaRendezvous(Integer.parseInt(nativePort),4,false,false,"Network progress","1.12.2",new P2PBridge.HostListener(){
                public void onRoomCreated(String code,boolean changed){try{write("room",code);}catch(Exception e){networkFailure=e;}}
                public void onFailed(String reason){networkFailure=new IllegalStateException(reason);}
            });stage=2;
        }else if(stage==2){
            if(!exists("guest-id")||!exists("joined-first"))return;guest=UUID.fromString(read("guest-id"));require(!guest.equals(own),"Accounts collapsed");
            work=new CompletableFuture<>();stage=3;
            server.addScheduledTask(()->{
                try{
                    EntityPlayerMP owner=server.getPlayerList().getPlayerByUUID(own),player=server.getPlayerList().getPlayerByUUID(guest);
                    require(player!=null&&owner!=null,"Native player UUID differs from account UUID");
                    require(server.getPlayerList().getCurrentPlayerCount()==2,"Expected two distinct players with same name");
                    owner.inventory.clear();owner.inventory.setInventorySlotContents(0,new ItemStack(Items.DIAMOND,7));owner.addExperience(150);
                    player.inventory.clear();player.inventory.setInventorySlotContents(0,new ItemStack(Items.GOLD_INGOT,3));player.getInventoryEnderChest().setInventorySlotContents(0,new ItemStack(Items.ENDER_PEARL,6));player.addExperience(900);
                    server.getPlayerList().saveAllPlayerData();work.complete(null);
                }catch(Throwable e){work.completeExceptionally(e);}
            });
        }else if(stage==3){if(!work.isDone())return;work.get();write("disconnect","go");stage=4;
        }else if(stage==4){
            if(!exists("left-first"))return;
            work=new CompletableFuture<>();stage=5;server.addScheduledTask(()->{
                try{require(server.getPlayerList().getPlayerByUUID(guest)==null,"Guest remained connected");work.complete(null);}catch(Throwable e){work.completeExceptionally(e);}
            });
        }else if(stage==5){if(!work.isDone())return;work.get();write("reconnect","go");stage=6;
        }else if(stage==6){
            if(!exists("joined-second"))return;work=new CompletableFuture<>();stage=7;
            server.addScheduledTask(()->{
                try{
                    EntityPlayerMP owner=server.getPlayerList().getPlayerByUUID(own),player=server.getPlayerList().getPlayerByUUID(guest);
                    require(player!=null&&owner!=null,"Reconnected account UUID mismatch");
                    require(owner.inventory.getStackInSlot(0).getItem()==Items.DIAMOND&&owner.inventory.getStackInSlot(0).getCount()==7&&owner.experienceTotal==150,"Owner progress changed");
                    require(player.inventory.getStackInSlot(0).getItem()==Items.GOLD_INGOT&&player.inventory.getStackInSlot(0).getCount()==3&&player.experienceTotal==900,"Guest progress lost on reconnect");
                    require(player.getInventoryEnderChest().getStackInSlot(0).getItem()==Items.ENDER_PEARL&&player.getInventoryEnderChest().getStackInSlot(0).getCount()==6,"Guest ender chest lost");
                    work.complete(null);
                }catch(Throwable e){work.completeExceptionally(e);}
            });
        }else if(stage==7){if(!work.isDone())return;work.get();write("finish","go");stage=8;
        }else if(stage==8){
            if(!exists("guest-done"))return;
            work=CompletableFuture.runAsync(()->{try{net.peercraft.client.handoff.WorldArchiver.saveAndStop(server,60000);}catch(Exception e){throw new CompletionException(e);}});stage=9;
        }else if(stage==9){
            if(!work.isDone())return;work.get();require(server.isServerStopped(),"Shutdown not confirmed");
            Path world=mc.gameDir.toPath().resolve("saves/network-progress");
            require(net.peercraft.world.PlayerDataMigration.isBound(world,guest),"Guest account not canonically bound");
            require(Files.exists(world.resolve("playerdata/"+own+".dat"))&&Files.exists(world.resolve("playerdata/"+guest+".dat")),"Separate saves missing");
            require(net.peercraft.world.PlayerProgressCatalog.unassignedPlayers(world).isEmpty(),"Authenticated progress appears anonymous");
            if(mc.world!=null){mc.world.sendQuittingDisconnectingPacket();mc.loadWorld(null);}
            done(mc);
        }
    }
    private void guestTick(Minecraft mc)throws Exception {
        if(stage==20){if(!exists("room"))return;connect(mc);stage=21;
        }else if(stage==21){if(mc.player==null||mc.world==null)return;require(own.equals(mc.player.getUniqueID()),"Joined UUID mismatch");write("joined-first","yes");stage=22;
        }else if(stage==22){if(!exists("disconnect"))return;leave(mc);stage=23;
        }else if(stage==23){if(mc.world != null || P2PBridge.INSTANCE.isClientSessionActive())return;write("left-first","yes");stage=24;
        }else if(stage==24){if(!exists("reconnect"))return;connect(mc);stage=25;
        }else if(stage==25){if(mc.player==null||mc.world==null)return;require(own.equals(mc.player.getUniqueID()),"Rejoined UUID mismatch");write("joined-second","yes");stage=26;
        }else if(stage==26){if(!exists("finish"))return;leave(mc);stage=27;
        }else if(stage==27){if(mc.world!=null)return;write("guest-done","yes");done(mc);}
    }
    private void connect(Minecraft mc)throws Exception {
        P2PBridge.INSTANCE.startClientViaRendezvous(read("room"),"127.0.0.1",port,new P2PBridge.ConnectListener(){
            public void onStatus(String message){}
            public void onConnected(){mc.addScheduledTask(()->mc.displayGuiScreen(new GuiConnecting(new GuiMainMenu(),mc,"127.0.0.1",P2PBridge.INSTANCE.getProxyPort())));}
            public void onFailed(String reason){networkFailure=new IllegalStateException(reason);}
        });
    }
    private void leave(Minecraft mc){mc.world.sendQuittingDisconnectingPacket();}
    private void done(Minecraft mc){System.out.println("NETWORK_PROGRESS_PROBE_DONE role="+(host?"host":"guest")+" account="+own);finished=true;P2PBridge.INSTANCE.stop();mc.shutdown();}
    private boolean exists(String name){return Files.exists(coordination.resolve(name));}
    private String read(String name)throws Exception{return new String(Files.readAllBytes(coordination.resolve(name)),java.nio.charset.StandardCharsets.UTF_8).trim();}
    private void write(String name,String value)throws Exception{Files.createDirectories(coordination);Path tmp=coordination.resolve(name+".tmp");Files.write(tmp,value.getBytes(java.nio.charset.StandardCharsets.UTF_8));Files.move(tmp,coordination.resolve(name),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
    private static void require(boolean condition,String message){if(!condition)throw new IllegalStateException(message);}
}
