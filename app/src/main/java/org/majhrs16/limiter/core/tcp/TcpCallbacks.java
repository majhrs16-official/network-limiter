package org.majhrs16.limiter.core.tcp;

import org.majhrs16.limiter.core.Stats;
import org.majhrs16.limiter.core.net.Delivery;
import org.majhrs16.limiter.core.net.FlowKey;

import java.nio.channels.SocketChannel;

/** Servicios que el motor ofrece a cada conexión TCP. */
public interface TcpCallbacks {

	void enqueueDownload(byte[] packet, FlowKey flow, Delivery delivery);

	void enqueueUpload(TcpConnection connection, byte[] payload, int wireLength);

	void onConnectionClosed(TcpConnection connection);

	boolean protect(SocketChannel channel);

	int mtu();

	/** Ventana máxima de recepción (BDP del enlace de subida). */
	long receiveWindowCap();

	/** Tamaño del buffer de envío del socket saliente (BDP de subida). */
	int sendBufferBytes();

	/** Tamaño del buffer de recepción del socket saliente (BDP de bajada). */
	int receiveBufferBytes();

	Stats stats();

	void wakeup();
}
