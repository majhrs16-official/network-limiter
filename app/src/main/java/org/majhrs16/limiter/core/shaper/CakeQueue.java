package org.majhrs16.limiter.core.shaper;

import org.majhrs16.limiter.core.net.FlowKey;
import org.majhrs16.limiter.core.net.HostKey;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * Cola de planificación CAKE (modo {@code besteffort triple-isolate wash}).
 *
 * <p>Reproduce el comportamiento de {@code net/sched/sch_cake.c}:</p>
 * <ul>
 *   <li>Shaper por tiempo de envío ({@code time_next_packet}), sin crédito
 *       acumulado cuando la cola está vacía y con failsafe a 1.5x.</li>
 *   <li>DRR++ con listas sparse/bulk/decaying y déficit escalado por la carga
 *       del host ({@code quantum_div}).</li>
 *   <li>AQM Cobalt por flujo.</li>
 *   <li>Límite de memoria calculado como en Cake.</li>
 * </ul>
 *
 * <p>La cola es pasiva: recibe el reloj como argumento para poder probarla de
 * forma determinista. El hilo que duerme vive en {@link CakeShaper}.</p>
 */
public final class CakeQueue {

	private static final int SET_NONE = 0;
	private static final int SET_SPARSE = 1;
	private static final int SET_SPARSE_WAIT = 2;
	private static final int SET_BULK = 3;
	private static final int SET_DECAYING = 4;

	private static final int CAKE_QUEUES = 1024;
	private static final int[] QUANTUM_DIV = new int[CAKE_QUEUES + 1];
	private static final long MIN_RATE = 64; /* bytes/s, igual que Cake */
	private static final long MIN_TARGET_NANOS = 5_000_000L;
	private static final long MIN_BUFFER = 4L << 20;
	/** Límite por paquetes que Cake impone junto al de memoria (sch->limit). */
	private static final int LIMIT_PACKETS = 10240;

	static {
		QUANTUM_DIV[0] = 0xFFFF;
		for (int i = 1; i <= CAKE_QUEUES; i++) {
			QUANTUM_DIV[i] = 65535 / i;
		}
	}

	/** Un paquete a planificar. */
	public static final class Item {
		public final FlowKey flow;
		public final byte[] data;
		public final int length;
		public final Object tag;
		public final Runnable onDrop;
		long enqueueNanos;

		public Item(FlowKey flow, byte[] data, int length, Object tag, Runnable onDrop) {
			this.flow = flow;
			this.data = data;
			this.length = Math.max(1, length);
			this.tag = tag;
			this.onDrop = onDrop;
		}
	}

	/** Resultado de {@link #poll(long)}: paquete listo o tiempo de espera. */
	public static final class Poll {
		public final Item item;
		public final long waitNanos;

		Poll(Item item, long waitNanos) {
			this.item = item;
			this.waitNanos = waitNanos;
		}
	}

	private static final class Host {
		final HostKey key;
		int srcBulk;
		int dstBulk;
		int activeFlows;

		Host(HostKey key) {
			this.key = key;
		}
	}

	private static final class Flow {
		final FlowKey key;
		final ArrayDeque<Item> queue = new ArrayDeque<>();
		final Cobalt cobalt = new Cobalt();
		Host srcHost;
		Host dstHost;
		int deficit;
		int set = SET_NONE;
		long queuedBytes;

		Flow(FlowKey key) {
			this.key = key;
		}
	}

	private final int mtu;
	private final Random random;
	private final Map<FlowKey, Flow> flows = new HashMap<>();
	private final Map<HostKey, Host> hosts = new HashMap<>();
	private final ArrayDeque<Flow> newFlows = new ArrayDeque<>();
	private final ArrayDeque<Flow> oldFlows = new ArrayDeque<>();
	private final ArrayDeque<Flow> decayingFlows = new ArrayDeque<>();
	private final Cobalt.Params params = new Cobalt.Params();

	private boolean dropEnabled;
	private long rate;
	private int flowQuantum = 1514;
	private long timeNextPacket;
	private long failsafeNextPacket;
	private long backlog;
	private long bufferLimit = MIN_BUFFER;
	private long bufferLimitOverride;
	private int sparseCount;
	private int bulkCount;
	private int decayingCount;

