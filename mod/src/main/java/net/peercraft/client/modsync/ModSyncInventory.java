package net.peercraft.client.modsync;

import net.peercraft.network.modsync.*;
import net.peercraft.platform.Services;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** One immutable physical-file snapshot, retained from HELLO through confirmation and staging. */
public final class ModSyncInventory {
    private final Map<String, List<ModJarScanner.ScannedJar>> byId = new LinkedHashMap<String, List<ModJarScanner.ScannedJar>>();
    private final Map<String, String> hashes = new HashMap<String, String>();
    public static final class Issue {
        public final String id, reason;
        public Issue(String id, String reason) { this.id = id; this.reason = reason; }
    }
    public final List<Issue> issues = new ArrayList<Issue>();
    public static ModSyncInventory capture() throws IOException {
        return new ModSyncInventory(ModJarScanner.scan(Services.PLATFORM.getModsDir(), InstalledModScanner.allInstalled()));
    }
    public ModSyncInventory(List<ModJarScanner.ScannedJar> jars) throws IOException {
        for (ModJarScanner.ScannedJar jar : jars) {
            List<ModJarScanner.ScannedJar> group = byId.get(jar.id());
            if (group == null) { group = new ArrayList<ModJarScanner.ScannedJar>(); byId.put(jar.id(), group); }
            group.add(jar);
            hashes.put(jar.fileName(), hex(ModSyncFilesystem.hashFile(jar.jarPath())));
        }
    }
    public Map<String, String> fingerprints() { return Collections.unmodifiableMap(new TreeMap<String, String>(hashes)); }

    public List<ModSyncProtocol.ModRef> refs() {
        List<ModSyncProtocol.ModRef> out = new ArrayList<ModSyncProtocol.ModRef>();
        for (List<ModJarScanner.ScannedJar> group : byId.values()) out.add(new ModSyncProtocol.ModRef(group.get(0).id(), group.get(0).version()));
        return out;
    }
    public List<ModEntry> candidates(List<ModEntry> offered, boolean replacements) {
        List<ModEntry> out = new ArrayList<ModEntry>();
        Map<String, Integer> ids = new HashMap<String, Integer>();
        Map<String, Integer> names = new HashMap<String, Integer>();
        for (ModEntry e : offered) {
            ids.put(e.id(), ids.containsKey(e.id()) ? ids.get(e.id()) + 1 : 1);
            names.put(e.fileName(), names.containsKey(e.fileName()) ? names.get(e.fileName()) + 1 : 1);
        }
        for (ModEntry e : ModDiff.sanitize(offered)) {
            List<ModJarScanner.ScannedJar> local = byId.get(e.id());
            String issue = null;
            if (ids.get(e.id()) != 1 || names.get(e.fileName()) != 1 || local != null && (local.size() != 1 || !local.get(0).replaceable())) issue = "ambiguous";
            else if (local != null && !replacements) issue = "incompatible";
            else if (local != null && (!ModDiff.knownVersion(e.version()) || !ModDiff.knownVersion(local.get(0).version()))) issue = "unknown";
            else if (local != null && e.version().equals(local.get(0).version())) continue;
            else {
                // A target filename belonging to another mod must never be overwritten.
                for (List<ModJarScanner.ScannedJar> group : byId.values()) for (ModJarScanner.ScannedJar jar : group)
                    if (jar.fileName().equalsIgnoreCase(e.fileName()) && !jar.id().equals(e.id())) issue = "collision";
            }
            if (issue == null) out.add(e); else issues.add(new Issue(e.id(), issue));
        }
        return out;
    }
    /**
     * The local jars behind {@code extraIds} (mods the host reported it doesn't have). Only a
     * single, cleanly identified jar per id is ever removed; anything else becomes an issue.
     * Env is the jar's own metadata — the caller may refine it before applying the mode.
     */
    public List<ModSyncPlan.Removal> removals(List<String> extraIds) {
        List<ModSyncPlan.Removal> out = new ArrayList<ModSyncPlan.Removal>();
        for (String id : extraIds) {
            List<ModJarScanner.ScannedJar> local = byId.get(id);
            if (local == null || ModSyncFilter.isExcluded(id, "both", false, local.get(0).fileName())) continue;
            if (local.size() != 1 || !local.get(0).replaceable()) { issues.add(new Issue(id, "remove_ambiguous")); continue; }
            ModJarScanner.ScannedJar jar = local.get(0);
            out.add(new ModSyncPlan.Removal(id, jar.fileName(), jar.version(), hashes.get(jar.fileName()), jar.env()));
        }
        return out;
    }

    public ModSyncPlan annotate(ModSyncPlan plan) {
        List<ModSyncPlan.PlannedMod> out = new ArrayList<ModSyncPlan.PlannedMod>();
        for (ModSyncPlan.PlannedMod mod : plan.mods()) {
            List<ModJarScanner.ScannedJar> local = byId.get(mod.entry().id());
            if (local != null) {
                ModJarScanner.ScannedJar jar = local.get(0);
                mod = mod.withReplacement(new ModSyncPlan.Replacement(jar.fileName(), jar.version(), hashes.get(jar.fileName())));
            }
            out.add(mod);
        }
        return ModSyncPlan.of(out);
    }
    public static String hex(byte[] bytes) {
        StringBuilder s = new StringBuilder();
        for (byte b : bytes) s.append(Character.forDigit((b >> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
        return s.toString();
    }
}
