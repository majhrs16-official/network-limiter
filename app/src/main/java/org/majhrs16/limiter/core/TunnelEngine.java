package org.majhrs16.limiter.core;

import android.os.ParcelFileDescriptor;

import org.majhrs16.limiter.core.net.Delivery;
import org.majhrs16.limiter.core.net.FlowKey;
import org.majhrs16.limiter.core.net.Icmp;
import org.majhrs16.limiter.core.net.IpPacket;
import org.majhrs16.limiter.core.net.TcpSegment;
import org.majhrs16.limiter.core.net.UdpDatagram;
import org.majhrs16.limiter.core.shaper.CakeQueue;
import org.majhrs16.limiter.core.shaper.CakeShaper;
import org.majhrs16.limiter.core.tcp.TcpCallbacks;
import org.majhrs16.limiter.core.tcp.TcpConnection;
import org.majhrs16.limiter.core.udp.UdpFlow;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Motor del túnel: hilo lector del TUN, selector de sockets salientes,
 * pila TCP/UDP en espacio de usuario y dos shapers Cake independientes.
 */
public final class TunnelEngine implements Runnable, TcpCallbacks, UdpFlow.Callbacks {

	private static final String TAG = "TunnelEngine";
	private static final int MAX_TCP = 512;
	private static final int MAX_UDP = 256;
	private static final long TICK_NANOS = 100_000_000L;
	private static final long UP_BUDGET = 8L << 20;

	private static final Delivery NOOP = new Delivery() {
		@Override
		public void onSent() {
		}

		@Override
		public void onDropped() {
		}
	};

	public interface Protector {
		boolean protectSocket(SocketChannel channel);

		boolean protectDatagram(DatagramChannel channel);
	}

	private final ParcelFileDescriptor tunPfd;
	private final Protector protector;
	private final Stats stats;
	private final ArrayBlockingQueue<byte[]> inbox = new ArrayBlockingQueue<>(2048);
	private final Map<FlowKey, TcpConnection> tcp = new HashMap<>();
	private final Map<FlowKey, UdpFlow> udp = new HashMap<>();

	private volatile VpnConfig config;
	private volatile boolean running;
	private volatile long upWindowCap;
	private Selector selector;
	private FileInputStream tunIn;
	private FileOutputStream tunOut;
	private Thread loopThread;
	private Thread readerThread;
	private CakeQueue upQueue;
	private CakeQueue downQueue;
	private CakeShaper upShaper;
	private CakeShaper downShaper;
	private final AtomicLong icmpId = new AtomicLong(1);
	/** Bytes de subida aceptados y aún no entregados al socket. */
	private final AtomicLong upPending = new AtomicLong();
	private final ByteBuffer udpReadBuffer = ByteBuffer.allocate(65535);

	public TunnelEngine(ParcelFileDescriptor tunPfd, Protector protector, VpnConfig config,
			Stats stats) {
		this.tunPfd = tunPfd;
		this.protector = protector;
		this.config = config.copy();
		this.stats = stats;
	}

	public void start() throws IOException {
		if (running) {
			return;
		}
		tunIn = new FileInputStream(tunPfd.getFileDescriptor());
		tunOut = new FileOutputStream(tunPfd.getFileDescriptor());
		selector = Selector.open();

		upQueue = new CakeQueue(config.mtu, config.upBytesPerSecond(),
				config.rttMs * 1_000_000L, false);
		downQueue = new CakeQueue(config.mtu, config.downBytesPerSecond(),
				config.rttMs * 1_000_000L, true);
		/* Lo ya confirmado al dispositivo no debe perderse: la cola de
		 * subida no aplica AQM y tiene holgura de memoria. */
		upQueue.setBufferLimit(16L << 20);
		upWindowCap = computeWindowCap(config);

		upShaper = new CakeShaper("cake-up", upQueue, this::deliverUpload);
		downShaper = new CakeShaper("cake-down", downQueue, this::deliverDownload);

		running = true;
		stats.start();
		upShaper.start();
		downShaper.start();

		readerThread = new Thread(this::readLoop, "tun-reader");
		readerThread.setDaemon(true);
		readerThread.start();

		loopThread = new Thread(this, "tunnel-engine");
		loopThread.setDaemon(true);
		loopThread.start();

		Diag.i(TAG, "Motor iniciado (up=" + config.upKbps + " kbps, down="
				+ config.downKbps + " kbps, rtt=" + config.rttMs + " ms)");
	}

