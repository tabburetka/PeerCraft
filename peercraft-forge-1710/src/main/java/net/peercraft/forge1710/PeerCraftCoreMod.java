package net.peercraft.forge1710;

import cpw.mods.fml.relauncher.IFMLLoadingPlugin;
import io.github.tox1cozz.mixinbooterlegacy.IEarlyMixinLoader;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Coremod whose only job is to hand PeerCraft's mixin config to the 1.7.10 Mixin loader early
 * enough to mix into vanilla classes. Twin of {@code PeerCraftCoreMod} in peercraft-forge-1122,
 * which implemented {@code zone.rong.mixinbooter.IEarlyMixinLoader} (a MixinBooter type). On
 * 1.7.10 the equivalent hook is {@link IEarlyMixinLoader} from GTNHMixins, which UniMixins
 * bundles. The manifest {@code MixinConfigs} attribute only works for a real jar in
 * {@code mods/}; this class covers both the dev {@code runClient} (loaded via
 * {@code -Dfml.coreMods.load=...}, see build.gradle) and production (manifest
 * {@code FMLCorePlugin}).
 *
 * <p>UniMixins 0.3.1 bundles MixinBooterLegacy, whose {@code IEarlyMixinLoader}
 * ({@code io.github.tox1cozz.mixinbooterlegacy}) is the interface used here — one abstract
 * {@code getMixinConfigs()} plus two defaulted hooks. Both our mixins are server-side
 * (IntegratedServer / NetHandlerLoginServer), so a "late" phase ({@code ILateMixinLoader})
 * would also work if the early one ever proves too early.
 */
@IFMLLoadingPlugin.MCVersion("1.7.10")
@IFMLLoadingPlugin.Name("PeerCraft")
@IFMLLoadingPlugin.SortingIndex(1001)
public class PeerCraftCoreMod implements IFMLLoadingPlugin, IEarlyMixinLoader {

    @Override
    public List<String> getMixinConfigs() {
        return Collections.singletonList("peercraft.forge1710.mixins.json");
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
