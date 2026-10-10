package net.peercraft.uitest;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.nbt.*;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.level.*;
import net.peercraft.network.account.AccountClient;
import net.peercraft.network.p2p.P2PBridge;

/** Isolated native two-process test. Coordination files carry only public IDs/status. */
public class NetworkHandoffProbe implements net.fabricmc.api.ClientModInitializer {
    private final boolean host = "host".equals(System.getProperty("peercraft.probe.role"));
    private final Path coordination = Paths.get(System.getProperty("peercraft.probe.coordination"));
    private final int port = Integer.getInteger("peercraft.probe.accountPort");
    private CompletableFuture<AccountClient.AccountSession> auth;
    private CompletableFuture<Void> work;
    private UUID own, guest;
    private IntegratedServer server;
    private int stage;
    private CompletableFuture<Void> transferReady;
    private net.minecraft.client.gui.screens.Screen acceptedScreen;
    private long deadline = System.currentTimeMillis()+600000;
    private volatile Throwable networkFailure;
    private boolean finished;
    private long progressSyncDeadline;
    private long disconnectDeadline;
    private volatile boolean serverDisconnected;
    private final boolean declineScenario="decline".equals(System.getProperty("peercraft.probe.scenario","cycle"));
    private volatile boolean declineReceived;
    private final boolean repeatScenario="repeat".equals(System.getProperty("peercraft.probe.scenario","cycle"));
    private int returnedCycles;
    private final boolean cancelScenario="cancel".equals(System.getProperty("peercraft.probe.scenario","cycle"));
    private boolean cancelSent;
    public void onInitializeClient() {
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(mc -> tick());
    }
    public void tick() {
        if(finished)return;
        Minecraft mc=Minecraft.getInstance();
        try {
            if (networkFailure!=null) throw new IllegalStateException("Network failed",networkFailure);
            if (exists("failed")) throw new IllegalStateException("Other participant failed: " + read("failed"));
            if (System.currentTimeMillis()>deadline) throw new IllegalStateException("Timed out role="+host+" stage="+stage);
            if (server != null && !server.isStopped()) installPacketDiagnostics();
            if(mc.screen instanceof net.minecraft.client.gui.screens.ConfirmScreen && mc.screen!=acceptedScreen) {
                acceptedScreen=mc.screen;
                java.lang.reflect.Field callback=net.minecraft.client.gui.screens.ConfirmScreen.class.getDeclaredField("callback");
                callback.setAccessible(true);
                if(cancelScenario)write("offer-visible","yes");
                else ((it.unimi.dsi.fastutil.booleans.BooleanConsumer)callback.get(acceptedScreen)).accept(!declineScenario);
                System.out.println("NETWORK_HANDOFF_PROBE_CONSENT role="+host);
            }
            if(mc.screen instanceof net.peercraft.client.gui.HandoffReclaimConfirmScreen && mc.screen!=acceptedScreen) {
                for(net.minecraft.client.gui.components.events.GuiEventListener child:mc.screen.children()) {
                    if(child instanceof net.minecraft.client.gui.components.Button) {
                        net.minecraft.client.gui.components.Button button=(net.minecraft.client.gui.components.Button)child;
                        if(button.getMessage().getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents
                                && "peercraft.handoff.reclaim.update".equals(((net.minecraft.network.chat.contents.TranslatableContents)button.getMessage().getContents()).getKey())) {
                            acceptedScreen=mc.screen;button.onPress();
                            System.out.println("NETWORK_HANDOFF_PROBE_RETURN_BACKUP_SELECTED");break;
                        }
                    }
                }
            }
            if(stage==0) {
                if(!(mc.screen instanceof TitleScreen)||mc.getOverlay()!=null)return;
                require(mc.gameDirectory.toPath().toAbsolutePath().normalize().equals(Paths.get(System.getProperty("peercraft.probe.gameDir")).toAbsolutePath().normalize()),"Fixture game directory is not isolated");
                if (Boolean.getBoolean("peercraft.probe.manifestOnly")) {
                    logManifestDependencies();
                    new net.peercraft.client.handoff.SafeHandoffSupport() {
                        public Path savesDirectory() { return mc.gameDirectory.toPath().resolve("saves"); }
                        public boolean modernWorldLock() { return false; }
                        public void render(Runnable action) { mc.execute(action); }
                    }.manifest();
                    System.out.println("NETWORK_HANDOFF_PROBE_MANIFEST_DONE");
                    finished=true; mc.stop(); return;
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
                    net.minecraft.world.level.LevelSettings settings=new net.minecraft.world.level.LevelSettings("Network progress",GameType.CREATIVE,false,net.minecraft.world.Difficulty.PEACEFUL,true,new GameRules(),net.minecraft.world.level.WorldDataConfiguration.DEFAULT);
                    stage=1;mc.createWorldOpenFlows().createFreshLevel("network-progress",settings,
                        new net.minecraft.world.level.levelgen.WorldOptions(123,false,false),
                        registries->registries.registryOrThrow(net.minecraft.core.registries.Registries.WORLD_PRESET).getHolderOrThrow(net.minecraft.world.level.levelgen.presets.WorldPresets.FLAT).value().createWorldDimensions(),new TitleScreen());
                }else{ P2PBridge.INSTANCE.startProxy(0);stage=20; }
            }else if(host) hostTick(mc);else guestTick(mc);
        }catch(Throwable failure){
            failure.printStackTrace();System.out.println("NETWORK_HANDOFF_PROBE_FAILED role="+(host?"host":"guest")+" stage="+stage);
            try{if(!exists("failed"))write("failed",failure.toString());}catch(Exception ignored){}
            finished=true;P2PBridge.INSTANCE.stop();mc.stop();
        }
    }
    private void installPacketDiagnostics() { }
    private void hostTick(Minecraft mc)throws Exception {
        if(stage==1){
            require(!(mc.screen instanceof net.minecraft.client.gui.screens.DisconnectedScreen), "Native local login failed before room creation");
            if(mc.player==null||mc.getSingleplayerServer()==null)return;
            server=mc.getSingleplayerServer();require(own.equals(mc.player.getUUID()),"Host UUID mismatch");
            net.peercraft.client.PeerCraftHostOptions.internetPlayRequested=true;
            net.peercraft.client.PeerCraftHostOptions.allowUnlicensedPlayers=true;
            net.peercraft.client.PeerCraftHostOptions.maxPlayers=4;
            require(server.publishServer(GameType.CREATIVE,true,net.minecraft.util.HttpUtil.getAvailablePort()),"LAN publish failed");
            stage=2;
        }else if(stage==2){
            String room=P2PBridge.INSTANCE.registeredRoomCode();
            if(room!=null&&!room.isEmpty()&&!exists("room"))write("room",room);
            if(!exists("guest-id")||!exists("joined-first"))return;guest=UUID.fromString(read("guest-id"));require(!guest.equals(own),"Accounts collapsed");
            work=new CompletableFuture<>();stage=3;
            server.execute(()->{
                try{
                    ServerPlayer owner=server.getPlayerList().getPlayer(own),player=server.getPlayerList().getPlayer(guest);
                    require(player!=null&&owner!=null,"Native player UUID differs from account UUID");
                    require(server.getPlayerList().getPlayerCount()==2,"Expected two distinct players with same name");
                    owner.getInventory().clearContent();owner.getInventory().setItem(0,new ItemStack(Items.DIAMOND,7));owner.giveExperiencePoints(150);
                    owner.awardStat(net.minecraft.stats.Stats.JUMP,17);
                    net.minecraft.advancements.AdvancementHolder achievement=server.getAdvancements().get(net.minecraft.resources.ResourceLocation.parse("minecraft:story/root"));
                    require(achievement!=null,"Advancement fixture missing");
                    for(String criterion:achievement.value().criteria().keySet())owner.getAdvancements().award(achievement,criterion);
                    net.minecraft.world.entity.animal.Wolf wolf=new net.minecraft.world.entity.animal.Wolf(net.minecraft.world.entity.EntityType.WOLF,owner.serverLevel());
                    wolf.setTame(true,true);wolf.setOwnerUUID(own);wolf.moveTo(owner.getX()+1,owner.getY(),owner.getZ(),0,0);wolf.setNoGravity(true);
                    require(owner.serverLevel().addFreshEntity(wolf),"Pet fixture spawn failed");
                    player.getInventory().clearContent();player.getInventory().setItem(0,new ItemStack(Items.GOLD_INGOT,3));player.getEnderChestInventory().setItem(0,new ItemStack(Items.ENDER_PEARL,6));player.giveExperiencePoints(900);
                    server.getPlayerList().saveAll();work.complete(null);
                }catch(Throwable e){work.completeExceptionally(e);}
            });
        }else if(stage==3){if(!work.isDone())return;work.get();write("disconnect","go");stage=4;
        }else if(stage==4){
            if(!exists("left-first"))return;
            if(disconnectDeadline==0)disconnectDeadline=System.currentTimeMillis()+15000;
            require(System.currentTimeMillis()<disconnectDeadline,"Server did not confirm guest disconnect");
            work=new CompletableFuture<>();stage=5;server.execute(()->{
                try{serverDisconnected=server.getPlayerList().getPlayer(guest)==null;work.complete(null);}catch(Throwable e){work.completeExceptionally(e);}
            });
        }else if(stage==5){if(!work.isDone())return;work.get();if(!serverDisconnected){stage=4;return;}write("reconnect","go");stage=6;
        }else if(stage==6){
            if(!exists("joined-second"))return;work=new CompletableFuture<>();stage=7;
            server.execute(()->{
                try{
                    ServerPlayer owner=server.getPlayerList().getPlayer(own),player=server.getPlayerList().getPlayer(guest);
                    require(player!=null&&owner!=null,"Reconnected account UUID mismatch");
                    require(owner.getInventory().getItem(0).getItem()==Items.DIAMOND&&owner.getInventory().getItem(0).getCount()==7&&owner.totalExperience==150,"Owner progress changed");
                    require(player.getInventory().getItem(0).getItem()==Items.GOLD_INGOT&&player.getInventory().getItem(0).getCount()==3&&player.totalExperience==900,"Guest progress lost on reconnect");
                    require(player.getEnderChestInventory().getItem(0).getItem()==Items.ENDER_PEARL&&player.getEnderChestInventory().getItem(0).getCount()==6,"Guest ender chest lost");
                    work.complete(null);
                }catch(Throwable e){work.completeExceptionally(e);}
            });
        }else if(stage==7){if(!work.isDone())return;work.get();beginTransfer(guest);stage=30;
        }else if(stage==50){
            if(!work.isDone())return;work.get();write("decline-verified","yes");stage=8;
        }else if(stage==30){
            if(declineScenario || cancelScenario) {
                if(cancelScenario && !cancelSent && exists("offer-visible")) {
                    cancelSent=true;net.peercraft.client.handoff.SafeHandoffSession.INSTANCE.cancel();
                    System.out.println("NETWORK_HANDOFF_PROBE_CANCEL_SENT");
                }
                if(!transferReady.isDone())return;transferReady.get();
                if(net.peercraft.client.handoff.SafeHandoffSession.INSTANCE.active())return;
                require(declineReceived,"Decline callback not received");
                require(!server.isStopped()&&mc.getSingleplayerServer()==server,"Declined transfer stopped original host");
                require(read("room").equals(P2PBridge.INSTANCE.registeredRoomCode()),"Decline changed hosting room");
                work=new CompletableFuture<>();stage=50;
                server.execute(()->{
                    try {
                        ServerPlayer owner=server.getPlayerList().getPlayer(own),player=server.getPlayerList().getPlayer(guest);
                        require(owner!=null&&player!=null,"Decline lost connected accounts");
                        require(owner.getInventory().getItem(0).getItem()==Items.DIAMOND&&owner.getInventory().getItem(0).getCount()==7&&owner.totalExperience==150,"Decline changed owner progress");
                        require(player.getInventory().getItem(0).getItem()==Items.GOLD_INGOT&&player.getInventory().getItem(0).getCount()==3&&player.totalExperience==900,"Decline changed guest progress");
                        require(player.getEnderChestInventory().getItem(0).getItem()==Items.ENDER_PEARL&&player.getEnderChestInventory().getItem(0).getCount()==6,"Decline changed ender chest");
                        server.getPlayerList().saveAll();work.complete(null);
                    }catch(Throwable failure){work.completeExceptionally(failure);}
                });
                return;
            }
            if(!transferReady.isDone() || !exists("successor-room"))return; transferReady.get();
            if(net.peercraft.client.handoff.SafeHandoffSession.INSTANCE.active())return;
            P2PBridge.INSTANCE.startProxy(0);connectRoom(mc,read("successor-room"));stage=31;
        }else if(stage==31){
            if(mc.player==null||mc.level==null||mc.getSingleplayerServer()!=null)return;
            require(own.equals(mc.player.getUUID()),"Original host reconnect UUID changed");
            if(!progressSynced(mc,Items.DIAMOND,7,150))return;
            write("owner-rejoined","yes");stage=32;
        }else if(stage==32){
            if(mc.getSingleplayerServer()==null||mc.player==null||!own.equals(mc.player.getUUID()))return;
            if(net.peercraft.client.handoff.SafeHandoffSession.INSTANCE.active())return;
            if(!progressSynced(mc,Items.DIAMOND,7,150))return;
            server=mc.getSingleplayerServer();verifyAuthorityAtHost();verifyHostAndOfflineGuest(mc);
            write("return-verified","yes");returnedCycles++;
            if(repeatScenario && returnedCycles<2) {
                String room=P2PBridge.INSTANCE.registeredRoomCode();
                require(room!=null&&!room.isEmpty(),"Returned host room missing");
                write("repeat-room",room);stage=60;
            }else stage=8;
        }else if(stage==60){
            if(!exists("repeat-joined"))return;
            for(String marker:new String[]{"successor-room","owner-rejoined","return-verified"})Files.deleteIfExists(coordination.resolve(marker));
            beginTransfer(guest);stage=30;
        }else if(stage==8){
            if(!exists("guest-done"))return;
            work=CompletableFuture.runAsync(()->{try{net.peercraft.client.handoff.WorldArchiver.saveAndStop(server,60000);}catch(Exception e){throw new CompletionException(e);}});stage=9;
        }else if(stage==9){
            if(!work.isDone())return;work.get();require(server.isStopped(),"Shutdown not confirmed");
            Path world=net.peercraft.client.handoff.WorldArchiver.worldDir(server);
            try(java.io.InputStream input=Files.newInputStream(world.resolve("level.dat"))){
                CompoundTag snapshot=NbtIo.readCompressed(input,NbtAccounter.unlimitedHeap()).getCompound("Data").getCompound("Player");
                require(snapshot.hasUUID("UUID")&&own.equals(snapshot.getUUID("UUID"))&&snapshot.getInt("XpTotal")==150,"Returned owner snapshot lost");
            }
            require(net.peercraft.world.PlayerDataMigration.isBound(world,guest),"Guest account not canonically bound");
            require(Files.exists(world.resolve("playerdata/"+own+".dat"))&&Files.exists(world.resolve("playerdata/"+guest+".dat")),"Separate saves missing");
            require(net.peercraft.world.PlayerProgressCatalog.unassignedPlayers(world).isEmpty(),"Authenticated progress appears anonymous");
            if(mc.level!=null){mc.level.disconnect();mc.disconnect();}
            done(mc);
        }
    }
    private void guestTick(Minecraft mc)throws Exception {
        if(stage==20){if(!exists("room"))return;connect(mc);stage=21;
        }else if(stage==21){if(mc.player==null||mc.level==null)return;require(own.equals(mc.player.getUUID()),"Joined UUID mismatch");write("joined-first","yes");stage=22;
        }else if(stage==22){if(!exists("disconnect"))return;leave(mc);stage=23;
        }else if(stage==23){if(mc.level != null || P2PBridge.INSTANCE.isClientSessionActive())return;write("left-first","yes");stage=24;
        }else if(stage==24){if(!exists("reconnect"))return;connect(mc);stage=25;
        }else if(stage==25){if(mc.player==null||mc.level==null)return;require(own.equals(mc.player.getUUID()),"Rejoined UUID mismatch");write("joined-second","yes");stage=26;
        }else if(stage==26){
            if(declineScenario || cancelScenario) {
                if(!exists("decline-verified"))return;
                require(mc.getSingleplayerServer()==null&&mc.player!=null&&own.equals(mc.player.getUUID()),"Declined guest unexpectedly became host");
                if(!progressSynced(mc,Items.GOLD_INGOT,3,900))return;
                write("guest-done","yes");done(mc);return;
            }
            if(mc.getSingleplayerServer()==null||mc.player==null||!own.equals(mc.player.getUUID()))return;
            if(net.peercraft.client.handoff.SafeHandoffSession.INSTANCE.active())return;
            server=mc.getSingleplayerServer();
            if(!progressSynced(mc,Items.GOLD_INGOT,3,900))return;
            verifyAuthorityAtHost();
            // Ender chest contents are server-owned and are not synced until opened.
            work=new CompletableFuture<>(); stage=27;
            server.execute(()->{
                try {
                    ServerPlayer successor=server.getPlayerList().getPlayer(own);
                    require(successor!=null,"Successor server UUID missing");
                    ItemStack pearls=successor.getEnderChestInventory().getItem(0);
                    require(pearls.getItem()==Items.ENDER_PEARL && pearls.getCount()==6,"Successor ender chest changed");
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
            beginTransfer(originalHost);stage=41;
        }else if(stage==41){
            if(!transferReady.isDone())return;transferReady.get();
            if(net.peercraft.client.handoff.SafeHandoffSession.INSTANCE.active()||!exists("return-verified"))return;
            returnedCycles++;
            if(repeatScenario && returnedCycles<2) {
                if(!exists("repeat-room")){returnedCycles--;return;}
                P2PBridge.INSTANCE.startProxy(0);connectRoom(mc,read("repeat-room"));stage=61;
            }else {write("guest-done","yes");done(mc);}
        }else if(stage==61){
            if(mc.player==null||mc.level==null||mc.getSingleplayerServer()!=null)return;
            require(own.equals(mc.player.getUUID()),"Repeated reconnect changed guest UUID");
            if(!progressSynced(mc,Items.GOLD_INGOT,3,900))return;
            write("repeat-joined","yes");stage=26;
        }
    }
    private void verifyAuthorityAtHost()throws Exception {
        Path world=net.peercraft.client.handoff.WorldArchiver.worldDir(server);
        net.peercraft.client.handoff.PeercraftWorldMeta meta=net.peercraft.client.handoff.PeercraftWorldMeta.loadOrNull(world);
        require(meta!=null&&read("stable-world-id").equals(meta.worldId()),"World ID changed after transfer");
        net.peercraft.client.handoff.SafeHandoffSupport.Grant grant=net.peercraft.client.handoff.SafeHandoffPlatform.INSTANCE.hostingGrant(world);
        require(grant!=null&&grant.epoch>Long.parseLong(read("last-host-epoch")),"Authority epoch did not advance");
        write("last-host-epoch",Long.toString(grant.epoch));
        System.out.println("NETWORK_HANDOFF_PROBE_HOST_EPOCH "+grant.epoch+" role="+(host?"host":"guest"));
    }
    private void logManifestDependencies() {
        for (net.peercraft.platform.services.PlatformMod mod : net.peercraft.platform.Services.PLATFORM.getInstalledMods())
            System.out.println("NETWORK_HANDOFF_PROBE_DEPENDENCY id=" + mod.id() + " parent=" + mod.parentId() + " jar=" + mod.jarPath());
        System.out.println("NETWORK_HANDOFF_PROBE_CAPTURE_SOURCE " + net.peercraft.network.handoff.HostManifestCapture.class.getProtectionDomain().getCodeSource().getLocation());
    }
    private void beginTransfer(UUID nextAccount)throws Exception {
        logManifestDependencies();
        if(!exists("stable-world-id")) {
            write("stable-world-id",net.peercraft.client.handoff.PeercraftWorldMeta.ensureHosting(net.peercraft.client.handoff.WorldArchiver.worldDir(server)).worldId());
            write("last-host-epoch","0");
        }
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
                public void onAborted(String reason){
                    if(cancelScenario && cancelSent){declineReceived=true;transferReady.complete(null);}
                    else transferReady.completeExceptionally(new IllegalStateException(reason));
                }
                public void onStatus(String message){System.out.println("NETWORK_HANDOFF_PROBE_STATUS role="+host+" "+message);}
            }),"Handoff attempt did not start");
    }
    private boolean progressSynced(Minecraft mc,net.minecraft.world.item.Item item,int count,int xp) {
        ItemStack stack=mc.player.getInventory().getItem(0);
        if(stack.getItem()==item && stack.getCount()==count && mc.player.totalExperience==xp) {
            progressSyncDeadline=0; return true;
        }
        if(progressSyncDeadline==0)progressSyncDeadline=System.currentTimeMillis()+15000;
        require(System.currentTimeMillis()<progressSyncDeadline,"Client progress did not synchronize: stage="+stage+" item="+stack.getItem()+" count="+stack.getCount()+" XP="+mc.player.totalExperience);
        return false;
    }
    private void verifyHostAndOfflineGuest(Minecraft mc)throws Exception {
        require(mc.player.getInventory().getItem(0).getItem()==Items.DIAMOND && mc.player.getInventory().getItem(0).getCount()==7 && mc.player.totalExperience==150,"Returned host progress lost");
        ServerPlayer nativeOwner=server.getPlayerList().getPlayer(own);
        require(nativeOwner!=null&&nativeOwner.getStats().getValue(net.minecraft.stats.Stats.CUSTOM,net.minecraft.stats.Stats.JUMP)==17,"Owner statistics lost on return");
        require(nativeOwner.getAdvancements().getOrStartProgress(server.getAdvancements().get(net.minecraft.resources.ResourceLocation.parse("minecraft:story/root"))).isDone(),"Owner advancement lost on return");
        java.util.List<net.minecraft.world.entity.animal.Wolf> pets=nativeOwner.serverLevel().getEntitiesOfClass(net.minecraft.world.entity.animal.Wolf.class,nativeOwner.getBoundingBox().inflate(32),pet->pet.isTame());
        require(!pets.isEmpty(),"Pet lost on return");
        for(net.minecraft.world.entity.animal.Wolf pet:pets)require(own.equals(pet.getOwnerUUID()),"Pet ownership changed on return");
        Path path=net.peercraft.client.handoff.WorldArchiver.worldDir(server);
        require(net.peercraft.client.handoff.HandoffOwnerPolicy.read(path).equals(own),"Original owner marker lost");
        require(net.peercraft.world.PlayerDataMigration.isBound(path,own)&&net.peercraft.world.PlayerDataMigration.isBound(path,guest),"Canonical account bindings lost");
        try(java.io.InputStream input=Files.newInputStream(path.resolve("playerdata/"+guest+".dat"))){
            CompoundTag data=NbtIo.readCompressed(input,NbtAccounter.unlimitedHeap());
            ListTag inventory=data.getList("Inventory",10),ender=data.getList("EnderItems",10);
            require(inventory.size()==1&&ItemStack.parseOptional(server.registryAccess(),inventory.getCompound(0)).getItem()==Items.GOLD_INGOT&&ItemStack.parseOptional(server.registryAccess(),inventory.getCompound(0)).getCount()==3,"Offline guest inventory lost on return");
            require(ender.size()==1&&ItemStack.parseOptional(server.registryAccess(),ender.getCompound(0)).getItem()==Items.ENDER_PEARL&&ItemStack.parseOptional(server.registryAccess(),ender.getCompound(0)).getCount()==6&&data.getInt("XpTotal")==900,"Offline guest ender chest/XP lost on return");
        }
    }
    private void connect(Minecraft mc)throws Exception {
        connectRoom(mc,read("room"));
    }
    private void connectRoom(Minecraft mc,String room)throws Exception {
        P2PBridge.INSTANCE.startClientViaRendezvous(room,"127.0.0.1",port,new P2PBridge.ConnectListener(){
            public void onStatus(String message){}
            public void onConnected(){mc.execute(()->ConnectScreen.startConnecting(new TitleScreen(),mc,new net.minecraft.client.multiplayer.resolver.ServerAddress("127.0.0.1",P2PBridge.INSTANCE.getProxyPort()),new net.minecraft.client.multiplayer.ServerData("PeerCraft","127.0.0.1:"+P2PBridge.INSTANCE.getProxyPort(),net.minecraft.client.multiplayer.ServerData.Type.OTHER),false,null));}
            public void onFailed(String reason){networkFailure=new IllegalStateException(reason);}
        });
    }
    private void leave(Minecraft mc){mc.level.disconnect();}
    private void done(Minecraft mc){System.out.println("NETWORK_HANDOFF_PROBE_DONE role="+(host?"host":"guest")+" account="+own);finished=true;P2PBridge.INSTANCE.stop();mc.stop();}
    private boolean exists(String name){return Files.exists(coordination.resolve(name));}
    private String read(String name)throws Exception{return new String(Files.readAllBytes(coordination.resolve(name)),java.nio.charset.StandardCharsets.UTF_8).trim();}
    private void write(String name,String value)throws Exception{Files.createDirectories(coordination);Path tmp=coordination.resolve(name+".tmp");Files.write(tmp,value.getBytes(java.nio.charset.StandardCharsets.UTF_8));Files.move(tmp,coordination.resolve(name),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
    private static void require(boolean condition,String message){if(!condition)throw new IllegalStateException(message);}
}
