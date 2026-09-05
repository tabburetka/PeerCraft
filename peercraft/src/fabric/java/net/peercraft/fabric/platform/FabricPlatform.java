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
        // Plain if/else rather than a switch expression: this file is also compiled at
        // --release 8 for the 1.16.5 backport (src/client-1165), where switch expressions
        // don't exist. Behaviour is identical on every other (Java 21) Fabric target.
        if (env == ModEnvironment.CLIENT) {
            return "client";
        }
        if (env == ModEnvironment.SERVER) {
            return "server";
        }
        return "both";
    }

    private static String contact(ModMetadata md, String key) {
        return md.getContact().get(key).orElse("");
    }
}
