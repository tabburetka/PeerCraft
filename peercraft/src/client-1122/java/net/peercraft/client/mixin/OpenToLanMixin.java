package net.peercraft.client.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.text.Style;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.GameType;
import net.peercraft.client.PeerCraftHostOptions;
import net.peercraft.client.gui.PeerCraftLang;
import net.peercraft.config.PeerCraftConfig;
import net.peercraft.network.p2p.P2PBridge;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Forge 1.12.2 backport of {@code src/main/.../OpenToLanMixin.java} (cf. 1.16.5 twin).
 * 1.12.2 deltas:
 * <ul>
 *   <li>{@code IntegratedServer.publishServer(...)} is {@code shareToLAN(GameType, boolean)}
 *       returning the LAN port as a {@code String} — parsed here rather than read via a
 *       {@code getPort()} accessor.</li>
 *   <li>No {@code setUsesAuthentication} — {@code MinecraftServer.setOnlineMode(boolean)}
 *       (the "allow unlicensed" decision on 1.12.2 really rides on
 *       {@link ServerLoginPacketListenerImplMixin} making the offline profile).</li>
 *   <li>Chat is {@code EntityPlayer.sendMessage(ITextComponent)}; the room code goes out as
 *       plain highlighted text (no {@code COPY_TO_CLIPBOARD} click event before 1.15).</li>
 *   <li>Logs through Log4j2 directly (no SLF4J on the 1.12.2 loader classpath).</li>
 * </ul>
 */
@Mixin(IntegratedServer.class)
public abstract class OpenToLanMixin {

    private static final Logger LOGGER = LogManager.getLogger("peercraft");

    @Inject(method = "shareToLAN", at = @At("RETURN"))
    private void peercraft$onOpenToLan(GameType gameMode, boolean cheatsAllowed, CallbackInfoReturnable<String> cir) {
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
        LOGGER.info("[PeerCraft P2P] Мир открыт для сети на порту {}", lanPort);

        server.setOnlineMode(!PeerCraftHostOptions.allowUnlicensedPlayers);

        if (P2PBridge.INSTANCE.getProxy() != null) {
            P2PBridge.INSTANCE.getProxy().stop();
        }

        if (PeerCraftHostOptions.internetPlayRequested) {
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
                            // PeerCraftLang (classpath reader), same as the rest of the 1.12.2 GUI.
                            sendChat(new TextComponentString(PeerCraftLang.tr(prefixKey))
                                    .appendSibling(new TextComponentString(" " + code)
                                            .setStyle(new Style().setColor(TextFormatting.YELLOW))));
                        }

                        @Override
                        public void onFailed(String reason) {
                            // `reason` is a peercraft.p2p.* translation key from the network layer.
                            sendChat(new TextComponentString(PeerCraftLang.tr("peercraft.mixin.open_to_lan.failed_prefix",
                                    PeerCraftLang.tr(reason))));
                        }
                    });
        } else {
            P2PBridge.INSTANCE.startHost(lanPort);
        }
    }

    @Inject(method = "stopServer", at = @At("HEAD"))
    private void peercraft$onStopServer(CallbackInfo ci) {
        P2PBridge.INSTANCE.cancelRendezvous();
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
        String levelName = server.getWorldName();
        if (levelName == null) {
            return "";
        }
        return levelName.length() > PeerCraftHostOptions.MAX_WORLD_NAME_LENGTH
                ? levelName.substring(0, PeerCraftHostOptions.MAX_WORLD_NAME_LENGTH)
                : levelName;
    }

    private static void sendChat(net.minecraft.util.text.ITextComponent message) {
        Minecraft.getMinecraft().addScheduledTask(() -> {
            EntityPlayer player = Minecraft.getMinecraft().player;
            if (player != null) {
                player.sendMessage(message);
            }
        });
    }
}
