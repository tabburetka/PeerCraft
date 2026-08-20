package net.peercraft.event;

// Server lifecycle events from the Fabric API
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

// The Minecraft server class itself
import net.minecraft.server.MinecraftServer;

// Logger for nicely formatted console output
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
