package net.peercraft.mixin;

import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.GameMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(IntegratedServer.class)
public abstract class OpenToLanMixin {

    private static final Logger LOGGER = LoggerFactory.getLogger("peercraft");

    @Inject(method = "openToLan", at = @At("RETURN"))
    private void onOpenToLan(GameMode gameMode, boolean cheatsAllowed, int port, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValue()) {
            IntegratedServer server = (IntegratedServer) (Object) this;

            // Получаем реальный порт сервера
            int lanPort = server.getServerPort();

            LOGGER.info("[PeerCraft P2P] Ура! Мир успешно открыт для сети на порту: {}", lanPort);
        }
    }
}