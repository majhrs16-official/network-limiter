package org.majhrs16.limiter.core.tcp;

import org.majhrs16.limiter.core.Diag;
import org.majhrs16.limiter.core.Stats;
import org.majhrs16.limiter.core.net.Delivery;
import org.majhrs16.limiter.core.net.FlowKey;
import org.majhrs16.limiter.core.net.IpPacket;
import org.majhrs16.limiter.core.net.TcpSegment;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Conexión TCP terminada en espacio de usuario.
 *
 * <p>El dispositivo cree hablar con el servidor remoto; nosotros hablamos con
 * el servidor mediante un {@link SocketChannel} protegido. Se implementan
 * handshake, ventana, retransmisión, control de congestión y medio cierre.</p>
 */
public final class TcpConnection {

	private static final String TAG = "TcpConnection";

	private static final int SYN_RECEIVED = 0;
	private static final int ESTABLISHED = 1;
	private static final int FIN_WAIT_1 = 2;
	private static final int FIN_WAIT_2 = 3;
	private static final int CLOSE_WAIT = 4;
	private static final int CLOSING = 5;
	private static final int LAST_ACK = 6;
	private static final int TIME_WAIT = 7;
	private static final int CLOSED = 8;

	private static final long MAX_CWND = 4L << 20;
	private static final int MAX_OOO_BYTES = 128 * 1024;
	private static final long MIN_RTO = 200_000_000L;
	private static final long MAX_RTO = 60_000_000_000L;
	private static final long INITIAL_RTO = 1_000_000_000L;
	private static final long TIME_WAIT_NANOS = 30_000_000_000L;
	private static final long SYN_TIMEOUT = 30_000_000_000L;
	private static final long IDLE_TIMEOUT = 300_000_000_000L;
	private static final long FIN_WAIT2_TIMEOUT = 60_000_000_000L;
	private static final long RCV_BUF_CAPACITY = 512L * 1024L;
	/* Presupuesto de E/S por despacho: evita que una conexión acapare el
	 * hilo del selector y deje sin servicio al resto del tráfico. */
	private static final int MAX_IO_ITERATIONS = 64;

	private static final Delivery NOOP = new Delivery() {
		@Override
		public void onSent() {
		}

		@Override
		public void onDropped() {
		}
	};

	private final TcpCallbacks cb;
	private final FlowKey key;
	private final FlowKey downloadKey;
	private final byte[] localIp;
	private final byte[] remoteIp;
	private final int localPort;
	private final int remotePort;
	private final boolean ipv6;
	private final int ourMss;
	private final int overhead;

	private SocketChannel channel;
	private SelectionKey selection;
	private boolean connected;
	private boolean connecting;

	private int state = SYN_RECEIVED;
	private final long iss;
	private final long irs;
	private long sndUna;
	private long sndNxt;
	private long sndWnd;
	private long sndWl1;
	private long sndWl2;
	private long rcvNxt;
	private final int sndScale;
	private final int rcvScale;
	private final boolean windowScaling;
	private final int peerMss;
	private long cwnd;
	private long ssthresh;
	private long rto = INITIAL_RTO;
	private long srtt = -1;
	private long rttvar;
	private long rtoDeadline;
	private long rttSampleSeq;
	private int dupAcks;

	/* Lo modifican el hilo del selector (onWritable) y el del shaper de
	 * subida (onUploadDropped); debe ser atómico. */
	private final AtomicLong pendingUpBytes = new AtomicLong();
	private final ArrayDeque<byte[]> pendingUp = new ArrayDeque<>();
	private final ConcurrentLinkedQueue<byte[]> outbound = new ConcurrentLinkedQueue<>();
	private final ByteBuffer readBuffer;
	private byte[] partialWrite;
	private boolean shutdownPending;
	private boolean shutdownDone;

	private final LinkedHashMap<Long, Segment> unacked = new LinkedHashMap<>();
	private final Map<Long, byte[]> ooo = new HashMap<>();
	private long oooBytes;

