package org.majhrs16.limiter.core.net;

/**
 * Vista parseada de un paquete IPv4 o IPv6.
 *
 * <p>No se reensamblan fragmentos: un paquete fragmentado se descarta y el
 * emisor deberá hacer PMTUD o retransmitir.</p>
 */
public final class IpPacket {

	public static final int PROTO_ICMP = 1;
	public static final int PROTO_TCP = 6;
	public static final int PROTO_UDP = 17;
	public static final int PROTO_ICMPV6 = 58;

	public final byte[] raw;
	public final int version;
	public final byte[] src;
	public final byte[] dst;
	public final int protocol;
	public final int payloadOffset;
	public final int payloadLength;
	public final int totalLength;
	public final int headerLength;

	private IpPacket(byte[] raw, int version, byte[] src, byte[] dst, int protocol,
			int payloadOffset, int payloadLength, int totalLength, int headerLength) {
		this.raw = raw;
		this.version = version;
		this.src = src;
		this.dst = dst;
		this.protocol = protocol;
		this.payloadOffset = payloadOffset;
		this.payloadLength = payloadLength;
		this.totalLength = totalLength;
		this.headerLength = headerLength;
	}

	public boolean isIpv6() {
		return version == 6;
	}

	/** @return paquete parseado o {@code null} si es inválido o fragmentado. */
	public static IpPacket parse(byte[] pkt) {
		if (pkt == null || pkt.length < 20) {
			return null;
		}
		int version = (pkt[0] & 0xFF) >>> 4;
		if (version == 4) {
			return parseIpv4(pkt);
		}
		if (version == 6) {
			return parseIpv6(pkt);
		}
		return null;
	}

	private static IpPacket parseIpv4(byte[] pkt) {
		int ihl = (pkt[0] & 0x0F) * 4;
		if (ihl < 20 || pkt.length < ihl) {
			return null;
		}
		int total = Checksum.getShort(pkt, 2);
		if (total < ihl || total > pkt.length) {
			return null;
		}

		/* Suma del encabezado (incluido el checksum) debe dar 0xFFFF. */
		int acc = Checksum.fold(Checksum.sum(pkt, 0, ihl, 0));
		if (acc != 0xFFFF) {
			return null;
		}

		int flagsAndOffset = Checksum.getShort(pkt, 6);
		if ((flagsAndOffset & 0x3FFF) != 0) {
			/* MF o fragment offset distinto de cero. */
			return null;
		}

		byte[] src = new byte[4];
		byte[] dst = new byte[4];
		System.arraycopy(pkt, 12, src, 0, 4);
		System.arraycopy(pkt, 16, dst, 0, 4);

		return new IpPacket(pkt, 4, src, dst, pkt[9] & 0xFF, ihl, total - ihl, total, ihl);
	}

	private static IpPacket parseIpv6(byte[] pkt) {
		if (pkt.length < 40) {
			return null;
		}
		int payloadLen = Checksum.getShort(pkt, 4);
		int total = 40 + payloadLen;
		if (total > pkt.length) {
			return null;
		}

		byte[] src = new byte[16];
		byte[] dst = new byte[16];
		System.arraycopy(pkt, 8, src, 0, 16);
		System.arraycopy(pkt, 24, dst, 0, 16);

		int next = pkt[6] & 0xFF;
		int offset = 40;
		for (int guard = 0; guard < 16; guard++) {
			switch (next) {
				case 0:   /* hop-by-hop */
				case 43:  /* routing */
				case 60: { /* destination options */
					if (offset + 2 > total) {
						return null;
					}
					int hdrLen = (pkt[offset + 1] & 0xFF);
					int len = (hdrLen + 1) * 8;
					if (offset + len > total) {
						return null;
					}
					next = pkt[offset] & 0xFF;
					offset += len;
					break;
				}
				case 44:  /* fragment */
				case 50:  /* ESP */
				case 51:  /* AH */
				case 59:  /* no next header */
					return null;
				default:
					return new IpPacket(pkt, 6, src, dst, next, offset, total - offset,
							total, offset);
			}
		}
		return null;
	}

	/** Construye un paquete IPv4 con DF activado. */
	public static byte[] buildIpv4(byte[] src, byte[] dst, int protocol,
			byte[] payload, int payloadOffset, int payloadLength, int id) {
		return buildIpv4(src, dst, protocol, payload, payloadOffset, payloadLength, id,
				0x4000, 64);
	}

	public static byte[] buildIpv4(byte[] src, byte[] dst, int protocol,
			byte[] payload, int payloadOffset, int payloadLength, int id,
			int flagsAndOffset) {
		return buildIpv4(src, dst, protocol, payload, payloadOffset, payloadLength, id,
				flagsAndOffset, 64);
	}

	public static byte[] buildIpv4(byte[] src, byte[] dst, int protocol,
			byte[] payload, int payloadOffset, int payloadLength, int id,
			int flagsAndOffset, int ttl) {
		int total = 20 + payloadLength;
		byte[] out = new byte[total];
		out[0] = 0x45;
		out[1] = 0;
		Checksum.putShort(out, 2, total);
		Checksum.putShort(out, 4, id & 0xFFFF);
		Checksum.putShort(out, 6, flagsAndOffset & 0xFFFF);
		out[8] = (byte) ttl;
		out[9] = (byte) protocol;
		System.arraycopy(src, 0, out, 12, 4);
		System.arraycopy(dst, 0, out, 16, 4);
		System.arraycopy(payload, payloadOffset, out, 20, payloadLength);
		int acc = Checksum.sum(out, 0, 20, 0);
		Checksum.putShort(out, 10, Checksum.finish(acc));
		return out;
	}

	public static byte[] buildIpv6(byte[] src, byte[] dst, int nextHeader,
			byte[] payload, int payloadOffset, int payloadLength) {
		int total = 40 + payloadLength;
		byte[] out = new byte[total];
		out[0] = 0x60;
		Checksum.putShort(out, 4, payloadLength);
		out[6] = (byte) nextHeader;
		out[7] = 64;
		System.arraycopy(src, 0, out, 8, 16);
		System.arraycopy(dst, 0, out, 24, 16);
		System.arraycopy(payload, payloadOffset, out, 40, payloadLength);
		return out;
	}
}
