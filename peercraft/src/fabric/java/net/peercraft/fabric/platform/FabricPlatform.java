package net.peercraft.fabric.platform;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModEnvironment;
import net.fabricmc.loader.api.metadata.ModMetadata;
import net.peercraft.platform.services.PeercraftPlatform;
import net.peercraft.platform.services.PlatformMod;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class FabricPlatform implements PeercraftPlatform {

    @Override
    public Path getConfigDir() {
        return FabricLoader.getInstance().getConfigDir();
    }

    @Override
    public Path getModsDir() {
        return FabricLoader.getInstance().getGameDir().resolve("mods");
    }

    @Override
    public List<PlatformMod> getInstalledMods() {
        List<PlatformMod> out = new ArrayList<>();
        for (ModContainer mc : FabricLoader.getInstance().getAllMods()) {
            ModMetadata md = mc.getMetadata();
            boolean nested = mc.getContainingMod().isPresent();
            Path jar = nested ? null : standaloneJar(mc);
            out.add(new PlatformMod(
                    md.getId(),
                    md.getVersion().getFriendlyString(),
                    jar,
                    envString(md.getEnvironment()),
                    contact(md, "homepage"),
                    contact(md, "sources"),
                    nested || jar == null));
        }
        return out;
    }

    /** The mod's own jar when it's a plain file mod, else null (jar-in-jar'd / dev classpath). */
    private static Path standaloneJar(ModContainer mc) {
        try {
            for (Path p : mc.getOrigin().getPaths()) {
                if (Files.isRegularFile(p) && p.getFileName().toString().endsWith(".jar")) {
                    return p.toAbsolutePath().normalize();
                }
            }
        } catch (RuntimeException ignored) {
            // getPaths() throws when the origin isn't a straightforward path (nested jar, etc.)
        }
        return null;
    }

    private static String envString(ModEnvironment env) {
        return switch (env) {
            case CLIENT -> "client";
            case SERVER -> "server";
            default -> "both";
        };
    }

    private static String contact(ModMetadata md, String key) {
        return md.getContact().get(key).orElse("");
    }
}
