package org.majhrs16.limiter.core.net;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Respuestas ICMP locales (echo) y errores de tamaño para IPv6. */
public final class Icmp {

	private Icmp() {
	}

	public static boolean isEchoRequest(IpPacket ip) {
		if (ip == null) {
			return false;
		}
		if (ip.protocol == IpPacket.PROTO_ICMP && ip.payloadLength >= 8) {
			return (ip.raw[ip.payloadOffset] & 0xFF) == 8;
		}
		if (ip.protocol == IpPacket.PROTO_ICMPV6 && ip.payloadLength >= 8) {
			return (ip.raw[ip.payloadOffset] & 0xFF) == 128;
		}
		return false;
	}

	/**
	 * Construye la respuesta a un echo request. La respuesta se genera
	 * localmente (el TUN no tiene un peer ICMP real).
	 */
	public static byte[] buildEchoReply(IpPacket ip) {
		if (!isEchoRequest(ip)) {
			return null;
		}
		byte[] out = Arrays.copyOf(ip.raw, ip.totalLength);
		int off = ip.payloadOffset;

		if (ip.version == 4) {
			/* Intercambia direcciones. */
			System.arraycopy(ip.dst, 0, out, 12, 4);
			System.arraycopy(ip.src, 0, out, 16, 4);
			out[8] = 64;
			out[off] = 0;     /* echo reply */
			out[off + 1] = 0;

			Checksum.putShort(out, off + 2, 0);
			int acc = Checksum.sum(out, off, ip.payloadLength, 0);
			Checksum.putShort(out, off + 2, Checksum.finish(acc));

			Checksum.putShort(out, 10, 0);
			int ipAcc = Checksum.sum(out, 0, ip.headerLength, 0);
			Checksum.putShort(out, 10, Checksum.finish(ipAcc));
			return out;
		}

		/* IPv6: intercambia direcciones. */
		System.arraycopy(ip.dst, 0, out, 8, 16);
		System.arraycopy(ip.src, 0, out, 24, 16);
		out[7] = 64;
		out[off] = (byte) 129; /* echo reply */
		out[off + 1] = 0;

		Checksum.putShort(out, off + 2, 0);
		int pseudo = Checksum.pseudoHeader(ip.dst, ip.src, IpPacket.PROTO_ICMPV6,
				ip.payloadLength);
		int acc = Checksum.sum(out, off, ip.payloadLength, pseudo);
		Checksum.putShort(out, off + 2, Checksum.finish(acc));
		return out;
	}

	/**
	 * ICMPv6 Packet Too Big (tipo 2) con el MTU del túnel. Permite que el
	 * emisor remoto ajuste su PMTU cuando un datagrama UDP no cabe en el TUN.
	 */
	public static byte[] buildPacketTooBig(IpPacket original, int mtu) {
		if (original == null || original.version != 6) {
			return null;
		}
		int include = Math.min(original.totalLength, 1232);
		int icmpLength = 8 + include;
		byte[] icmp = new byte[icmpLength];
		icmp[0] = 2;
		icmp[1] = 0;
		Checksum.putShort(icmp, 2, 0);
		Checksum.putInt(icmp, 4, mtu);
		System.arraycopy(original.raw, 0, icmp, 8, include);

		int pseudo = Checksum.pseudoHeader(original.dst, original.src,
				IpPacket.PROTO_ICMPV6, icmpLength);
		int acc = Checksum.sum(icmp, 0, icmpLength, pseudo);
		Checksum.putShort(icmp, 2, Checksum.finish(acc));

		return IpPacket.buildIpv6(original.dst, original.src, IpPacket.PROTO_ICMPV6,
				icmp, 0, icmpLength);
	}

	/**
	 * Fragmenta un datagrama IPv4 para que cada trozo quepa en el MTU.
	 * El checksum de transporte se calculó sobre el datagrama completo.
	 */
	public static List<byte[]> fragmentIpv4(byte[] src, byte[] dst, int protocol,
			byte[] payload, int payloadOffset, int payloadLength, int mtu, int id) {
		List<byte[]> out = new ArrayList<>();
		int maxData = ((mtu - 20) / 8) * 8;
		if (maxData <= 0) {
			return out;
		}
		int position = 0;
		while (position < payloadLength) {
			int chunk = Math.min(maxData, payloadLength - position);
			boolean more = position + chunk < payloadLength;
			int flags = more ? 0x2000 : 0;
			flags |= (position / 8) & 0x1FFF;
			out.add(IpPacket.buildIpv4(src, dst, protocol, payload,
					payloadOffset + position, chunk, id, flags));
			position += chunk;
		}
		return out;
	}
}
