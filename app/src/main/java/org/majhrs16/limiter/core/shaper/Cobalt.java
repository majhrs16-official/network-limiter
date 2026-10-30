package org.majhrs16.limiter.core.shaper;

import java.util.Random;

/**
 * Implementación fiel del AQM Cobalt que usa CAKE
 * (net/sched/sch_cake.c, Linux 6.6): ley de control de CoDel con punto fijo
 * Q0.32 para 1/sqrt(count) más la probabilidad aleatoria de BLUE.
 *
 * <p>El estado es por flujo, igual que {@code struct cobalt_vars}.</p>
 */
final class Cobalt {

	static final long FIXED_ONE = 0xFFFFFFFFL;
	private static final long[] INV_SQRT_CACHE = new long[16];

	static {
		long invsqrt = FIXED_ONE;
		INV_SQRT_CACHE[0] = invsqrt;
		for (int count = 1; count < INV_SQRT_CACHE.length; count++) {
			for (int step = 0; step < 4; step++) {
				invsqrt = newtonStep(invsqrt, count);
			}
			INV_SQRT_CACHE[count] = invsqrt;
		}
	}

	/** Parámetros derivados del ritmo y del RTT (struct cobalt_params). */
	static final class Params {
		long interval;
		long target;
		long mtuTime;
		long pInc = 1L << 24; /* 1/256 en Q0.32 */
		long pDec = 1L << 20; /* 1/4096 en Q0.32 */
	}

	long count;
	long recInvSqrt;
	long dropNextNanos;
	long blueTimerNanos;
	long pDrop;
	boolean dropping;
	boolean ecnMarked;

	void reset() {
		count = 0;
		recInvSqrt = 0;
		dropNextNanos = 0;
		blueTimerNanos = 0;
		pDrop = 0;
		dropping = false;
		ecnMarked = false;
	}

	/**
	 * @return true si el paquete debe descartarse.
	 */
	boolean shouldDrop(Params p, long now, long enqueueNanos, Random random) {
		long sojourn = now - enqueueNanos;
		long schedule = now - dropNextNanos;
		boolean overTarget = sojourn > p.target && sojourn > p.mtuTime * 4;
		boolean nextDue = count != 0 && schedule >= 0;

		ecnMarked = false;

		if (overTarget) {
			if (!dropping) {
				dropping = true;
				dropNextNanos = control(now, p.interval, recInvSqrt);
			}
			if (count == 0) {
				count = 1;
			}
		} else if (dropping) {
			dropping = false;
		}

		boolean drop = false;
		if (nextDue && dropping) {
			/* Sin ECN: se descarta directamente. */
			drop = true;
			count = (count + 1) & 0xFFFFFFFFL;
			if (count == 0) {
				count = 0xFFFFFFFFL;
			}
			invSqrt();
			dropNextNanos = control(dropNextNanos, p.interval, recInvSqrt);
			schedule = now - dropNextNanos;
		} else {
			while (nextDue) {
				count--;
				invSqrt();
				dropNextNanos = control(dropNextNanos, p.interval, recInvSqrt);
				schedule = now - dropNextNanos;
				nextDue = count != 0 && schedule >= 0;
			}
		}

		/* BLUE simple, sin ECN deliberadamente. */
		if (pDrop != 0) {
			drop |= (random.nextInt() & 0xFFFFFFFFL) < pDrop;
		}

		/* drop_next también actúa como timeout de actividad. */
		if (count == 0) {
			dropNextNanos = now + p.interval;
		} else if (schedule > 0 && !drop) {
			dropNextNanos = now;
		}

		return drop;
	}

	/** La cola del flujo se vació: decae el estado BLUE/CoDel. */
	void queueEmpty(Params p, long now) {
		if (pDrop != 0 && now - blueTimerNanos > p.target) {
			pDrop = Math.max(0, pDrop - p.pDec);
			blueTimerNanos = now;
		}
		dropping = false;
		if (count != 0 && now - dropNextNanos >= 0) {
			count--;
			invSqrt();
			dropNextNanos = control(dropNextNanos, p.interval, recInvSqrt);
		}
	}

	/** Desbordamiento de buffer: activa BLUE (cobalt_queue_full). */
	void queueFull(Params p, long now) {
		if (now - blueTimerNanos > p.target) {
			pDrop = Math.min(FIXED_ONE, pDrop + p.pInc);
			blueTimerNanos = now;
		}
		dropping = true;
		dropNextNanos = now;
		if (count == 0) {
			count = 1;
		}
	}

	boolean atRest(long now) {
		return pDrop == 0 && count == 0 && now >= dropNextNanos;
	}

	/** Solo para pruebas: valor de la caché Q0.32 de 1/sqrt(n). */
	static long cacheValue(int n) {
		return INV_SQRT_CACHE[n];
	}

	private void invSqrt() {
		if (count < INV_SQRT_CACHE.length) {
			recInvSqrt = INV_SQRT_CACHE[(int) count];
		} else {
			recInvSqrt = newtonStep(recInvSqrt, count);
		}
	}

	/** control_law: t + interval / sqrt(count), en Q0.32. */
	private static long control(long t, long interval, long recInvSqrt) {
		return t + ((interval * recInvSqrt) >>> 32);
	}

	/**
	 * Misma iteración que el kernel, con aritmética modular de 64 bits:
	 * new_invsqrt = (invsqrt / 2) * (3 - count * invsqrt^2).
	 */
	private static long newtonStep(long invsqrt, long count) {
		long invsqrt2 = (invsqrt * invsqrt) >>> 32;
		long val = (3L << 32) - (count * invsqrt2);
		val >>>= 2;
		return ((val * invsqrt) >>> 31) & 0xFFFFFFFFL;
	}
}
