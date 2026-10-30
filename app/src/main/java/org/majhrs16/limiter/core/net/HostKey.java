package org.majhrs16.limiter.core.net;

import java.util.Arrays;

/** Clave de host (dirección IP) para el aislamiento de Cake. */
public final class HostKey {

	public final byte[] address;
	private final int hash;

	public HostKey(byte[] address) {
		this.address = address;
		this.hash = Arrays.hashCode(address);
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof HostKey)) {
			return false;
		}
		return Arrays.equals(address, ((HostKey) o).address);
	}

	@Override
	public int hashCode() {
		return hash;
	}
}
