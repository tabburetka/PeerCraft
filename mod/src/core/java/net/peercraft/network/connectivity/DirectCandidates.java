package net.peercraft.network.connectivity;

import net.peercraft.network.rendezvous.RendezvousProtocol;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/** Bounded exact endpoints, never port guesses or router configuration changes. */
public final class DirectCandidates {
    private DirectCandidates() { }

    public static List<RendezvousProtocol.Address> gather(int boundPort) {
        if (boundPort < 1 || boundPort > 65535) return Collections.emptyList();
        List<RendezvousProtocol.Address> result = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) return result;
            while (interfaces.hasMoreElements() && result.size() < 8) {
                NetworkInterface network = interfaces.nextElement();
                if (!network.isUp() || network.isLoopback()) continue;
                Enumeration<InetAddress> addresses = network.getInetAddresses();
                while (addresses.hasMoreElements() && result.size() < 8) {
                    InetAddress address = addresses.nextElement();
                    if (!eligible(address)) continue;
                    RendezvousProtocol.Address endpoint = new RendezvousProtocol.Address(address, boundPort);
                    if (!result.contains(endpoint)) result.add(endpoint);
                }
            }
        } catch (SocketException | SecurityException ignored) {
            // Candidate discovery is optional; the rendezvous-observed endpoint remains usable.
        }
        return Collections.unmodifiableList(result);
    }

    public static boolean eligible(InetAddress address) {
        if (address == null || address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isMulticastAddress()) return false;
        if (address instanceof Inet4Address) return true;
        if (!(address instanceof Inet6Address) || Boolean.getBoolean("java.net.preferIPv4Stack")) return false;
        // Only global-unicast IPv6. ULA and scoped link-local addresses cannot be exported as Internet candidates.
        return (address.getAddress()[0] & 0xe0) == 0x20;
    }

    public static boolean isOnLocalSubnet(InetAddress address) {
        if (!(address instanceof Inet4Address)) return false;
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) return false;
            while (interfaces.hasMoreElements()) {
                NetworkInterface network = interfaces.nextElement();
                if (!network.isUp() || network.isLoopback()) continue;
                for (InterfaceAddress local : network.getInterfaceAddresses()) {
                    if (!(local.getAddress() instanceof Inet4Address)) continue;
                    int prefix = local.getNetworkPrefixLength();
                    if (prefix < 1 || prefix > 32) continue;
                    byte[] left = local.getAddress().getAddress(), right = address.getAddress();
                    boolean same = true;
                    for (int i = 0; i < 4; i++) {
                        int bits = Math.min(8, Math.max(0, prefix - i * 8));
                        int mask = bits == 0 ? 0 : (255 << (8 - bits)) & 255;
                        if ((left[i] & mask) != (right[i] & mask)) { same = false; break; }
                    }
                    if (same) return true;
                }
            }
        } catch (SocketException | SecurityException ignored) { }
        return false;
    }
}
