package net.peercraft.config;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
class TelemetryConsentTest {
    @TempDir Path dir;
    @Test
    void firstRunCorruptSettingsAndLaunchFlagDoNotAuthorizeSending() throws Exception {
        Path file = dir.resolve("settings.json");
        var settings = PeerCraftSettingsStore.load(file);
        assertFalse(TelemetryConsent.permitted(settings, true, "auto"));
        settings.telemetryEnabled = "true";
        assertFalse(TelemetryConsent.permitted(settings, true, "auto"));
        Files.writeString(file, "not json");
        assertFalse(TelemetryConsent.permitted(PeerCraftSettingsStore.load(file), true, "auto"));
    }
    @Test
    void choiceSurvivesReloadAndResetButCannotOverrideAnOffChoice() {
        var settings = new PeerCraftSettings();
        settings.telemetryNoticeAcknowledged = true;
        settings.telemetryEnabled = "false";
        settings.maxPlayers = "12";
        Path file = dir.resolve("settings.json");
        PeerCraftSettingsStore.save(file, settings);
        var loaded = PeerCraftSettingsStore.load(file);
        assertTrue(loaded.telemetryNoticeAcknowledged);
        assertFalse(TelemetryConsent.permitted(loaded, true, "auto"));
        var reset = TelemetryConsent.defaultsPreservingChoice(loaded);
        assertTrue(reset.telemetryNoticeAcknowledged);
        assertEquals("false", reset.telemetryEnabled);
        assertNull(reset.maxPlayers);
        assertFalse(reset.toOverrideMap().containsKey("_telemetryNoticeAcknowledged"));
        reset.telemetryEnabled = "true";
        assertTrue(TelemetryConsent.permitted(reset, true, "auto"));
        assertFalse(TelemetryConsent.permitted(reset, false, "auto"));
        assertFalse(TelemetryConsent.permitted(reset, true, "disabled"));
    }
}
