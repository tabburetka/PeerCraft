package net.peercraft.client;

import net.peercraft.config.PeerCraftConfig;

// The "publish the world over the internet or just locally" decision is now made by the
// host fresh every time on the "Open to LAN" screen (a checkbox), rather than once at
// launch via -Dpeercraft.internetPlay. The checkbox's initial state is taken from the
// config — this keeps -Dpeercraft.internetPlay=true as a convenient default for a machine
// that always hosts over the internet, without forcing that on everyone else.
public final class PeerCraftHostOptions {
    public static volatile boolean internetPlayRequested = PeerCraftConfig.internetPlay();
    public static volatile int maxPlayers = PeerCraftConfig.maxPlayers();
    // true (default) preserves the mod's original behavior — both licensed and unlicensed
    // ("pirate") clients can join. false leaves the IntegratedServer's Mojang authentication
    // enabled (it's already on by default at world startup, see IntegratedServer.initServer —
    // OpenToLanMixin previously always turned it off unconditionally) so only players who can
    // actually pass the real Mojang session handshake get in.
    public static volatile boolean allowUnlicensedPlayers = true;

    private PeerCraftHostOptions() {
    }
}
