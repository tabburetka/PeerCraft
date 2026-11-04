package net.peercraft.client.skin;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import net.peercraft.network.skin.OfficialSkinProfiles;
import java.util.UUID;

public final class SkinProfileSupport {
    private SkinProfileSupport() { }
    public static GameProfile copy(GameProfile original, UUID id) {
        GameProfile result = new GameProfile(id, original.getName());
        result.getProperties().putAll(original.getProperties()); return result;
    }
    public static UUID id(GameProfile profile) { return profile.getId(); }
    public static boolean missing(GameProfile profile) { return !profile.getProperties().containsKey("textures"); }
    public static GameProfile withTexture(GameProfile profile, OfficialSkinProfiles.Texture texture) {
        GameProfile result = copy(profile, id(profile));
        result.getProperties().put("textures", new Property("textures", texture.value, texture.signature)); return result;
    }
}