	private boolean finSent;
	private long finSeq;
	private boolean finReceived;
	private boolean finAcked;
	private long pendingFinSeq = -1;
	private boolean eof;

	private long lastAdvertisedWnd;
	private long lastActivity;
	private long createdNanos;
	private long closeAtNanos;
	private volatile boolean closed;

	public TcpConnection(TcpCallbacks cb, FlowKey key, IpPacket ip, TcpSegment syn, long now) {
		this.cb = cb;
		this.key = key;
		this.downloadKey = key.reverse();
		this.localIp = key.srcIp;
		this.remoteIp = key.dstIp;
		this.localPort = key.srcPort;
		this.remotePort = key.dstPort;
		this.ipv6 = key.isIpv6();
		this.ourMss = ipv6 ? cb.mtu() - 60 : cb.mtu() - 40;
		this.overhead = ipv6 ? 60 : 40;

		this.iss = ThreadLocalRandom.current().nextInt() & 0xFFFFFFFFL;
		this.irs = syn.seq;
		this.rcvNxt = add(irs, 1);
		this.sndUna = iss;
		this.sndNxt = add(iss, 1);
		this.sndWnd = syn.window;
		this.sndWl1 = syn.seq;
		this.sndWl2 = 0;
		this.windowScaling = syn.windowScale >= 0;
		this.sndScale = windowScaling ? syn.windowScale : 0;
		this.rcvScale = windowScaling ? 4 : 0;
		this.peerMss = syn.mss > 0
				? Math.max(536, Math.min(ourMss, syn.mss))
				: 536;
		this.readBuffer = ByteBuffer.allocate(peerMss);
		this.cwnd = 10L * peerMss;
		this.ssthresh = MAX_CWND;
		this.rttSampleSeq = iss;
		this.createdNanos = now;
		this.lastActivity = now;
	}

	public FlowKey key() {
		return key;
	}

	public SocketChannel channel() {
		return channel;
	}

	public SelectionKey selectionKey() {
		return selection;
	}

	public void attach(SelectionKey selection) {
		this.selection = selection;
	}

	public boolean isClosed() {
		return closed;
	}

	/** Abre el socket saliente y responde al SYN del dispositivo. */
	public void open(long now) {
		sendSynAck(now);

		try {
			channel = SocketChannel.open();
			channel.configureBlocking(false);
			try {
				channel.socket().setTcpNoDelay(true);
			} catch (Exception ignored) {
				/* algunos proveedores no exponen el adaptador */
			}

			boolean protectedSocket = false;
			try {
				protectedSocket = cb.protect(channel);
			} catch (Throwable error) {
				Diag.w(TAG, "protect() lanzó para " + key, error);
			}
			if (!protectedSocket) {
				/* La app está excluida de la VPN, así que el socket ya sale
				 * por la red subyacente. No se cierra por un protect()
				 * fallido. */
				Diag.w(TAG, "protect() devolvió false para " + key
						+ "; se continúa");
			}

			/* Buffers del socket acotados al BDP: evita que el kernel
			 * acumule segundos de datos fuera del shaper. */
			try {
				channel.socket().setSendBufferSize(cb.sendBufferBytes());
				channel.socket().setReceiveBufferSize(cb.receiveBufferBytes());
			} catch (Exception ignored) {
				/* algunos proveedores no permiten ajustarlos */
			}

			InetSocketAddress remote = new InetSocketAddress(
					InetAddress.getByAddress(remoteIp), remotePort);
			connecting = true;
			if (channel.connect(remote)) {
				connected = true;
				connecting = false;
				flushPendingUp();
				Diag.d(TAG, "TCP conectado " + key);
			}
		} catch (Throwable e) {
			Diag.w(TAG, "No se pudo conectar a " + key, e);
			close(true, now);
		}
	}

	public void onConnectable(long now) {
		if (channel == null) {
			return;
		}
		try {
			if (channel.finishConnect()) {
				connected = true;
				connecting = false;
				flushPendingUp();
				updateInterest();
				Diag.d(TAG, "TCP conectado " + key);
			}
		} catch (Throwable e) {
			Diag.w(TAG, "connect() falló para " + key, e);
			close(true, now);
		}
	}

