package net.peercraft.uitest;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.world.*;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.passive.EntityWolf;
import net.minecraft.stats.StatList;
import net.minecraft.nbt.*;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.peercraft.network.account.AccountClient;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

@Mod(modid="peercraft_world_progress_probe", name="PeerCraft world progress probe", version="1", clientSideOnly=true)
public class WorldProgressProbe {
    private UUID account = UUID.fromString("53be8187-fbe4-44a1-973c-013253294811");
    private UUID successor = UUID.fromString("482d3559-1aa7-4726-9ab4-6023c8540f33");
    private final Map<UUID, AccountClient.AccountSession> authenticated = new ConcurrentHashMap<>();
    private CompletableFuture<Void> registration;
    private int stage;
    private long deadline = System.currentTimeMillis() + 180000, waitUntil;
    private IntegratedServer server;
    private CompletableFuture<Void> operation;
    private UUID original;
    private byte[] sourceBefore, successorSave;
    private Path world;
    private boolean finished;
    @Mod.EventHandler public void init(FMLInitializationEvent event) { net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(this); }
    @SubscribeEvent public void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || finished) return;
        Minecraft mc = Minecraft.getMinecraft();
        try {
            if (System.currentTimeMillis() > deadline) throw new IllegalStateException("World probe timed out at stage "+stage);
            if (stage == 0) {
                if (!(mc.currentScreen instanceof GuiMainMenu)) return;
                int authPort = Integer.getInteger("peercraft.probe.accountPort", 0);
                if (authPort > 0) {
                    if (registration == null) {
                        AccountClient.INSTANCE.connect("127.0.0.1", authPort);
                        registration = registerAccount().thenCompose(first -> {
                            account = first.accountId(); authenticated.put(account, first);
                            return registerAccount();
                        }).thenAccept(second -> {
                            successor = second.accountId(); authenticated.put(successor, second);
                            require(!account.equals(successor), "Two registrations reused one UUID");
                            System.out.println("WORLD_PROBE_AUTHENTICATED accounts=" + authenticated.size());
                        });
                    }
                    if (!registration.isDone()) return;
                    registration.get();
                }
                AccountClient.INSTANCE.clearSession();
                world = mc.gameDir.toPath().resolve("saves/progress-probe");
                if (Files.exists(world)) throw new IllegalStateException("Probe world already exists; use a fresh isolated directory");
                stage = 1;
                WorldSettings settings = new WorldSettings(123, GameType.CREATIVE, false, false, WorldType.FLAT); settings.enableCommands();
                mc.launchIntegratedServer("progress-probe", "Progress probe", settings);
            } else if (stage == 1) {
                if (mc.player == null || mc.getIntegratedServer() == null) return;
                server = mc.getIntegratedServer(); original = mc.player.getUniqueID();
                operation = new CompletableFuture<Void>(); stage = 2;
                server.addScheduledTask(() -> {
                    try {
                        EntityPlayerMP player = server.getPlayerList().getPlayerByUUID(original);
                        require(player != null, "Anonymous server player missing");
                        player.inventory.clear(); player.inventory.setInventorySlotContents(0, new ItemStack(Items.DIAMOND,7));
                        player.getInventoryEnderChest().setInventorySlotContents(0, new ItemStack(Items.EMERALD,13));
                        player.addExperience(150); player.capabilities.isFlying = true; player.sendPlayerAbilities();
                        player.setPositionAndUpdate(24.5,70.0,-16.25); player.addStat(StatList.JUMP,17);
                        net.minecraft.advancements.Advancement achievement = server.getAdvancementManager().getAdvancement(new net.minecraft.util.ResourceLocation("minecraft:story/root"));
                        require(achievement != null, "Advancement fixture missing");
                        for (String criterion : achievement.getCriteria().keySet()) player.getAdvancements().grantCriterion(achievement, criterion);
                        EntityWolf wolf = new EntityWolf(player.world); wolf.setTamed(true); wolf.setOwnerId(original);
                        wolf.setLocationAndAngles(25.5,70,-16.25,0,0); wolf.setNoGravity(true); require(player.world.spawnEntity(wolf), "Pet spawn failed");
                        operation.complete(null);
                    } catch (Throwable failure) { operation.completeExceptionally(failure); }
                });
            } else if (stage == 2) {
                if (!operation.isDone()) return; operation.get(); waitUntil = System.currentTimeMillis()+2000; stage = 3;
            } else if (stage == 3) {
                if (System.currentTimeMillis() < waitUntil) return;
                closeWorld(mc); stage = 4;
            } else if (stage == 4) {
                if (!operation.isDone()) return; operation.get();
                require(server.isServerStopped(), "Server closure was not confirmed"); finishClose(mc);
                sourceBefore = Files.readAllBytes(world.resolve("playerdata/"+original+".dat"));
                NBTTagCompound saved = read(world.resolve("level.dat")).getCompoundTag("Data").getCompoundTag("Player");
                require(saved.hasUniqueId("UUID") && original.equals(saved.getUniqueId("UUID")), "Original Data.Player UUID missing");
                verifySaved(saved);
                setAccount(account);
                stage = 5; mc.launchIntegratedServer("progress-probe", "Progress probe", null);
            } else if (stage == 5) {
                if (mc.player == null || mc.getIntegratedServer() == null) return;
                require(account.equals(mc.player.getUniqueID()), "Client reopened under another UUID: "+mc.player.getUniqueID());
                server = mc.getIntegratedServer(); operation = new CompletableFuture<Void>(); stage = 6;
                server.addScheduledTask(() -> {
                    try {
                        EntityPlayerMP player = server.getPlayerList().getPlayerByUUID(account);
                        require(player != null, "Account server player missing");
                        NBTTagCompound tag = new NBTTagCompound(); player.writeToNBT(tag); verifySaved(tag);
                        require(player.getStatFile().readStat(StatList.JUMP)==17, "Statistics changed");
                        require(player.getAdvancements().getProgress(server.getAdvancementManager().getAdvancement(new net.minecraft.util.ResourceLocation("minecraft:story/root"))).isDone(), "Advancement changed");
                        java.util.List<EntityWolf> pets = player.world.getEntities(EntityWolf.class, pet -> pet.isTamed());
                        require(!pets.isEmpty(), "Pet missing after reopen");
                        for (EntityWolf pet : pets) require(account.equals(pet.getOwnerId()), "Pet owner was not migrated");
                        operation.complete(null);
                    } catch (Throwable failure) { operation.completeExceptionally(failure); }
                });
            } else if (stage == 6) {
                if (!operation.isDone()) return; operation.get(); closeWorld(mc); stage = 7;
            } else if (stage == 7) {
                if (!operation.isDone()) return; operation.get();
                require(server.isServerStopped(), "Server closure was not confirmed"); finishClose(mc);
                require(Arrays.equals(sourceBefore,Files.readAllBytes(world.resolve("playerdata/"+original+".dat"))), "Original anonymous save changed");
                NBTTagCompound current = read(world.resolve("playerdata/"+account+".dat")); verifySaved(current);
                NBTTagCompound vanilla = read(world.resolve("level.dat")).getCompoundTag("Data").getCompoundTag("Player");
                require(account.equals(vanilla.getUniqueId("UUID")), "Vanilla owner snapshot UUID changed"); verifySaved(vanilla);
                try (java.util.stream.Stream<Path> backups = Files.list(world.resolve(".peercraft-backup"))) {
                    require(backups.anyMatch(path -> path.toString().endsWith(".zip")), "Full backup missing");
                }
                net.peercraft.client.handoff.HandoffOwnerPolicy.write(world, account);
                world = installCopy(world, "progress-successor", "outbound-probe");
                setAccount(successor); waitUntil=0; stage = 8; mc.launchIntegratedServer("progress-successor", "Progress successor", null);
            } else if (stage == 8) {
                if (mc.player == null || mc.getIntegratedServer() == null) return;
                require(successor.equals(mc.player.getUniqueID()), "New host UUID changed");
                require(net.peercraft.world.PlayerDataMigration.isBound(world, successor), "New host account lacks binding");
                server = mc.getIntegratedServer(); operation = new CompletableFuture<Void>(); stage = 9;
                server.addScheduledTask(() -> {
                    try {
                        EntityPlayerMP player = server.getPlayerList().getPlayerByUUID(successor);
                        require(player != null && player.inventory.isEmpty() && player.experienceTotal == 0,
                                "New host inherited former host progress");
                        require(player.getInventoryEnderChest().isEmpty(), "New host inherited ender chest");
                        player.inventory.setInventorySlotContents(0,new ItemStack(Items.GOLD_INGOT,3));
                        player.getInventoryEnderChest().setInventorySlotContents(0,new ItemStack(Items.ENDER_PEARL,6));
                        player.addExperience(900); player.capabilities.isFlying=true; player.sendPlayerAbilities();
                        player.setPositionAndUpdate(-24.5,70,16.25); operation.complete(null);
                    } catch(Throwable failure) { operation.completeExceptionally(failure); }
                });
            } else if (stage == 9) {
                if (!operation.isDone()) return; operation.get();
                if (waitUntil == 0) { waitUntil=System.currentTimeMillis()+2000; return; }
                if (System.currentTimeMillis()<waitUntil) return;
                closeWorld(mc); stage=10;
            } else if (stage == 10) {
                if (!operation.isDone()) return; operation.get(); finishClose(mc);
                successorSave=Files.readAllBytes(world.resolve("playerdata/"+successor+".dat"));
                verifySuccessor(read(world.resolve("playerdata/"+successor+".dat")));
                world=installCopy(world,"progress-return","return-probe");
                setAccount(account); stage=11; mc.launchIntegratedServer("progress-return","Progress return",null);
            } else if (stage == 11) {
                if (mc.player == null || mc.getIntegratedServer() == null) return;
                require(account.equals(mc.player.getUniqueID()),"Returning owner UUID changed");
                server=mc.getIntegratedServer(); operation=new CompletableFuture<Void>(); stage=12;
                server.addScheduledTask(() -> {
                    try {
                        EntityPlayerMP player=server.getPlayerList().getPlayerByUUID(account);
                        NBTTagCompound tag=new NBTTagCompound(); player.writeToNBT(tag); verifySaved(tag);
                        require(player.getStatFile().readStat(StatList.JUMP)==17,"Returned statistics changed");
                        require(player.getAdvancements().getProgress(server.getAdvancementManager().getAdvancement(new net.minecraft.util.ResourceLocation("minecraft:story/root"))).isDone(),"Returned advancement changed");
                        require(net.peercraft.client.handoff.HandoffOwnerPolicy.read(world).equals(account),"Original host marker changed");
                        operation.complete(null);
                    } catch(Throwable failure) { operation.completeExceptionally(failure); }
                });
            } else if (stage == 12) {
                if(!operation.isDone()) return; operation.get(); closeWorld(mc); stage=13;
            } else if (stage == 13) {
                if(!operation.isDone()) return; operation.get(); finishClose(mc);
                verifySaved(read(world.resolve("playerdata/"+account+".dat")));
                verifySuccessor(read(world.resolve("playerdata/"+successor+".dat")));
                require(Arrays.equals(successorSave,Files.readAllBytes(world.resolve("playerdata/"+successor+".dat"))),"Offline successor progress changed during return");
                require(net.peercraft.world.PlayerProgressCatalog.unassignedPlayers(world).isEmpty(),"Account progress appears anonymous");
                System.out.println("WORLD_PROGRESS_PROBE_DONE original="+original+" account="+account+" successor="+successor); finished=true; mc.shutdown();
            }
        } catch (Throwable failure) {
            failure.printStackTrace(); System.out.println("WORLD_PROGRESS_PROBE_FAILED stage="+stage); finished=true;
            if (mc.world != null) closeWorld(mc); mc.shutdown();
        }
    }
    private CompletableFuture<AccountClient.AccountSession> registerAccount() {
        CompletableFuture<AccountClient.AccountSession> result = new CompletableFuture<>();
        AccountClient.INSTANCE.registerUnlicensed("Developer", "LocalProbePassword-Only".toCharArray(), new AccountClient.AuthCallback() {
            public void onSuccess(AccountClient.AccountSession session) { result.complete(session); }
            public void onFailed(String reason) { result.completeExceptionally(new IllegalStateException("Registration failed: " + reason)); }
        });
        return result;
    }
    private void setAccount(UUID account) throws Exception {
        Field session=AccountClient.class.getDeclaredField("currentSession"); session.setAccessible(true);
        session.set(AccountClient.INSTANCE,authenticated.containsKey(account) ? authenticated.get(account) : new AccountClient.AccountSession(account,new byte[16],new byte[16],false,"ABC123","Developer"));
    }
    private Path installCopy(Path source,String folder,String phase) throws Exception {
        Path saves=source.getParent(), archiveDir=saves.getParent().resolve("probe-archives");
        net.peercraft.client.handoff.WorldArchiver.Result result=net.peercraft.client.handoff.WorldArchiver.archiveClosed(source,archiveDir,phase);
        Path staging=saves.resolve(phase+"-staging"), target=saves.resolve(folder);
        net.peercraft.network.handoff.WorldInstall.unpack(result.zip(),staging,1024L*1024*1024);
        net.peercraft.network.handoff.WorldInstall.replace(staging,target,saves.resolve(phase+"-backup"),saves.resolve(phase+"-journal"),true,false,()->{});
        return target;
    }
    private static void verifySuccessor(NBTTagCompound tag) {
        NBTTagList inventory=tag.getTagList("Inventory",10), ender=tag.getTagList("EnderItems",10);
        require(inventory.tagCount()==1 && new ItemStack(inventory.getCompoundTagAt(0)).getItem()==Items.GOLD_INGOT
                && new ItemStack(inventory.getCompoundTagAt(0)).getCount()==3,"Successor inventory changed");
        require(ender.tagCount()==1 && new ItemStack(ender.getCompoundTagAt(0)).getItem()==Items.ENDER_PEARL
                && new ItemStack(ender.getCompoundTagAt(0)).getCount()==6,"Successor ender chest changed");
        require(tag.getInteger("XpTotal")==900,"Successor experience changed");
    }
    private void closeWorld(Minecraft mc) {
        final IntegratedServer closing = server;
        operation = CompletableFuture.runAsync(() -> {
            try { net.peercraft.client.handoff.WorldArchiver.saveAndStop(closing, 60000); }
            catch (Exception failure) { throw new CompletionException(failure); }
        });
    }
    private static void finishClose(Minecraft mc) {
        if (mc.world != null) { mc.world.sendQuittingDisconnectingPacket(); mc.loadWorld(null); }
        mc.displayGuiScreen(new GuiMainMenu());
    }
    private static NBTTagCompound read(Path path) throws Exception { try (java.io.InputStream input=Files.newInputStream(path)) { return CompressedStreamTools.readCompressed(input); } }
    private static void verifySaved(NBTTagCompound data) {
        NBTTagList inventory=data.getTagList("Inventory",10), ender=data.getTagList("EnderItems",10);
        require(inventory.tagCount()==1 && new ItemStack(inventory.getCompoundTagAt(0)).getItem()==Items.DIAMOND
                && new ItemStack(inventory.getCompoundTagAt(0)).getCount()==7,"Inventory changed");
        require(ender.tagCount()==1 && new ItemStack(ender.getCompoundTagAt(0)).getItem()==Items.EMERALD
                && new ItemStack(ender.getCompoundTagAt(0)).getCount()==13,"Ender chest changed");
        require(data.getInteger("XpTotal")==150,"Experience changed");
        NBTTagList position=data.getTagList("Pos",6);
        require(Math.abs(position.getDoubleAt(0)-24.5)<0.1 && Math.abs(position.getDoubleAt(2)+16.25)<0.1,"Position changed");
    }
    private static void require(boolean condition,String message) { if(!condition)throw new IllegalStateException(message); }
}
