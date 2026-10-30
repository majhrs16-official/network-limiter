package org.majhrs16.limiter.core.shaper;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * La caché Q0.32 debe coincidir bit a bit con la de sch_cake.c; es la base
 * de la ley de control de CoDel.
 */
public class CobaltTest {

	@Test
	public void cacheMatchesKernelValues() {
		assertEquals(0xFFFFFFFFL, Cobalt.cacheValue(0));
		assertEquals(0xFFFFFFFFL, Cobalt.cacheValue(1));
		assertEquals(3035289760L, Cobalt.cacheValue(2));
		assertEquals(2479700455L, Cobalt.cacheValue(3));
		assertEquals(2147483647L, Cobalt.cacheValue(4));
		assertEquals(1431655765L, Cobalt.cacheValue(9));
		assertEquals(1108955788L, Cobalt.cacheValue(15));
	}

	@Test
	public void cacheApproximatesReciprocalSquareRoot() {
		for (int n = 1; n < 16; n++) {
			double expected = 1.0 / Math.sqrt(n);
			double actual = Cobalt.cacheValue(n) / 4294967296.0;
			assertTrue("n=" + n + " esperado=" + expected + " real=" + actual,
					Math.abs(expected - actual) < 0.001);
		}
	}

	@Test
	public void firstDropHappensAfterInterval() {
		Cobalt cobalt = new Cobalt();
		Cobalt.Params params = new Cobalt.Params();
		params.target = 5_000_000L;
		params.mtuTime = 1_500_000L;
		params.interval = 80_000_000L;
		params.pInc = 1L << 24;
		params.pDec = 1L << 20;

		java.util.Random random = new java.util.Random(1);
		long enqueue = 0;

		/* Antes de target no hay congestión. */
		assertTrue(!cobalt.shouldDrop(params, 4_000_000L, enqueue, random));

		/* Al superar target se arma dropping. En el kernel rec_inv_sqrt
		 * parte de 0, así que el primer control_law deja drop_next = now
		 * y el siguiente paquete ya se descarta. */
		assertTrue(!cobalt.shouldDrop(params, 7_000_000L, enqueue, random));
		assertTrue(cobalt.shouldDrop(params, 7_500_000L, enqueue, random));
		assertEquals(2, cobalt.count);

		/* El siguiente descarte respeta interval/sqrt(2) ~= 56,5 ms. */
		assertTrue(!cobalt.shouldDrop(params, 8_000_000L, enqueue, random));
		assertTrue(!cobalt.shouldDrop(params, 60_000_000L, enqueue, random));
		assertTrue(cobalt.shouldDrop(params, 70_000_000L, enqueue, random));
		assertEquals(3, cobalt.count);
	}

	@Test
	public void blueProbabilityDecaysWhenQueueEmpties() {
		Cobalt cobalt = new Cobalt();
		Cobalt.Params params = new Cobalt.Params();
		params.target = 5_000_000L;
		params.mtuTime = 1_500_000L;
		params.interval = 80_000_000L;

		cobalt.queueFull(params, 10_000_000L);
		long initial = cobalt.pDrop;
		assertTrue(initial > 0);

		cobalt.queueEmpty(params, 100_000_000L);
		assertTrue(cobalt.pDrop < initial);
	}
}
