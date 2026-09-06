package net.peercraft.client.mixin;

import net.minecraft.ChatFormatting;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.network.chat.TranslatableComponent;
import net.minecraft.world.level.GameType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import net.peercraft.client.PeerCraftHostOptions;
import net.peercraft.client.modsync.HostModSyncProviderImpl;
import net.peercraft.config.PeerCraftConfig;
import net.peercraft.network.modsync.ModSyncHostProvider;
import net.peercraft.network.p2p.P2PBridge;
import net.peercraft.platform.Services;

/**
 * Minecraft 1.16.5 backport of {@code src/main/.../OpenToLanMixin.java}. The hook target is
 * unchanged — {@code IntegratedServer.publishServer(GameType, boolean, int)} has the same
 * signature on 1.16.5 — so only the text-component construction differs: 1.16.5 has no
 * {@code Component.translatable/literal} statics (use {@code TranslatableComponent}/{@code
 * TextComponent}) and {@code ClickEvent}/{@code HoverEvent} are still the pre-1.21.5 concrete
 * {@code Action}+value classes. {@code SharedConstants.getCurrentVersion().getName()} and
 * {@code LocalPlayer.displayClientMessage(Component, boolean)} exist as-is.
 */
@Mixin(IntegratedServer.class)
public abstract class OpenToLanMixin {

    private static final Logger LOGGER = LoggerFactory.getLogger("peercraft");

    @Inject(method = "publishServer", at = @At("RETURN"))
    private void onOpenToLan(GameType gameMode, boolean cheatsAllowed, int port, CallbackInfoReturnable<Boolean> cir) {

        if (cir.getReturnValue()) {
            if (PeerCraftConfig.MODE_DISABLED.equals(PeerCraftConfig.mode()) || PeerCraftConfig.MODE_CLIENT.equals(PeerCraftConfig.mode())) {
                LOGGER.info("[PeerCraft P2P] Хост-мост не запускается в режиме {}", PeerCraftConfig.mode());
                return;
            }

            IntegratedServer server = (IntegratedServer) (Object) this;

            int lanPort = server.getPort();

            LOGGER.info("[PeerCraft P2P] Мир успешно открыт для сети на порту: {}", lanPort);

            if (PeerCraftHostOptions.allowUnlicensedPlayers) {
                server.setUsesAuthentication(false);
                LOGGER.info("[PeerCraft P2P] Проверка сессии Mojang отключена для хоста PeerCraft (разрешены нелицензионные игроки).");
            } else {
                server.setUsesAuthentication(true);
                LOGGER.info("[PeerCraft P2P] Проверка сессии Mojang включена — только лицензионные игроки смогут подключиться.");
            }

            if (P2PBridge.INSTANCE.getProxy() != null) {
                P2PBridge.INSTANCE.getProxy().stop();
                LOGGER.info("[PeerCraft P2P] localProxy закрыт на Хосте.");
            }

            if (PeerCraftHostOptions.internetPlayRequested) {
                LOGGER.info("[PeerCraft P2P] Через интернет — используем сервер знакомств (макс. игроков: {}), peerHost/peerPort игнорируются.", PeerCraftHostOptions.maxPlayers);
                ModSyncHostProvider modSyncProvider = null;
                net.peercraft.config.ModSyncMode hostMode = PeerCraftConfig.modSyncHostMode();
                if (hostMode != net.peercraft.config.ModSyncMode.OFF) {
                    try {
                        modSyncProvider = HostModSyncProviderImpl.start(Services.PLATFORM.getModsDir(), hostMode);
                    } catch (RuntimeException e) {
                        LOGGER.warn("[PeerCraft P2P] Не удалось подготовить mod-sync для хоста: {}", e.toString());
                    }
                }
                P2PBridge.INSTANCE.startHostViaRendezvous(lanPort, PeerCraftHostOptions.maxPlayers, PeerCraftHostOptions.friendsOnly,
                        PeerCraftHostOptions.publicRoom, publicRoomWorldName(server), currentMinecraftVersion(), new P2PBridge.HostListener() {
                    @Override
                    public void onRoomCreated(String code, boolean changed) {
                        String prefixKey = changed
                                ? "peercraft.mixin.open_to_lan.room_code_changed_prefix"
                                : "peercraft.mixin.open_to_lan.room_code_prefix";
                        sendChatMessage(copyableCodeMessage(new TranslatableComponent(prefixKey).getString(), code));
                    }

                    @Override
                    public void onFailed(String reason) {
                        // `reason` is a peercraft.p2p.* translation key from the network layer — resolve it as
                        // a nested component so the chat line comes out localized, not as a raw key.
                        sendChatMessage(new TranslatableComponent("peercraft.mixin.open_to_lan.failed_prefix",
                                new TranslatableComponent(reason)));
                    }
                }, modSyncProvider);
            } else {
                P2PBridge.INSTANCE.startHost(lanPort);
            }
        }
    }

    @Inject(method = "stopServer", at = @At("HEAD"))
    private void onStopServer(CallbackInfo ci) {
        P2PBridge.INSTANCE.cancelRendezvous();
    }

    private static String currentMinecraftVersion() {
        return SharedConstants.getCurrentVersion().getName();
    }

    private static String publicRoomWorldName(IntegratedServer server) {
        String worldName = PeerCraftHostOptions.worldName;
        if (worldName != null && !worldName.trim().isEmpty()) {
            return worldName;
        }
        String levelName = server.getWorldData().getLevelName();
        if (levelName == null) {
            return "";
        }
        return levelName.length() > PeerCraftHostOptions.MAX_WORLD_NAME_LENGTH
                ? levelName.substring(0, PeerCraftHostOptions.MAX_WORLD_NAME_LENGTH)
                : levelName;
    }

    private static void sendChatMessage(Component message) {
        Minecraft.getInstance().execute(() -> {
            LocalPlayer player = Minecraft.getInstance().player;
            if (player != null) {
                player.displayClientMessage(message, false);
            }
        });
    }

    private static Component copyableCodeMessage(String prefix, String code) {
        ClickEvent clickEvent = new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, code);
        HoverEvent hoverEvent = new HoverEvent(HoverEvent.Action.SHOW_TEXT, new TranslatableComponent("peercraft.mixin.open_to_lan.hover_copy"));
        MutableComponent codeComponent = new TextComponent(code).withStyle(style -> style
                .withColor(ChatFormatting.YELLOW)
                .withUnderlined(true)
                .withClickEvent(clickEvent)
                .withHoverEvent(hoverEvent));
        return new TextComponent(prefix).append(codeComponent);
    }
}
