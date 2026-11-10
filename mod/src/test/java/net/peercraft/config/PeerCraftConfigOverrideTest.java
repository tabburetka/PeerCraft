package net.peercraft.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PeerCraftConfigOverrideTest {

    @AfterEach
    void reset() {
        PeerCraftConfig.applyOverrides(null);
        System.clearProperty("peercraft.maxPlayers");
        System.clearProperty("peercraft.modSync.host");
        System.clearProperty("peercraft.modSync");
        System.clearProperty("peercraft.rendezvousHost");
    }

    @Test
    void baselineValueReflectsALaunchFlagAndIgnoresOverrides() {
        // Nothing set -> "" (the settings screen falls back to its own hardcoded default).
        assertEquals("", PeerCraftConfig.baselineValue("rendezvousHost"));

        Map<String, String> m = new HashMap<>();
        m.put("rendezvousHost", "10.0.0.9");
        PeerCraftConfig.applyOverrides(m);
        // baselineValue must NOT see the settings.json override layer...
        assertEquals("", PeerCraftConfig.baselineValue("rendezvousHost"));
        // ...but a real launch flag does show through (so the screen can display it).
        System.setProperty("peercraft.rendezvousHost", "1.2.3.4");
        assertEquals("1.2.3.4", PeerCraftConfig.baselineValue("rendezvousHost"));
        assertEquals("1.2.3.4", PeerCraftConfig.rendezvousHost());
    }

    @Test
    void overrideBeatsTheBuiltInDefault() {
        assertEquals(4, PeerCraftConfig.maxPlayers());
        Map<String, String> m = new HashMap<>();
        m.put("maxPlayers", "7");
        PeerCraftConfig.applyOverrides(m);
        assertEquals(7, PeerCraftConfig.maxPlayers());
    }

    @Test
    void launchFlagBeatsTheOverride() {
        System.setProperty("peercraft.maxPlayers", "9");
        Map<String, String> m = new HashMap<>();
        m.put("maxPlayers", "7");
        PeerCraftConfig.applyOverrides(m);
        assertEquals(9, PeerCraftConfig.maxPlayers());
    }

    @Test
    void modSyncModesDefaultToAll() {
        assertEquals(ModSyncMode.ALL, PeerCraftConfig.modSyncHostMode());
        assertEquals(ModSyncMode.ALL, PeerCraftConfig.modSyncClientMode());
    }

    @Test
    void legacyModSyncFalseMakesBothModesOff() {
        System.setProperty("peercraft.modSync", "false");
        assertEquals(ModSyncMode.OFF, PeerCraftConfig.modSyncHostMode());
        assertEquals(ModSyncMode.OFF, PeerCraftConfig.modSyncClientMode());
    }

    @Test
    void overrideSelectsHostModeIndependently() {
        Map<String, String> m = new HashMap<>();
        m.put("modSync.host", "required");
        PeerCraftConfig.applyOverrides(m);
        assertEquals(ModSyncMode.REQUIRED, PeerCraftConfig.modSyncHostMode());
        assertEquals(ModSyncMode.ALL, PeerCraftConfig.modSyncClientMode());
    }

    @Test
    void unknownModeTokenFallsBack() {
        assertEquals(ModSyncMode.ALL, ModSyncMode.fromKey("nonsense", ModSyncMode.ALL));
        assertEquals(ModSyncMode.OFF, ModSyncMode.fromKey(null, ModSyncMode.OFF));
        assertEquals(ModSyncMode.REQUIRED, ModSyncMode.fromKey("  REQUIRED ", ModSyncMode.OFF));
    }
}
