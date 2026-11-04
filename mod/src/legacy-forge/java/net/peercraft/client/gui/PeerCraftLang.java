package net.peercraft.client.gui;

import net.minecraft.client.Minecraft;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 //? if >=1.12 {
 * PeerCraft localization for the 1.12.2 backport, independent of Minecraft's resource-pack
 * lang loading. In an RFG dev workspace (and, it turns out, for a jar loaded as an
 * {@code FMLCorePluginContainsFMLMod}) the mod's {@code FMLFileResourcePack} serves
 * {@code assets/peercraft/icon.png} but NOT {@code assets/peercraft/lang/*.lang}, so
 * {@code I18n.format("peercraft.…")} just echoes the key. This reads the same generated
 * {@code .lang} files straight off the classpath (which works — same path
 * {@code ServiceLoader} uses) and does the {@code %s} formatting itself.
 //?} else {
//~  * PeerCraft localization for the 1.7.10 backport (twin of the {@code src/client-1122}
//~  * {@code PeerCraftLang}), independent of Minecraft's resource-pack lang loading. For a jar
//~  * loaded as an {@code FMLCorePluginContainsFMLMod} (and in an RFG dev workspace) the mod's
//~  * {@code FMLFileResourcePack} serves {@code assets/peercraft/icon.png} but NOT
//~  * {@code assets/peercraft/lang/*.lang}, so {@code I18n.format("peercraft.…")} just echoes the
//~  * key. This reads the same generated {@code .lang} files straight off the classpath (which
//~  * works — same path {@code ServiceLoader} uses) and does the {@code %s} formatting itself.
 //?}
 *
 //? if >=1.12 {
 * <p>Falls back to {@code en_us} for missing keys, and to the raw key if all else fails.
 //?} else {
//~  * <p>1.7.10 delta vs the 1.12.2 twin: the language code carries an upper-case region
//~  * ({@code en_US} / {@code ru_RU}), not the lower-case {@code en_us} of 1.11+. The generated
//~  * {@code .lang} files are named to match (see {@code generateLang} in build.gradle).
//~  *
//~  * <p>Falls back to {@code en_US} for missing keys, and to the raw key if all else fails.
 //?}
 */
public final class PeerCraftLang {

    //? if >=1.12 {
    private static final Properties EN = load("en_us");
    //?} else {
//~     private static final Properties EN = load("en_US");
    //?}
    private static Properties active = EN;
    //? if >=1.12 {
    private static String activeCode = "en_us";
    //?} else {
//~     private static String activeCode = "en_US";
    //?}

    private PeerCraftLang() {
    }

    public static String tr(String key, Object... args) {
        refreshIfLanguageChanged();
        String pattern = active.getProperty(key);
        if (pattern == null && active != EN) {
            pattern = EN.getProperty(key);
        }
        if (pattern == null) {
            return key;
        }
        if (args == null || args.length == 0) {
            return pattern;
        }
        try {
            return String.format(pattern, args);
        } catch (RuntimeException e) {
            return pattern;
        }
    }

    private static void refreshIfLanguageChanged() {
        String code;
        try {
            code = Minecraft.getMinecraft().gameSettings.language;
        } catch (Throwable t) {
            //? if >=1.12 {
            return; // too early / no client — keep en_us
            //?} else {
//~             return; // too early / no client — keep en_US
            //?}
        }
        if (code == null || code.equals(activeCode)) {
            return;
        }
        activeCode = code;
        //? if >=1.12 {
        active = "en_us".equals(code) ? EN : load(code);
        //?} else {
//~         active = "en_US".equals(code) ? EN : load(code);
        //?}
    }

    private static Properties load(String code) {
        Properties props = new Properties();
        try (InputStream in = PeerCraftLang.class.getResourceAsStream("/assets/peercraft/lang/" + code + ".lang")) {
            if (in != null) {
                props.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
            // keep whatever loaded; tr() falls back to the key
        }
        return props;
    }
}
