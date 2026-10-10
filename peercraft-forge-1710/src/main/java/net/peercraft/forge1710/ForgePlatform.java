package net.peercraft.forge1710;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.ModContainer;
import net.peercraft.platform.services.PeercraftPlatform;
import net.peercraft.platform.services.PlatformMod;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 1.7.10 Forge implementation of the loader seam (twin of the 1.12.2 {@code ForgePlatform}).
 * Registered via {@code META-INF/services/net.peercraft.platform.services.PeercraftPlatform}.
 * Only the FML package prefix differs from the 1.12.2 backport ({@code cpw.mods.fml}).
 *
 * <p>Mod sync is backported here too. {@link #getInstalledMods()} is only used to read
 * PeerCraft's own version (and as a fallback mod list) — mod-sync enumerates the real jar set
 * through {@code ModJarScanner}, not this.
 */
public final class ForgePlatform implements PeercraftPlatform {

    @Override
    public Path getConfigDir() {
        return Loader.instance().getConfigDir().toPath();
    }

    @Override
    public Path getModsDir() {
        return Loader.instance().getConfigDir().toPath().resolveSibling("mods");
    }

    @Override
    public List<PlatformMod> getInstalledMods() {
        List<PlatformMod> out = new ArrayList<PlatformMod>();
        try {
            for (ModContainer mc : Loader.instance().getActiveModList()) {
                File src = mc.getSource();
                Path jar = src != null ? src.toPath() : null;
                // Injected coremod containers (CodeChickenCore, for example) report
                // FML's dummy minecraft.jar. Resolve the container's own class resource
                // so the actual coremod bytes remain mandatory in handoff preflight.
                if ((jar == null || !java.nio.file.Files.isRegularFile(jar))
                        && mc instanceof cpw.mods.fml.common.InjectedModContainer) {
                    ModContainer implementation = ((cpw.mods.fml.common.InjectedModContainer) mc).wrappedContainer;
                    jar = net.peercraft.network.handoff.LegacyModSource.resolve(jar, implementation.getClass());
                }
                out.add(new PlatformMod(mc.getModId(), mc.getVersion(), jar, "both", "", "", false));
            }
        } catch (RuntimeException ignored) {
            // Called too early / loader not ready — an empty list is fine (selfVersion() falls back).
        }
        boolean minecraft = false;
        for (PlatformMod mod : out) if ("minecraft".equalsIgnoreCase(mod.id())) minecraft = true;
        if (!minecraft) out.add(new PlatformMod("minecraft", "1.7.10", null, "both", "", "", false));
        return net.peercraft.network.handoff.NestedModParents.containedDependencies(out);
    }
}
