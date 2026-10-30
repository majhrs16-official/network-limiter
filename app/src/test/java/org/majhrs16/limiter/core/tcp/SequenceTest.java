package org.majhrs16.limiter.core.tcp;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Aritmética modular de secuencia TCP de 32 bits. */
public class SequenceTest {

	@Test
	public void additionWraps() {
		assertEquals(0L, TcpConnection.add(0xFFFFFFFFL, 1));
		assertEquals(5L, TcpConnection.add(0xFFFFFFFFL, 6));
		assertEquals(0xFFFFFFFFL, TcpConnection.add(0xFFFFFFFEL, 1));
	}

	@Test
	public void comparisonsAcrossWrap() {
		assertTrue(TcpConnection.lt(0xFFFFFFFEL, 2L));
		assertTrue(TcpConnection.gt(2L, 0xFFFFFFFEL));
		assertTrue(TcpConnection.le(0xFFFFFFFFL, 0L));
		assertTrue(TcpConnection.ge(0L, 0xFFFFFFFFL));
		assertFalse(TcpConnection.lt(2L, 0xFFFFFFFEL));
	}

	@Test
	public void distanceIsModular() {
		long before = 0xFFFFFF00L;
		long after = 0x00000100L;
		assertEquals(0x200L, (after - before) & 0xFFFFFFFFL);
	}
}
