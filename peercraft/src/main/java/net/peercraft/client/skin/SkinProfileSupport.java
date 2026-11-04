package net.peercraft.client.skin;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import net.peercraft.network.skin.OfficialSkinProfiles;
import java.util.UUID;

public final class SkinProfileSupport {
    private SkinProfileSupport() { }
    public static GameProfile copy(GameProfile original, UUID id) {
        //? if <1.21.9 {
        GameProfile result = new GameProfile(id, original.getName());
        result.getProperties().putAll(original.getProperties());
        return result;
        //?} else {
        /*return new GameProfile(id, original.name(), original.properties());
        *///?}
    }
    public static UUID id(GameProfile profile) {
        //? if <1.21.9
        return profile.getId();
        //? if >=1.21.9
        /*return profile.id();*/
    }
    public static boolean missing(GameProfile profile) {
        //? if <1.21.9
        return !profile.getProperties().containsKey("textures");
        //? if >=1.21.9
        /*return !profile.properties().containsKey("textures");*/
    }
    public static GameProfile withTexture(GameProfile profile, OfficialSkinProfiles.Texture texture) {
        //? if <1.21.9 {
        GameProfile result = copy(profile, id(profile));
        result.getProperties().put("textures", new Property("textures", texture.value, texture.signature));
        return result;
        //?} else {
        /*com.google.common.collect.Multimap<String, Property> properties = com.google.common.collect.ArrayListMultimap.create(profile.properties());
        properties.put("textures", new Property("textures", texture.value, texture.signature));
        return new GameProfile(profile.id(), profile.name(), new com.mojang.authlib.properties.PropertyMap(properties));
        *///?}
    }
}