	public void onReadable(long now) {
		if (!connected || eof || finSent || channel == null) {
			return;
		}
		try {
			for (int iteration = 0; iteration < MAX_IO_ITERATIONS; iteration++) {
				long allowed = sendAvailable();
				if (allowed <= 0) {
					break;
				}
				int want = (int) Math.min(peerMss, allowed);
				if (want <= 0) {
					break;
				}
				readBuffer.clear();
				readBuffer.limit(want);
				int read = channel.read(readBuffer);
				if (read < 0) {
					eof = true;
					sendFin(now);
					break;
				}
				if (read == 0) {
					break;
				}
				byte[] data = Arrays.copyOf(readBuffer.array(), read);
				sendData(data, now);
				if (read < want) {
					break;
				}
			}
		} catch (Throwable error) {
			Diag.w(TAG, "Lectura TCP falló; se cierra " + key, error);
			close(true, now);
			return;
		}
		updateInterest();
	}

	public void onWritable(long now) {
		if (!connected || channel == null) {
			return;
		}
		try {
			for (int iteration = 0; iteration < MAX_IO_ITERATIONS; iteration++) {
				byte[] chunk = partialWrite;
				if (chunk != null) {
					int written = channel.write(ByteBuffer.wrap(chunk));
					if (written <= 0) {
						break;
					}
					pendingUpBytes.updateAndGet(value -> Math.max(0, value - written));
					if (written < chunk.length) {
						partialWrite = Arrays.copyOfRange(chunk, written, chunk.length);
						break;
					}
					partialWrite = null;
					continue;
				}

				chunk = outbound.peek();
				if (chunk == null) {
					break;
				}
				int written = channel.write(ByteBuffer.wrap(chunk));
				if (written <= 0) {
					break;
				}
				pendingUpBytes.updateAndGet(value -> Math.max(0, value - written));
				if (written < chunk.length) {
					outbound.poll();
					partialWrite = Arrays.copyOfRange(chunk, written, chunk.length);
					break;
				}
				outbound.poll();
			}

			tryShutdownOutput();
			maybeWindowUpdate(now);
		} catch (Throwable error) {
			Diag.w(TAG, "Escritura TCP falló; se cierra " + key, error);
			close(true, now);
			return;
		}
		updateInterest();
	}

	public void onSegment(IpPacket ip, TcpSegment segment, long now) {
		if (closed) {
			return;
		}
		lastActivity = now;

		if (state == TIME_WAIT) {
			if (segment.has(TcpSegment.ACK) && segment.payloadLength == 0) {
				sendAck(now);
			}
			return;
		}

		if (segment.has(TcpSegment.RST)) {
			Diag.d(TAG, "TCP RST recibido " + key);
			close(false, now);
			return;
		}

		if (segment.has(TcpSegment.SYN)) {
			if (state == SYN_RECEIVED && segment.seq == irs) {
				Segment syn = unacked.get(iss);
				if (syn != null && !syn.inFlight) {
					enqueue(syn, now);
				}
			} else {
				close(true, now);
			}
			return;
		}

		if (!segment.has(TcpSegment.ACK)) {
			return;
		}
		if (gt(segment.ack, sndNxt)) {
			sendAck(now);
			return;
		}

		processAck(segment, now);

		if (state == SYN_RECEIVED) {
			if (segment.ack == add(iss, 1)) {
				state = ESTABLISHED;
				Diag.d(TAG, "TCP establecido " + key);
				flushPendingUp();
				updateInterest();
			} else {
				close(true, now);
				return;
			}
		}

		if (segment.payloadLength > 0) {
			handlePayload(ip, segment, now);
		}
		if (segment.has(TcpSegment.FIN)) {
			handleFin(segment, now);
		}
		if (pendingFinSeq >= 0 && pendingFinSeq == rcvNxt) {
			long fin = pendingFinSeq;
			pendingFinSeq = -1;
			rcvNxt = add(rcvNxt, 1);
			finReceived = true;
			sendAck(now);
			onFinReceived(now);
		}
		updateInterest();
	}

