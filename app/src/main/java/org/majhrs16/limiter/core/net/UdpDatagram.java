package org.majhrs16.limiter.core.net;

/** Datagrama UDP: parseo con checksum y construcción del paquete IP. */
public final class UdpDatagram {

	private static final java.util.concurrent.atomic.AtomicInteger IP_ID =
			new java.util.concurrent.atomic.AtomicInteger(1);

	public final int srcPort;
	public final int dstPort;
	public final int length;
	public final int checksum;
	public final int payloadOffset;
	public final int payloadLength;

	private UdpDatagram(int srcPort, int dstPort, int length, int checksum,
			int payloadOffset, int payloadLength) {
		this.srcPort = srcPort;
		this.dstPort = dstPort;
		this.length = length;
		this.checksum = checksum;
		this.payloadOffset = payloadOffset;
		this.payloadLength = payloadLength;
	}

	public static UdpDatagram parse(IpPacket ip) {
		if (ip == null || ip.protocol != IpPacket.PROTO_UDP || ip.payloadLength < 8) {
			return null;
		}
		byte[] pkt = ip.raw;
		int off = ip.payloadOffset;
		int udpLength = Checksum.getShort(pkt, off + 4);
		if (udpLength < 8 || udpLength > ip.payloadLength) {
			return null;
		}

		int checksum = Checksum.getShort(pkt, off + 6);
		/* En IPv4 el checksum es opcional (0 = no calculado). */
		if (ip.version == 6 || checksum != 0) {
			int pseudo = Checksum.pseudoHeader(ip.src, ip.dst, IpPacket.PROTO_UDP, udpLength);
			int acc = Checksum.sum(pkt, off, udpLength, pseudo);
			if (Checksum.fold(acc) != 0xFFFF) {
				return null;
			}
		}

		return new UdpDatagram(Checksum.getShort(pkt, off),
				Checksum.getShort(pkt, off + 2), udpLength, checksum,
				off + 8, udpLength - 8);
	}

	public static byte[] build(byte[] srcIp, byte[] dstIp, int srcPort, int dstPort,
			byte[] payload, int payloadOffset, int payloadLength) {
		int udpLength = 8 + payloadLength;
		byte[] seg = new byte[udpLength];
		Checksum.putShort(seg, 0, srcPort);
		Checksum.putShort(seg, 2, dstPort);
		Checksum.putShort(seg, 4, udpLength);
		Checksum.putShort(seg, 6, 0);
		if (payloadLength > 0) {
			System.arraycopy(payload, payloadOffset, seg, 8, payloadLength);
		}

		int pseudo = Checksum.pseudoHeader(srcIp, dstIp, IpPacket.PROTO_UDP, udpLength);
		int acc = Checksum.sum(seg, 0, udpLength, pseudo);
		int sum = Checksum.finish(acc);
		if (sum == 0) {
			sum = 0xFFFF;
		}
		Checksum.putShort(seg, 6, sum);

		if (srcIp.length == 16) {
			return IpPacket.buildIpv6(srcIp, dstIp, IpPacket.PROTO_UDP, seg, 0, udpLength);
		}
		return IpPacket.buildIpv4(srcIp, dstIp, IpPacket.PROTO_UDP, seg, 0, udpLength,
				IP_ID.getAndIncrement());
	}
}
