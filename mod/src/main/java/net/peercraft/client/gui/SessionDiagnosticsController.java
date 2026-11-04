package net.peercraft.client.gui;

public final class SessionDiagnosticsController {
    private static long nextSample;
    public static void tick() {
        long now = System.nanoTime(); if (now < nextSample) return; nextSample = now + 1_000_000_000L;
        net.minecraft.client.Minecraft minecraft = net.minecraft.client.Minecraft.getInstance();
        java.util.List<net.peercraft.network.diagnostics.TransferRegistry.Entry> transfers =
                net.peercraft.network.diagnostics.TransferRegistry.snapshot();
        long remaining=0; java.util.List<String> files=new java.util.ArrayList<String>();
        for (net.peercraft.network.diagnostics.TransferRegistry.Entry e : transfers) {
            remaining+=e.remaining; if (files.size()<32) files.add(e.name);
        }
        net.peercraft.network.p2p.P2PBridge.INSTANCE.sessionMonitor().publish(minecraft.player == null ? "" : minecraft.player.getName().getString(), tickMillis(minecraft),
                transfers.size(), remaining, files.toArray(new String[0]));
    }
    private static double tickMillis(net.minecraft.client.Minecraft minecraft) {
        var server = minecraft.getSingleplayerServer();
        return server == null ? -1 : server.getAverageTickTimeNanos() / 1_000_000.0;
    }

}
