package net.peercraft.event;

// События жизненного цикла сервера из Fabric API
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

// Класс самого сервера Minecraft
import net.minecraft.server.MinecraftServer;

// Логгер для красивого вывода сообщений в консоль
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ServerLifecycleHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger("peercraft");
    public static void registerEvent(){
        ServerLifecycleEvents.SERVER_STARTED.register(server ->{
            LOGGER.info("[PeerCraft] одиночная игра запущенна");
        });
    }

}
