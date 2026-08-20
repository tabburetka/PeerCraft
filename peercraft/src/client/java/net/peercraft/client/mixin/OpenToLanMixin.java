package net.peercraft.client.mixin;

import net.minecraft.ChatFormatting;
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
import net.peercraft.config.PeerCraftConfig;
import net.peercraft.network.p2p.P2PBridge;

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
                P2PBridge.INSTANCE.startHostViaRendezvous(lanPort, PeerCraftHostOptions.maxPlayers, new P2PBridge.HostListener() {
                    @Override
                    public void onRoomCreated(String code, boolean changed) {
                        String prefixKey = changed
                                ? "peercraft.mixin.open_to_lan.room_code_changed_prefix"
                                : "peercraft.mixin.open_to_lan.room_code_prefix";
                        sendChatMessage(copyableCodeMessage(Component.translatable(prefixKey).getString(), code));
                    }

                    @Override
                    public void onFailed(String reason) {
                        // `reason` comes from the rendezvous/network layer, not authored here — can't localize it.
                        sendChatMessage(Component.translatable("peercraft.mixin.open_to_lan.failed_prefix", reason));
                    }
                });
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

    // P2PBridge/RendezvousClient callbacks are invoked from a background thread — a chat
    // message (client-side only, never sent anywhere) can only be shown from the client thread.
    private static void sendChatMessage(Component message) {
        Minecraft.getInstance().execute(() -> {
            LocalPlayer player = Minecraft.getInstance().player;
            if (player != null) {
                player.displayClientMessage(message, false);
            }
        });
    }

    // The room is created once per hosting session, and players read the code off the
    // screen/a screenshot and type it in by hand — copy_to_clipboard avoids typos when
    // entering 6+ characters.
    private static Component copyableCodeMessage(String prefix, String code) {
        MutableComponent codeComponent = Component.literal(code).withStyle(style -> style
                .withColor(ChatFormatting.YELLOW)
                .withUnderlined(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, code))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.translatable("peercraft.mixin.open_to_lan.hover_copy"))));
        return Component.literal(prefix).append(codeComponent);
    }
}