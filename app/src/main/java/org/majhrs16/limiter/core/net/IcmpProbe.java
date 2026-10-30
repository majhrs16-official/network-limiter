package org.majhrs16.limiter.core.net;

/**
 * Sonda ICMP real. La implementación de Android usa un socket ICMP sin
 * privilegios; el núcleo solo depende de esta interfaz para poder probarse en
 * JVM.
 */
public interface IcmpProbe {

	final class Result {
		public final boolean reachable;
		public final long rttNanos;
		public final String method;
		public final String detail;

		public Result(boolean reachable, long rttNanos, String method, String detail) {
			this.reachable = reachable;
			this.rttNanos = rttNanos;
			this.method = method;
			this.detail = detail;
		}

		public static Result reachable(long rttNanos, String method) {
			return new Result(true, rttNanos, method, null);
		}

		public static Result unreachable(String method, String detail) {
			return new Result(false, 0, method, detail);
		}
	}

	/**
	 * Envía el eco original al destino y espera una respuesta real.
	 *
	 * @param dstIp       dirección del destino (4 o 16 bytes)
	 * @param icmpPayload mensaje ICMP completo del dispositivo
	 * @param timeoutMs   tiempo máximo de espera
	 */
	Result probe(byte[] dstIp, byte[] icmpPayload, int timeoutMs);
}
