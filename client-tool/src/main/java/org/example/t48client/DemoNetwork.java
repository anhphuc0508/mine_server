package org.example.t48client;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetSocketAddress;
import java.net.SocketAddress;

/** Address policy for an already established connection to the user's demo server. */
public final class DemoNetwork {
    private DemoNetwork() { }

    /**
     * Accepts loopback, RFC 1918 IPv4, and IPv6 unique-local destinations.
     * Reads the actual resolved socket address; this method never resolves a hostname.
     */
    public static boolean isAllowed(SocketAddress remote) {
        if (!(remote instanceof InetSocketAddress socket) || socket.isUnresolved()) return false;
        var address = socket.getAddress();
        if (address == null || address.isAnyLocalAddress() || address.isMulticastAddress()) return false;
        if (address.isLoopbackAddress()) return true;
        if (address instanceof Inet4Address) return address.isSiteLocalAddress();
        if (address instanceof Inet6Address) {
            // fc00::/7; Java's isSiteLocalAddress instead identifies obsolete fec0::/10.
            int firstOctet = address.getAddress()[0] & 0xff;
            return (firstOctet & 0xfe) == 0xfc;
        }
        return false;
    }
}
