package net.peercraft.client.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;
import net.minecraft.world.WorldSettings;
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
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Forge 1.7.10 backport of {@code src/main/.../OpenToLanMixin.java} (twin of the
 * {@code src/client-1122} mixin). Starts the PeerCraft host bridge when a world is opened to
 * LAN. 1.7.10 deltas vs the 1.12.2 twin:
 * <ul>
 *   <li>{@code IntegratedServer.shareToLAN(WorldSettings.GameType, boolean)} — {@code GameType}
 *       was a nested class of {@code WorldSettings} before 1.8. Still returns the LAN port as a
 *       {@code String}, parsed here.</li>
 *   <li>Text components: {@code net.minecraft.util.text.*} → {@code net.minecraft.util.*}
 *       ({@code ChatComponentText} / {@code ChatComponentTranslation} / {@code ChatStyle} /
 *       {@code EnumChatFormatting}); {@code player.sendMessage} → {@code addChatMessage};
 *       {@code player.setStyle} → {@code setChatStyle}. {@code Minecraft.player} →
 *       {@code Minecraft.thePlayer}.</li>
 *   <li>The {@code stopServer} inject is gone — {@code MinecraftServer.stopServer} is not
 *       overridden by {@code IntegratedServer} on 1.7.10 (Mixin won't inject inherited
 *       methods), so {@code cancelRendezvous()} moved to {@code FMLServerStoppingEvent} in
 *       {@code PeerCraftForge}.</li>
 * </ul>
 *
 * <p>VERIFY on first RFG build (MCP {@code stable_12}): the {@code shareToLAN} method name and
 * that {@code IntegratedServer} really declares it (it does on 1.7.10). Runtime application is
 * not proven by compilation — check the log for "Mixing OpenToLanMixin into ...IntegratedServer".
 */
@Mixin(IntegratedServer.class)
public abstract class OpenToLanMixin {

    private static final Logger LOGGER = LogManager.getLogger("peercraft");

    @Inject(method = "shareToLAN", at = @At("RETURN"))
    private void peercraft$onOpenToLan(WorldSettings.GameType gameMode, boolean cheatsAllowed, CallbackInfoReturnable<String> cir) {
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
                server.getConfigurationManager().func_152604_a(null);
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
                            // PeerCraftLang (classpath reader), same as onFailed below and the rest of the GUI.
                            sendChat(new ChatComponentText(PeerCraftLang.tr(prefixKey))
                                    .appendSibling(new ChatComponentText(" " + code)
                                            .setChatStyle(new ChatStyle().setColor(EnumChatFormatting.YELLOW))));
                        }

                        @Override
                        public void onFailed(String reason) {
                            // reason is a peercraft.p2p.* translation key now. The mod's FML
                            // resource pack doesn't serve assets/peercraft/lang/*.lang in this
                            // coremod config, so resolve it through PeerCraftLang (classpath
                            // reader) rather than vanilla chat i18n.
                            sendChat(new ChatComponentText(PeerCraftLang.tr("peercraft.mixin.open_to_lan.failed_prefix",
                                    PeerCraftLang.tr(reason))));
                        }
                    }, modSyncProvider);
        } else {
            P2PBridge.INSTANCE.startHost(lanPort);
        }
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
        String levelName = server.getFolderName();
        if (levelName == null) {
            return "";
        }
        return levelName.length() > PeerCraftHostOptions.MAX_WORLD_NAME_LENGTH
                ? levelName.substring(0, PeerCraftHostOptions.MAX_WORLD_NAME_LENGTH)
                : levelName;
    }

    private static void sendChat(IChatComponent message) {
        Minecraft.getMinecraft().func_152344_a(new Runnable() {
            @Override
            public void run() {
                EntityPlayer player = Minecraft.getMinecraft().thePlayer;
                if (player != null) {
                    player.addChatMessage(message);
                }
            }
        });
    }
}
