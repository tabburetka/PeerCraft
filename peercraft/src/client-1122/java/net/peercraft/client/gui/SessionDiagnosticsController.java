package net.peercraft.client.gui;

public final class SessionDiagnosticsController {
    private static long nextSample;
    public static void tick() {
        long now = System.nanoTime(); if (now < nextSample) return; nextSample = now + 1_000_000_000L;
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getMinecraft();
        java.util.List<net.peercraft.network.diagnostics.TransferRegistry.Entry> transfers =
                net.peercraft.network.diagnostics.TransferRegistry.snapshot();
        long remaining=0; java.util.List<String> files=new java.util.ArrayList<String>();
        for (net.peercraft.network.diagnostics.TransferRegistry.Entry e : transfers) {
            remaining+=e.remaining; if (files.size()<32) files.add(e.name);
        }
        net.peercraft.network.p2p.P2PBridge.INSTANCE.sessionMonitor().publish(mc.player == null ? "" : mc.player.getName(), tickMillis(mc),
                transfers.size(), remaining, files.toArray(new String[0]));
    }
    private static double tickMillis(net.minecraft.client.Minecraft mc) {
        net.minecraft.server.MinecraftServer server = mc.getIntegratedServer();
        if (server == null) return -1;
        long sum = 0; int count = 0;
        for (long tick : server.tickTimeArray) if (tick > 0) { sum += tick; count++; }
        return count == 0 ? -1 : sum / (double) count / 1_000_000.0;
    }

}
