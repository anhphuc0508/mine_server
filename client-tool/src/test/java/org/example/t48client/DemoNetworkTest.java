package org.example.t48client;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.UnknownHostException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DemoNetworkTest {
    @Test
    void acceptsEntirePrivateIpv4SubnetsIncludingTheirBoundaries() throws UnknownHostException {
        int[][] accepted = {
                {10, 0, 0, 0}, {10, 0, 0, 1}, {10, 255, 255, 255},
                {172, 16, 0, 0}, {172, 16, 0, 1}, {172, 31, 255, 255},
                {192, 168, 0, 0}, {192, 168, 1, 25}, {192, 168, 255, 255}
        };
        for (int[] octets : accepted) {
            var remote = ipv4(octets);
            assertTrue(DemoNetwork.isAllowed(remote), remote.toString());
        }
    }

    @Test
    void rejectsAddressesImmediatelyOutsidePrivateIpv4Subnets() throws UnknownHostException {
        int[][] rejected = {
                {9, 255, 255, 255}, {11, 0, 0, 0},
                {172, 15, 255, 255}, {172, 32, 0, 0},
                {192, 167, 255, 255}, {192, 169, 0, 0}
        };
        for (int[] octets : rejected) {
            var remote = ipv4(octets);
            assertFalse(DemoNetwork.isAllowed(remote), remote.toString());
        }
    }

    @Test
    void acceptsIpv4AndIpv6Loopback() throws UnknownHostException {
        assertTrue(DemoNetwork.isAllowed(ipv4(127, 0, 0, 1)));
        assertTrue(DemoNetwork.isAllowed(ipv4(127, 42, 0, 3)));
        byte[] loopback = new byte[16];
        loopback[15] = 1;
        assertTrue(DemoNetwork.isAllowed(ipv6(loopback)));
    }

    @Test
    void acceptsBothHalvesOfIpv6UniqueLocalPrefix() throws UnknownHostException {
        assertTrue(DemoNetwork.isAllowed(ipv6WithPrefix(0xfc, 0)));
        assertTrue(DemoNetwork.isAllowed(ipv6WithPrefix(0xfd, 0xff)));
    }

    @Test
    void rejectsIpv6OutsideUniqueLocalPrefix() throws UnknownHostException {
        assertFalse(DemoNetwork.isAllowed(ipv6WithPrefix(0xfb, 0xff)));
        assertFalse(DemoNetwork.isAllowed(ipv6WithPrefix(0xfe, 0)));
        assertFalse(DemoNetwork.isAllowed(ipv6WithPrefix(0xfe, 0xc0))); // deprecated site-local
        assertFalse(DemoNetwork.isAllowed(ipv6WithPrefix(0xfe, 0x80))); // link-local
        assertFalse(DemoNetwork.isAllowed(ipv6WithPrefix(0xff, 0x02))); // multicast
        assertFalse(DemoNetwork.isAllowed(ipv6WithPrefix(0x20, 0x01))); // public/global
    }

    @Test
    void rejectsUnspecifiedPublicMulticastAndLinkLocalIpv4() throws UnknownHostException {
        int[][] rejected = {
                {0, 0, 0, 0}, {8, 8, 8, 8}, {1, 1, 1, 1},
                {224, 0, 0, 1}, {239, 255, 255, 250},
                {169, 254, 1, 1}, {100, 64, 0, 1}, {255, 255, 255, 255}
        };
        for (int[] octets : rejected) {
            var remote = ipv4(octets);
            assertFalse(DemoNetwork.isAllowed(remote), remote.toString());
        }
        assertFalse(DemoNetwork.isAllowed(ipv6(new byte[16])));
    }

    @Test
    void rejectsNullUnresolvedAndNonInternetSocketsWithoutDnsLookup() {
        assertFalse(DemoNetwork.isAllowed(null));
        assertFalse(DemoNetwork.isAllowed(InetSocketAddress.createUnresolved("localhost", 25565)));
        assertFalse(DemoNetwork.isAllowed(InetSocketAddress.createUnresolved("192.168.1.10", 25565)));
        assertFalse(DemoNetwork.isAllowed(new SocketAddress() { }));
    }

    @Test
    void trustsResolvedAddressRatherThanItsHostnameLabel() throws UnknownHostException {
        var misleadingLocalName = InetAddress.getByAddress("localhost", new byte[]{8, 8, 8, 8});
        assertFalse(DemoNetwork.isAllowed(new InetSocketAddress(misleadingLocalName, 25565)));
        var misleadingPublicName = InetAddress.getByAddress("public.example", new byte[]{10, 1, 2, 3});
        assertTrue(DemoNetwork.isAllowed(new InetSocketAddress(misleadingPublicName, 25565)));
    }

    private static InetSocketAddress ipv4(int... octets) throws UnknownHostException {
        byte[] bytes = new byte[4];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) octets[i];
        return new InetSocketAddress(InetAddress.getByAddress(bytes), 25565);
    }

    private static InetSocketAddress ipv6WithPrefix(int first, int second) throws UnknownHostException {
        byte[] bytes = new byte[16];
        bytes[0] = (byte) first;
        bytes[1] = (byte) second;
        bytes[15] = 1;
        return ipv6(bytes);
    }

    private static InetSocketAddress ipv6(byte[] bytes) throws UnknownHostException {
        return new InetSocketAddress(InetAddress.getByAddress(bytes), 25565);
    }
}
