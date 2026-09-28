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
import net.peercraft.config.ModSyncMode;
import net.peercraft.config.PeerCraftConfig;
import net.peercraft.network.modsync.ModSyncHostProvider;
import net.peercraft.network.p2p.P2PBridge;
import net.peercraft.platform.Services;
import net.peercraft.client.handoff.HandoffCommandOwner;

@Mixin(IntegratedServer.class)
public abstract class OpenToLanMixin {

    private static final Logger LOGGER = LoggerFactory.getLogger("peercraft");
    // publishServer supplies the world's default mode as a forced mode. After handoff that
    // would overwrite each returning player's independently saved creative/survival mode.
    @Inject(method = "getForcedGameType", at = @At("HEAD"), cancellable = true)
    private void peercraft$preservePlayerGameType(CallbackInfoReturnable<GameType> cir) {
        if (HandoffCommandOwner.originalOwner((IntegratedServer) (Object) this) != null) cir.setReturnValue(null);
    }

    // 26.2 reworked "Open to LAN": MultiplayerOptionsScreen -> changeMultiplayerScope -> publish()
    // calls the NEW two-arg IntegratedServer.publishServer(MultiplayerScope, int) — the old
    // publishServer(GameType, boolean, int) still exists (as publishServer(scope, gameType,
    // cheats, port)) but is no longer on the Open-to-LAN path, so injecting into it does nothing
    // (mod loads, hook never fires, no room code). Target the two-arg one; handler never used
    // gameType/cheats anyway.
    //? if <26.2 {
    @Inject(method = "publishServer", at = @At("RETURN"))
    private void onOpenToLan(GameType gameMode, boolean cheatsAllowed, int port, CallbackInfoReturnable<Boolean> cir) {
    //?} else {
    /*@Inject(method = "publishServer(Lnet/minecraft/server/MinecraftServer$MultiplayerScope;I)Z", at = @At("RETURN"))
    private void onOpenToLan(net.minecraft.server.MinecraftServer.MultiplayerScope scope, int port, CallbackInfoReturnable<Boolean> cir) {*/
    //?}

        if (cir.getReturnValue()) {
            if (PeerCraftConfig.MODE_DISABLED.equals(PeerCraftConfig.mode()) || PeerCraftConfig.MODE_CLIENT.equals(PeerCraftConfig.mode())) {
                LOGGER.info("[PeerCraft P2P] Хост-мост не запускается в режиме {}", PeerCraftConfig.mode());
                return;
            }

            IntegratedServer server = (IntegratedServer) (Object) this;

            // Get the LAN port the Minecraft world came up on
            int lanPort = server.getPort();

            LOGGER.info("[PeerCraft P2P] Мир успешно открыт для сети на порту: {}", lanPort);

            // Players connect through our UDP bridge rather than directly over the LAN, but
            // P2PBridge's relay is byte-transparent — the real encrypted Mojang handshake
            // passes through the tunnel untouched. IntegratedServer.initServer() already turns
            // authentication on (setUsesAuthentication(true)) by default when the world starts —
            // publishServer() doesn't touch it, so if the host did NOT allow unlicensed players,
            // there's nothing to do at all, authentication just stays on. If the host explicitly
            // allowed unlicensed players — turn it off, same as before.
            if (PeerCraftHostOptions.allowUnlicensedPlayers) {
                server.setUsesAuthentication(false);
                LOGGER.info("[PeerCraft P2P] Проверка сессии Mojang отключена для хоста PeerCraft (разрешены нелицензионные игроки).");
            } else {
                server.setUsesAuthentication(true);
                LOGGER.info("[PeerCraft P2P] Проверка сессии Mojang включена — только лицензионные игроки смогут подключиться.");
            }

            // 1. Close LocalProxy on the host to free up 25565
            if (P2PBridge.INSTANCE.getProxy() != null) {
                P2PBridge.INSTANCE.getProxy().stop();
                LOGGER.info("[PeerCraft P2P] localProxy закрыт на Хосте.");
            }

            // 2. Pass the LAN port to P2PBridge to start the UDP host — either statically
            // (127.0.0.1/local test), or through the rendezvous server + hole punching
            // for real internet P2P. The decision is the "PeerCraft: play over the internet"
            // checkbox on this same screen (ShareToLanScreenMixin), not a launch flag.
            if (PeerCraftHostOptions.internetPlayRequested) {
                LOGGER.info("[PeerCraft P2P] Через интернет — используем сервер знакомств (макс. игроков: {}), peerHost/peerPort игнорируются.", PeerCraftHostOptions.maxPlayers);
                // Give this world a stable PeerCraft id (for handoff round-trips) and mark that
                // this machine is hosting it now — clears any leftover "handed off" warning flag.
                try {
                    net.peercraft.client.handoff.PeercraftWorldMeta.ensureHosting(
                            net.peercraft.client.handoff.WorldArchiver.worldDir(server));
                } catch (RuntimeException e) {
                    LOGGER.warn("[PeerCraft P2P] Не удалось записать метаданные мира: {}", e.toString());
                }
                ModSyncHostProvider modSyncProvider = null;
                ModSyncMode hostMode = PeerCraftConfig.modSyncHostMode();
                if (hostMode != ModSyncMode.OFF) {
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
                        sendChatMessage(copyableCodeMessage(Component.translatable(prefixKey).getString(), code));
                    }

                    @Override
                    public void onFailed(String reason) {
                        // `reason` is a peercraft.p2p.* translation key from the network layer — resolve it as
                        // a nested Component so the chat line comes out localized, not as a raw key.
                        sendChatMessage(Component.translatable("peercraft.mixin.open_to_lan.failed_prefix", Component.translatable(reason)));
                    }
                }, modSyncProvider);
            } else {
                P2PBridge.INSTANCE.startHost(lanPort);
            }
        }
    }

    // The host closed their world (quit, disconnected, went back to the title screen) —
    // if a hosting attempt through the rendezvous server was never claimed by a joiner,
    // its RendezvousClient would otherwise keep sending keepalive REGISTERs forever, silently
    // recreating the room with a new code on the server every ~10 minutes (see cancelRendezvous).
    @Inject(method = "stopServer", at = @At("HEAD"))
    private void onStopServer(CallbackInfo ci) {
        P2PBridge.INSTANCE.cancelRendezvous();
    }

    // Shown in the public game browser (Phase 7) so a joiner can tell whether their own client
    // can actually connect — vanilla's own network protocol only lets same-version clients talk
    // to each other, hole punching succeeding doesn't change that. WorldVersion.getName()/name()
    // is the same rename as GameProfile.getName()/name() elsewhere in this file's package —
    // see AccountClient.loginLicensed for the identical split.
    private static String currentMinecraftVersion() {
        //? if <1.21.6
        return SharedConstants.getCurrentVersion().getName();
        //? if >=1.21.6
        /*return SharedConstants.getCurrentVersion().name();*/
    }

    // If the host left the world-name field blank (ShareToLanScreenMixin), fall back to the
    // actual Minecraft world/save name instead of listing the public game with no name at all.
    // Truncated to the same bound as the manual field — the save name isn't limited by that
    // EditBox's setMaxLength and could otherwise overflow the wire format's 1-byte string length.
    private static String publicRoomWorldName(IntegratedServer server) {
        String worldName = PeerCraftHostOptions.worldName;
        if (worldName != null && !worldName.isBlank()) {
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

    // P2PBridge/RendezvousClient callbacks are invoked from a background thread — a chat
    // message (client-side only, never sent anywhere) can only be shown from the client thread.
    private static void sendChatMessage(Component message) {
        Minecraft.getInstance().execute(() -> {
            LocalPlayer player = Minecraft.getInstance().player;
            if (player != null) {
                // Player.displayClientMessage(Component, boolean) was replaced by
                // sendSystemMessage(Component) in 26.1 (the actionbar variant is sendOverlayMessage).
                //? if <26.1
                player.displayClientMessage(message, false);
                //? if >=26.1
                /*player.sendSystemMessage(message);*/
            }
        });
    }

    // The room is created once per hosting session, and players read the code off the
    // screen/a screenshot and type it in by hand — copy_to_clipboard avoids typos when
    // entering 6+ characters.
    private static Component copyableCodeMessage(String prefix, String code) {
        // 1.21.5 turned ClickEvent/HoverEvent from concrete Action+value classes into sealed
        // interfaces with typed record variants.
        //? if >=1.21.5 {
        /*ClickEvent clickEvent = new ClickEvent.CopyToClipboard(code);
        HoverEvent hoverEvent = new HoverEvent.ShowText(Component.translatable("peercraft.mixin.open_to_lan.hover_copy"));
        *///?} else {
        ClickEvent clickEvent = new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, code);
        HoverEvent hoverEvent = new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.translatable("peercraft.mixin.open_to_lan.hover_copy"));
        //?}
        MutableComponent codeComponent = Component.literal(code).withStyle(style -> style
                .withColor(ChatFormatting.YELLOW)
                .withUnderlined(true)
                .withClickEvent(clickEvent)
                .withHoverEvent(hoverEvent));
        return Component.literal(prefix).append(codeComponent);
    }
}
