package net.peercraft.client.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.server.integrated.IntegratedServer;
//? if >=1.12 {
import net.minecraft.util.text.Style;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.GameType;
//?} else {
//~ import net.minecraft.util.ChatComponentText;
//~ import net.minecraft.util.ChatStyle;
//~ import net.minecraft.util.EnumChatFormatting;
//~ import net.minecraft.util.IChatComponent;
//~ import net.minecraft.world.WorldSettings;
//?}
import net.peercraft.client.PeerCraftHostOptions;
import net.peercraft.client.gui.PeerCraftLang;
import net.peercraft.client.modsync.HostModSyncProviderImpl;
import net.peercraft.config.PeerCraftConfig;
import net.peercraft.network.modsync.ModSyncHostProvider;
import net.peercraft.network.p2p.P2PBridge;
import net.peercraft.platform.Services;
import net.peercraft.client.handoff.HandoffOwnerPolicy;
import net.peercraft.client.handoff.WorldArchiver;
import java.io.IOException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
//? if >=1.12 {
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//?}
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 //? if >=1.12 {
 * Forge 1.12.2 backport of {@code src/main/.../OpenToLanMixin.java} (cf. 1.16.5 twin).
 * 1.12.2 deltas:
 //?} else {
//~  * Forge 1.7.10 backport of {@code src/main/.../OpenToLanMixin.java} (twin of the
//~  * {@code src/client-1122} mixin). Starts the PeerCraft host bridge when a world is opened to
//~  * LAN. 1.7.10 deltas vs the 1.12.2 twin:
 //?}
 * <ul>
 //? if >=1.12 {
 *   <li>{@code IntegratedServer.publishServer(...)} is {@code shareToLAN(GameType, boolean)}
 *       returning the LAN port as a {@code String} — parsed here rather than read via a
 *       {@code getPort()} accessor.</li>
 *   <li>No {@code setUsesAuthentication} — {@code MinecraftServer.setOnlineMode(boolean)}
 *       (the "allow unlicensed" decision on 1.12.2 really rides on
 *       {@link ServerLoginPacketListenerImplMixin} making the offline profile).</li>
 *   <li>Chat is {@code EntityPlayer.sendMessage(ITextComponent)}; the room code goes out as
 *       plain highlighted text (no {@code COPY_TO_CLIPBOARD} click event before 1.15).</li>
 *   <li>Logs through Log4j2 directly (no SLF4J on the 1.12.2 loader classpath).</li>
 //?} else {
//~  *   <li>{@code IntegratedServer.shareToLAN(WorldSettings.GameType, boolean)} — {@code GameType}
//~  *       was a nested class of {@code WorldSettings} before 1.8. Still returns the LAN port as a
//~  *       {@code String}, parsed here.</li>
//~  *   <li>Text components: {@code net.minecraft.util.text.*} → {@code net.minecraft.util.*}
//~  *       ({@code ChatComponentText} / {@code ChatComponentTranslation} / {@code ChatStyle} /
//~  *       {@code EnumChatFormatting}); {@code player.sendMessage} → {@code addChatMessage};
//~  *       {@code player.setStyle} → {@code setChatStyle}. {@code Minecraft.player} →
//~  *       {@code Minecraft.thePlayer}.</li>
//~  *   <li>The {@code stopServer} inject is gone — {@code MinecraftServer.stopServer} is not
//~  *       overridden by {@code IntegratedServer} on 1.7.10 (Mixin won't inject inherited
//~  *       methods), so {@code cancelRendezvous()} moved to {@code FMLServerStoppingEvent} in
//~  *       {@code PeerCraftForge}.</li>
 //?}
 * </ul>
 //? if <1.12 {
//~  *
//~  * <p>VERIFY on first RFG build (MCP {@code stable_12}): the {@code shareToLAN} method name and
//~  * that {@code IntegratedServer} really declares it (it does on 1.7.10). Runtime application is
//~  * not proven by compilation — check the log for "Mixing OpenToLanMixin into ...IntegratedServer".
 //?}
 */
@Mixin(IntegratedServer.class)
public abstract class OpenToLanMixin {

    private static final Logger LOGGER = LogManager.getLogger("peercraft");

