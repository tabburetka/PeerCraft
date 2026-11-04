package net.peercraft.client.update;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ModVersionTest {
    private static ModVersion v(String text) {
        ModVersion parsed = ModVersion.parse(text);
        assertNotNull(parsed, text);
        return parsed;
    }

    @Test
    void ordersReleasesAndPrereleasesAsInVersioningDoc() {
        String[] ascending = {"3.4.2", "3.4.3", "3.5.0-beta.1", "3.5.0-beta.2", "3.5.0-beta.10",
                "3.5.0-rc.1", "3.5.0", "3.9.9", "3.10.0", "4.0.0"};
        for (int i = 1; i < ascending.length; i++) {
            assertTrue(v(ascending[i - 1]).compareTo(v(ascending[i])) < 0, ascending[i - 1] + " < " + ascending[i]);
        }
        assertEquals(v("3.4.3"), v("v3.4.3"));
        assertTrue(v("3.5.0-beta").compareTo(v("3.5.0-beta.1")) < 0);
        assertTrue(v("3.5.0-beta.1").isPrerelease());
        assertFalse(v("3.5.0").isPrerelease());
    }

    @Test
    void rejectsAnythingPeerCraftWouldNotPublish() {
        for (String bad : new String[] {null, "", "3.4", "3.4.3.1", "3.x.0", "3.4.3-alpha.1", "3.4.3-beta.x", "${version}"}) {
            assertNull(ModVersion.parse(bad), String.valueOf(bad));
        }
    }

    @Test
    void stablePlayersOnlySeeStableReleases() {
        assertNull(UpdateChecker.pick(v("3.4.3"), v("3.4.3"), v("3.5.0-beta.1")));
        assertEquals(v("3.5.0"), UpdateChecker.pick(v("3.4.3"), v("3.5.0"), v("3.6.0-beta.1")));
        assertNull(UpdateChecker.pick(v("3.5.0"), v("3.4.3"), null));
    }

    @Test
    void prereleasePlayersSeeNewerBetasAndTheFinalRelease() {
        assertEquals(v("3.5.0-beta.2"), UpdateChecker.pick(v("3.5.0-beta.1"), v("3.4.3"), v("3.5.0-beta.2")));
        assertEquals(v("3.5.0"), UpdateChecker.pick(v("3.5.0-rc.1"), v("3.5.0"), v("3.5.0-rc.1")));
        assertNull(UpdateChecker.pick(v("3.5.0-beta.2"), v("3.4.3"), v("3.5.0-beta.2")));
        assertNull(UpdateChecker.pick(v("3.5.0-beta.1"), null, null));
    }

    @Test
    void onlyPeerCraftPagesAreOpened() {
        assertEquals("https://www.curseforge.com/minecraft/mc-mods/peercraft/files/1",
                UpdateChecker.safePage("https://www.curseforge.com/minecraft/mc-mods/peercraft/files/1"));
        assertEquals(UpdateChecker.DEFAULT_PAGE, UpdateChecker.safePage("https://evil.example/peercraft"));
        assertEquals(UpdateChecker.DEFAULT_PAGE, UpdateChecker.safePage("https://www.curseforge.com.evil.example/"));
        assertEquals(UpdateChecker.DEFAULT_PAGE, UpdateChecker.safePage(null));
    }
}