	private long delivered;
	private long dropped;
	private long aqmDropped;
	private long bytes;
	private long avgDelay;
	private long peakDelay;
	private long baseDelay;

	public CakeQueue(int mtu, long rateBytesPerSecond, long rttNanos, boolean dropEnabled) {
		this.mtu = Math.max(576, mtu);
		this.random = new Random();
		this.dropEnabled = dropEnabled;
		setRate(rateBytesPerSecond, rttNanos);
	}

	public synchronized void setRate(long rateBytesPerSecond, long rttNanos) {
		long oldRate = rate;
		rate = Math.max(0, rateBytesPerSecond);

		if (rate == 0) {
			flowQuantum = 1514;
			params.mtuTime = 0;
		} else {
			flowQuantum = (int) Math.max(300, Math.min(1514, rate >> 12));
			params.mtuTime = mtu * 1_000_000_000L / Math.max(MIN_RATE, rate);
		}
		params.target = Math.max(params.mtuTime * 3 / 2, MIN_TARGET_NANOS);
		params.interval = Math.max(rttNanos + params.target - MIN_TARGET_NANOS,
				params.target * 2);
		recomputeBufferLimit();

		/* Al desactivar el shaper o subir el ritmo con la cola vacía, el
		 * horario anterior ya no es válido; se reinicia en el próximo
		 * enqueue (que recibe el reloj real). */
		if (rate == 0 || (rate > oldRate && backlog == 0)) {
			timeNextPacket = 0;
			failsafeNextPacket = 0;
		}
	}

	public synchronized void setDropEnabled(boolean enabled) {
		dropEnabled = enabled;
	}

	/**
	 * Fija un límite de memoria distinto del calculado por Cake. La cola de
	 * subida lo usa para no perder datos ya confirmados al dispositivo.
	 */
	public synchronized void setBufferLimit(long bytes) {
		bufferLimitOverride = Math.max(MIN_BUFFER, bytes);
		recomputeBufferLimit();
	}

	private void recomputeBufferLimit() {
		if (bufferLimitOverride > 0) {
			bufferLimit = bufferLimitOverride;
			return;
		}
		/* Cake: max(4 x BDP, 4 MiB) acotado por sch->limit (10240 paquetes). */
		long bdp4 = rate * params.interval / 250_000_000L;
		long cap = (long) LIMIT_PACKETS * mtu;
		bufferLimit = Math.min(Math.max(bdp4, MIN_BUFFER), cap);
	}

	/** Encola un paquete. Debe llamarse con el reloj actual. */
	public synchronized void enqueue(Item item, long now) {
		Flow flow = flows.get(item.flow);
		if (flow == null) {
			flow = new Flow(item.flow);
			flow.srcHost = host(item.flow.srcIp);
			flow.dstHost = host(item.flow.dstIp);
			flows.put(item.flow, flow);
		}

		if (flow.set == SET_DECAYING) {
			decayingFlows.remove(flow);
			decayingCount--;
			flow.set = SET_SPARSE;
			sparseCount++;
			newFlows.addLast(flow);
			flow.deficit = initialDeficit(flow);
		} else if (flow.set == SET_NONE) {
			flow.set = SET_SPARSE;
			sparseCount++;
			newFlows.addLast(flow);
			flow.deficit = initialDeficit(flow);
		} else if (flow.set == SET_SPARSE_WAIT) {
			newFlows.remove(flow);
			flow.set = SET_BULK;
			sparseCount--;
			bulkCount++;
			bulkInc(flow);
			oldFlows.addLast(flow);
		}

		/* Estado del shaper obsoleto tras un periodo inactivo. Cake reinicia
		 * el reloj al vaciarse la cola; aquí se hace al recibir el primer
		 * paquete del siguiente hueco. */
		if (backlog == 0) {
			timeNextPacket = now;
			failsafeNextPacket = now;
		}

		if (backlog + item.length > bufferLimit) {
			/* Igual que cake_drop: se poda la cola más larga, no la que
			 * acaba de llegar. */
			item.enqueueNanos = now;
			flow.queue.addLast(item);
			flow.queuedBytes += item.length;
			backlog += item.length;
			while (backlog > bufferLimit) {
				Flow victim = null;
				long max = -1;
				for (Flow candidate : flows.values()) {
					if (candidate.queuedBytes > max) {
						max = candidate.queuedBytes;
						victim = candidate;
					}
				}
				if (victim == null || victim.queue.isEmpty()) {
					break;
				}
				Item droppedItem = victim.queue.pollFirst();
				victim.queuedBytes -= droppedItem.length;
				backlog -= droppedItem.length;
				dropped++;
				victim.cobalt.queueFull(params, now);
				if (droppedItem.onDrop != null) {
					droppedItem.onDrop.run();
				}
				if (victim.queue.isEmpty()) {
					handleEmpty(victim, now);
				}
			}
			notifyAll();
			return;
		}

		item.enqueueNanos = now;
		flow.queue.addLast(item);
		flow.queuedBytes += item.length;
		backlog += item.length;
		notifyAll();
	}