	public void stop() {
		if (!running) {
			return;
		}
		running = false;
		Diag.i(TAG, "Deteniendo motor");

		Selector s = selector;
		if (s != null) {
			s.wakeup();
		}
		try {
			tunPfd.close();
		} catch (IOException ignored) {
			/* nada */
		}
		if (upShaper != null) {
			upShaper.stop();
		}
		if (downShaper != null) {
			downShaper.stop();
		}

		join(loopThread);
		join(readerThread);

		long now = System.nanoTime();
		for (TcpConnection connection : new ArrayList<>(tcp.values())) {
			connection.close(false, now);
		}
		for (UdpFlow flow : new ArrayList<>(udp.values())) {
			flow.close(now);
		}
		tcp.clear();
		udp.clear();

		if (s != null) {
			try {
				s.close();
			} catch (IOException ignored) {
				/* nada */
			}
		}
		stats.tcpConnections.set(0);
		stats.udpFlows.set(0);
		stats.upQueueBytes.set(0);
		stats.downQueueBytes.set(0);
	}

	public void updateConfig(VpnConfig updated) {
		VpnConfig copy = updated.copy();
		config = copy;
		upWindowCap = computeWindowCap(copy);
		if (upQueue != null) {
			upQueue.setRate(copy.upBytesPerSecond(), copy.rttMs * 1_000_000L);
		}
		if (downQueue != null) {
			downQueue.setRate(copy.downBytesPerSecond(), copy.rttMs * 1_000_000L);
		}
		Diag.i(TAG, "Configuración aplicada: up=" + copy.upKbps + " kbps, down="
				+ copy.downKbps + " kbps, rtt=" + copy.rttMs + " ms");
	}

	/* ------------------------------ hilos ------------------------------ */

	private void readLoop() {
		byte[] buffer = new byte[Math.max(config.mtu + 256, 2048)];
		while (running) {
			int read;
			try {
				read = tunIn.read(buffer);
			} catch (IOException e) {
				if (running) {
					Diag.w(TAG, "Lectura del TUN terminó", e);
				}
				break;
			}
			if (read <= 0) {
				continue;
			}
			byte[] packet = Arrays.copyOf(buffer, read);
			if (!inbox.offer(packet)) {
				stats.dropped.incrementAndGet();
				continue;
			}
			Selector s = selector;
			if (s != null) {
				s.wakeup();
			}
		}
	}

	@Override
	public void run() {
		long lastTick = 0;
		long lastSample = 0;
		try {
			while (running) {
				selector.select(50);
				long now = System.nanoTime();

				Iterator<SelectionKey> iterator = selector.selectedKeys().iterator();
				while (iterator.hasNext()) {
					SelectionKey key = iterator.next();
					iterator.remove();
					dispatch(key, now);
				}

				drainInbox(now);
				refreshInterests();

				if (now - lastTick >= TICK_NANOS) {
					timers(now);
					lastTick = now;
				}
				if (now - lastSample >= 1_000_000_000L) {
					stats.sample();
					stats.upQueueBytes.set(upQueue.backlogBytes());
					stats.downQueueBytes.set(downQueue.backlogBytes());
					lastSample = now;
				}
			}
		} catch (ClosedSelectorException ignored) {
			/* parada normal */
		} catch (Throwable t) {
			Diag.e(TAG, "El bucle del motor falló", t);
		}
	}

