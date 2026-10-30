package org.majhrs16.limiter;

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;

import org.majhrs16.limiter.core.Diag;
import org.majhrs16.limiter.core.net.Checksum;
import org.majhrs16.limiter.core.net.IcmpProbe;

import java.io.FileDescriptor;
import java.net.InetAddress;
import java.util.Arrays;

/**
 * Sonda ICMP para Android.
 *
 * <p>Intenta usar un socket {@code SOCK_DGRAM} + {@code IPPROTO_ICMP} (el mismo
 * mecanismo que {@code ping} sin privilegios). Si el kernel no lo permite, cae
 * a {@link InetAddress#isReachable(int)}, que también hace una comprobación
 * real. Nunca devuelve una respuesta inventada.</p>
 */
public final class AndroidIcmpProbe implements IcmpProbe {

	private static final String TAG = "AndroidIcmpProbe";

	public AndroidIcmpProbe() {
	}

	@Override
	public Result probe(byte[] dstIp, byte[] icmpPayload, int timeoutMs) {
		long start = System.nanoTime();
		try {
			Result direct = probeIcmp(dstIp, icmpPayload, timeoutMs, start);
			if (direct.reachable) {
				return direct;
			}
			/* Un fallo de tipo/secuencia/datos puede deberse a cómo el
			 * kernel reescribe el identificador; se intenta el respaldo
			 * real antes de declarar el destino inalcanzable. */
			Diag.d(TAG, "Sonda ICMP directa sin éxito (" + direct.detail
					+ "); se prueba isReachable");
		} catch (ErrnoException error) {
			Diag.w(TAG, "Socket ICMP sin privilegios no disponible (errno="
					+ error.errno + "); se prueba isReachable", error);
		} catch (Throwable error) {
			Diag.w(TAG, "La sonda ICMP falló; se prueba isReachable", error);
		}

		try {
			InetAddress address = InetAddress.getByAddress(dstIp);
			long begin = System.nanoTime();
			/* El respaldo solo recibe la mitad del tiempo para que el
			 * ping del dispositivo no espere demasiado. */
			boolean reachable = address.isReachable(Math.max(1, timeoutMs / 2));
			long elapsed = System.nanoTime() - begin;
			return new Result(reachable, elapsed, "isReachable",
					reachable ? null : "sin respuesta");
		} catch (Throwable error) {
			return Result.unreachable("isReachable", error.toString());
		}
	}

	private Result probeIcmp(byte[] dstIp, byte[] icmpPayload, int timeoutMs,
			long start) throws Exception {
		int domain = dstIp.length == 16 ? OsConstants.AF_INET6 : OsConstants.AF_INET;
		int protocol = dstIp.length == 16
				? OsConstants.IPPROTO_ICMPV6
				: OsConstants.IPPROTO_ICMP;

		FileDescriptor fd = Os.socket(domain, OsConstants.SOCK_DGRAM, protocol);
		try {
			/* La app está excluida de la VPN en todos los modos de split
			 * tunneling, así que este socket ya usa la red subyacente. No
			 * se puede llamar a VpnService.protect(int) desde aquí porque
			 * el FileDescriptor público no expone su entero. */

			InetAddress destination = InetAddress.getByAddress(dstIp);
			/* El socket ICMP sin privilegios debe estar enlazado para que
			 * ping_bind() asigne un identificador único y lo registre en la
			 * tabla de sockets. Sin bind(), el kernel usa id 0, no encuentra
			 * el socket al recibir el eco y la respuesta se descarta. */
			InetAddress any = dstIp.length == 16
					? InetAddress.getByName("::")
					: InetAddress.getByName("0.0.0.0");
			Os.bind(fd, any, 0);
			Os.connect(fd, destination, 0);

			byte[] message = buildProbeMessage(dstIp, icmpPayload);
			Os.write(fd, message, 0, message.length);

			StructPollfd poll = new StructPollfd();
			poll.fd = fd;
			poll.events = (short) OsConstants.POLLIN;
			int ready = Os.poll(new StructPollfd[]{poll}, Math.max(1, timeoutMs));
			if (ready <= 0) {
				return Result.unreachable("icmp", "tiempo de espera agotado");
			}

			byte[] buffer = new byte[Math.max(1500, message.length + 64)];
			int read;
			try {
				read = Os.read(fd, buffer, 0, buffer.length);
			} catch (ErrnoException error) {
				if (error.errno == OsConstants.EAGAIN) {
					return Result.unreachable("icmp", "tiempo de espera agotado");
				}
				throw error;
			}

			long elapsed = System.nanoTime() - start;
			if (read < 8) {
				return Result.unreachable("icmp", "respuesta vacía");
			}
			int expectedType = dstIp.length == 16 ? 129 : 0;
			int type = buffer[0] & 0xFF;
			if (type != expectedType) {
				return Result.unreachable("icmp", "tipo " + type);
			}
			int sequence = Checksum.getShort(buffer, 6);
			int originalSequence = Checksum.getShort(icmpPayload, 6);
			if (sequence != originalSequence) {
				return Result.unreachable("icmp", "secuencia " + sequence);
			}
			int dataLength = Math.min(read - 8, icmpPayload.length - 8);
			for (int i = 0; i < dataLength; i++) {
				if (buffer[8 + i] != icmpPayload[8 + i]) {
					return Result.unreachable("icmp", "datos distintos");
				}
			}
			return Result.reachable(elapsed, "icmp");
		} finally {
			try {
				Os.close(fd);
			} catch (Throwable ignored) {
				/* nada */
			}
		}
	}

	/**
	 * El kernel reescribe el identificador del eco para el socket sin
	 * privilegios; la secuencia y los datos se conservan. El checksum IPv4 se
	 * recalcula aquí y el de IPv6 lo calcula el propio kernel.
	 */
	private static byte[] buildProbeMessage(byte[] dstIp, byte[] original) {
		byte[] message = Arrays.copyOf(original, original.length);
		if (message.length < 8) {
			return message;
		}
		message[2] = 0;
		message[3] = 0;
		if (dstIp.length == 4) {
			int sum = Checksum.sum(message, 0, message.length, 0);
			Checksum.putShort(message, 2, Checksum.finish(sum));
		}
		return message;
	}
}
