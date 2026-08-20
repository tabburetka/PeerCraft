package net.peercraft.client;

import net.peercraft.config.PeerCraftConfig;

// Решение "публиковать мир через интернет или локально" теперь принимается хостом
// каждый раз заново на экране "Открыть для сети" (флажок), а не один раз при запуске
// через -Dpeercraft.internetPlay. Начальное состояние флажка берётся из конфига —
// это сохраняет -Dpeercraft.internetPlay=true как удобный дефолт для машины, которая
// всегда хостит через интернет, но не заставляет к этому.
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