	private void dispatch(SelectionKey key, long now) {
		Object attachment = key.attachment();
		try {
			if (attachment instanceof TcpConnection) {
				TcpConnection connection = (TcpConnection) attachment;
				if (!key.isValid()) {
					return;
				}
				if (key.isConnectable()) {
					connection.onConnectable(now);
				}
				if (key.isValid() && key.isReadable()) {
					connection.onReadable(now);
				}
				if (key.isValid() && key.isWritable()) {
					connection.onWritable(now);
				}
			} else if (attachment instanceof UdpFlow) {
				UdpFlow flow = (UdpFlow) attachment;
				if (!key.isValid()) {
					return;
				}
				if (key.isReadable()) {
					flow.onReadable(now);
				}
				if (key.isValid() && key.isWritable()) {
					flow.onWritable(now);
				}
			}
		} catch (CancelledKeyException ignored) {
			/* la conexión se cerró durante el dispatch */
		} catch (Throwable t) {
			Diag.e(TAG, "Error atendiendo un socket", t);
		}
	}

	private void drainInbox(long now) {
		for (int i = 0; i < 512; i++) {
			byte[] packet = inbox.poll();
			if (packet == null) {
				return;
			}
			handlePacket(packet, now);
		}
	}

	/**
	 * El shaper añade datos a las colas de escritura desde su propio hilo;
	 * tras despertar hay que reactivar OP_WRITE en los sockets afectados.
	 */
	private void refreshInterests() {
		for (TcpConnection connection : tcp.values()) {
			connection.updateInterest();
		}
		for (UdpFlow flow : udp.values()) {
			flow.updateInterest();
		}
	}

	private void timers(long now) {
		for (TcpConnection connection : new ArrayList<>(tcp.values())) {
			connection.onTimer(now);
		}
		for (UdpFlow flow : new ArrayList<>(udp.values())) {
			flow.onTimer(now);
		}
		stats.upQueueBytes.set(upQueue.backlogBytes());
		stats.downQueueBytes.set(downQueue.backlogBytes());
		stats.aqmDrops.set(downQueue.aqmDroppedPackets());
	}

	/* --------------------------- paquetes ------------------------------ */

	private void handlePacket(byte[] packet, long now) {
		IpPacket ip = IpPacket.parse(packet);
		if (ip == null) {
			stats.badPackets.incrementAndGet();
			return;
		}
		stats.upBytes.addAndGet(packet.length);
		stats.upPackets.incrementAndGet();

		switch (ip.protocol) {
			case IpPacket.PROTO_TCP: {
				TcpSegment segment = TcpSegment.parse(ip);
				if (segment == null) {
					stats.badPackets.incrementAndGet();
					return;
				}
				handleTcp(ip, segment, now);
				break;
			}
			case IpPacket.PROTO_UDP: {
				UdpDatagram datagram = UdpDatagram.parse(ip);
				if (datagram == null) {
					stats.badPackets.incrementAndGet();
					return;
				}
				handleUdp(ip, datagram, now);
				break;
			}
			case IpPacket.PROTO_ICMP:
			case IpPacket.PROTO_ICMPV6:
				handleIcmp(ip);
				break;
			default:
				stats.dropped.incrementAndGet();
				break;
		}
	}

	private void handleTcp(IpPacket ip, TcpSegment segment, long now) {
		FlowKey key = new FlowKey(ip.src, segment.srcPort, ip.dst, segment.dstPort,
				IpPacket.PROTO_TCP);
		TcpConnection connection = tcp.get(key);

		if (connection == null) {
			if (segment.has(TcpSegment.SYN) && !segment.has(TcpSegment.ACK)
					&& !segment.has(TcpSegment.RST)) {
				if (tcp.size() >= MAX_TCP) {
					sendRst(ip, segment);
					stats.dropped.incrementAndGet();
					return;
				}
				connection = new TcpConnection(this, key, ip, segment, now);
				tcp.put(key, connection);
				stats.tcpConnections.incrementAndGet();
				connection.open(now);
				if (!connection.isClosed()) {
					register(connection);
				}
			} else if (!segment.has(TcpSegment.RST)) {
				sendRst(ip, segment);
			}
			return;
		}

		connection.onSegment(ip, segment, now);
	}

