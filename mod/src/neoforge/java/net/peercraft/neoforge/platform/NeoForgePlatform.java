package net.peercraft.neoforge.platform;

import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforgespi.language.IModInfo;
import net.peercraft.platform.services.PeercraftPlatform;
import net.peercraft.platform.services.PlatformMod;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class NeoForgePlatform implements PeercraftPlatform {

    @Override
    public Path getConfigDir() {
        return FMLPaths.CONFIGDIR.get();
    }

    @Override
    public Path getModsDir() {
        return FMLPaths.MODSDIR.get();
    }

    /** Execution inventory includes loader-reported jar-in-jar parentage. */
    @Override
    public List<PlatformMod> getInstalledMods() {
        List<PlatformMod> out = new ArrayList<>();
        for (IModInfo info : ModList.get().getMods()) {
            // The mod's real jar on disk (a Connector-relocated top-level mod still has one).
            // A null / non-regular-file path means jar-in-jar'd or synthetic -> not shippable.
            Path jar = null;
            var parent = info.getOwningFile().getFile().getDiscoveryAttributes().parent();
            String parentId = parent == null ? "" : parent.getModInfos().stream()
                    .map(IModInfo::getModId).sorted().findFirst().orElse("");
            try {
                Path p = info.getOwningFile().getFile().getFilePath();
                if (p != null && Files.isRegularFile(p) && p.getFileName().toString().endsWith(".jar")) {
                    jar = p.toAbsolutePath().normalize();
                }
            } catch (RuntimeException ignored) {
                // virtual/synthetic mod files have no real path
            }
            out.add(new PlatformMod(
                    info.getModId(),
                    info.getVersion().toString(),
                    jar,
                    "both",
                    info.getModURL().map(Object::toString).orElse(""),
                    "",
                    parent != null || jar == null, parentId));
        }
        return out;
    }
}
