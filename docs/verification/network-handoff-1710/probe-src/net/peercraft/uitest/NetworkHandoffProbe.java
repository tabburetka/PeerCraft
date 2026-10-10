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
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.peercraft.network.account.AccountClient;
import net.peercraft.network.p2p.P2PBridge;

/** Isolated native two-process test. Coordination files carry only public IDs/status. */
@Mod(modid="peercraft_network_handoff_probe",name="PeerCraft network progress probe",version="1")
public class NetworkHandoffProbe {
    private final boolean host = "host".equals(System.getProperty("peercraft.probe.role"));
    private final Path coordination = Paths.get(System.getProperty("peercraft.probe.coordination"));
    private final int port = Integer.getInteger("peercraft.probe.accountPort");
    private CompletableFuture<AccountClient.AccountSession> auth;
    private CompletableFuture<Void> work;
    private UUID own, guest;
    private IntegratedServer server;
    private int stage;
    private CompletableFuture<Void> transferReady;
    private net.minecraft.client.gui.GuiScreen acceptedScreen;
    private long deadline = System.currentTimeMillis()+600000;
    private volatile Throwable networkFailure;
    private boolean finished;
    private long progressSyncDeadline;
    private final boolean declineScenario="decline".equals(System.getProperty("peercraft.probe.scenario","cycle"));
    private volatile boolean declineReceived;
    @Mod.EventHandler public void init(FMLInitializationEvent event) { cpw.mods.fml.common.FMLCommonHandler.instance().bus().register(this); }
    @SubscribeEvent public void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || finished) return;
        Minecraft mc=Minecraft.getMinecraft();
        try {
            if (networkFailure!=null) throw new IllegalStateException("Network failed",networkFailure);
            if (exists("failed")) throw new IllegalStateException("Other participant failed: " + read("failed"));
            if (System.currentTimeMillis()>deadline) throw new IllegalStateException("Timed out role="+host+" stage="+stage);
            if (server != null && !server.isServerStopped()) installPacketDiagnostics();
            if (mc.currentScreen instanceof net.minecraft.client.gui.GuiYesNo && mc.currentScreen != acceptedScreen) {
                acceptedScreen = mc.currentScreen;
                java.lang.reflect.Method yes = net.minecraft.client.gui.GuiYesNo.class.getDeclaredMethod("actionPerformed", net.minecraft.client.gui.GuiButton.class);
                yes.setAccessible(true); yes.invoke(acceptedScreen, new net.minecraft.client.gui.GuiButton(declineScenario?1:0,0,0,declineScenario?"No":"Yes"));
                System.out.println("NETWORK_HANDOFF_PROBE_CONSENT role=" + host);
            }
            if(stage==0) {
                if(!(mc.currentScreen instanceof GuiMainMenu))return;
                if (Boolean.getBoolean("peercraft.probe.manifestOnly")) {
                    logManifestDependencies();
                    new net.peercraft.client.handoff.SafeHandoffSupport() {
                        public Path savesDirectory() { return mc.mcDataDir.toPath().resolve("saves"); }
                        public boolean modernWorldLock() { return false; }
                        public void render(Runnable action) { mc.func_152344_a(action); }
                    }.manifest();
                    System.out.println("NETWORK_HANDOFF_PROBE_MANIFEST_DONE");
                    finished=true; mc.shutdown(); return;
                }
                if(auth==null) {
                    AccountClient.INSTANCE.connect("127.0.0.1",port); auth=new CompletableFuture<>();
                    AccountClient.INSTANCE.registerUnlicensed("Developer","LocalNetworkProbePassword".toCharArray(),new AccountClient.AuthCallback(){
                        public void onSuccess(AccountClient.AccountSession session){auth.complete(session);}
                        public void onFailed(String reason){auth.completeExceptionally(new IllegalStateException(reason));}
                    });
                }
                if(!auth.isDone())return;
                // Offline launcher fixtures need no external Mojang skin/property lookup.
                // Account authentication still uses the real local PeerCraft service.
                
                own=auth.get().accountId(); write(host?"host-id":"guest-id",own.toString());
                net.peercraft.client.gui.HandoffClientController.INSTANCE.register();
                if(host){
                    WorldSettings settings=new WorldSettings(123,WorldSettings.GameType.CREATIVE,false,false,WorldType.FLAT);settings.enableCommands();
                    stage=1;mc.launchIntegratedServer("network-progress","Network progress",settings);
                }else{ P2PBridge.INSTANCE.startProxy(0);stage=20; }
            }else if(host) hostTick(mc);else guestTick(mc);
        }catch(Throwable failure){
            failure.printStackTrace();System.out.println("NETWORK_HANDOFF_PROBE_FAILED role="+(host?"host":"guest")+" stage="+stage);
            try{if(!exists("failed"))write("failed",failure.toString());}catch(Exception ignored){}
            finished=true;P2PBridge.INSTANCE.stop();mc.shutdown();
        }
    }
    private void installPacketDiagnostics() throws Exception {
        java.lang.reflect.Field field = net.minecraft.network.NetworkSystem.class.getDeclaredField("networkManagers");
        field.setAccessible(true);
        java.util.List<net.minecraft.network.NetworkManager> managers = (java.util.List<net.minecraft.network.NetworkManager>) field.get(server.func_147137_ag());
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
            require(!(mc.currentScreen instanceof net.minecraft.client.gui.GuiDisconnected), "Native local login failed before room creation");
            if(mc.thePlayer==null||mc.getIntegratedServer()==null)return;
            server=mc.getIntegratedServer();require(net.minecraft.entity.player.EntityPlayer.func_146094_a(mc.getSession().func_148256_e()).equals(mc.thePlayer.getUniqueID()),"Vanilla client launcher UUID mismatch");
            net.peercraft.client.PeerCraftHostOptions.internetPlayRequested=true;
            net.peercraft.client.PeerCraftHostOptions.allowUnlicensedPlayers=true;
            net.peercraft.client.PeerCraftHostOptions.maxPlayers=4;
            String nativePort=server.shareToLAN(WorldSettings.GameType.CREATIVE,true);require(nativePort!=null,"LAN publish failed");
            stage=2;
        }else if(stage==2){
            String room=P2PBridge.INSTANCE.registeredRoomCode();
            if(room!=null&&!room.isEmpty()&&!exists("room"))write("room",room);
            if(!exists("guest-id")||!exists("joined-first"))return;guest=UUID.fromString(read("guest-id"));require(!guest.equals(own),"Accounts collapsed");
            work=new CompletableFuture<>();stage=3;
            net.peercraft.network.handoff.ServerThreadTasks.execute(server,()->{
                try{
                    EntityPlayerMP owner=nativePlayer(own),player=nativePlayer(guest);
                    require(player!=null&&owner!=null,"Native player UUID differs from account UUID");
                    require(server.getConfigurationManager().getCurrentPlayerCount()==2,"Expected two distinct players with same name");
                    clearInventory(owner);owner.inventory.setInventorySlotContents(0,new ItemStack(Items.diamond,7));owner.addExperience(150);
                    owner.addStat(net.minecraft.stats.StatList.jumpStat,17);
                    owner.addStat(net.minecraft.stats.AchievementList.openInventory,1);
                    net.minecraft.entity.passive.EntityWolf wolf=new net.minecraft.entity.passive.EntityWolf(owner.worldObj);
                    wolf.setTamed(true);wolf.func_152115_b(own.toString());wolf.setLocationAndAngles(owner.posX+1,owner.posY,owner.posZ,0,0);
                    require(owner.worldObj.spawnEntityInWorld(wolf),"Pet fixture spawn failed");
                    clearInventory(player);player.inventory.setInventorySlotContents(0,new ItemStack(Items.gold_ingot,3));player.getInventoryEnderChest().setInventorySlotContents(0,new ItemStack(Items.ender_pearl,6));player.addExperience(900);
                    server.getConfigurationManager().saveAllPlayerData();work.complete(null);
                }catch(Throwable e){work.completeExceptionally(e);}
            });
        }else if(stage==3){if(!work.isDone())return;work.get();write("disconnect","go");stage=4;
        }else if(stage==4){
            if(!exists("left-first"))return;
            work=new CompletableFuture<>();stage=5;net.peercraft.network.handoff.ServerThreadTasks.execute(server,()->{
                try{require(nativePlayer(guest)==null,"Guest remained connected");work.complete(null);}catch(Throwable e){work.completeExceptionally(e);}
            });
        }else if(stage==5){if(!work.isDone())return;work.get();write("reconnect","go");stage=6;
        }else if(stage==6){
            if(!exists("joined-second"))return;work=new CompletableFuture<>();stage=7;
            net.peercraft.network.handoff.ServerThreadTasks.execute(server,()->{
                try{
                    EntityPlayerMP owner=nativePlayer(own),player=nativePlayer(guest);
                    require(player!=null&&owner!=null,"Reconnected account UUID mismatch");
                    require(owner.inventory.getStackInSlot(0).getItem()==Items.diamond&&owner.inventory.getStackInSlot(0).stackSize==7&&owner.experienceTotal==150,"Owner progress changed");
                    require(player.inventory.getStackInSlot(0).getItem()==Items.gold_ingot&&player.inventory.getStackInSlot(0).stackSize==3&&player.experienceTotal==900,"Guest progress lost on reconnect");
                    require(player.getInventoryEnderChest().getStackInSlot(0).getItem()==Items.ender_pearl&&player.getInventoryEnderChest().getStackInSlot(0).stackSize==6,"Guest ender chest lost");
                    work.complete(null);
                }catch(Throwable e){work.completeExceptionally(e);}
            });
        }else if(stage==7){if(!work.isDone())return;work.get();beginTransfer(guest);stage=30;
        }else if(stage==50){
            if(!work.isDone())return;work.get();write("decline-verified","yes");stage=8;
        }else if(stage==30){
            if(declineScenario) {
                if(!transferReady.isDone())return;transferReady.get();
                if(net.peercraft.client.handoff.SafeHandoffSession.INSTANCE.active())return;
                require(declineReceived,"Decline callback not received");
                require(!server.isServerStopped()&&mc.getIntegratedServer()==server,"Declined transfer stopped original host");
                require(read("room").equals(P2PBridge.INSTANCE.registeredRoomCode()),"Decline changed hosting room");
                work=new CompletableFuture<>();stage=50;
                net.peercraft.network.handoff.ServerThreadTasks.execute(server,()->{
                    try {
                        EntityPlayerMP owner=nativePlayer(own),player=nativePlayer(guest);
                        require(owner!=null&&player!=null,"Decline lost connected accounts");
                        require(owner.inventory.getStackInSlot(0).getItem()==Items.diamond&&owner.inventory.getStackInSlot(0).stackSize==7&&owner.experienceTotal==150,"Decline changed owner progress");
                        require(player.inventory.getStackInSlot(0).getItem()==Items.gold_ingot&&player.inventory.getStackInSlot(0).stackSize==3&&player.experienceTotal==900,"Decline changed guest progress");
                        require(player.getInventoryEnderChest().getStackInSlot(0).getItem()==Items.ender_pearl&&player.getInventoryEnderChest().getStackInSlot(0).stackSize==6,"Decline changed ender chest");
                        server.getConfigurationManager().saveAllPlayerData();work.complete(null);
                    }catch(Throwable failure){work.completeExceptionally(failure);}
                });
                return;
            }
            if(!transferReady.isDone() || !exists("successor-room"))return; transferReady.get();
            if(net.peercraft.client.handoff.SafeHandoffSession.INSTANCE.active())return;
            P2PBridge.INSTANCE.startProxy(0);connectRoom(mc,read("successor-room"));stage=31;
        }else if(stage==31){
            if(mc.thePlayer==null||mc.theWorld==null||mc.getIntegratedServer()!=null)return;
            require(net.minecraft.entity.player.EntityPlayer.func_146094_a(mc.getSession().func_148256_e()).equals(mc.thePlayer.getUniqueID()),"Client launcher UUID changed on reconnect");
            if(!progressSynced(mc,Items.diamond,7,150))return;
            write("owner-rejoined","yes");stage=32;
        }else if(stage==32){
            if(mc.getIntegratedServer()==null||mc.thePlayer==null||!net.minecraft.entity.player.EntityPlayer.func_146094_a(mc.getSession().func_148256_e()).equals(mc.thePlayer.getUniqueID()))return;
            if(net.peercraft.client.handoff.SafeHandoffSession.INSTANCE.active())return;
            if(!progressSynced(mc,Items.diamond,7,150))return;
            server=mc.getIntegratedServer();verifyHostAndOfflineGuest(mc);
            write("return-verified","yes");stage=8;
        }else if(stage==8){
            if(!exists("guest-done"))return;
            work=CompletableFuture.runAsync(()->{try{net.peercraft.client.handoff.WorldArchiver.saveAndStop(server,60000);}catch(Exception e){throw new CompletionException(e);}});stage=9;
        }else if(stage==9){
            if(!work.isDone())return;work.get();require(server.isServerStopped(),"Shutdown not confirmed");
            Path world=net.peercraft.client.handoff.WorldArchiver.worldDir(server);
            try(java.io.InputStream input=Files.newInputStream(world.resolve("level.dat"))){
                NBTTagCompound snapshot=CompressedStreamTools.readCompressed(input).getCompoundTag("Data").getCompoundTag("Player");
                require(snapshot.hasKey("UUIDMost")&&own.equals(new UUID(snapshot.getLong("UUIDMost"),snapshot.getLong("UUIDLeast")))&&snapshot.getInteger("XpTotal")==150,"Returned owner snapshot lost");
            }
            require(net.peercraft.world.PlayerDataMigration.isBound(world,guest),"Guest account not canonically bound");
            require(Files.exists(world.resolve("playerdata/"+own+".dat"))&&Files.exists(world.resolve("playerdata/"+guest+".dat")),"Separate saves missing");
            require(net.peercraft.world.PlayerProgressCatalog.unassignedPlayers(world).isEmpty(),"Authenticated progress appears anonymous");
            if(mc.theWorld!=null){mc.theWorld.sendQuittingDisconnectingPacket();mc.loadWorld(null);}
            done(mc);
        }
    }
    private void guestTick(Minecraft mc)throws Exception {
        if(stage==20){if(!exists("room"))return;connect(mc);stage=21;
        }else if(stage==21){if(mc.thePlayer==null||mc.theWorld==null)return;require(net.minecraft.entity.player.EntityPlayer.func_146094_a(mc.getSession().func_148256_e()).equals(mc.thePlayer.getUniqueID()),"Client launcher UUID changed on join");write("joined-first","yes");stage=22;
        }else if(stage==22){if(!exists("disconnect"))return;leave(mc);stage=23;
        }else if(stage==23){if(mc.theWorld != null || P2PBridge.INSTANCE.isClientSessionActive())return;write("left-first","yes");stage=24;
        }else if(stage==24){if(!exists("reconnect"))return;connect(mc);stage=25;
        }else if(stage==25){if(mc.thePlayer==null||mc.theWorld==null)return;require(net.minecraft.entity.player.EntityPlayer.func_146094_a(mc.getSession().func_148256_e()).equals(mc.thePlayer.getUniqueID()),"Client launcher UUID changed on rejoin");write("joined-second","yes");stage=26;
        }else if(stage==26){
            if(declineScenario) {
                if(!exists("decline-verified"))return;
                require(mc.getIntegratedServer()==null&&mc.thePlayer!=null&&net.minecraft.entity.player.EntityPlayer.func_146094_a(mc.getSession().func_148256_e()).equals(mc.thePlayer.getUniqueID()),"Declined guest unexpectedly became host");
                if(!progressSynced(mc,Items.gold_ingot,3,900))return;
                write("guest-done","yes");done(mc);return;
            }
            if(mc.getIntegratedServer()==null||mc.thePlayer==null||!net.minecraft.entity.player.EntityPlayer.func_146094_a(mc.getSession().func_148256_e()).equals(mc.thePlayer.getUniqueID()))return;
            if(net.peercraft.client.handoff.SafeHandoffSession.INSTANCE.active())return;
            server=mc.getIntegratedServer();
            if(!progressSynced(mc,Items.gold_ingot,3,900))return;
            // Ender chest contents are server-owned and are not synced until opened.
            work=new CompletableFuture<>(); stage=27;
            net.peercraft.network.handoff.ServerThreadTasks.execute(server,()->{
                try {
                    EntityPlayerMP successor=nativePlayer(own);
                    require(successor!=null,"Successor server UUID missing");
                    ItemStack pearls=successor.getInventoryEnderChest().getStackInSlot(0);
                    require(pearls.getItem()==Items.ender_pearl && pearls.stackSize==6,"Successor ender chest changed");
                    work.complete(null);
                } catch(Throwable failure) { work.completeExceptionally(failure); }
            });
        }else if(stage==27){
            if(!work.isDone())return;work.get();
            String room=P2PBridge.INSTANCE.registeredRoomCode();if(room==null||room.isEmpty())return;
            write("successor-room",room);stage=40;
        }else if(stage==40){
            if(!exists("owner-rejoined"))return;
            UUID originalHost=UUID.fromString(read("host-id"));
            EntityPlayerMP rejoined=nativePlayer(originalHost);
            require(rejoined!=null,"Rejoined host missing canonical server UUID");
            require(rejoined.inventory.getStackInSlot(0)!=null&&rejoined.inventory.getStackInSlot(0).getItem()==Items.diamond&&rejoined.inventory.getStackInSlot(0).stackSize==7&&rejoined.experienceTotal==150,"Rejoined canonical server progress changed");
            beginTransfer(originalHost);stage=41;
        }else if(stage==41){
            if(!transferReady.isDone())return;transferReady.get();
            if(net.peercraft.client.handoff.SafeHandoffSession.INSTANCE.active()||!exists("return-verified"))return;
            write("guest-done","yes");done(mc);
        }
    }
    private EntityPlayerMP nativePlayer(UUID id) {
        for(Object entry:server.getConfigurationManager().playerEntityList) {
            EntityPlayerMP player=(EntityPlayerMP)entry;
            if(id.equals(player.getUniqueID()))return player;
        }
        return null;
    }
    private void clearInventory(EntityPlayerMP player) {
        for(int slot=0;slot<player.inventory.getSizeInventory();slot++)player.inventory.setInventorySlotContents(slot,null);
    }
    private void logManifestDependencies() {
        for (net.peercraft.platform.services.PlatformMod mod : new net.peercraft.forge1710.ForgePlatform().getInstalledMods())
            System.out.println("NETWORK_HANDOFF_PROBE_DEPENDENCY id=" + mod.id() + " parent=" + mod.parentId() + " jar=" + mod.jarPath());
        System.out.println("NETWORK_HANDOFF_PROBE_CAPTURE_SOURCE " + net.peercraft.network.handoff.HostManifestCapture.class.getProtectionDomain().getCodeSource().getLocation());
    }
    private void beginTransfer(UUID nextAccount)throws Exception {
        logManifestDependencies();
        net.peercraft.network.p2p.P2PBridge.HandoffCandidate selected=null;
        for(net.peercraft.network.p2p.P2PBridge.HandoffCandidate candidate:P2PBridge.INSTANCE.connectedJoiners())
            if(nextAccount.equals(candidate.accountId()))selected=candidate;
        require(selected!=null,"Successor account is not connected");
        transferReady=new CompletableFuture<>();
        net.peercraft.network.handoff.HandoffProtocol.Offer offer=new net.peercraft.network.handoff.HandoffProtocol.Offer(
            net.peercraft.network.handoff.HandoffProtocol.PROTO_VERSION,System.nanoTime(),"Network progress",0,4,
            net.peercraft.network.handoff.HandoffProtocol.OFFER_FLAG_ALLOW_UNLICENSED,Collections.emptyList(),
            net.peercraft.client.handoff.PeercraftWorldMeta.ensureHosting(net.peercraft.client.handoff.WorldArchiver.worldDir(server)).worldId());
        require(net.peercraft.client.handoff.SafeHandoffSession.INSTANCE.begin(server,selected.peer(),host?"GuestDeveloper":"Developer",offer,
            new net.peercraft.network.handoff.HandoffCoordinator.Callbacks(){
                public void onAccepted(){System.out.println("NETWORK_HANDOFF_PROBE_ACCEPTED role="+host);}
                public void onDeclined(String reason){if(declineScenario){declineReceived=true;transferReady.complete(null);}else transferReady.completeExceptionally(new IllegalStateException(reason));}
                public void onSuccessorReady(){transferReady.complete(null);}
                public void onAborted(String reason){transferReady.completeExceptionally(new IllegalStateException(reason));}
                public void onStatus(String message){System.out.println("NETWORK_HANDOFF_PROBE_STATUS role="+host+" "+message);}
            }),"Handoff attempt did not start");
    }
    private boolean progressSynced(Minecraft mc,net.minecraft.item.Item item,int count,int xp) {
        ItemStack stack=mc.thePlayer.inventory.getStackInSlot(0);
        if(stack!=null && stack.getItem()==item && stack.stackSize==count && mc.thePlayer.experienceTotal==xp) {
            progressSyncDeadline=0; return true;
        }
        if(progressSyncDeadline==0)progressSyncDeadline=System.currentTimeMillis()+15000;
        require(System.currentTimeMillis()<progressSyncDeadline,"Client progress did not synchronize: stage="+stage+" item="+(stack==null?"empty":stack.getItem())+" count="+(stack==null?0:stack.stackSize)+" XP="+mc.thePlayer.experienceTotal);
        return false;
    }
    private void verifyHostAndOfflineGuest(Minecraft mc)throws Exception {
        require(mc.thePlayer.inventory.getStackInSlot(0).getItem()==Items.diamond && mc.thePlayer.inventory.getStackInSlot(0).stackSize==7 && mc.thePlayer.experienceTotal==150,"Returned host progress lost");
        EntityPlayerMP nativeOwner=nativePlayer(own);
        require(nativeOwner!=null&&nativeOwner.func_147099_x().writeStat(net.minecraft.stats.StatList.jumpStat)==17,"Owner statistics lost on return");
        require(nativeOwner.func_147099_x().hasAchievementUnlocked(net.minecraft.stats.AchievementList.openInventory),"Owner achievement lost on return");
        java.util.List<net.minecraft.entity.passive.EntityWolf> pets=nativeOwner.worldObj.getEntitiesWithinAABB(net.minecraft.entity.passive.EntityWolf.class,nativeOwner.boundingBox.expand(32,32,32));
        require(!pets.isEmpty(),"Pet lost on return");
        for(net.minecraft.entity.passive.EntityWolf pet:pets)require(own.equals(UUID.fromString(pet.func_152113_b())),"Pet ownership changed on return");
        Path path=net.peercraft.client.handoff.WorldArchiver.worldDir(server);
        require(net.peercraft.client.handoff.HandoffOwnerPolicy.read(path).equals(own),"Original owner marker lost");
        require(net.peercraft.world.PlayerDataMigration.isBound(path,own)&&net.peercraft.world.PlayerDataMigration.isBound(path,guest),"Canonical account bindings lost");
        try(java.io.InputStream input=Files.newInputStream(path.resolve("playerdata/"+guest+".dat"))){
            NBTTagCompound data=CompressedStreamTools.readCompressed(input);
            NBTTagList inventory=data.getTagList("Inventory",10),ender=data.getTagList("EnderItems",10);
            require(inventory.tagCount()==1&&ItemStack.loadItemStackFromNBT(inventory.getCompoundTagAt(0)).getItem()==Items.gold_ingot&&ItemStack.loadItemStackFromNBT(inventory.getCompoundTagAt(0)).stackSize==3,"Offline guest inventory lost on return");
            require(ender.tagCount()==1&&ItemStack.loadItemStackFromNBT(ender.getCompoundTagAt(0)).getItem()==Items.ender_pearl&&ItemStack.loadItemStackFromNBT(ender.getCompoundTagAt(0)).stackSize==6&&data.getInteger("XpTotal")==900,"Offline guest ender chest/XP lost on return");
        }
    }
    private void connect(Minecraft mc)throws Exception {
        connectRoom(mc,read("room"));
    }
    private void connectRoom(Minecraft mc,String room)throws Exception {
        P2PBridge.INSTANCE.startClientViaRendezvous(room,"127.0.0.1",port,new P2PBridge.ConnectListener(){
            public void onStatus(String message){}
            public void onConnected(){mc.func_152344_a(()->net.peercraft.client.gui.PeerCraftUi.connectLocal(new GuiMainMenu(),P2PBridge.INSTANCE.getProxyPort()));}
            public void onFailed(String reason){networkFailure=new IllegalStateException(reason);}
        });
    }
    private void leave(Minecraft mc){mc.theWorld.sendQuittingDisconnectingPacket();}
    private void done(Minecraft mc){System.out.println("NETWORK_HANDOFF_PROBE_DONE role="+(host?"host":"guest")+" account="+own);finished=true;P2PBridge.INSTANCE.stop();mc.shutdown();}
    private boolean exists(String name){return Files.exists(coordination.resolve(name));}
    private String read(String name)throws Exception{return new String(Files.readAllBytes(coordination.resolve(name)),java.nio.charset.StandardCharsets.UTF_8).trim();}
    private void write(String name,String value)throws Exception{Files.createDirectories(coordination);Path tmp=coordination.resolve(name+".tmp");Files.write(tmp,value.getBytes(java.nio.charset.StandardCharsets.UTF_8));Files.move(tmp,coordination.resolve(name),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
    private static void require(boolean condition,String message){if(!condition)throw new IllegalStateException(message);}
}
