package org.majhrs16.limiter.core;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Contadores del túnel. Las lecturas se hacen desde la UI; las escrituras
 * desde el hilo del motor y los shapers.
 */
public final class Stats {

	public final AtomicLong upBytes = new AtomicLong();
	public final AtomicLong downBytes = new AtomicLong();
	public final AtomicLong upPackets = new AtomicLong();
	public final AtomicLong downPackets = new AtomicLong();
	public final AtomicLong dropped = new AtomicLong();
	public final AtomicLong aqmDrops = new AtomicLong();
	public final AtomicLong retransmits = new AtomicLong();
	public final AtomicLong badPackets = new AtomicLong();
	public final AtomicLong tcpConnections = new AtomicLong();
	public final AtomicLong udpFlows = new AtomicLong();
	public final AtomicLong upQueueBytes = new AtomicLong();
	public final AtomicLong downQueueBytes = new AtomicLong();

	private volatile long startNanos;
	private volatile long lastSampleNanos;
	private volatile long lastUpBytes;
	private volatile long lastDownBytes;
	private volatile long upRate;
	private volatile long downRate;

	public void start() {
		reset();
		startNanos = System.nanoTime();
		lastSampleNanos = startNanos;
	}

	public void reset() {
		upBytes.set(0);
		downBytes.set(0);
		upPackets.set(0);
		downPackets.set(0);
		dropped.set(0);
		aqmDrops.set(0);
		retransmits.set(0);
		badPackets.set(0);
		tcpConnections.set(0);
		udpFlows.set(0);
		upQueueBytes.set(0);
		downQueueBytes.set(0);
		lastUpBytes = 0;
		lastDownBytes = 0;
		upRate = 0;
		downRate = 0;
		startNanos = 0;
		lastSampleNanos = 0;
	}

	public long uptimeMillis() {
		long start = startNanos;
		return start == 0 ? 0 : (System.nanoTime() - start) / 1_000_000L;
	}

	public void sample() {
		long now = System.nanoTime();
		if (lastSampleNanos == 0) {
			lastSampleNanos = now;
			lastUpBytes = upBytes.get();
			lastDownBytes = downBytes.get();
			return;
		}
		long up = upBytes.get();
		long down = downBytes.get();
		long elapsed = now - lastSampleNanos;
		if (elapsed <= 0) {
			return;
		}
		upRate = (up - lastUpBytes) * 1_000_000_000L / elapsed;
		downRate = (down - lastDownBytes) * 1_000_000_000L / elapsed;
		lastUpBytes = up;
		lastDownBytes = down;
		lastSampleNanos = now;
	}

	public Snapshot snapshot() {
		return new Snapshot(this);
	}

	/** Copia inmutable para pintar la interfaz. */
	public static final class Snapshot {
		public final long upBytes;
		public final long downBytes;
		public final long upPackets;
		public final long downPackets;
		public final long upRate;
		public final long downRate;
		public final long dropped;
		public final long aqmDrops;
		public final long retransmits;
		public final long tcpConnections;
		public final long udpFlows;
		public final long upQueueBytes;
		public final long downQueueBytes;
		public final long uptimeMillis;

		Snapshot(Stats s) {
			upBytes = s.upBytes.get();
			downBytes = s.downBytes.get();
			upPackets = s.upPackets.get();
			downPackets = s.downPackets.get();
			upRate = s.upRate;
			downRate = s.downRate;
			dropped = s.dropped.get();
			aqmDrops = s.aqmDrops.get();
			retransmits = s.retransmits.get();
			tcpConnections = s.tcpConnections.get();
			udpFlows = s.udpFlows.get();
			upQueueBytes = s.upQueueBytes.get();
			downQueueBytes = s.downQueueBytes.get();
			uptimeMillis = s.uptimeMillis();
		}
	}
}
