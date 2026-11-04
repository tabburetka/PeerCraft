package net.peercraft.uitest;

import com.mojang.authlib.GameProfile;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.resources.PlayerSkin;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Isolated client fixture: no real session, world, account, or launcher data required. */
public final class OfficialSkinProbe implements net.fabricmc.api.ClientModInitializer {
    private CompletableFuture<PlayerSkin> loading;
    private final long deadline = System.currentTimeMillis() + 90000;
    private boolean finished;
    public void onInitializeClient() {
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (finished) return;
            try {
                if (System.currentTimeMillis() > deadline) throw new IllegalStateException("Skin probe timeout");
                if (!(mc.screen instanceof TitleScreen) || mc.getOverlay() != null) return;
                if (loading == null) {
                    GameProfile profile = new GameProfile(UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5"), "Notch");
                    long start = System.nanoTime();
                    loading = mc.getSkinManager().getOrLoad(profile);
                    if (System.nanoTime() - start > 100_000_000L) throw new IllegalStateException("Skin lookup blocked render thread");
                    return;
                }
                if (!loading.isDone()) return;
                PlayerSkin skin = loading.join();
                if (!skin.secure() || !skin.texture().getPath().startsWith("skins/"))
                    throw new IllegalStateException("Expected downloaded signed official skin, got " + skin);
                System.out.println("OFFICIAL_SKIN_PROBE_PASSED texture=" + skin.texture() + " secure=" + skin.secure());
                finished = true; mc.stop();
            } catch (Throwable failure) {
                failure.printStackTrace(); System.out.println("OFFICIAL_SKIN_PROBE_FAILED");
                finished = true; mc.stop();
            }
        });
    }
}
