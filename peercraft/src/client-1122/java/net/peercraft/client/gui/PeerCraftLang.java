package net.peercraft.client.gui;

import net.minecraft.client.Minecraft;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * PeerCraft localization for the 1.12.2 backport, independent of Minecraft's resource-pack
 * lang loading. In an RFG dev workspace (and, it turns out, for a jar loaded as an
 * {@code FMLCorePluginContainsFMLMod}) the mod's {@code FMLFileResourcePack} serves
 * {@code assets/peercraft/icon.png} but NOT {@code assets/peercraft/lang/*.lang}, so
 * {@code I18n.format("peercraft.…")} just echoes the key. This reads the same generated
 * {@code .lang} files straight off the classpath (which works — same path
 * {@code ServiceLoader} uses) and does the {@code %s} formatting itself.
 *
 * <p>Falls back to {@code en_us} for missing keys, and to the raw key if all else fails.
 */
public final class PeerCraftLang {

    private static final Properties EN = load("en_us");
    private static Properties active = EN;
    private static String activeCode = "en_us";

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
            return; // too early / no client — keep en_us
        }
        if (code == null || code.equals(activeCode)) {
            return;
        }
        activeCode = code;
        active = "en_us".equals(code) ? EN : load(code);
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
