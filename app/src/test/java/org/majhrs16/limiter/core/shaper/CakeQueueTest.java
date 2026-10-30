package org.majhrs16.limiter.core.shaper;

import org.junit.Test;

import org.majhrs16.limiter.core.net.FlowKey;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Comportamiento determinista del shaper y del DRR++ con reloj inyectado. */
public class CakeQueueTest {

	private static FlowKey key(int id) {
		return new FlowKey(new byte[]{10, 0, 0, (byte) id}, 1000 + id,
				new byte[]{93, (byte) 184, (byte) 216, (byte) id}, 443,
				6);
	}

	private static CakeQueue.Item item(FlowKey key, int length) {
		return new CakeQueue.Item(key, new byte[length], length, null, null);
	}

	@Test
	public void shaperHonorsConfiguredRate() {
		long rate = 1_000_000; /* 8 Mbps */
		CakeQueue queue = new CakeQueue(1500, rate, 80_000_000L, false);
		FlowKey flow = key(1);
		for (int i = 0; i < 50; i++) {
			queue.enqueue(item(flow, 1000), 0);
		}

		long now = 0;
		int delivered = 0;
		for (int i = 0; i < 100000 && delivered < 50; i++) {
			CakeQueue.Poll poll = queue.poll(now);
			if (poll.item != null) {
				delivered++;
			} else {
				now += Math.max(1, poll.waitNanos);
			}
		}

		assertEquals(50, delivered);
		/* 50 paquetes de 1000 B a 1 MB/s = 49 ms entre el primero y el último. */
		assertTrue("tiempo=" + now, now >= 45_000_000L && now <= 55_000_000L);
	}

	@Test
	public void unlimitedRateDeliversImmediately() {
		CakeQueue queue = new CakeQueue(1500, 0, 80_000_000L, false);
		FlowKey flow = key(1);
		for (int i = 0; i < 100; i++) {
			queue.enqueue(item(flow, 1000), 0);
		}

		int delivered = 0;
		for (int i = 0; i < 10000 && delivered < 100; i++) {
			CakeQueue.Poll poll = queue.poll(0);
			if (poll.item != null) {
				delivered++;
			}
		}
		assertEquals(100, delivered);
		assertEquals(0L, queue.backlogBytes());
	}

	@Test
	public void sparseFlowIsServedBeforeBulk() {
		CakeQueue queue = new CakeQueue(1500, 0, 80_000_000L, false);
		FlowKey sparse = key(1);
		FlowKey bulk = key(2);

		queue.enqueue(item(sparse, 500), 0);
		for (int i = 0; i < 10; i++) {
			queue.enqueue(item(bulk, 500), 0);
		}

		CakeQueue.Poll first = queue.poll(0);
		assertNotNull(first.item);
		assertEquals(sparse, first.item.flow);
	}

	@Test
	public void aqmDropsUnderSustainedQueueing() {
		long rate = 1_000_000;
		CakeQueue queue = new CakeQueue(1500, rate, 80_000_000L, true);
		FlowKey flow = key(1);
		int packets = 400;
		for (int i = 0; i < packets; i++) {
			queue.enqueue(item(flow, 1500), 0);
		}

		long now = 0;
		int delivered = 0;
		for (int i = 0; i < 2_000_000; i++) {
			if (delivered + queue.aqmDroppedPackets() >= packets) {
				break;
			}
			CakeQueue.Poll poll = queue.poll(now);
			if (poll.item != null) {
				delivered++;
			} else {
				now += Math.max(1, poll.waitNanos);
			}
		}

		long drops = queue.aqmDroppedPackets();
		assertTrue("debería haber descartes Cobalt, drops=" + drops, drops > 0);
		assertEquals(packets, delivered + drops);
		assertEquals(0L, queue.backlogBytes());
	}

	@Test
	public void bufferLimitProtectsMemory() {
		CakeQueue queue = new CakeQueue(1500, 0, 80_000_000L, false);
		FlowKey flow = key(1);
		/* 5000 * 1500 = 7.5 MB > 4 MB de límite mínimo. */
		for (int i = 0; i < 5000; i++) {
			queue.enqueue(item(flow, 1500), 0);
		}
		assertTrue(queue.droppedPackets() > 0);
		assertTrue(queue.backlogBytes() <= 4L * 1024 * 1024);
	}

	@Test
	public void overflowPrunesLongestQueueWithoutBreaking() {
		CakeQueue queue = new CakeQueue(1500, 0, 80_000_000L, false);
		for (int f = 0; f < 20; f++) {
			FlowKey flow = key(f);
			for (int i = 0; i < 300; i++) {
				queue.enqueue(item(flow, 1500), 0);
			}
		}
		assertTrue(queue.droppedPackets() > 0);
		assertTrue(queue.backlogBytes() <= 4L * 1024 * 1024);
		/* La cola sigue siendo utilizable tras podar. */
		assertNotNull(queue.poll(1_000_000L));
	}

	@Test
	public void rateChangeAppliesToQueuedTraffic() {
		CakeQueue queue = new CakeQueue(1500, 1_000_000, 80_000_000L, false);
		FlowKey flow = key(1);
		for (int i = 0; i < 20; i++) {
			queue.enqueue(item(flow, 1000), 0);
		}

		/* Baja a 100 kB/s: el primer paquete sale ya, el resto más lento. */
		queue.setRate(100_000, 80_000_000L);
		long now = 0;
		int delivered = 0;
		for (int i = 0; i < 100000 && delivered < 20; i++) {
			CakeQueue.Poll poll = queue.poll(now);
			if (poll.item != null) {
				delivered++;
			} else {
				now += Math.max(1, poll.waitNanos);
			}
		}
		assertEquals(20, delivered);
		assertTrue("tiempo=" + now, now > 100_000_000L);
	}
}
