package org.majhrs16.limiter.core.shaper;

import org.majhrs16.limiter.core.Diag;

/**
 * Hilo que consume una {@link CakeQueue} y entrega los paquetes al sumidero.
 * La cola es pasiva y recibe el reloj; este hilo solo duerme y despierta.
 */
public final class CakeShaper implements Runnable {

	public interface Sink {
		void accept(CakeQueue.Item item);
	}

	private final String name;
	private final CakeQueue queue;
	private final Sink sink;
	private volatile boolean running;
	private Thread thread;

	public CakeShaper(String name, CakeQueue queue, Sink sink) {
		this.name = name;
		this.queue = queue;
		this.sink = sink;
	}

	public CakeQueue queue() {
		return queue;
	}

	public void start() {
		if (running) {
			return;
		}
		running = true;
		thread = new Thread(this, name);
		thread.setDaemon(true);
		thread.start();
	}

	public void stop() {
		running = false;
		synchronized (queue) {
			queue.notifyAll();
		}
		Thread t = thread;
		if (t != null) {
			t.interrupt();
			try {
				t.join(1000);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
		thread = null;
	}

	public boolean isRunning() {
		return running;
	}

	@Override
	public void run() {
		while (running) {
			CakeQueue.Poll poll;
			synchronized (queue) {
				poll = queue.poll(System.nanoTime());
				if (poll.item == null) {
					long millis;
					if (poll.waitNanos == Long.MAX_VALUE) {
						millis = 100;
					} else {
						millis = Math.max(1, Math.min(100, poll.waitNanos / 1_000_000L));
					}
					try {
						queue.wait(millis);
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
						return;
					}
				}
			}

			if (poll.item != null) {
				try {
					sink.accept(poll.item);
				} catch (Throwable t) {
					Diag.e(name, "Error entregando paquete", t);
				}
			}
		}
	}
}
