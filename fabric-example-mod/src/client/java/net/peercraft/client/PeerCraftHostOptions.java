package net.peercraft.client;

import net.peercraft.config.PeerCraftConfig;

// Решение "публиковать мир через интернет или локально" теперь принимается хостом
// каждый раз заново на экране "Открыть для сети" (флажок), а не один раз при запуске
// через -Dpeercraft.internetPlay. Начальное состояние флажка берётся из конфига —
// это сохраняет -Dpeercraft.internetPlay=true как удобный дефолт для машины, которая
// всегда хостит через интернет, но не заставляет к этому.
public final class PeerCraftHostOptions {
    public static volatile boolean internetPlayRequested = PeerCraftConfig.internetPlay();

    private PeerCraftHostOptions() {
    }
}
