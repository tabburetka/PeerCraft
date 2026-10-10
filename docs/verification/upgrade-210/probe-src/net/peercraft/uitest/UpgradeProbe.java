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
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
@Mod(modid="peercraft_upgrade_probe",name="Upgrade probe",version="1",clientSideOnly=true)
public class UpgradeProbe {
 private boolean old="old".equals(System.getProperty("peercraft.upgrade.phase"));
 private int stage;private long deadline=System.currentTimeMillis()+240000,wait;
 private CompletableFuture<Void> work;private IntegratedServer server;private UUID identity;
 private UUID account=UUID.fromString("53be8187-fbe4-44a1-973c-013253294811");
 private Path world;private boolean finished;
 @Mod.EventHandler public void init(FMLInitializationEvent e){net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(this);}
 @SubscribeEvent public void tick(TickEvent.ClientTickEvent e){
  if(e.phase!=TickEvent.Phase.END||finished)return;
  Minecraft mc=Minecraft.getMinecraft();
  try {
   require(System.currentTimeMillis()<deadline,"Upgrade timeout stage "+stage);
   if(stage==0){
    if(!(mc.currentScreen instanceof GuiMainMenu))return;
    world=mc.gameDir.toPath().resolve("saves/upgrade-world");
    System.out.println("UPGRADE_PRODUCTION_SOURCE "+AccountClient.class.getProtectionDomain().getCodeSource().getLocation());
    if(old){require(!Files.exists(world),"Old world already exists");AccountClient.INSTANCE.clearSession();}
    else {
     require(Files.exists(world.resolve("level.dat")),"Old baseline missing");
     java.lang.reflect.Field f=AccountClient.class.getDeclaredField("currentSession");f.setAccessible(true);
     f.set(AccountClient.INSTANCE,new AccountClient.AccountSession(account,new byte[16],new byte[16],false,"ABC123","Developer"));
    }
    WorldSettings settings=new WorldSettings(123,GameType.CREATIVE,false,false,WorldType.FLAT);settings.enableCommands();stage=1;
    mc.launchIntegratedServer("upgrade-world","Upgrade world",old?settings:null);
   }else if(stage==1){
    if(mc.player==null||mc.getIntegratedServer()==null)return;
    server=mc.getIntegratedServer();identity=mc.player.getUniqueID();
    if(!old)require(account.equals(identity),"New account UUID not selected");
    work=new CompletableFuture<Void>();stage=2;
    server.addScheduledTask(()->{try{
     EntityPlayerMP player=server.getPlayerList().getPlayerByUUID(identity);require(player!=null,"Native player missing");
     if(old){
      player.inventory.clear();player.inventory.setInventorySlotContents(0,new ItemStack(Items.DIAMOND,7));
      player.getInventoryEnderChest().setInventorySlotContents(0,new ItemStack(Items.EMERALD,13));player.addExperience(150);
      player.capabilities.isFlying=true;player.sendPlayerAbilities();player.setPositionAndUpdate(24.5,70,-16.25);player.addStat(StatList.JUMP,17);
      net.minecraft.advancements.Advancement a=server.getAdvancementManager().getAdvancement(new net.minecraft.util.ResourceLocation("minecraft:story/root"));
      for(String criterion:a.getCriteria().keySet())player.getAdvancements().grantCriterion(a,criterion);
      EntityWolf wolf=new EntityWolf(player.world);wolf.setTamed(true);wolf.setOwnerId(identity);wolf.setLocationAndAngles(25.5,70,-16.25,0,0);wolf.setNoGravity(true);require(player.world.spawnEntity(wolf),"Pet spawn failed");
     }else{
      NBTTagCompound tag=new NBTTagCompound();player.writeToNBT(tag);verifySaved(tag);
      require(player.getStatFile().readStat(StatList.JUMP)==17,"Stats lost after upgrade");
      require(player.getAdvancements().getProgress(server.getAdvancementManager().getAdvancement(new net.minecraft.util.ResourceLocation("minecraft:story/root"))).isDone(),"Advancement lost");
      java.util.List<EntityWolf> pets=player.world.getEntities(EntityWolf.class,w->w.isTamed());require(!pets.isEmpty(),"Pet missing");
      for(EntityWolf wolf:pets)require(account.equals(wolf.getOwnerId()),"Pet ownership not migrated");
     }
     work.complete(null);
    }catch(Throwable x){work.completeExceptionally(x);}});
   }else if(stage==2){if(!work.isDone())return;work.get();wait=System.currentTimeMillis()+2000;stage=3;
   }else if(stage==3){if(System.currentTimeMillis()<wait)return;
    if(old){mc.world.sendQuittingDisconnectingPacket();mc.loadWorld(null);mc.displayGuiScreen(new GuiMainMenu());stage=4;}
    else {work=CompletableFuture.runAsync(()->{try{net.peercraft.client.handoff.WorldArchiver.saveAndStop(server,60000);}catch(Exception x){throw new CompletionException(x);}});stage=40;}
   }else if(stage==40){
    if(!work.isDone())return;work.get();require(server.isServerStopped(),"Production stop incomplete");
    if(mc.world!=null){mc.world.sendQuittingDisconnectingPacket();mc.loadWorld(null);}mc.displayGuiScreen(new GuiMainMenu());stage=4;
   }else if(stage==4){
    require(server.isServerStopped(),"Server not stopped");
    NBTTagCompound tag=read(world.resolve("level.dat")).getCompoundTag("Data").getCompoundTag("Player");verifySaved(tag);
    require(tag.hasUniqueId("UUID")&&identity.equals(tag.getUniqueId("UUID")),"Saved UUID mismatch");
    Files.write(mc.gameDir.toPath().resolve("upgrade-result.txt"),(identity.toString()+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
    System.out.println("UPGRADE_PROBE_DONE phase="+(old?"old":"new"));finished=true;mc.shutdown();
   }
  }catch(Throwable x){x.printStackTrace();System.out.println("UPGRADE_PROBE_FAILED stage="+stage);finished=true;mc.shutdown();}
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
