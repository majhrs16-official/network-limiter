package org.majhrs16.limiter.core.net;

import org.junit.Test;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Parseo/construcción de IPv4, IPv6, TCP, UDP, ICMP y fragmentación. */
public class PacketTest {

	private static byte[] ip(String literal) throws Exception {
		return InetAddress.getByName(literal).getAddress();
	}

	@Test
	public void ipv4TcpRoundTripWithOptions() throws Exception {
		byte[] src = ip("10.0.0.1");
		byte[] dst = ip("93.184.216.34");
		byte[] payload = "hola mundo".getBytes(StandardCharsets.US_ASCII);

		byte[] packet = TcpSegment.build(src, dst, 12345, 443, 1000L, 2000L,
				TcpSegment.SYN | TcpSegment.ACK, 65535, payload, 0, payload.length,
				1460, 7, true);

		IpPacket ipPacket = IpPacket.parse(packet);
		assertNotNull(ipPacket);
		assertEquals(4, ipPacket.version);
		assertArrayEquals(src, ipPacket.src);
		assertArrayEquals(dst, ipPacket.dst);
		assertEquals(IpPacket.PROTO_TCP, ipPacket.protocol);

		TcpSegment segment = TcpSegment.parse(ipPacket);
		assertNotNull(segment);
		assertEquals(12345, segment.srcPort);
		assertEquals(443, segment.dstPort);
		assertEquals(1000L, segment.seq);
		assertEquals(2000L, segment.ack);
		assertEquals(1460, segment.mss);
		assertEquals(7, segment.windowScale);
		assertTrue(segment.sackPermitted);
		assertTrue(segment.has(TcpSegment.SYN));
		assertTrue(segment.has(TcpSegment.ACK));
		assertEquals(payload.length, segment.payloadLength);
		assertArrayEquals(payload,
				java.util.Arrays.copyOfRange(packet, segment.payloadOffset,
						segment.payloadOffset + segment.payloadLength));
	}

	@Test
	public void ipv6TcpRoundTrip() throws Exception {
		byte[] src = ip("2001:db8::1");
		byte[] dst = ip("2606:4700:4700::1111");
		byte[] payload = "abcdef".getBytes(StandardCharsets.US_ASCII);

		byte[] packet = TcpSegment.build(src, dst, 5555, 80, 42L, 7L,
				TcpSegment.ACK | TcpSegment.PSH, 4096, payload, 0, payload.length,
				0, -1, false);

		IpPacket ipPacket = IpPacket.parse(packet);
		assertNotNull(ipPacket);
		assertEquals(6, ipPacket.version);
		assertArrayEquals(src, ipPacket.src);
		assertArrayEquals(dst, ipPacket.dst);

		TcpSegment segment = TcpSegment.parse(ipPacket);
		assertNotNull(segment);
		assertEquals(5555, segment.srcPort);
		assertEquals(80, segment.dstPort);
		assertEquals(42L, segment.seq);
		assertEquals(7L, segment.ack);
		assertEquals(payload.length, segment.payloadLength);
	}

	@Test
	public void tcpChecksumRejectsCorruption() throws Exception {
		byte[] packet = TcpSegment.build(ip("10.0.0.1"), ip("10.0.0.2"),
				1, 2, 10, 20, TcpSegment.ACK, 1000,
				"datos".getBytes(StandardCharsets.US_ASCII), 0, 5, 0, -1, false);
		packet[packet.length - 1] ^= 0xFF;

		IpPacket ipPacket = IpPacket.parse(packet);
		assertNotNull(ipPacket);
		assertNull(TcpSegment.parse(ipPacket));
	}

	@Test
	public void ipv4HeaderChecksumRejectsCorruption() throws Exception {
		byte[] packet = TcpSegment.build(ip("10.0.0.1"), ip("10.0.0.2"),
				1, 2, 10, 20, TcpSegment.ACK, 1000, null, 0, 0, 0, -1, false);
		packet[12] ^= 0x01;
		assertNull(IpPacket.parse(packet));
	}

