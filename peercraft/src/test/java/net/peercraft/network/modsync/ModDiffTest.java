package net.peercraft.network.modsync;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ModDiffTest {

    private static ModEntry mod(String id, String version) {
        return mod(id, version, ModEntry.Env.BOTH);
    }

    private static ModEntry mod(String id, String version, ModEntry.Env env) {
        byte[] sha = new byte[64];
        sha[0] = (byte) id.hashCode();
        return new ModEntry(id, version, 100, sha, id + "-" + version + ".jar", env, "", "");
    }

    @Test
    void missingOnlyWhenJoinerLacksTheIdEntirely() {
        List<ModEntry> host = List.of(mod("aaa", "1.0"), mod("bbb", "2.0"), mod("ccc", "3.0"));
        // Joiner has aaa and bbb (bbb at a different version) — only ccc is genuinely absent.
        Map<String, String> joiner = Map.of("aaa", "1.0", "bbb", "1.9");

        List<ModEntry> missing = ModDiff.missing(joiner, host);

        assertEquals(List.of("ccc"), missing.stream().map(ModEntry::id).toList());
    }

    @Test
    void onlyTheLoaderAndPeerCraftItselfAreExcluded() {
        // "Clone the whole modpack" model: a fresh joiner (empty mod list) gets EVERYTHING the
        // host has except the loader/game and PeerCraft — Fabric API, Kotlin For Forge,
        // Connector, client-only mods all included.
        List<ModEntry> host = List.of(
                mod("peercraft", "9.9"),
                mod("minecraft", "1.21.1"),
                mod("neoforge", "21.1"),
                mod("fabricloader", "0.16"),
                mod("fabric-api", "0.1"),
                mod("kotlinforforge", "5.12.0"),
                mod("connector", "1.0"),
                mod("clientthing", "1.0", ModEntry.Env.CLIENT),
                mod("realmod", "1.0"));
        Map<String, String> joiner = Map.of();

        List<ModEntry> missing = ModDiff.missing(joiner, host);

        assertEquals(
                List.of("fabric-api", "kotlinforforge", "connector", "clientthing", "realmod"),
                missing.stream().map(ModEntry::id).toList());
    }

    @Test
    void sanitizeDropsUnsafeNamesBadHashesAndExcludedIds() {
        ModEntry ok = mod("good", "1.0");
        ModEntry badName = new ModEntry("evil", "1", 10, new byte[64], "../evil.jar", ModEntry.Env.BOTH, "", "");
        ModEntry noHash = new ModEntry("nohash", "1", 10, new byte[0], "nohash.jar", ModEntry.Env.BOTH, "", "");
        ModEntry zeroSize = new ModEntry("zero", "1", 0, new byte[64], "zero.jar", ModEntry.Env.BOTH, "", "");
        ModEntry excluded = mod("peercraft", "1");

        List<ModEntry> clean = ModDiff.sanitize(List.of(ok, badName, noHash, zeroSize, excluded));

        assertEquals(List.of("good"), clean.stream().map(ModEntry::id).toList());
    }
}
