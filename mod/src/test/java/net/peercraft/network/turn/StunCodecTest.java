package net.peercraft.network.turn;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.jupiter.api.Assertions.*;

class StunCodecTest {
    @Test void matchesPublishedRfc5769IntegrityVector() throws Exception {
        byte[] message = hex("000100582112a442b7e7a701bc34d686fa87dfae"
                + "802200105354554e207465737420636c69656e74"
                + "002400046e0001ff80290008932ff9b151263b36"
                + "000600096576746a3a68367659202020"
                + "000800149aeaa70cbfd8cb56781ef2b5b2d3f249c1b571a2"
                + "80280004e57a3bcf");
        assertTrue(StunCodec.verifyIntegrity(StunCodec.parse(message), "VOkJxbRl1RmTxUk/WvJxBt".getBytes(StandardCharsets.UTF_8)));
        message[message.length - 1] ^= 1;
        assertThrows(IOException.class, () -> StunCodec.parse(message));
    }
    @Test void ipv4AndIpv6XorAddressesRoundTrip() throws Exception {
        byte[] id = StunCodec.newTransactionId();
        for (String ip : new String[] { "192.0.2.99", "2001:db8:1234::89" }) {
            InetSocketAddress expected = new InetSocketAddress(InetAddress.getByName(ip), 34567);
            byte[] message = StunCodec.encode(StunCodec.BINDING_SUCCESS, id,
                    Collections.singletonList(StunCodec.xorAddressAttribute(StunCodec.XOR_MAPPED_ADDRESS, expected, id)), null);
            StunCodec.Message parsed = StunCodec.parse(message);
            assertTrue(parsed.matches(id)); assertEquals(expected, StunCodec.mappedAddress(parsed));
        }
    }
    @Test void rejectsTruncationAndWrongLengthAndIntegrity() throws Exception {
        byte[] id = StunCodec.newTransactionId(), key = StunCodec.longTermKey("user", "realm", "password");
        byte[] packet = StunCodec.encode(StunCodec.ALLOCATE_SUCCESS, id,
                Collections.singletonList(StunCodec.integerAttribute(StunCodec.LIFETIME, 600)), key);
        assertTrue(StunCodec.verifyIntegrity(StunCodec.parse(packet), key));
        assertFalse(StunCodec.verifyIntegrity(StunCodec.parse(packet), new byte[16]));
        assertThrows(IOException.class, () -> StunCodec.parse(Arrays.copyOf(packet, packet.length - 1)));
        packet[3] ^= 4; assertThrows(IOException.class, () -> StunCodec.parse(packet));
    }
    private static byte[] hex(String value) {
        byte[] result = new byte[value.length() / 2];
        for (int i = 0; i < result.length; i++) result[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        return result;
    }
}