	@Test
	public void udpRoundTripAndOptionalIpv4Checksum() throws Exception {
		byte[] src = ip("10.0.0.1");
		byte[] dst = ip("8.8.8.8");
		byte[] payload = new byte[64];
		for (int i = 0; i < payload.length; i++) {
			payload[i] = (byte) i;
		}

		byte[] packet = UdpDatagram.build(src, dst, 4444, 53, payload, 0, payload.length);
		IpPacket ipPacket = IpPacket.parse(packet);
		assertNotNull(ipPacket);
		UdpDatagram datagram = UdpDatagram.parse(ipPacket);
		assertNotNull(datagram);
		assertEquals(4444, datagram.srcPort);
		assertEquals(53, datagram.dstPort);
		assertEquals(payload.length + 8, datagram.length);
		assertEquals(payload.length, datagram.payloadLength);

		/* En IPv4 un checksum cero significa "no calculado": debe aceptarse. */
		byte[] withoutChecksum = packet.clone();
		Checksum.putShort(withoutChecksum, ipPacket.payloadOffset + 6, 0);
		IpPacket ip2 = IpPacket.parse(withoutChecksum);
		assertNotNull(ip2);
		assertNotNull(UdpDatagram.parse(ip2));

		/* Con checksum distinto de cero, la corrupción se detecta. */
		byte[] corrupted = packet.clone();
		corrupted[corrupted.length - 1] ^= 0xFF;
		IpPacket ip3 = IpPacket.parse(corrupted);
		assertNotNull(ip3);
		assertNull(UdpDatagram.parse(ip3));
	}

	@Test
	public void ipv6UdpRoundTrip() throws Exception {
		byte[] src = ip("2001:db8::1");
		byte[] dst = ip("2001:db8::2");
		byte[] payload = "dns".getBytes(StandardCharsets.US_ASCII);

		byte[] packet = UdpDatagram.build(src, dst, 5353, 53, payload, 0, payload.length);
		IpPacket ipPacket = IpPacket.parse(packet);
		assertNotNull(ipPacket);
		assertEquals(6, ipPacket.version);
		UdpDatagram datagram = UdpDatagram.parse(ipPacket);
		assertNotNull(datagram);
		assertEquals(5353, datagram.srcPort);
		assertEquals(53, datagram.dstPort);
	}

	@Test
	public void icmpv4EchoReply() throws Exception {
		byte[] src = ip("10.0.0.1");
		byte[] dst = ip("1.1.1.1");
		byte[] icmp = new byte[8 + 16];
		icmp[0] = 8;
		icmp[1] = 0;
		Checksum.putShort(icmp, 4, 0x1234);
		Checksum.putShort(icmp, 6, 1);
		for (int i = 8; i < icmp.length; i++) {
			icmp[i] = (byte) i;
		}
		Checksum.putShort(icmp, 2, Checksum.finish(Checksum.sum(icmp, 0, icmp.length, 0)));

		byte[] request = IpPacket.buildIpv4(src, dst, IpPacket.PROTO_ICMP, icmp, 0, icmp.length, 9);
		IpPacket parsed = IpPacket.parse(request);
		assertNotNull(parsed);
		assertTrue(Icmp.isEchoRequest(parsed));

		byte[] reply = Icmp.buildEchoReply(parsed);
		assertNotNull(reply);
		IpPacket replyIp = IpPacket.parse(reply);
		assertNotNull(replyIp);
		assertArrayEquals(dst, replyIp.src);
		assertArrayEquals(src, replyIp.dst);
		assertEquals(0, replyIp.raw[replyIp.payloadOffset] & 0xFF);
		assertEquals(0xFFFF, Checksum.fold(Checksum.sum(reply,
				replyIp.payloadOffset, replyIp.payloadLength, 0)));
	}

