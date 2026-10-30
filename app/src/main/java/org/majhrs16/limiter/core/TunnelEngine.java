package org.majhrs16.limiter.core;

import android.os.ParcelFileDescriptor;

import org.majhrs16.limiter.core.net.Delivery;
import org.majhrs16.limiter.core.net.FlowKey;
import org.majhrs16.limiter.core.net.Icmp;
import org.majhrs16.limiter.core.net.IcmpForwarder;
import org.majhrs16.limiter.core.net.IcmpProbe;
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
import java.net.StandardProtocolFamily;
import java.nio.ByteBuffer;
import java.nio.channels.CancelledKeyException;
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
	private final IcmpProbe icmpProbe;
	private final ArrayBlockingQueue<byte[]> inbox = new ArrayBlockingQueue<>(2048);
	/* El ping no debe morir porque una descarga llene el buzón principal. */
	private final ArrayBlockingQueue<byte[]> icmpInbox = new ArrayBlockingQueue<>(256);
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
	private Thread watchdogThread;
	private volatile long lastIterationNanos;
	private CakeQueue upQueue;
	private CakeQueue downQueue;
	private CakeShaper upShaper;
	private CakeShaper downShaper;
	private IcmpForwarder icmpForwarder;
	/** Bytes de subida aceptados y aún no entregados al socket. */
	private final AtomicLong upPending = new AtomicLong();
	private final ByteBuffer udpReadBuffer = ByteBuffer.allocate(65535);

	public TunnelEngine(ParcelFileDescriptor tunPfd, Protector protector, VpnConfig config,
			Stats stats) {
		this(tunPfd, protector, config, stats, null);
	}

	public TunnelEngine(ParcelFileDescriptor tunPfd, Protector protector, VpnConfig config,
			Stats stats, IcmpProbe icmpProbe) {
		this.tunPfd = tunPfd;
		this.protector = protector;
		this.config = config.copy();
		this.stats = stats;
		this.icmpProbe = icmpProbe;
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

		if (icmpProbe != null) {
			icmpForwarder = new IcmpForwarder(icmpProbe, this::enqueueIcmpReply);
			icmpForwarder.start();
		}

		readerThread = new Thread(this::readLoop, "tun-reader");
		readerThread.setDaemon(true);
		readerThread.start();

		loopThread = new Thread(this, "tunnel-engine");
		loopThread.setDaemon(true);
		loopThread.start();

		watchdogThread = new Thread(this::watchdogLoop, "tunnel-watchdog");
		watchdogThread.setDaemon(true);
		watchdogThread.start();

		Diag.i(TAG, "Motor iniciado (up=" + config.upKbps + " kbps, down="
				+ config.downKbps + " kbps, rtt=" + config.rttMs + " ms)");
	}

	public void stop() {
		if (!running) {
			return;
		}
		running = false;
		Diag.i(TAG, "Deteniendo motor");

		if (icmpForwarder != null) {
			icmpForwarder.stop();
			icmpForwarder = null;
		}

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
		Thread watchdog = watchdogThread;
		if (watchdog != null) {
			watchdog.interrupt();
			try {
				watchdog.join(1000);
			} catch (InterruptedException interrupted) {
				Thread.currentThread().interrupt();
			}
			watchdogThread = null;
		}

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
		FileInputStream input = tunIn;
		if (input == null) {
			Diag.e(TAG, "Lector del TUN sin flujo de entrada", null);
			return;
		}
		while (running) {
			int read;
			try {
				read = input.read(buffer);
			} catch (Throwable error) {
				if (running) {
					Diag.w(TAG, "Lectura del TUN terminó", error);
				}
				break;
			}
			if (read <= 0) {
				continue;
			}
			byte[] packet = Arrays.copyOf(buffer, read);
			ArrayBlockingQueue<byte[]> target = isIcmpPacket(packet)
					? icmpInbox
					: inbox;
			boolean queued = target.offer(packet);
			/* Si el buzón ICMP está lleno, el principal sigue siendo una
			 * alternativa mejor que descartar el eco. */
			if (!queued && target != inbox) {
				queued = inbox.offer(packet);
			}
			if (!queued) {
				stats.dropped.incrementAndGet();
				if (Diag.isPacketTrace()) {
					Diag.w(TAG, "Buzón del TUN lleno; paquete descartado ("
							+ read + " bytes)");
				}
				continue;
			}
			Selector s = selector;
			if (s != null) {
				s.wakeup();
			}
		}
	}

	/**
	 * Vigila que el hilo del selector siga avanzando. Si una conexión o una
	 * escritura lo bloquea, el volcado de pila aparece en la pestaña de
	 * registros y permite identificar el punto exacto.
	 */
	private void watchdogLoop() {
		while (running) {
			try {
				Thread.sleep(5000);
			} catch (InterruptedException interrupted) {
				return;
			}
			if (!running) {
				return;
			}
			long last = lastIterationNanos;
			if (last == 0) {
				continue;
			}
			long age = System.nanoTime() - last;
			if (age < 5_000_000_000L) {
				continue;
			}
			Thread engine = loopThread;
			Throwable trace = new Throwable("Iteración del motor detenida");
			if (engine != null) {
				trace.setStackTrace(engine.getStackTrace());
			}
			Diag.e(TAG, "El motor lleva " + (age / 1_000_000L)
					+ " ms sin completar una iteración", trace);
		}
	}

	@Override
	public void run() {
		long lastTick = 0;
		long lastSample = 0;
		while (running) {
			lastIterationNanos = System.nanoTime();
			try {
				Selector s = selector;
				if (s == null) {
					break;
				}
				if (inbox.isEmpty() && icmpInbox.isEmpty()) {
					s.select(50);
				} else {
					/* Hay trabajo pendiente del TUN: no dormir 50 ms. */
					s.selectNow();
				}
				long now = System.nanoTime();

				Iterator<SelectionKey> iterator = s.selectedKeys().iterator();
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
					if (upQueue != null) {
						stats.upQueueBytes.set(upQueue.backlogBytes());
					}
					if (downQueue != null) {
						stats.downQueueBytes.set(downQueue.backlogBytes());
					}
					lastSample = now;
				}
			} catch (ClosedSelectorException ignored) {
				break;
			} catch (Throwable error) {
				/* Un paquete o un socket defectuoso no debe tumbar todo el
				 * túnel. Se registra y se sigue atendiendo el resto. */
				Diag.e(TAG, "Iteración del motor falló; se continúa", error);
				try {
					Thread.sleep(10);
				} catch (InterruptedException interrupted) {
					Thread.currentThread().interrupt();
					break;
				}
			}
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
			byte[] packet = icmpInbox.poll();
			if (packet == null) {
				packet = inbox.poll();
			}
			if (packet == null) {
				return;
			}
			try {
				handlePacket(packet, now);
			} catch (Throwable error) {
				stats.badPackets.incrementAndGet();
				Diag.e(TAG, "Paquete descartado por error (" + packet.length
						+ " bytes)", error);
			}
		}
	}

	/**
	 * El shaper añade datos a las colas de escritura desde su propio hilo;
	 * tras despertar hay que reactivar OP_WRITE en los sockets afectados.
	 */
	private void refreshInterests() {
		/* Copia defensiva: un cierre durante la actualización no debe
		 * invalidar el iterador del mapa. */
		for (TcpConnection connection : new ArrayList<>(tcp.values())) {
			try {
				connection.updateInterest();
			} catch (Throwable error) {
				Diag.w(TAG, "No se pudo actualizar el interés de un TCP", error);
			}
		}
		for (UdpFlow flow : new ArrayList<>(udp.values())) {
			try {
				flow.updateInterest();
			} catch (Throwable error) {
				Diag.w(TAG, "No se pudo actualizar el interés de un UDP", error);
			}
		}
	}

	private void timers(long now) {
		for (TcpConnection connection : new ArrayList<>(tcp.values())) {
			try {
				connection.onTimer(now);
			} catch (Throwable error) {
				Diag.w(TAG, "Temporizador TCP falló; se cierra la conexión", error);
				connection.close(true, now);
			}
		}
		for (UdpFlow flow : new ArrayList<>(udp.values())) {
			try {
				flow.onTimer(now);
			} catch (Throwable error) {
				Diag.w(TAG, "Temporizador UDP falló; se cierra el flujo", error);
				flow.close(now);
			}
		}
		if (upQueue != null) {
			stats.upQueueBytes.set(upQueue.backlogBytes());
		}
		if (downQueue != null) {
			stats.downQueueBytes.set(downQueue.backlogBytes());
			stats.aqmDrops.set(downQueue.aqmDroppedPackets());
		}
	}

	/* --------------------------- paquetes ------------------------------ */

	private void handlePacket(byte[] packet, long now) {
		IpPacket ip = IpPacket.parse(packet);
		if (ip == null) {
			stats.badPackets.incrementAndGet();
			if (Diag.isPacketTrace()) {
				Diag.w(TAG, "Paquete IP inválido del TUN (" + packet.length + " bytes)");
			}
			return;
		}
		stats.upBytes.addAndGet(packet.length);
		stats.upPackets.incrementAndGet();
		if (Diag.isPacketTrace()) {
			Diag.d(TAG, "TUN↑ " + describe(ip) + " len=" + packet.length);
		}

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
					Diag.w(TAG, "Límite de conexiones TCP alcanzado; RST a "
							+ address(ip.dst) + ":" + segment.dstPort);
					sendRst(ip, segment);
					stats.dropped.incrementAndGet();
					return;
				}
				Diag.d(TAG, "TCP SYN " + address(ip.src) + ":" + segment.srcPort
						+ " → " + address(ip.dst) + ":" + segment.dstPort);
				connection = new TcpConnection(this, key, ip, segment, now);
				tcp.put(key, connection);
				stats.tcpConnections.incrementAndGet();
				try {
					connection.open(now);
				} catch (Throwable error) {
					Diag.e(TAG, "No se pudo abrir el TCP hacia " + address(ip.dst)
							+ ":" + segment.dstPort, error);
					connection.close(true, now);
					return;
				}
				if (!connection.isClosed()) {
					register(connection);
				}
			} else if (!segment.has(TcpSegment.RST)) {
				sendRst(ip, segment);
			}
			return;
		}

		try {
			connection.onSegment(ip, segment, now);
		} catch (Throwable error) {
			Diag.w(TAG, "Segmento TCP falló; se cierra " + key, error);
			connection.close(true, now);
		}
	}

	private void handleUdp(IpPacket ip, UdpDatagram datagram, long now) {
		if (isLocalOnly(ip.dst)) {
			/* mDNS, SSDP, DHCP, descubrimiento IPv6: no salen del enlace. */
			stats.dropped.incrementAndGet();
			if (Diag.isPacketTrace()) {
				Diag.d(TAG, "UDP local descartado " + describe(ip));
			}
			return;
		}
		FlowKey key = new FlowKey(ip.src, datagram.srcPort, ip.dst, datagram.dstPort,
				IpPacket.PROTO_UDP);
		UdpFlow flow = udp.get(key);

		if (flow == null) {
			if (udp.size() >= MAX_UDP) {
				Diag.w(TAG, "Límite de flujos UDP alcanzado; se descarta "
						+ address(ip.dst) + ":" + datagram.dstPort);
				stats.dropped.incrementAndGet();
				return;
			}
			DatagramChannel channel = null;
			try {
				channel = openDatagram(ip.isIpv6());
				channel.configureBlocking(false);

				boolean protectedSocket = false;
				try {
					protectedSocket = protector.protectDatagram(channel);
				} catch (Throwable error) {
					Diag.w(TAG, "protect() lanzó para UDP " + address(ip.dst)
							+ ":" + datagram.dstPort, error);
				}
				if (!protectedSocket) {
					/* La app siempre está excluida de la VPN, así que el
					 * socket ya usa la red subyacente. No se aborta por un
					 * protect() fallido. */
					Diag.w(TAG, "protect() devolvió false para UDP "
							+ address(ip.dst) + ":" + datagram.dstPort
							+ "; se continúa");
				}

				channel.connect(new InetSocketAddress(
						InetAddress.getByAddress(ip.dst), datagram.dstPort));
				flow = new UdpFlow(this, key, channel, now);
				SelectionKey selection = channel.register(selector, 0, flow);
				flow.attach(selection);
				udp.put(key, flow);
				flow.updateInterest();
				stats.udpFlows.incrementAndGet();

				if (datagram.dstPort == 53) {
					Diag.i(TAG, "DNS consulta " + address(ip.src) + " → "
							+ address(ip.dst) + ":" + datagram.dstPort + " ("
							+ datagram.payloadLength + " bytes)");
				} else {
					Diag.d(TAG, "UDP flujo " + address(ip.src) + ":" + datagram.srcPort
							+ " → " + address(ip.dst) + ":" + datagram.dstPort);
				}
			} catch (Throwable error) {
				Diag.w(TAG, "No se pudo abrir UDP hacia " + address(ip.dst)
						+ ":" + datagram.dstPort, error);
				stats.dropped.incrementAndGet();
				if (flow != null && udp.remove(key) != null) {
					stats.udpFlows.decrementAndGet();
				}
				if (channel != null) {
					try {
						channel.close();
					} catch (Throwable ignored) {
						/* nada */
					}
				}
				return;
			}
		}

		byte[] payload = new byte[datagram.payloadLength];
		System.arraycopy(ip.raw, datagram.payloadOffset, payload, 0, payload.length);
		int overhead = ip.isIpv6() ? 48 : 28;
		enqueueUpload(flow, payload, payload.length + overhead);
	}

	/**
	 * Abre un canal de la familia exacta del destino. Android usa por defecto
	 * un canal INET6 dual-stack; pedir la familia explícita evita depender de
	 * ese detalle en redes IPv4-only.
	 */
	private static DatagramChannel openDatagram(boolean ipv6) throws IOException {
		try {
			return DatagramChannel.open(ipv6
					? StandardProtocolFamily.INET6
					: StandardProtocolFamily.INET);
		} catch (UnsupportedOperationException error) {
			Diag.w(TAG, "Familia " + (ipv6 ? "INET6" : "INET")
					+ " no soportada para UDP; se usa el canal por defecto", error);
			return DatagramChannel.open();
		}
	}

	private void handleIcmp(IpPacket ip) {
		if (isLocalOnly(ip.dst)) {
			stats.dropped.incrementAndGet();
			return;
		}
		if (!Icmp.isEchoRequest(ip)) {
			/* Los errores ICMP y otros tipos no se contestan localmente. */
			stats.dropped.incrementAndGet();
			return;
		}
		IcmpForwarder forwarder = icmpForwarder;
		if (forwarder == null) {
			stats.dropped.incrementAndGet();
			return;
		}
		Diag.d(TAG, "ICMP eco hacia " + address(ip.dst) + " ("
				+ ip.payloadLength + " bytes)");
		forwarder.submit(ip);
	}

	private void enqueueIcmpReply(byte[] packet, FlowKey flow) {
		if (!running) {
			stats.dropped.incrementAndGet();
			return;
		}
		enqueueDownload(packet, flow, NOOP);
	}

	/** Clasifica el paquete para dar prioridad al eco ICMP/ICMPv6. */
	private static boolean isIcmpPacket(byte[] packet) {
		if (packet == null || packet.length < 1) {
			return false;
		}
		int version = (packet[0] >> 4) & 0x0F;
		if (version == 4) {
			return packet.length >= 10 && (packet[9] & 0xFF) == 1;
		}
		if (version == 6) {
			return packet.length >= 7 && (packet[6] & 0xFF) == 58;
		}
		return false;
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

	private static String describe(IpPacket ip) {
		String protocol;
		switch (ip.protocol) {
			case IpPacket.PROTO_TCP:
				protocol = "TCP";
				break;
			case IpPacket.PROTO_UDP:
				protocol = "UDP";
				break;
			case IpPacket.PROTO_ICMP:
				protocol = "ICMP";
				break;
			case IpPacket.PROTO_ICMPV6:
				protocol = "ICMPv6";
				break;
			default:
				protocol = "proto=" + ip.protocol;
				break;
		}
		return "IPv" + (ip.isIpv6() ? "6" : "4") + " " + protocol + " "
				+ address(ip.src) + " → " + address(ip.dst);
	}

	private static String address(byte[] raw) {
		try {
			return InetAddress.getByAddress(raw).getHostAddress();
		} catch (Throwable ignored) {
			return "?";
		}
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
		} catch (Throwable error) {
			Diag.w(TAG, "No se pudo registrar el TCP en el selector", error);
			connection.close(true, System.nanoTime());
		}
	}

	/* ---------------------------- shapers ------------------------------ */

	private void deliverUpload(CakeQueue.Item item) {
		if (item == null || item.data == null) {
			return;
		}
		upPending.addAndGet(-item.data.length);
		Object tag = item.tag;
		try {
			if (tag instanceof TcpConnection) {
				((TcpConnection) tag).onUploadReady(item.data);
			} else if (tag instanceof UdpFlow) {
				((UdpFlow) tag).onUploadReady(item.data);
			}
		} catch (Throwable error) {
			stats.dropped.incrementAndGet();
			Diag.w(TAG, "Fallo entregando datos de subida", error);
		}
	}

	private void deliverDownload(CakeQueue.Item item) {
		if (item == null || item.data == null) {
			return;
		}
		boolean sent = false;
		try {
			FileOutputStream output = tunOut;
			if (output == null) {
				throw new IOException("TUN de salida cerrado");
			}
			output.write(item.data, 0, item.data.length);
			stats.downBytes.addAndGet(item.data.length);
			stats.downPackets.incrementAndGet();
			sent = true;
		} catch (Throwable error) {
			if (running) {
				Diag.w(TAG, "Escritura al TUN falló", error);
			}
		}
		if (item.tag instanceof Delivery) {
			Delivery delivery = (Delivery) item.tag;
			try {
				if (sent) {
					delivery.onSent();
				} else {
					delivery.onDropped();
				}
			} catch (Throwable error) {
				Diag.w(TAG, "Callback de entrega falló", error);
			}
		}
	}

	@Override
	public void enqueueDownload(byte[] packet, FlowKey flow, Delivery delivery) {
		Delivery d = delivery == null ? NOOP : delivery;
		CakeQueue queue = downQueue;
		if (queue == null) {
			stats.dropped.incrementAndGet();
			d.onDropped();
			return;
		}
		CakeQueue.Item item = new CakeQueue.Item(flow, packet, packet.length, d,
				() -> {
					stats.dropped.incrementAndGet();
					if (Diag.isPacketTrace()) {
						Diag.d(TAG, "Descarte down " + flow);
					}
					d.onDropped();
				});
		try {
			queue.enqueue(item, System.nanoTime());
		} catch (Throwable error) {
			stats.dropped.incrementAndGet();
			Diag.w(TAG, "No se pudo encolar el paquete de bajada", error);
			d.onDropped();
		}
	}

	@Override
	public void enqueueUpload(TcpConnection connection, byte[] payload, int wireLength) {
		CakeQueue queue = upQueue;
		if (queue == null) {
			stats.dropped.incrementAndGet();
			connection.onUploadDropped(payload.length);
			return;
		}
		upPending.addAndGet(payload.length);
		CakeQueue.Item item = new CakeQueue.Item(connection.key(), payload, wireLength,
				connection, () -> {
					upPending.addAndGet(-payload.length);
					stats.dropped.incrementAndGet();
					connection.onUploadDropped(payload.length);
				});
		try {
			queue.enqueue(item, System.nanoTime());
		} catch (Throwable error) {
			upPending.addAndGet(-payload.length);
			connection.onUploadDropped(payload.length);
			stats.dropped.incrementAndGet();
			Diag.w(TAG, "No se pudo encolar datos TCP de subida", error);
		}
	}

	private void enqueueUpload(UdpFlow flow, byte[] payload, int wireLength) {
		CakeQueue queue = upQueue;
		if (queue == null) {
			stats.dropped.incrementAndGet();
			return;
		}
		upPending.addAndGet(payload.length);
		CakeQueue.Item item = new CakeQueue.Item(flow.key(), payload, wireLength, flow,
				() -> {
					upPending.addAndGet(-payload.length);
					stats.dropped.incrementAndGet();
				});
		try {
			queue.enqueue(item, System.nanoTime());
		} catch (Throwable error) {
			upPending.addAndGet(-payload.length);
			stats.dropped.incrementAndGet();
			Diag.w(TAG, "No se pudo encolar datos UDP de subida", error);
		}
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
		try {
			return protector.protectSocket(channel);
		} catch (Throwable error) {
			Diag.w(TAG, "protect() lanzó para TCP; se continúa (la app está "
					+ "excluida de la VPN)", error);
			return false;
		}
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
