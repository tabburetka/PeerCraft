package net.peercraft.network.skin;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class OfficialSkinProfilesTest {
    private static final UUID ID = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");
    private String response(UUID profileId, UUID textureId, boolean signed) {
        JsonObject payload = new JsonObject();
        payload.addProperty("profileId", textureId.toString().replace("-", ""));
        payload.add("textures", new JsonObject());
        JsonObject property = new JsonObject();
        property.addProperty("name", "textures");
        property.addProperty("value", Base64.getEncoder().encodeToString(payload.toString().getBytes(StandardCharsets.UTF_8)));
        if (signed) property.addProperty("signature", "preserve-original-signature");
        JsonArray properties = new JsonArray(); properties.add(property);
        JsonObject profile = new JsonObject();
        profile.addProperty("id", profileId.toString().replace("-", ""));
        profile.add("properties", properties); return profile.toString();
    }
    @Test void retainsSignedTextureForExactUuid() {
        OfficialSkinProfiles.Texture result = OfficialSkinProfiles.parse(ID, response(ID, ID, true)).orElseThrow();
        assertEquals("preserve-original-signature", result.signature);
        assertTrue(new String(Base64.getDecoder().decode(result.value), StandardCharsets.UTF_8).contains(ID.toString().replace("-", "")));
    }
    @Test void rejectsDifferentProfileOrTextureOwner() {
        assertFalse(OfficialSkinProfiles.parse(ID, response(UUID.randomUUID(), ID, true)).isPresent());
        assertFalse(OfficialSkinProfiles.parse(ID, response(ID, UUID.randomUUID(), true)).isPresent());
    }
    @Test void rejectsUnsignedOrMalformedResponse() {
        assertFalse(OfficialSkinProfiles.parse(ID, response(ID, ID, false)).isPresent());
        for (String invalid : new String[]{"{}", "null", "not json", "{\"id\":\"wrong\"}"})
            assertFalse(OfficialSkinProfiles.parse(ID, invalid).isPresent());
    }
    @Test void offlineNicknameUuidDoesNotStartNetworkLookup() {
        UUID offline = UUID.nameUUIDFromBytes("OfflinePlayer:Steve".getBytes(StandardCharsets.UTF_8));
        assertTrue(OfficialSkinProfiles.lookup(offline).isDone());
        assertFalse(OfficialSkinProfiles.lookup(offline).join().isPresent());
        assertFalse(OfficialSkinProfiles.lookup(null).join().isPresent());
    }
}