	@Test
	public void icmpv6EchoReply() throws Exception {
		byte[] src = ip("2001:db8::1");
		byte[] dst = ip("2606:4700:4700::1111");
		byte[] icmp = new byte[8 + 8];
		icmp[0] = (byte) 128;
		icmp[1] = 0;
		Checksum.putShort(icmp, 4, 0x7777);
		Checksum.putShort(icmp, 6, 2);
		int pseudo = Checksum.pseudoHeader(src, dst, IpPacket.PROTO_ICMPV6, icmp.length);
		Checksum.putShort(icmp, 2, Checksum.finish(Checksum.sum(icmp, 0, icmp.length, pseudo)));

		byte[] request = IpPacket.buildIpv6(src, dst, IpPacket.PROTO_ICMPV6, icmp, 0, icmp.length);
		IpPacket parsed = IpPacket.parse(request);
		assertNotNull(parsed);
		assertTrue(Icmp.isEchoRequest(parsed));

		byte[] reply = Icmp.buildEchoReply(parsed);
		assertNotNull(reply);
		IpPacket replyIp = IpPacket.parse(reply);
		assertNotNull(replyIp);
		assertArrayEquals(dst, replyIp.src);
		assertArrayEquals(src, replyIp.dst);
		assertEquals(129, replyIp.raw[replyIp.payloadOffset] & 0xFF);
		int replyPseudo = Checksum.pseudoHeader(replyIp.src, replyIp.dst,
				IpPacket.PROTO_ICMPV6, replyIp.payloadLength);
		assertEquals(0xFFFF, Checksum.fold(Checksum.sum(reply,
				replyIp.payloadOffset, replyIp.payloadLength, replyPseudo)));
	}

	@Test
	public void ipv4FragmentationPreservesPayload() throws Exception {
		byte[] src = ip("10.0.0.1");
		byte[] dst = ip("10.0.0.2");
		byte[] payload = new byte[3000];
		for (int i = 0; i < payload.length; i++) {
			payload[i] = (byte) (i * 31);
		}

		List<byte[]> fragments = Icmp.fragmentIpv4(src, dst, IpPacket.PROTO_UDP,
				payload, 0, payload.length, 1500, 42);
		assertEquals(3, fragments.size());

		byte[] reassembled = new byte[payload.length];
		int position = 0;
		for (int i = 0; i < fragments.size(); i++) {
			byte[] fragment = fragments.get(i);
			int total = Checksum.getShort(fragment, 2);
			int flagsAndOffset = Checksum.getShort(fragment, 6);
			int offset = (flagsAndOffset & 0x1FFF) * 8;
			boolean more = (flagsAndOffset & 0x2000) != 0;
			assertEquals(position, offset);
			assertEquals(i < fragments.size() - 1, more);
			int chunk = total - 20;
			System.arraycopy(fragment, 20, reassembled, offset, chunk);
			position += chunk;
			/* Los fragmentos no deben reensamblarse en la pila. */
			assertNull(IpPacket.parse(fragment));
		}
		assertArrayEquals(payload, reassembled);
	}

	@Test
	public void ipv6PacketTooBig() throws Exception {
		byte[] src = ip("2001:db8::1");
		byte[] dst = ip("2001:db8::2");
		byte[] payload = new byte[2000];
		byte[] udp = UdpDatagram.build(src, dst, 1, 2, payload, 0, payload.length);
		IpPacket original = IpPacket.parse(udp);
		assertNotNull(original);

		byte[] tooBig = Icmp.buildPacketTooBig(original, 1500);
		assertNotNull(tooBig);
		IpPacket reply = IpPacket.parse(tooBig);
		assertNotNull(reply);
		assertArrayEquals(dst, reply.src);
		assertArrayEquals(src, reply.dst);
		assertEquals(2, reply.raw[reply.payloadOffset] & 0xFF);
		assertEquals(1500, Checksum.getInt(reply.raw, reply.payloadOffset + 4));
	}

	@Test
	public void nonEchoIcmpIsIgnored() throws Exception {
		byte[] icmp = new byte[8];
		icmp[0] = 3; /* destination unreachable */
		Checksum.putShort(icmp, 2, Checksum.finish(Checksum.sum(icmp, 0, 8, 0)));
		byte[] packet = IpPacket.buildIpv4(ip("10.0.0.1"), ip("10.0.0.2"),
				IpPacket.PROTO_ICMP, icmp, 0, 8, 1);
		IpPacket parsed = IpPacket.parse(packet);
		assertNotNull(parsed);
		assertFalse(Icmp.isEchoRequest(parsed));
		assertNull(Icmp.buildEchoReply(parsed));
	}
}