	/**
	 * Devuelve el siguiente paquete o el tiempo que falta para poder enviarlo.
	 */
	public synchronized Poll poll(long now) {
		if (backlog == 0) {
			return new Poll(null, Long.MAX_VALUE);
		}
		if (timeNextPacket > now && failsafeNextPacket > now) {
			long wait = Math.min(timeNextPacket, failsafeNextPacket) - now;
			return new Poll(null, Math.max(1, wait));
		}

		boolean first = true;
		for (int guard = 0; guard < 8192; guard++) {
			Flow flow = pick(first);
			first = false;
			if (flow == null) {
				return new Poll(null, 50_000_000L);
			}

			if (flow.queue.isEmpty()) {
				handleEmpty(flow, now);
				continue;
			}

			if (flow.deficit <= 0) {
				if (flow.set == SET_SPARSE) {
					if (!flow.queue.isEmpty()) {
						sparseCount--;
						bulkCount++;
						bulkInc(flow);
						flow.set = SET_BULK;
					} else {
						flow.set = SET_SPARSE_WAIT;
					}
				}
				int hostLoad = hostLoad(flow);
				flow.deficit += (int) (((long) flowQuantum * QUANTUM_DIV[hostLoad]
						+ random.nextInt(65536)) >> 16);
				moveToOld(flow);
				continue;
			}

			Item item = flow.queue.peekFirst();
			boolean drop = dropEnabled
					&& flow.cobalt.shouldDrop(params, now, item.enqueueNanos, random);
			if (drop && flow.queue.size() > 1) {
				flow.queue.pollFirst();
				flow.queuedBytes -= item.length;
				backlog -= item.length;
				dropped++;
				aqmDropped++;
				if (item.onDrop != null) {
					item.onDrop.run();
				}
				continue;
			}

			flow.queue.pollFirst();
			flow.queuedBytes -= item.length;
			backlog -= item.length;
			flow.deficit -= item.length;

			if (rate > 0) {
				long duration = duration(item.length);
				/* Misma secuencia que cake_advance_shaper: si el reloj
				 * está atrasado se recupera sin ráfagas. */
				if (timeNextPacket < now) {
					timeNextPacket += duration;
				} else if (timeNextPacket < now + duration) {
					timeNextPacket = now + duration;
				}
				failsafeNextPacket += duration + duration / 2;
			}

			long delay = now - item.enqueueNanos;
			avgDelay = ewma(avgDelay, delay, 8);
			peakDelay = ewma(peakDelay, delay, delay > peakDelay ? 2 : 8);
			baseDelay = ewma(baseDelay, delay, delay < baseDelay ? 2 : 8);

			delivered++;
			bytes += item.length;

			/* Como cake_dequeue: si el flujo se vació, se cierra su ciclo
			 * (o pasa a decaying) sin esperar al siguiente paquete. */
			if (flow.queue.isEmpty()) {
				handleEmpty(flow, now);
			}
			return new Poll(item, 0);
		}

		return new Poll(null, 1_000_000L);
	}

	public synchronized boolean isEmpty() {
		return backlog == 0;
	}

	public synchronized long backlogBytes() {
		return backlog;
	}

	public synchronized long droppedPackets() {
		return dropped;
	}

	public synchronized long aqmDroppedPackets() {
		return aqmDropped;
	}