	public void onTimer(long now) {
		if (closed) {
			return;
		}
		if (state == TIME_WAIT) {
			if (now >= closeAtNanos) {
				close(false, now);
			}
			return;
		}
		if (state == SYN_RECEIVED && now - createdNanos > SYN_TIMEOUT) {
			close(true, now);
			return;
		}
		if (state == FIN_WAIT_2 && now - lastActivity > FIN_WAIT2_TIMEOUT) {
			close(true, now);
			return;
		}
		if (now - lastActivity > IDLE_TIMEOUT) {
			close(true, now);
			return;
		}

		if (!unacked.isEmpty() && rtoDeadline > 0 && now >= rtoDeadline) {
			boolean retransmitted = false;
			for (Segment segment : unacked.values()) {
				if (!segment.inFlight) {
					segment.retransmitted = true;
					enqueue(segment, now);
					cb.stats().retransmits.incrementAndGet();
					retransmitted = true;
				}
			}
			/* Si todo seguía en la cola del shaper no hay pérdida: no se
			 * castiga el RTO ni se reduce el cwnd por un simple retraso. */
			if (retransmitted) {
				ssthresh = Math.max(cwnd / 2, 2L * peerMss);
				cwnd = peerMss;
				rto = Math.min(MAX_RTO, rto * 2);
			}
			rtoDeadline = now + rto;
		}
	}

	/** Llamado desde el hilo del shaper de subida. */
	public void onUploadReady(byte[] data) {
		outbound.add(data);
		cb.wakeup();
	}

	/**
	 * El shaper descartó datos antes de entregarlos (desbordamiento de la cola).
	 * Se libera el hueco de recepción para no cerrar la ventana para siempre.
	 */
	public void onUploadDropped(int bytes) {
		pendingUpBytes.updateAndGet(value -> Math.max(0, value - bytes));
		/* Un descarte es esperado bajo carga; no debe generar una tormenta
		 * de logs que compita con el propio motor. */
		if (Diag.isPacketTrace()) {
			Diag.d(TAG, "Shaper descartó " + bytes + " bytes de subida para " + key);
		}
	}

	public void updateInterest() {
		SelectionKey k = selection;
		if (k == null || !k.isValid() || closed) {
			return;
		}
		int ops = 0;
		if (connecting) {
			ops |= SelectionKey.OP_CONNECT;
		}
		if (connected) {
			if (!eof && !finSent && sendAvailable() > 0) {
				ops |= SelectionKey.OP_READ;
			}
			if (partialWrite != null || !outbound.isEmpty()
					|| (shutdownPending && !shutdownDone)) {
				ops |= SelectionKey.OP_WRITE;
			}
		}
		if (k.interestOps() != ops) {
			k.interestOps(ops);
		}
	}

	public void close(boolean rst, long now) {
		if (closed) {
			return;
		}
		if (rst) {
			sendRst(now);
		}
		closed = true;
		state = CLOSED;
		Diag.d(TAG, "TCP cerrado " + key + (rst ? " (RST)" : ""));
		SelectionKey k = selection;
		if (k != null) {
			k.cancel();
		}
		if (channel != null) {
			try {
				channel.close();
			} catch (IOException ignored) {
				/* nada */
			}
		}
		cb.onConnectionClosed(this);
	}

	/* ------------------------------------------------------------------ */

	private void sendSynAck(long now) {
		byte[] packet = TcpSegment.build(remoteIp, localIp, remotePort, localPort,
				iss, rcvNxt, TcpSegment.SYN | TcpSegment.ACK,
				(int) Math.min(65535, rcvWnd()), null, 0, 0,
				ourMss, windowScaling ? rcvScale : -1, false);
		Diag.d(TAG, "SYN-ACK " + key + " mss=" + ourMss
				+ " scale=" + (windowScaling ? rcvScale : -1));
		Segment segment = new Segment(iss, 1, packet, true, false);
		unacked.put(iss, segment);
		enqueue(segment, now);
		lastAdvertisedWnd = rcvWnd();
	}

