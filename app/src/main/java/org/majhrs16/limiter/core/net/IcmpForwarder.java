package org.majhrs16.limiter.core.net;

import org.majhrs16.limiter.core.Diag;

import java.net.InetAddress;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Reenvía los echo request del dispositivo al destino real.
 *
 * <p>La sonda se ejecuta fuera del hilo del motor para no bloquear el resto
 * del tráfico. Solo se construye la respuesta si el destino contestó de
 * verdad; si la sonda falla no se inventa una respuesta local.</p>
 */
public final class IcmpForwarder {

	public interface Sink {
		void send(byte[] packet, FlowKey flow);
	}

	private static final String TAG = "IcmpForwarder";
	private static final int MAX_PENDING = 32;
	private static final int TIMEOUT_MS = 1200;

	private final IcmpProbe probe;
	private final Sink sink;
	private final AtomicInteger pending = new AtomicInteger();
	private volatile boolean running;
	private ExecutorService executor;

	public IcmpForwarder(IcmpProbe probe, Sink sink) {
		this.probe = probe;
		this.sink = sink;
	}

	public void start() {
		if (running) {
			return;
		}
		running = true;
		executor = Executors.newFixedThreadPool(2, runnable -> {
			Thread thread = new Thread(runnable, "icmp-probe");
			thread.setDaemon(true);
			return thread;
		});
		Diag.i(TAG, "Reenviador ICMP iniciado");
	}

	public void stop() {
		running = false;
		ExecutorService current = executor;
		executor = null;
		if (current != null) {
			current.shutdownNow();
		}
		Diag.i(TAG, "Reenviador ICMP detenido");
	}

	public void submit(IpPacket request) {
		if (!running || request == null || !Icmp.isEchoRequest(request)) {
			return;
		}
		ExecutorService current = executor;
		if (current == null) {
			return;
		}
		if (pending.incrementAndGet() > MAX_PENDING) {
			pending.decrementAndGet();
			Diag.w(TAG, "Demasiadas sondas ICMP pendientes; se descarta "
					+ address(request.dst));
			return;
		}

		/* El paquete del TUN se reutiliza al volver al bucle; se conserva una
		 * copia para el hilo de la sonda. */
		byte[] raw = Arrays.copyOf(request.raw, request.totalLength);
		IpPacket copy = IpPacket.parse(raw);
		if (copy == null) {
			pending.decrementAndGet();
			return;
		}
		try {
			current.execute(() -> runProbe(copy));
		} catch (Throwable error) {
			pending.decrementAndGet();
			Diag.w(TAG, "No se pudo encolar la sonda ICMP", error);
		}
	}

	private void runProbe(IpPacket request) {
		String destination = address(request.dst);
		int sequence = sequence(request);
		long start = System.nanoTime();
		try {
			byte[] payload = new byte[request.payloadLength];
			System.arraycopy(request.raw, request.payloadOffset, payload, 0, payload.length);

			IcmpProbe.Result result = probe == null
					? null
					: probe.probe(request.dst, payload, TIMEOUT_MS);
			long elapsed = System.nanoTime() - start;

			if (result == null || !result.reachable) {
				String detail = result == null ? "sin sonda" : result.method + ": " + result.detail;
				Diag.w(TAG, "ICMP " + destination + " seq=" + sequence
						+ " sin respuesta (" + detail + ") tras "
						+ (elapsed / 1_000_000L) + " ms");
				return;
			}

			byte[] reply = Icmp.buildEchoReply(request);
			if (reply == null) {
				Diag.w(TAG, "No se pudo construir la respuesta ICMP para " + destination);
				return;
			}

			FlowKey flow = new FlowKey(request.dst, 0, request.src, 0, request.protocol);
			sink.send(reply, flow);
			Diag.d(TAG, "ICMP " + destination + " seq=" + sequence + " respondido en "
					+ (result.rttNanos / 1_000_000L) + " ms vía " + result.method);
		} catch (Throwable error) {
			Diag.w(TAG, "Fallo reenviando ICMP a " + destination, error);
		} finally {
			pending.decrementAndGet();
		}
	}

	private static int sequence(IpPacket request) {
		if (request.payloadLength < 8) {
			return -1;
		}
		return Checksum.getShort(request.raw, request.payloadOffset + 6);
	}

	private static String address(byte[] raw) {
		try {
			return InetAddress.getByAddress(raw).getHostAddress();
		} catch (Throwable ignored) {
			return "?";
		}
	}
}
