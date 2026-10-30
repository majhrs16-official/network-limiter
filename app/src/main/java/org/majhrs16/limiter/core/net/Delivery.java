package org.majhrs16.limiter.core.net;

/** Notificación del shaper sobre el destino de un paquete. */
public interface Delivery {
	void onSent();

	void onDropped();
}