	private void processAck(TcpSegment segment, long now) {
		long ack = segment.ack;

		if (gt(segment.seq, sndWl1)
				|| (segment.seq == sndWl1 && ge(ack, sndWl2))) {
			sndWnd = ((long) segment.window) << sndScale;
			sndWl1 = segment.seq;
			sndWl2 = ack;
		}

		if (gt(ack, sndUna)) {
			long previous = sndUna;
			sndUna = ack;
			int acked = (int) (ack - previous);

			Iterator<Map.Entry<Long, Segment>> it = unacked.entrySet().iterator();
			while (it.hasNext()) {
				Segment sent = it.next().getValue();
				if (le(add(sent.seq, sent.len), ack)) {
					sent.acked = true;
					it.remove();
					sampleRtt(sent, now);
				} else {
					break;
				}
			}

			if (cwnd < ssthresh) {
				cwnd += Math.min(acked, peerMss);
			} else {
				cwnd += (long) peerMss * peerMss / Math.max(1, cwnd);
			}
			if (cwnd > MAX_CWND) {
				cwnd = MAX_CWND;
			}

			dupAcks = 0;
			rtoDeadline = unacked.isEmpty() ? 0 : now + rto;
			checkFinAcked(now);
		} else if (ack == sndUna && segment.payloadLength == 0
				&& !segment.has(TcpSegment.SYN) && !segment.has(TcpSegment.FIN)) {
			dupAcks++;
			if (dupAcks == 3) {
				fastRetransmit(now);
			}
		}
	}

	private void sampleRtt(Segment segment, long now) {
		if (segment.retransmitted || segment.sentAt == 0) {
			return;
		}
		if (!gt(segment.seq, rttSampleSeq)) {
			return;
		}
		long sample = now - segment.sentAt;
		if (sample <= 0) {
			return;
		}
		if (srtt < 0) {
			srtt = sample;
			rttvar = sample / 2;
		} else {
			long error = Math.abs(srtt - sample);
			rttvar = (3 * rttvar + error) / 4;
			srtt = (7 * srtt + sample) / 8;
		}
		rto = Math.max(MIN_RTO, Math.min(MAX_RTO, srtt + 4 * rttvar));
		rttSampleSeq = segment.seq;
	}

	private void fastRetransmit(long now) {
		if (unacked.isEmpty()) {
			return;
		}
		Segment first = unacked.values().iterator().next();
		if (first.syn || first.inFlight) {
			return;
		}
		ssthresh = Math.max(cwnd / 2, 2L * peerMss);
		cwnd = ssthresh;
		first.retransmitted = true;
		enqueue(first, now);
		cb.stats().retransmits.incrementAndGet();
	}

	private void handlePayload(IpPacket ip, TcpSegment segment, long now) {
		int len = segment.payloadLength;
		long seq = segment.seq;

		if (seq == rcvNxt) {
			if (accept(ip, segment, 0, len)) {
				rcvNxt = add(rcvNxt, len);
				flushOutOfOrder();
			}
			sendAck(now);
		} else if (lt(seq, rcvNxt)) {
			int overlap = (int) ((rcvNxt - seq) & 0xFFFFFFFFL);
			if (overlap < len) {
				if (accept(ip, segment, overlap, len - overlap)) {
					rcvNxt = add(rcvNxt, len - overlap);
					flushOutOfOrder();
				}
			}
			sendAck(now);
		} else {
			if (oooBytes + len <= MAX_OOO_BYTES && !ooo.containsKey(seq)) {
				ooo.put(seq, copy(ip, segment, 0, len));
				oooBytes += len;
			}
			sendAck(now);
		}
	}

