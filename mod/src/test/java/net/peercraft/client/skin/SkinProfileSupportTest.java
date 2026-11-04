package net.peercraft.client.skin;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.google.common.collect.Multimap;
import net.peercraft.network.skin.OfficialSkinProfiles;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class SkinProfileSupportTest {
    @SuppressWarnings("unchecked")
    private Multimap<String, Property> properties(GameProfile profile) throws Exception {
        try { return (Multimap<String, Property>) GameProfile.class.getMethod("getProperties").invoke(profile); }
        catch (NoSuchMethodException immutableAuthlib) {
            return (Multimap<String, Property>) GameProfile.class.getMethod("properties").invoke(profile);
        }
    }
    @Test void identityCopyRetainsExactSignedTextureAndDoesNotChangeOriginal() throws Exception {
        UUID originalId = UUID.randomUUID(), playingId = UUID.randomUUID();
        GameProfile original = SkinProfileSupport.withTexture(new GameProfile(originalId, "Player"),
                texture());
        Property signed = properties(original).get("textures").iterator().next();
        GameProfile copied = SkinProfileSupport.copy(original, playingId);
        assertEquals(playingId, SkinProfileSupport.id(copied));
        assertEquals(originalId, SkinProfileSupport.id(original));
        assertSame(signed, properties(copied).get("textures").iterator().next());
        assertFalse(SkinProfileSupport.missing(copied));
    }
    @Test void addingTextureKeepsIdentityAndLeavesSourceEmpty() throws Exception {
        UUID id = UUID.randomUUID(); GameProfile original = new GameProfile(id, "Player");
        GameProfile textured = SkinProfileSupport.withTexture(original, texture());
        assertEquals(id, SkinProfileSupport.id(textured));
        assertTrue(SkinProfileSupport.missing(original));
        assertFalse(SkinProfileSupport.missing(textured));
    }
    private OfficialSkinProfiles.Texture texture() throws Exception {
        var constructor = OfficialSkinProfiles.Texture.class.getDeclaredConstructor(String.class, String.class);
        constructor.setAccessible(true); return constructor.newInstance("exact-payload", "exact-signature");
    }
}