	private void handleUdp(IpPacket ip, UdpDatagram datagram, long now) {
		if (isLocalOnly(ip.dst)) {
			/* mDNS, SSDP, DHCP, descubrimiento IPv6: no salen del enlace. */
			stats.dropped.incrementAndGet();
			return;
		}
		FlowKey key = new FlowKey(ip.src, datagram.srcPort, ip.dst, datagram.dstPort,
				IpPacket.PROTO_UDP);
		UdpFlow flow = udp.get(key);

		if (flow == null) {
			if (udp.size() >= MAX_UDP) {
				stats.dropped.incrementAndGet();
				return;
			}
			try {
				DatagramChannel channel = DatagramChannel.open();
				channel.configureBlocking(false);
				if (!protector.protectDatagram(channel)) {
					channel.close();
					return;
				}
				channel.connect(new InetSocketAddress(
						InetAddress.getByAddress(ip.dst), datagram.dstPort));
				flow = new UdpFlow(this, key, channel, now);
				udp.put(key, flow);
				SelectionKey selection = channel.register(selector, 0, flow);
				flow.attach(selection);
				flow.updateInterest();
				stats.udpFlows.incrementAndGet();
			} catch (IOException e) {
				Diag.w(TAG, "No se pudo abrir UDP hacia " + key, e);
				stats.dropped.incrementAndGet();
				return;
			}
		}

		byte[] payload = new byte[datagram.payloadLength];
		System.arraycopy(ip.raw, datagram.payloadOffset, payload, 0, payload.length);
		int overhead = ip.isIpv6() ? 48 : 28;
		enqueueUpload(flow, payload, payload.length + overhead);
	}

	private void handleIcmp(IpPacket ip) {
		if (isLocalOnly(ip.dst)) {
			return;
		}
		byte[] reply = Icmp.buildEchoReply(ip);
		if (reply != null) {
			FlowKey flow = new FlowKey(ip.dst, 0, ip.src, 0, ip.protocol);
			enqueueDownload(reply, flow, NOOP);
		} else if (Icmp.isEchoRequest(ip)) {
			stats.dropped.incrementAndGet();
		}
	}

	/** Direcciones que no tienen sentido fuera del enlace local. */
	private static boolean isLocalOnly(byte[] dst) {
		if (dst.length == 4) {
			int first = dst[0] & 0xFF;
			if (first >= 224 && first <= 239) {
				return true;
			}
			if (first == 169 && (dst[1] & 0xFF) == 254) {
				return true; /* link-local 169.254/16 */
			}
			return first == 255 && (dst[1] & 0xFF) == 255
					&& (dst[2] & 0xFF) == 255 && (dst[3] & 0xFF) == 255;
		}
		if ((dst[0] & 0xFF) == 0xFF) {
			return true; /* multicast ff00::/8 */
		}
		/* link-local unicast fe80::/10 */
		return (dst[0] & 0xFF) == 0xFE && (dst[1] & 0xC0) == 0x80;
	}

	private void sendRst(IpPacket ip, TcpSegment segment) {
		long seq;
		long ack = 0;
		int flags;
		if (segment.has(TcpSegment.ACK)) {
			seq = segment.ack;
			flags = TcpSegment.RST;
		} else {
			seq = 0;
			ack = TcpConnection.add(segment.seq, segment.payloadLength
					+ (segment.has(TcpSegment.SYN) ? 1 : 0)
					+ (segment.has(TcpSegment.FIN) ? 1 : 0));
			flags = TcpSegment.RST | TcpSegment.ACK;
		}
		byte[] packet = TcpSegment.build(ip.dst, ip.src, segment.dstPort, segment.srcPort,
				seq, ack, flags, 0, null, 0, 0, 0, -1, false);
		FlowKey key = new FlowKey(ip.dst, segment.dstPort, ip.src, segment.srcPort,
				IpPacket.PROTO_TCP);
		enqueueDownload(packet, key, NOOP);
	}

	private void register(TcpConnection connection) {
		try {
			SelectionKey selection = connection.channel().register(selector, 0, connection);
			connection.attach(selection);
			connection.updateInterest();
		} catch (ClosedChannelException e) {
			connection.close(true, System.nanoTime());
		}
	}

	/* ---------------------------- shapers ------------------------------ */

	private void deliverUpload(CakeQueue.Item item) {
		upPending.addAndGet(-item.data.length);
		Object tag = item.tag;
		if (tag instanceof TcpConnection) {
			((TcpConnection) tag).onUploadReady(item.data);
		} else if (tag instanceof UdpFlow) {
			((UdpFlow) tag).onUploadReady(item.data);
		}
	}