	private boolean accept(IpPacket ip, TcpSegment segment, int skip, int len) {
		long free = RCV_BUF_CAPACITY - pendingUpBytes.get();
		if (len > free) {
			return false;
		}
		byte[] data = copy(ip, segment, skip, len);
		pendingUpBytes.addAndGet(len);
		if (connected && state != SYN_RECEIVED) {
			cb.enqueueUpload(this, data, len + overhead);
		} else {
			pendingUp.add(data);
		}
		return true;
	}

	private boolean acceptBytes(byte[] data) {
		if (data.length > RCV_BUF_CAPACITY - pendingUpBytes.get()) {
			return false;
		}
		pendingUpBytes.addAndGet(data.length);
		if (connected && state != SYN_RECEIVED) {
			cb.enqueueUpload(this, data, data.length + overhead);
		} else {
			pendingUp.add(data);
		}
		return true;
	}

	private void flushOutOfOrder() {
		while (true) {
			byte[] data = ooo.get(rcvNxt);
			if (data == null) {
				break;
			}
			if (!acceptBytes(data)) {
				break;
			}
			ooo.remove(rcvNxt);
			oooBytes -= data.length;
			rcvNxt = add(rcvNxt, data.length);
		}
	}

	private void flushPendingUp() {
		if (!connected || state == SYN_RECEIVED) {
			return;
		}
		while (!pendingUp.isEmpty()) {
			byte[] data = pendingUp.pollFirst();
			cb.enqueueUpload(this, data, data.length + overhead);
		}
	}

	private void handleFin(TcpSegment segment, long now) {
		long finAt = add(segment.seq, segment.payloadLength);
		if (finAt == rcvNxt) {
			rcvNxt = add(rcvNxt, 1);
			finReceived = true;
			sendAck(now);
			onFinReceived(now);
		} else if (lt(finAt, rcvNxt)) {
			sendAck(now);
		} else {
			pendingFinSeq = finAt;
		}
	}

	private void onFinReceived(long now) {
		shutdownPending = true;
		tryShutdownOutput();

		switch (state) {
			case ESTABLISHED:
				state = CLOSE_WAIT;
				break;
			case FIN_WAIT_1:
				if (finAcked) {
					state = TIME_WAIT;
					closeAtNanos = now + TIME_WAIT_NANOS;
				} else {
					state = CLOSING;
				}
				break;
			case FIN_WAIT_2:
				state = TIME_WAIT;
				closeAtNanos = now + TIME_WAIT_NANOS;
				break;
			case LAST_ACK:
				close(false, now);
				break;
			default:
				break;
		}
	}

	private void tryShutdownOutput() {
		if (shutdownPending && !shutdownDone && outbound.isEmpty()
				&& partialWrite == null && channel != null) {
			try {
				channel.shutdownOutput();
			} catch (IOException ignored) {
				/* el socket ya puede estar cerrado */
			}
			shutdownDone = true;
		}
	}

	private void sendData(byte[] data, long now) {
		long seq = sndNxt;
		sndNxt = add(sndNxt, data.length);
		byte[] packet = TcpSegment.build(remoteIp, localIp, remotePort, localPort,
				seq, rcvNxt, TcpSegment.ACK | TcpSegment.PSH, advertisedWindow(),
				data, 0, data.length, 0, -1, false);
		Segment segment = new Segment(seq, data.length, packet, false, false);
		unacked.put(seq, segment);
		enqueue(segment, now);
		if (rtoDeadline == 0) {
			rtoDeadline = now + rto;
		}
	}

	private void sendFin(long now) {
		if (finSent) {
			return;
		}
		finSent = true;
		finSeq = sndNxt;
		sndNxt = add(sndNxt, 1);
		byte[] packet = TcpSegment.build(remoteIp, localIp, remotePort, localPort,
				finSeq, rcvNxt, TcpSegment.FIN | TcpSegment.ACK, advertisedWindow(),
				null, 0, 0, 0, -1, false);
		Segment segment = new Segment(finSeq, 1, packet, false, true);
		unacked.put(finSeq, segment);
		enqueue(segment, now);

		if (state == ESTABLISHED) {
			state = FIN_WAIT_1;
		} else if (state == CLOSE_WAIT) {
			state = LAST_ACK;
		}
	}

