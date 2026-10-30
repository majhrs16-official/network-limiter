package org.majhrs16.limiter.core.net;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Clave de flujo de 5 tuplas. Se usa tanto para el mapa de conexiones como
 * para la cola de planificación del limitador.
 */
public final class FlowKey {

	public final byte[] srcIp;
	public final byte[] dstIp;
	public final int srcPort;
	public final int dstPort;
	public final int protocol;
	private final int hash;

	public FlowKey(byte[] srcIp, int srcPort, byte[] dstIp, int dstPort, int protocol) {
		this.srcIp = srcIp;
		this.dstIp = dstIp;
		this.srcPort = srcPort;
		this.dstPort = dstPort;
		this.protocol = protocol;

		int h = protocol;
		h = 31 * h + srcPort;
		h = 31 * h + dstPort;
		h = 31 * h + java.util.Arrays.hashCode(srcIp);
		h = 31 * h + java.util.Arrays.hashCode(dstIp);
		this.hash = h;
	}

	public FlowKey reverse() {
		return new FlowKey(dstIp, dstPort, srcIp, srcPort, protocol);
	}

	/** Dirección remota (la que no pertenece al túnel) como {@link InetAddress}. */
	public InetAddress remoteAddress() throws UnknownHostException {
		return InetAddress.getByAddress(dstIp);
	}

	/** Dirección local del túnel. */
	public InetAddress localAddress() throws UnknownHostException {
		return InetAddress.getByAddress(srcIp);
	}

	public boolean isIpv6() {
		return srcIp.length == 16;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof FlowKey)) {
			return false;
		}
		FlowKey k = (FlowKey) o;
		return srcPort == k.srcPort
				&& dstPort == k.dstPort
				&& protocol == k.protocol
				&& java.util.Arrays.equals(srcIp, k.srcIp)
				&& java.util.Arrays.equals(dstIp, k.dstIp);
	}

	@Override
	public int hashCode() {
		return hash;
	}

	@Override
	public String toString() {
		return address(srcIp) + ":" + srcPort + " -> " + address(dstIp) + ":" + dstPort
				+ "/" + protocol;
	}

	private static String address(byte[] ip) {
		try {
			return InetAddress.getByAddress(ip).getHostAddress();
		} catch (UnknownHostException e) {
			return "?";
		}
	}
}