	public synchronized long deliveredPackets() {
		return delivered;
	}

	public synchronized long deliveredBytes() {
		return bytes;
	}

	public synchronized long averageDelayNanos() {
		return avgDelay;
	}

	public synchronized long peakDelayNanos() {
		return peakDelay;
	}

	public synchronized long baseDelayNanos() {
		return baseDelay;
	}

	public synchronized int activeFlows() {
		return sparseCount + bulkCount;
	}

	private int initialDeficit(Flow flow) {
		int hostLoad = hostLoad(flow);
		return (int) (((long) flowQuantum * QUANTUM_DIV[hostLoad]
				+ random.nextInt(65536)) >> 16);
	}

	private int hostLoad(Flow flow) {
		return Math.min(CAKE_QUEUES, Math.max(flow.srcHost.srcBulk, flow.dstHost.dstBulk));
	}

	private Flow pick(boolean first) {
		if (first && !decayingFlows.isEmpty()) {
			return decayingFlows.peekFirst();
		}
		if (!newFlows.isEmpty()) {
			return newFlows.peekFirst();
		}
		if (!oldFlows.isEmpty()) {
			return oldFlows.peekFirst();
		}
		if (!decayingFlows.isEmpty()) {
			return decayingFlows.peekFirst();
		}
		return null;
	}

	private void handleEmpty(Flow flow, long now) {
		flow.cobalt.queueEmpty(params, now);
		removeFromList(flow);
		flow.set = SET_NONE;

		if (flow.cobalt.atRest(now)) {
			removeFlow(flow);
		} else {
			flow.set = SET_DECAYING;
			decayingCount++;
			decayingFlows.addLast(flow);
		}
	}

	private void removeFromList(Flow flow) {
		switch (flow.set) {
			case SET_SPARSE:
			case SET_SPARSE_WAIT:
				newFlows.remove(flow);
				sparseCount--;
				break;
			case SET_BULK:
				oldFlows.remove(flow);
				bulkCount--;
				bulkDec(flow);
				break;
			case SET_DECAYING:
				decayingFlows.remove(flow);
				decayingCount--;
				break;
			default:
				break;
		}
	}

	private void moveToOld(Flow flow) {
		if (flow.set == SET_SPARSE || flow.set == SET_SPARSE_WAIT) {
			newFlows.remove(flow);
			sparseCount--;
			flow.set = SET_BULK;
			bulkCount++;
			bulkInc(flow);
			oldFlows.addLast(flow);
		} else if (flow.set == SET_BULK) {
			oldFlows.remove(flow);
			oldFlows.addLast(flow);
		} else if (flow.set == SET_DECAYING) {
			decayingFlows.remove(flow);
			decayingCount--;
			flow.set = SET_BULK;
			bulkCount++;
			bulkInc(flow);
			oldFlows.addLast(flow);
		}
	}

	private void removeFlow(Flow flow) {
		removeFromList(flow);
		flow.set = SET_NONE;
		flows.remove(flow.key);
		releaseHost(flow.srcHost);
		releaseHost(flow.dstHost);
	}

	private void bulkInc(Flow flow) {
		flow.srcHost.srcBulk++;
		flow.dstHost.dstBulk++;
	}

	private void bulkDec(Flow flow) {
		flow.srcHost.srcBulk = Math.max(0, flow.srcHost.srcBulk - 1);
		flow.dstHost.dstBulk = Math.max(0, flow.dstHost.dstBulk - 1);
	}

	private Host host(byte[] ip) {
		HostKey key = new HostKey(ip);
		Host h = hosts.get(key);
		if (h == null) {
			h = new Host(key);
			hosts.put(key, h);
		}
		h.activeFlows++;
		return h;
	}

	private void releaseHost(Host host) {
		if (host == null) {
			return;
		}
		host.activeFlows--;
		if (host.activeFlows <= 0 && host.srcBulk <= 0 && host.dstBulk <= 0) {
			hosts.remove(host.key);
		}
	}

	private long duration(int length) {
		return length * 1_000_000_000L / Math.max(MIN_RATE, rate);
	}

	private static long ewma(long average, long sample, int shift) {
		long result = average - (average >> shift);
		result += sample >> shift;
		return result;
	}
}
