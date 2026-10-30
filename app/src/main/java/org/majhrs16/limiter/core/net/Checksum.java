package org.majhrs16.limiter.core.net;

/**
 * Checksums de Internet (RFC 1071) y pseudo-cabeceras TCP/UDP/ICMPv6.
 *
 * <p>Todas las funciones trabajan sobre arreglos de bytes y desplazamientos
 * absolutos para evitar copias en el camino caliente del túnel.</p>
 */
public final class Checksum {

	private Checksum() {
	}

	/** Suma en complemento a uno de {@code len} bytes. */
	public static int sum(byte[] data, int offset, int len, int initial) {
		int acc = initial;
		int i = offset;
		final int end = offset + len;
		while (i + 1 < end) {
			acc += ((data[i] & 0xFF) << 8) | (data[i + 1] & 0xFF);
			i += 2;
		}
		if (i < end) {
			acc += (data[i] & 0xFF) << 8;
		}
		return acc;
	}

	/** Pliega los acarreos de 16 bits. */
	public static int fold(int acc) {
		while ((acc >>> 16) != 0) {
			acc = (acc & 0xFFFF) + (acc >>> 16);
		}
		return acc & 0xFFFF;
	}

	/** Resultado final (complemento a uno) listo para escribirse en el campo. */
	public static int finish(int acc) {
		return (~fold(acc)) & 0xFFFF;
	}

	/**
	 * Suma de la pseudo-cabecera IPv4/IPv6.
	 *
	 * @param src    dirección origen (4 o 16 bytes)
	 * @param dst    dirección destino (4 o 16 bytes)
	 * @param proto  protocolo de transporte
	 * @param length longitud del segmento de transporte
	 */
	public static int pseudoHeader(byte[] src, byte[] dst, int proto, int length) {
		int acc = sum(src, 0, src.length, 0);
		acc = sum(dst, 0, dst.length, acc);
		acc += proto;
		acc += length;
		return acc;
	}

	/** Verifica un checksum ya calculado sobre el rango indicado. */
	public static boolean verify(byte[] data, int offset, int len, int expected) {
		int acc = sum(data, offset, len, 0);
		acc = fold(acc);
		return acc == (expected & 0xFFFF);
	}

	/** Escribe un valor de 16 bits en orden de red. */
	public static void putShort(byte[] buf, int off, int value) {
		buf[off] = (byte) (value >>> 8);
		buf[off + 1] = (byte) value;
	}

	/** Lee un valor de 16 bits en orden de red. */
	public static int getShort(byte[] buf, int off) {
		return ((buf[off] & 0xFF) << 8) | (buf[off + 1] & 0xFF);
	}

	/** Escribe un valor de 32 bits en orden de red. */
	public static void putInt(byte[] buf, int off, long value) {
		buf[off] = (byte) (value >>> 24);
		buf[off + 1] = (byte) (value >>> 16);
		buf[off + 2] = (byte) (value >>> 8);
		buf[off + 3] = (byte) value;
	}

	/** Lee un valor de 32 bits sin signo en orden de red. */
	public static long getInt(byte[] buf, int off) {
		return ((long) (buf[off] & 0xFF) << 24)
				| ((long) (buf[off + 1] & 0xFF) << 16)
				| ((long) (buf[off + 2] & 0xFF) << 8)
				| (buf[off + 3] & 0xFF);
	}
}