    @Inject(method = "shareToLAN", at = @At("RETURN"))
    //? if >=1.12 {
    private void peercraft$onOpenToLan(GameType gameMode, boolean cheatsAllowed, CallbackInfoReturnable<String> cir) {
    //?} else {
//~     private void peercraft$onOpenToLan(WorldSettings.GameType gameMode, boolean cheatsAllowed, CallbackInfoReturnable<String> cir) {
    //?}
        int lanPort = parsePort(cir.getReturnValue());
        if (lanPort <= 0) {
            return; // shareToLAN failed
        }
        String mode = PeerCraftConfig.mode();
        if (PeerCraftConfig.MODE_DISABLED.equals(mode) || PeerCraftConfig.MODE_CLIENT.equals(mode)) {
            LOGGER.info("[PeerCraft P2P] Хост-мост не запускается в режиме {}", mode);
            return;
        }

        IntegratedServer server = (IntegratedServer) (Object) this;
        try {
            if (HandoffOwnerPolicy.read(WorldArchiver.worldDir(server)) != null)
                //? if >=1.12 {
                server.getPlayerList().setGameType(null);
                //?} else {
//~                 server.getConfigurationManager().func_152604_a(null);
                //?}
        } catch (IOException invalid) { throw new IllegalStateException("Invalid handoff owner record", invalid); }
        LOGGER.info("[PeerCraft P2P] Мир открыт для сети на порту {}", lanPort);

        server.setOnlineMode(!PeerCraftHostOptions.allowUnlicensedPlayers);

        if (P2PBridge.INSTANCE.getProxy() != null) {
            P2PBridge.INSTANCE.getProxy().stop();
        }

        if (PeerCraftHostOptions.internetPlayRequested) {
            ModSyncHostProvider modSyncProvider = null;
            net.peercraft.config.ModSyncMode hostMode = PeerCraftConfig.modSyncHostMode();
            if (hostMode != net.peercraft.config.ModSyncMode.OFF) {
                try {
                    modSyncProvider = HostModSyncProviderImpl.start(Services.PLATFORM.getModsDir(), hostMode);
                } catch (RuntimeException e) {
                    LOGGER.warn("[PeerCraft P2P] Не удалось подготовить mod-sync для хоста: {}", e.toString());
                }
            }
            P2PBridge.INSTANCE.startHostViaRendezvous(lanPort, PeerCraftHostOptions.maxPlayers,
                    PeerCraftHostOptions.friendsOnly, PeerCraftHostOptions.publicRoom,
                    publicRoomWorldName(server), server.getMinecraftVersion(), new P2PBridge.HostListener() {
                        @Override
                        public void onRoomCreated(String code, boolean changed) {
                            String prefixKey = changed
                                    ? "peercraft.mixin.open_to_lan.room_code_changed_prefix"
                                    : "peercraft.mixin.open_to_lan.room_code_prefix";
                            // The mod's FML resource pack doesn't serve assets/peercraft/lang/*.lang in this
                            // coremod config, so vanilla chat i18n would just echo the key — resolve it through
                            //? if >=1.12 {
                            // PeerCraftLang (classpath reader), same as the rest of the 1.12.2 GUI.
                            sendChat(new TextComponentString(PeerCraftLang.tr(prefixKey))
                                    .appendSibling(new TextComponentString(" " + code)
                                            .setStyle(new Style().setColor(TextFormatting.YELLOW))));
                            //?} else {
//~                             // PeerCraftLang (classpath reader), same as onFailed below and the rest of the GUI.
//~                             sendChat(new ChatComponentText(PeerCraftLang.tr(prefixKey))
//~                                     .appendSibling(new ChatComponentText(" " + code)
//~                                             .setChatStyle(new ChatStyle().setColor(EnumChatFormatting.YELLOW))));
                            //?}
                        }

                        @Override
                        public void onFailed(String reason) {
                            //? if >=1.12 {
                            // `reason` is a peercraft.p2p.* translation key from the network layer.
                            sendChat(new TextComponentString(PeerCraftLang.tr("peercraft.mixin.open_to_lan.failed_prefix",
                            //?} else {
//~                             // reason is a peercraft.p2p.* translation key now. The mod's FML
//~                             // resource pack doesn't serve assets/peercraft/lang/*.lang in this
//~                             // coremod config, so resolve it through PeerCraftLang (classpath
//~                             // reader) rather than vanilla chat i18n.
//~                             sendChat(new ChatComponentText(PeerCraftLang.tr("peercraft.mixin.open_to_lan.failed_prefix",
                            //?}
                                    PeerCraftLang.tr(reason))));
                        }
                    }, modSyncProvider);
        } else {
            P2PBridge.INSTANCE.startHost(lanPort);
        }
    //? if >=1.12 {
    }

    @Inject(method = "stopServer", at = @At("HEAD"))
    private void peercraft$onStopServer(CallbackInfo ci) {
        P2PBridge.INSTANCE.cancelRendezvous();
    //?}
    }

    private static int parsePort(String returned) {
        if (returned == null) {
            return -1;
        }
        try {
            return Integer.parseInt(returned.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String publicRoomWorldName(IntegratedServer server) {
        String worldName = PeerCraftHostOptions.worldName;
        if (worldName != null && !worldName.trim().isEmpty()) {
            return worldName;
        }
        //? if >=1.12 {
        String levelName = server.getWorldName();
        //?} else {
//~         String levelName = server.getFolderName();
        //?}
        if (levelName == null) {
            return "";
        }
        return levelName.length() > PeerCraftHostOptions.MAX_WORLD_NAME_LENGTH
                ? levelName.substring(0, PeerCraftHostOptions.MAX_WORLD_NAME_LENGTH)
                : levelName;
    }

    //? if >=1.12 {
    private static void sendChat(net.minecraft.util.text.ITextComponent message) {
        Minecraft.getMinecraft().addScheduledTask(() -> {
            EntityPlayer player = Minecraft.getMinecraft().player;
            if (player != null) {
                player.sendMessage(message);
    //?} else {
//~     private static void sendChat(IChatComponent message) {
//~         Minecraft.getMinecraft().func_152344_a(new Runnable() {
//~             @Override
//~             public void run() {
//~                 EntityPlayer player = Minecraft.getMinecraft().thePlayer;
//~                 if (player != null) {
//~                     player.addChatMessage(message);
//~                 }
    //?}
            }
        });
    }
}