	private void deliverDownload(CakeQueue.Item item) {
		boolean sent = false;
		try {
			tunOut.write(item.data, 0, item.data.length);
			stats.downBytes.addAndGet(item.data.length);
			stats.downPackets.incrementAndGet();
			sent = true;
		} catch (IOException e) {
			if (running) {
				Diag.w(TAG, "Escritura al TUN falló", e);
			}
		}
		if (item.tag instanceof Delivery) {
			Delivery delivery = (Delivery) item.tag;
			if (sent) {
				delivery.onSent();
			} else {
				delivery.onDropped();
			}
		}
	}

	@Override
	public void enqueueDownload(byte[] packet, FlowKey flow, Delivery delivery) {
		Delivery d = delivery == null ? NOOP : delivery;
		CakeQueue.Item item = new CakeQueue.Item(flow, packet, packet.length, d,
				() -> d.onDropped());
		downQueue.enqueue(item, System.nanoTime());
	}

	@Override
	public void enqueueUpload(TcpConnection connection, byte[] payload, int wireLength) {
		upPending.addAndGet(payload.length);
		CakeQueue.Item item = new CakeQueue.Item(connection.key(), payload, wireLength,
				connection, () -> {
					upPending.addAndGet(-payload.length);
					connection.onUploadDropped(payload.length);
				});
		upQueue.enqueue(item, System.nanoTime());
	}

	private void enqueueUpload(UdpFlow flow, byte[] payload, int wireLength) {
		upPending.addAndGet(payload.length);
		CakeQueue.Item item = new CakeQueue.Item(flow.key(), payload, wireLength, flow,
				() -> upPending.addAndGet(-payload.length));
		upQueue.enqueue(item, System.nanoTime());
	}

	/* --------------------------- callbacks ----------------------------- */

	@Override
	public void onConnectionClosed(TcpConnection connection) {
		if (tcp.remove(connection.key()) != null) {
			stats.tcpConnections.decrementAndGet();
		}
	}

	@Override
	public void onFlowClosed(UdpFlow flow) {
		if (udp.remove(flow.key()) != null) {
			stats.udpFlows.decrementAndGet();
		}
	}

	@Override
	public boolean protect(SocketChannel channel) {
		return protector.protectSocket(channel);
	}

	@Override
	public int mtu() {
		return config.mtu;
	}

	@Override
	public long receiveWindowCap() {
		/* Presupuesto global: si el shaper de subida acumula demasiado,
		 * todas las conexiones reducen su ventana anunciada. */
		long headroom = UP_BUDGET - upPending.get();
		long floor = 4L * Math.max(536, config.mtu - 40);
		if (headroom < floor) {
			headroom = floor;
		}
		return Math.min(upWindowCap, headroom);
	}

	@Override
	public int sendBufferBytes() {
		return socketBufferBytes(config.upBytesPerSecond());
	}

	@Override
	public int receiveBufferBytes() {
		return socketBufferBytes(config.downBytesPerSecond());
	}

	private int socketBufferBytes(long rateBytesPerSecond) {
		long bdp2 = rateBytesPerSecond * config.rttMs / 1000L * 2L;
		return (int) Math.max(16L << 10, Math.min(256L << 10, bdp2));
	}

	@Override
	public ByteBuffer readBuffer() {
		return udpReadBuffer;
	}

	@Override
	public Stats stats() {
		return stats;
	}

	@Override
	public void wakeup() {
		Selector s = selector;
		if (s != null) {
			s.wakeup();
		}
	}

	private static long computeWindowCap(VpnConfig config) {
		int mss = Math.max(536, config.mtu - 40);
		/* 1.25 x BDP: suficiente para sostener el ritmo de subida con un
		 * RTT de referencia, sin acumular segundos de cola. */
		long bdp = config.upBytesPerSecond() * config.rttMs / 1000L;
		long cap = Math.max(4L * mss, bdp + bdp / 4L);
		return Math.min(cap, 512L * 1024L);
	}

	private static void join(Thread thread) {
		if (thread == null) {
			return;
		}
		try {
			thread.join(1500);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
