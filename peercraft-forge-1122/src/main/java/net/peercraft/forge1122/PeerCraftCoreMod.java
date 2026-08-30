package net.peercraft.forge1122;

import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin;
import zone.rong.mixinbooter.IEarlyMixinLoader;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Coremod whose only job is to hand PeerCraft's mixin config to MixinBooter via
 * {@link IEarlyMixinLoader}. The manifest {@code MixinConfigs} attribute only works for a real
 * jar in {@code mods/}; this works in the dev {@code runClient} too (loaded via
 * {@code -Dfml.coreMods.load=...}, see build.gradle) and in production (manifest
 * {@code FMLCorePlugin}).
 */
@IFMLLoadingPlugin.MCVersion("1.12.2")
@IFMLLoadingPlugin.Name("PeerCraft")
@IFMLLoadingPlugin.SortingIndex(1001)
public class PeerCraftCoreMod implements IFMLLoadingPlugin, IEarlyMixinLoader {

    @Override
    public List<String> getMixinConfigs() {
        return Collections.singletonList("peercraft.forge.mixins.json");
    }

    @Override
    public String[] getASMTransformerClass() {
        return new String[0];
    }

    @Override
    public String getModContainerClass() {
        return null;
    }

    @Override
    public String getSetupClass() {
        return null;
    }

    @Override
    public void injectData(Map<String, Object> data) {
    }

    @Override
    public String getAccessTransformerClass() {
        return null;
    }
}
