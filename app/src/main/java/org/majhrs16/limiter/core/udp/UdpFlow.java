package org.majhrs16.limiter.core.udp;

import org.majhrs16.limiter.core.Diag;
import org.majhrs16.limiter.core.Stats;
import org.majhrs16.limiter.core.net.Delivery;
import org.majhrs16.limiter.core.net.FlowKey;
import org.majhrs16.limiter.core.net.Icmp;
import org.majhrs16.limiter.core.net.IpPacket;
import org.majhrs16.limiter.core.net.UdpDatagram;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.util.Arrays;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/** Flujo UDP con NAT por 5-tupla y socket protegido. */
public final class UdpFlow {

	public interface Callbacks {
		void enqueueDownload(byte[] packet, FlowKey flow, Delivery delivery);

		void wakeup();

		int mtu();

		Stats stats();

		/** Buffer compartido del hilo del motor para leer datagramas. */
		ByteBuffer readBuffer();

		void onFlowClosed(UdpFlow flow);
	}

	private static final Delivery NOOP = new Delivery() {
		@Override
		public void onSent() {
		}

		@Override
		public void onDropped() {
		}
	};

	private static final long IDLE_TIMEOUT = 60_000_000_000L;
	private static final int MAX_OUTBOUND_BYTES = 64 * 1024;
	private static final int MAX_IO_ITERATIONS = 32;
	private static final AtomicInteger IP_ID = new AtomicInteger(1);
	private static final String TAG = "UdpFlow";

	private final Callbacks cb;
	private final FlowKey key;
	private final FlowKey downloadKey;
	private final DatagramChannel channel;
	private final ConcurrentLinkedQueue<byte[]> outbound = new ConcurrentLinkedQueue<>();
	private final AtomicInteger outboundBytes = new AtomicInteger();
	private SelectionKey selection;
	private volatile long lastActivity;
	private boolean closed;

	public UdpFlow(Callbacks cb, FlowKey key, DatagramChannel channel, long now) {
		this.cb = cb;
		this.key = key;
		this.downloadKey = key.reverse();
		this.channel = channel;
		this.lastActivity = now;
	}

	public FlowKey key() {
		return key;
	}

	public DatagramChannel channel() {
		return channel;
	}

	public void attach(SelectionKey selection) {
		this.selection = selection;
	}

	public boolean isClosed() {
		return closed;
	}

	public void onReadable(long now) {
		if (closed) {
			return;
		}
		ByteBuffer buffer = cb.readBuffer();
		try {
			for (int iteration = 0; iteration < MAX_IO_ITERATIONS; iteration++) {
				buffer.clear();
				int read = channel.read(buffer);
				if (read <= 0) {
					break;
				}
				lastActivity = now;

				byte[] payload = Arrays.copyOf(buffer.array(), read);
				byte[] packet = UdpDatagram.build(key.dstIp, key.srcIp,
						key.dstPort, key.srcPort, payload, 0, read);

				int mtu = cb.mtu();
				if (packet.length <= mtu) {
					cb.enqueueDownload(packet, downloadKey, NOOP);
				} else if (key.isIpv6()) {
					IpPacket ip = IpPacket.parse(packet);
					byte[] tooBig = Icmp.buildPacketTooBig(ip, mtu);
					if (tooBig != null) {
						cb.enqueueDownload(tooBig, downloadKey, NOOP);
					}
				} else {
					for (byte[] fragment : Icmp.fragmentIpv4(key.dstIp, key.srcIp,
							IpPacket.PROTO_UDP, packet, 20, packet.length - 20, mtu,
							IP_ID.getAndIncrement())) {
						cb.enqueueDownload(fragment, downloadKey, NOOP);
					}
				}
				if (key.dstPort == 53) {
					Diag.i(TAG, "DNS respuesta de " + address(key.dstIp)
							+ " (" + read + " bytes)");
				}
			}
		} catch (Throwable error) {
			Diag.w(TAG, "Lectura UDP falló para " + key, error);
			close(now);
			return;
		}
		updateInterest();
	}

	public void onWritable(long now) {
		if (closed) {
			return;
		}
		try {
			for (int iteration = 0; iteration < MAX_IO_ITERATIONS; iteration++) {
				byte[] data = outbound.peek();
				if (data == null) {
					break;
				}
				int written = channel.write(ByteBuffer.wrap(data));
				if (written <= 0) {
					break;
				}
				if (written < data.length) {
					/* UDP no debería partir datagramas; se descarta el resto. */
					outbound.poll();
					outboundBytes.addAndGet(-data.length);
					cb.stats().dropped.incrementAndGet();
				} else {
					outbound.poll();
					outboundBytes.addAndGet(-data.length);
				}
			}
		} catch (Throwable error) {
			Diag.w(TAG, "Escritura UDP falló para " + key, error);
			close(now);
			return;
		}
		updateInterest();
	}

	/** Llamado desde el hilo del shaper de subida. */
	public void onUploadReady(byte[] data) {
		if (outboundBytes.addAndGet(data.length) > MAX_OUTBOUND_BYTES) {
			outboundBytes.addAndGet(-data.length);
			cb.stats().dropped.incrementAndGet();
			if (Diag.isPacketTrace()) {
				Diag.d(TAG, "Cola UDP llena; se descarta " + key);
			}
			return;
		}
		outbound.add(data);
		cb.wakeup();
	}

	public void onTimer(long now) {
		if (!closed && now - lastActivity > IDLE_TIMEOUT) {
			close(now);
		}
	}

	public void updateInterest() {
		SelectionKey k = selection;
		if (k == null || !k.isValid() || closed) {
			return;
		}
		int ops = SelectionKey.OP_READ;
		if (!outbound.isEmpty()) {
			ops |= SelectionKey.OP_WRITE;
		}
		if (k.interestOps() != ops) {
			k.interestOps(ops);
		}
	}

	public void close(long now) {
		if (closed) {
			return;
		}
		closed = true;
		if (selection != null) {
			selection.cancel();
		}
		try {
			channel.close();
		} catch (IOException ignored) {
			/* nada */
		}
		Diag.d(TAG, "Flujo UDP cerrado " + key);
		cb.onFlowClosed(this);
	}

	private static String address(byte[] raw) {
		try {
			return java.net.InetAddress.getByAddress(raw).getHostAddress();
		} catch (Throwable ignored) {
			return "?";
		}
	}
}
