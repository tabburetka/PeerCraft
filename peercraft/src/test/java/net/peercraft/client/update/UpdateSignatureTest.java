package net.peercraft.client.update;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class UpdateSignatureTest {
    private static final String SHA = "a".repeat(128);

    private static KeyPair keys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }

    private static String sign(KeyPair pair, byte[] body) throws Exception {
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(pair.getPrivate());
        signer.update(body);
        return Base64.getEncoder().encodeToString(signer.sign());
    }

    @Test
    void acceptsOnlyTheExactBytesSignedByAKnownKey() throws Exception {
        KeyPair release = keys();
        KeyPair stranger = keys();
        String[] pinned = {Base64.getEncoder().encodeToString(release.getPublic().getEncoded())};
        byte[] body = "{\"stable\":\"3.5.0\"}\n".getBytes(StandardCharsets.UTF_8);
        String signature = sign(release, body);

        assertTrue(UpdateSignature.verify(body, signature + "\n", pinned));
        byte[] tampered = body.clone();
        tampered[12] = '6';
        assertFalse(UpdateSignature.verify(tampered, signature, pinned));
        assertFalse(UpdateSignature.verify(body, sign(stranger, body), pinned));
        assertFalse(UpdateSignature.verify(body, "", pinned));
        assertFalse(UpdateSignature.verify(body, "not base64 !!", pinned));
        assertFalse(UpdateSignature.verify(body, null, pinned));
        assertFalse(UpdateSignature.verify(body, signature, new String[] {"broken"}));
    }

    @Test
    void shippedReleaseKeyParses() {
        assertTrue(UpdateSignature.PUBLIC_KEYS.length > 0);
        // A bogus signature must be rejected without throwing, proving the key itself decodes.
        assertFalse(UpdateSignature.verify(new byte[] {1}, Base64.getEncoder().encodeToString(new byte[8])));
    }

    private static JsonObject manifest(String url, String file, String sha, long size) {
        return new JsonParser().parse("{\"files\":{\"3.5.0\":{\"fabric-1.21.1\":{\"file\":\"" + file
                + "\",\"url\":\"" + url + "\",\"sha512\":\"" + sha + "\",\"size\":" + size + "}}}}").getAsJsonObject();
    }

    @Test
    void picksThisBuildsFileFromTheCurseForgeCdnOnly() {
        String name = "peercraft-fabric-1.21.1-3.5.0.jar";
        String good = "https://mediafilez.forgecdn.net/files/9500/1/" + name;
        UpdateManifest.File file = UpdateManifest.fileFor(manifest(good, name, SHA, 1000), "3.5.0", "fabric-1.21.1");
        assertNotNull(file);
        assertEquals(name, file.fileName);
        assertEquals(1000, file.size);

        assertNull(UpdateManifest.fileFor(manifest(good, name, SHA, 1000), "3.5.0", "neoforge-1.21.1"));
        assertNull(UpdateManifest.fileFor(manifest(good, name, SHA, 1000), "3.4.9", "fabric-1.21.1"));
        assertNull(UpdateManifest.fileFor(manifest("http://mediafilez.forgecdn.net/files/9500/1/" + name, name, SHA, 1000), "3.5.0", "fabric-1.21.1"));
        assertNull(UpdateManifest.fileFor(manifest("https://evil.example/files/" + name, name, SHA, 1000), "3.5.0", "fabric-1.21.1"));
        assertNull(UpdateManifest.fileFor(manifest("https://mediafilez.forgecdn.net.evil.example/files/" + name, name, SHA, 1000), "3.5.0", "fabric-1.21.1"));
        assertNull(UpdateManifest.fileFor(manifest("https://mediafilez.forgecdn.net/files/9500/1/other.jar", name, SHA, 1000), "3.5.0", "fabric-1.21.1"));
        assertNull(UpdateManifest.fileFor(manifest(good, "../" + name, SHA, 1000), "3.5.0", "fabric-1.21.1"));
        assertNull(UpdateManifest.fileFor(manifest(good, name, "abc", 1000), "3.5.0", "fabric-1.21.1"));
        assertNull(UpdateManifest.fileFor(manifest(good, name, SHA, 0), "3.5.0", "fabric-1.21.1"));
        assertNull(UpdateManifest.fileFor(new JsonParser().parse("{\"stable\":\"3.5.0\"}").getAsJsonObject(), "3.5.0", "fabric-1.21.1"));
    }

    @Test
    void buildKeysMatchTheReleaseScript() {
        assertEquals("fabric-1.21.1", UpdateManifest.buildKey("FABRIC", "1.21.1"));
        assertEquals("forge-1.12.2", UpdateManifest.buildKey("FORGE", "1.12.2"));
        assertEquals("neoforge-26.2", UpdateManifest.buildKey("NEOFORGE", "26.2"));
        assertNull(UpdateManifest.buildKey("UNKNOWN", "1.21.1"));
        assertNull(UpdateManifest.buildKey("FABRIC", ""));
    }
}
