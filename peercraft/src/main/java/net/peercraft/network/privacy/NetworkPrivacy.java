package net.peercraft.network.privacy;
import java.util.regex.Pattern;
/** Numeric address masking shared by clients and the rendezvous service. */
public final class NetworkPrivacy {
    private static final Pattern V4 = Pattern.compile("(?<![\\w.])(?:[0-9]{1,3}\\.){3}[0-9]{1,3}(?![\\w.])");
    private static final Pattern V6 = Pattern.compile("(?i)(?<![\\w:])(?:[0-9a-f]{0,4}:){2,}[0-9a-f:.]*(?:%[\\w.-]+)?");
    private NetworkPrivacy() { }

    public static String redact(String value) {
        if (value == null) return null;
        return V6.matcher(V4.matcher(value).replaceAll("[IP hidden]")).replaceAll("[IP hidden]");
    }

}
