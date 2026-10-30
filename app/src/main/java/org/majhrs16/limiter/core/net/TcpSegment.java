package org.majhrs16.limiter.core.net;

/**
 * Segmento TCP: parseo con validación de checksum y construcción con
 * negociación de MSS/window scaling.
 */
public final class TcpSegment {

	public static final int FIN = 0x01;
	public static final int SYN = 0x02;
	public static final int RST = 0x04;
	public static final int PSH = 0x08;
	public static final int ACK = 0x10;
	public static final int URG = 0x20;

	private static final java.util.concurrent.atomic.AtomicInteger IP_ID =
			new java.util.concurrent.atomic.AtomicInteger(1);

	public final int srcPort;
	public final int dstPort;
	public final long seq;
	public final long ack;
	public final int headerLength;
	public final int flags;
	public final int window;
	public final int checksum;
	public final int payloadOffset;
	public final int payloadLength;

	public final int mss;
	public final int windowScale;
	public final boolean sackPermitted;
	public final boolean hasTimestamp;
	public final long timestampValue;
	public final long timestampEcho;

	private TcpSegment(int srcPort, int dstPort, long seq, long ack, int headerLength,
			int flags, int window, int checksum, int payloadOffset, int payloadLength,
			int mss, int windowScale, boolean sackPermitted, boolean hasTimestamp,
			long timestampValue, long timestampEcho) {
		this.srcPort = srcPort;
		this.dstPort = dstPort;
		this.seq = seq;
		this.ack = ack;
		this.headerLength = headerLength;
		this.flags = flags;
		this.window = window;
		this.checksum = checksum;
		this.payloadOffset = payloadOffset;
		this.payloadLength = payloadLength;
		this.mss = mss;
		this.windowScale = windowScale;
		this.sackPermitted = sackPermitted;
		this.hasTimestamp = hasTimestamp;
		this.timestampValue = timestampValue;
		this.timestampEcho = timestampEcho;
	}

	public boolean has(int flag) {
		return (flags & flag) != 0;
	}

	public static TcpSegment parse(IpPacket ip) {
		if (ip == null || ip.protocol != IpPacket.PROTO_TCP || ip.payloadLength < 20) {
			return null;
		}
		byte[] pkt = ip.raw;
		int off = ip.payloadOffset;
		int len = ip.payloadLength;

		int dataOffset = ((pkt[off + 12] & 0xFF) >>> 4) * 4;
		if (dataOffset < 20 || dataOffset > len) {
			return null;
		}

		int pseudo = Checksum.pseudoHeader(ip.src, ip.dst, IpPacket.PROTO_TCP, len);
		int acc = Checksum.sum(pkt, off, len, pseudo);
		if (Checksum.fold(acc) != 0xFFFF) {
			return null;
		}

		int srcPort = Checksum.getShort(pkt, off);
		int dstPort = Checksum.getShort(pkt, off + 2);
		long seq = Checksum.getInt(pkt, off + 4);
		long ack = Checksum.getInt(pkt, off + 8);
		int flags = pkt[off + 13] & 0xFF;
		int window = Checksum.getShort(pkt, off + 14);
		int checksum = Checksum.getShort(pkt, off + 16);

		int mss = 0;
		int windowScale = -1;
		boolean sack = false;
		boolean hasTs = false;
		long tsVal = 0;
		long tsEcr = 0;

		int i = off + 20;
		int end = off + dataOffset;
		while (i < end) {
			int kind = pkt[i] & 0xFF;
			if (kind == 0) {
				break;
			}
			if (kind == 1) {
				i++;
				continue;
			}
			if (i + 1 >= end) {
				break;
			}
			int optLen = pkt[i + 1] & 0xFF;
			if (optLen < 2 || i + optLen > end) {
				break;
			}
			switch (kind) {
				case 2:
					if (optLen == 4) {
						mss = Checksum.getShort(pkt, i + 2);
					}
					break;
				case 3:
					if (optLen == 3) {
						windowScale = pkt[i + 2] & 0xFF;
						if (windowScale > 14) {
							windowScale = 14;
						}
					}
					break;
				case 4:
					if (optLen == 2) {
						sack = true;
					}
					break;
				case 5:
					if (optLen == 10) {
						hasTs = true;
						tsVal = Checksum.getInt(pkt, i + 2);
						tsEcr = Checksum.getInt(pkt, i + 6);
					}
					break;
				default:
					break;
			}
			i += optLen;
		}

		return new TcpSegment(srcPort, dstPort, seq, ack, dataOffset, flags, window,
				checksum, off + dataOffset, len - dataOffset, mss, windowScale, sack,
				hasTs, tsVal, tsEcr);
	}

	/**
	 * Construye el paquete IP completo con el segmento TCP.
	 *
	 * @param mss           MSS a anunciar, o 0 para no incluirlo
	 * @param windowScale   escala de ventana a anunciar, o -1 para no incluirla
	 * @param sackPermitted anunciar SACK permitted (no se envían bloques SACK)
	 */
	public static byte[] build(byte[] srcIp, byte[] dstIp, int srcPort, int dstPort,
			long seq, long ack, int flags, int window,
			byte[] payload, int payloadOffset, int payloadLength,
			int mss, int windowScale, boolean sackPermitted) {

		byte[] options = new byte[40];
		int optLen = 0;
		if (mss > 0) {
			options[optLen++] = 2;
			options[optLen++] = 4;
			Checksum.putShort(options, optLen, mss);
			optLen += 2;
		}
		if (windowScale >= 0) {
			options[optLen++] = 3;
			options[optLen++] = 3;
			options[optLen++] = (byte) windowScale;
		}
		if (sackPermitted) {
			options[optLen++] = 4;
			options[optLen++] = 2;
		}
		while ((optLen & 3) != 0) {
			options[optLen++] = 1;
		}

		int headerLen = 20 + optLen;
		int total = headerLen + payloadLength;
		byte[] seg = new byte[total];

		Checksum.putShort(seg, 0, srcPort);
		Checksum.putShort(seg, 2, dstPort);
		Checksum.putInt(seg, 4, seq);
		Checksum.putInt(seg, 8, ack);
		seg[12] = (byte) ((headerLen / 4) << 4);
		seg[13] = (byte) flags;
		Checksum.putShort(seg, 14, window);
		Checksum.putShort(seg, 16, 0);
		Checksum.putShort(seg, 18, 0);

		System.arraycopy(options, 0, seg, 20, optLen);
		if (payloadLength > 0) {
			System.arraycopy(payload, payloadOffset, seg, headerLen, payloadLength);
		}

		int pseudo = Checksum.pseudoHeader(srcIp, dstIp, IpPacket.PROTO_TCP, total);
		int acc = Checksum.sum(seg, 0, total, pseudo);
		Checksum.putShort(seg, 16, Checksum.finish(acc));

		if (srcIp.length == 16) {
			return IpPacket.buildIpv6(srcIp, dstIp, IpPacket.PROTO_TCP, seg, 0, total);
		}
		return IpPacket.buildIpv4(srcIp, dstIp, IpPacket.PROTO_TCP, seg, 0, total,
				IP_ID.getAndIncrement());
	}
}