	private void checkFinAcked(long now) {
		if (finSent && ge(sndUna, add(finSeq, 1))) {
			finAcked = true;
			if (state == FIN_WAIT_1) {
				state = FIN_WAIT_2;
			} else if (state == CLOSING) {
				state = TIME_WAIT;
				closeAtNanos = now + TIME_WAIT_NANOS;
			} else if (state == LAST_ACK) {
				close(false, now);
			}
		}
	}

	private void sendAck(long now) {
		byte[] packet = TcpSegment.build(remoteIp, localIp, remotePort, localPort,
				sndNxt, rcvNxt, TcpSegment.ACK, advertisedWindow(),
				null, 0, 0, 0, -1, false);
		cb.enqueueDownload(packet, downloadKey, NOOP);
		lastAdvertisedWnd = rcvWnd();
	}

	private void maybeWindowUpdate(long now) {
		long wnd = rcvWnd();
		if (wnd > lastAdvertisedWnd + 2L * peerMss
				|| (lastAdvertisedWnd == 0 && wnd > 0)) {
			sendAck(now);
		}
	}

	private void sendRst(long now) {
		byte[] packet = TcpSegment.build(remoteIp, localIp, remotePort, localPort,
				sndNxt, rcvNxt, TcpSegment.RST | TcpSegment.ACK, 0,
				null, 0, 0, 0, -1, false);
		cb.enqueueDownload(packet, downloadKey, NOOP);
	}

	private void enqueue(Segment segment, long now) {
		segment.inFlight = true;
		segment.sentAt = now;
		if (rtoDeadline == 0) {
			rtoDeadline = now + rto;
		}
		cb.enqueueDownload(segment.packet, downloadKey, segment);
	}

	private long rcvWnd() {
		long free = RCV_BUF_CAPACITY - pendingUpBytes.get();
		if (free < 0) {
			free = 0;
		}
		long cap = cb.receiveWindowCap();
		if (cap > 0 && free > cap) {
			free = cap;
		}
		return free;
	}

	private int advertisedWindow() {
		long wnd = rcvWnd() >> rcvScale;
		return (int) Math.max(0, Math.min(65535, wnd));
	}

	private long sendAvailable() {
		long inFlight = (sndNxt - sndUna) & 0xFFFFFFFFL;
		long limit = Math.min(cwnd, sndWnd);
		return limit - inFlight;
	}

	private static byte[] copy(IpPacket ip, TcpSegment segment, int skip, int len) {
		byte[] out = new byte[len];
		System.arraycopy(ip.raw, segment.payloadOffset + skip, out, 0, len);
		return out;
	}

	/* Aritmética de secuencia de 32 bits. */

	public static long add(long seq, long delta) {
		return (seq + delta) & 0xFFFFFFFFL;
	}

	public static boolean lt(long a, long b) {
		return ((a - b) & 0xFFFFFFFFL) > 0x7FFFFFFFL;
	}

	public static boolean le(long a, long b) {
		return a == b || lt(a, b);
	}

	public static boolean gt(long a, long b) {
		return lt(b, a);
	}

	public static boolean ge(long a, long b) {
		return a == b || gt(a, b);
	}

	private final class Segment implements Delivery {
		final long seq;
		final int len;
		final byte[] packet;
		final boolean syn;
		final boolean fin;
		volatile long sentAt;
		volatile boolean inFlight;
		boolean retransmitted;
		boolean acked;

		Segment(long seq, int len, byte[] packet, boolean syn, boolean fin) {
			this.seq = seq;
			this.len = len;
			this.packet = packet;
			this.syn = syn;
			this.fin = fin;
		}

		@Override
		public void onSent() {
			inFlight = false;
			sentAt = System.nanoTime();
		}

		@Override
		public void onDropped() {
			inFlight = false;
		}
	}
}
