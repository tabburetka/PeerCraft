package net.peercraft.config;

import com.google.gson.annotations.SerializedName;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The in-game-editable mirror of {@link PeerCraftConfig}'s launch flags, persisted to
 * {@code config/peercraft/settings.json} by {@link PeerCraftSettingsStore} and fed back into
 * {@code PeerCraftConfig} as an override layer at client init (below {@code -Dpeercraft.*} /
 * {@code PEERCRAFT_*}, above the baked defaults).
 *
 * <p>Every flag field is a {@code String} and defaults to {@code null} = "not set by the
 * player, fall through to the launch flag / built-in default". {@link #toOverrideMap()} emits
 * only the fields that actually have a value, keyed by the same short keys {@code PeerCraftConfig}
 * resolves ({@code "mode"}, {@code "proxyPort"}, {@code "modSync.host"}, …).
 *
 * <p>{@link #showDeveloperSection} is UI state, not a flag — it is never part of the override map.
 *
 * <p>Java-8 clean, no {@code net.minecraft} imports: synced verbatim into the Forge backports.
 */
public final class PeerCraftSettings {

    @SerializedName("telemetry.enabled")
    public String telemetryEnabled;

    /** The first-run choice is separate from a launch flag: silence never grants consent. */
    @SerializedName("_telemetryNoticeAcknowledged")
    public boolean telemetryNoticeAcknowledged;

    @SerializedName("privacy.approveStrangers")
    public String approveStrangers;

    @SerializedName("updateCheck.enabled")
    public String updateCheckEnabled;

    /** The release whose title-screen notice the player closed; never part of {@link #toOverrideMap()}. */
    @SerializedName("_updateNoticeDismissedVersion")
    public String updateNoticeDismissedVersion;

    // ---- normal section -------------------------------------------------------------------
    @SerializedName("modSync.client")
    public String modSyncClient;
    @SerializedName("modSync.host")
    public String modSyncHost;
    @SerializedName("modSync.removeExtra")
    public String modSyncRemoveExtra;
    @SerializedName("modSync.reofferDeclined")
    public String modSyncReofferDeclined;
    @SerializedName("modSync.maxTotalMb")
    public String modSyncMaxTotalMb;
    @SerializedName("modSync.maxModMb")
    public String modSyncMaxModMb;
    @SerializedName("internetPlay")
    public String internetPlay;
    @SerializedName("maxPlayers")
    public String maxPlayers;
    @SerializedName("handoff")
    public String handoff;
    @SerializedName("handoff.declineSuccessor")
    public String handoffDeclineSuccessor;
    @SerializedName("handoff.confirmBeforeOffer")
    public String handoffConfirmBeforeOffer;
    @SerializedName("handoff.chatNotify")
    public String handoffChatNotify;

    // ---- developer section --------------------------------------------------------------
    @SerializedName("mode")
    public String mode;
    @SerializedName("modSync.autoAccept")
    public String modSyncAutoAccept;
    @SerializedName("rendezvousHost")
    public String rendezvousHost;
    @SerializedName("rendezvousPort")
    public String rendezvousPort;
    @SerializedName("proxyPort")
    public String proxyPort;
    @SerializedName("clientUdpPort")
    public String clientUdpPort;
    @SerializedName("hostUdpPort")
    public String hostUdpPort;
    @SerializedName("peerHost")
    public String peerHost;
    @SerializedName("peerPort")
    public String peerPort;

    /**
     * Code of the player's own peercraft-relay ({@code pcrelay://host:port#secret}). It holds a
     * secret, so it is never a launch flag and stays out of {@link #FLAG_KEYS}.
     */
    @SerializedName("relay.ownCode")
    public String ownRelayCode;

    // ---- UI state (not a flag) --------------------------------------------------------
    @SerializedName("_showDeveloper")
    public boolean showDeveloperSection;

    /**
     * One-time acknowledgement that mod sync installs code chosen by the host. Set true the
     * first time the player clears {@code ModSyncSecurityNoticeScreen}; consent state, never
     * part of {@link #toOverrideMap()}.
     */
    @SerializedName("_modSyncTrustAcknowledged")
    public boolean modSyncTrustAcknowledged;

    /** Config-key ↔ field, in screen order. Used by both {@link #toOverrideMap()} and the screen. */
    public static final String[] FLAG_KEYS = {
            "privacy.approveStrangers", "telemetry.enabled", "updateCheck.enabled", "modSync.client", "modSync.host", "modSync.removeExtra", "modSync.reofferDeclined",
            "modSync.maxTotalMb", "modSync.maxModMb", "internetPlay", "maxPlayers",
            "handoff", "handoff.declineSuccessor", "handoff.confirmBeforeOffer", "handoff.chatNotify",
            "mode", "modSync.autoAccept", "rendezvousHost", "rendezvousPort",
            "proxyPort", "clientUdpPort", "hostUdpPort", "peerHost", "peerPort",
    };

    public String get(String key) {
        switch (key) {
            case "telemetry.enabled": return telemetryEnabled;
            case "updateCheck.enabled": return updateCheckEnabled;
            case "modSync.client": return modSyncClient;
            case "modSync.host": return modSyncHost;
            case "modSync.removeExtra": return modSyncRemoveExtra;
            case "modSync.reofferDeclined": return modSyncReofferDeclined;
            case "modSync.maxTotalMb": return modSyncMaxTotalMb;
            case "modSync.maxModMb": return modSyncMaxModMb;
            case "internetPlay": return internetPlay;
            case "maxPlayers": return maxPlayers;
            case "handoff": return handoff;
            case "handoff.declineSuccessor": return handoffDeclineSuccessor;
            case "handoff.confirmBeforeOffer": return handoffConfirmBeforeOffer;
            case "privacy.approveStrangers": return approveStrangers;
            case "handoff.chatNotify": return handoffChatNotify;
            case "mode": return mode;
            case "modSync.autoAccept": return modSyncAutoAccept;
            case "rendezvousHost": return rendezvousHost;
            case "rendezvousPort": return rendezvousPort;
            case "proxyPort": return proxyPort;
            case "clientUdpPort": return clientUdpPort;
            case "hostUdpPort": return hostUdpPort;
            case "peerHost": return peerHost;
            case "peerPort": return peerPort;
            case "relay.ownCode": return ownRelayCode;
            default: return null;
        }
    }

    public void set(String key, String value) {
        String v = (value == null || value.trim().isEmpty()) ? null : value.trim();
        switch (key) {
            case "telemetry.enabled": telemetryEnabled = v; break;
            case "updateCheck.enabled": updateCheckEnabled = v; break;
            case "modSync.client": modSyncClient = v; break;
            case "modSync.host": modSyncHost = v; break;
            case "modSync.removeExtra": modSyncRemoveExtra = v; break;
            case "modSync.reofferDeclined": modSyncReofferDeclined = v; break;
            case "modSync.maxTotalMb": modSyncMaxTotalMb = v; break;
            case "modSync.maxModMb": modSyncMaxModMb = v; break;
            case "internetPlay": internetPlay = v; break;
            case "maxPlayers": maxPlayers = v; break;
            case "handoff": handoff = v; break;
            case "handoff.declineSuccessor": handoffDeclineSuccessor = v; break;
            case "handoff.confirmBeforeOffer": handoffConfirmBeforeOffer = v; break;
            case "privacy.approveStrangers": approveStrangers = v; break;
            case "handoff.chatNotify": handoffChatNotify = v; break;
            case "mode": mode = v; break;
            case "modSync.autoAccept": modSyncAutoAccept = v; break;
            case "rendezvousHost": rendezvousHost = v; break;
            case "rendezvousPort": rendezvousPort = v; break;
            case "proxyPort": proxyPort = v; break;
            case "clientUdpPort": clientUdpPort = v; break;
            case "hostUdpPort": hostUdpPort = v; break;
            case "peerHost": peerHost = v; break;
            case "peerPort": peerPort = v; break;
            case "relay.ownCode": ownRelayCode = v; break;
            default: break;
        }
    }

    /** Only the flags the player actually set, keyed as {@code PeerCraftConfig} expects. */
    public Map<String, String> toOverrideMap() {
        Map<String, String> out = new LinkedHashMap<String, String>();
        for (String key : FLAG_KEYS) {
            String v = get(key);
            if (v != null && !v.trim().isEmpty()) {
                out.put(key, v.trim());
            }
        }
        if (ownRelayCode != null && !ownRelayCode.trim().isEmpty()) out.put("relay.ownCode", ownRelayCode.trim());
        return out;
    }
}
