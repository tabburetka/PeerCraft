package net.peercraft.fabric;

import net.fabricmc.api.ClientModInitializer;
import net.peercraft.client.PeerCraftClientCommon;

public final class PeerCraftClientFabric implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        PeerCraftClientCommon.initClient();
    }
}
