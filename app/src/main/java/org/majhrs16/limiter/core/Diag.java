package org.majhrs16.limiter.core;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * Registro de diagnóstico del núcleo.
 *
 * <p>El núcleo se prueba en JVM y no puede depender de {@code android.util.Log}.
 * Por eso mantiene un búfer circular en memoria con todos los niveles
 * (incluido {@code DEBUG}) y, si la app instala un {@link Sink}, lo replica en
 * Logcat. La pestaña «Registros» lee directamente el búfer, así que funciona
 * incluso sin adb.</p>
 */
public final class Diag {

	public interface Sink {
		void log(char level, String tag, String message, Throwable error);
	}

	public static final int DEFAULT_CAPACITY = 3000;
	private static final int MAX_MESSAGE = 4000;
	private static final int MAX_STACK = 8000;
	private static final Object LOCK = new Object();
	private static final ArrayDeque<Entry> BUFFER = new ArrayDeque<>();

	private static volatile Sink sink;
	private static volatile boolean verbose = true;
	private static volatile boolean packetTrace;
	private static volatile int capacity = DEFAULT_CAPACITY;
	private static volatile long dropped;
	private static volatile long version;

	private Diag() {
	}

	/** Una línea de registro ya formateada. */
	public static final class Entry {
		public final long timeMillis;
		public final char level;
		public final String tag;
		public final String message;
		public final String stackTrace;

		private Entry(long timeMillis, char level, String tag, String message,
				String stackTrace) {
			this.timeMillis = timeMillis;
			this.level = level;
			this.tag = tag;
			this.message = message;
			this.stackTrace = stackTrace;
		}

		public String line() {
			StringBuilder out = new StringBuilder(96);
			Calendar calendar = Calendar.getInstance();
			calendar.setTimeInMillis(timeMillis);
			out.append(String.format(Locale.US, "%02d:%02d:%02d.%03d",
					calendar.get(Calendar.HOUR_OF_DAY),
					calendar.get(Calendar.MINUTE),
					calendar.get(Calendar.SECOND),
					calendar.get(Calendar.MILLISECOND)));
			out.append(' ').append(level).append('/').append(tag).append(": ")
					.append(message);
			if (stackTrace != null && !stackTrace.isEmpty()) {
				out.append('\n').append(stackTrace);
			}
			return out.toString();
		}

		public String text() {
			return line();
		}
	}

	public static void setSink(Sink value) {
		sink = value;
	}

	public static void setVerbose(boolean value) {
		verbose = value;
	}

	public static boolean isVerbose() {
		return verbose;
	}

	/** Activa el trazado paquete a paquete; pensado para diagnóstico puntual. */
	public static void setPacketTrace(boolean value) {
		packetTrace = value;
	}

	public static boolean isPacketTrace() {
		return packetTrace;
	}

	public static void setCapacity(int value) {
		int next = Math.max(100, Math.min(20_000, value));
		synchronized (LOCK) {
			capacity = next;
			while (BUFFER.size() > next) {
				BUFFER.removeFirst();
				dropped++;
			}
			version++;
		}
	}

	public static int capacity() {
		return capacity;
	}

	public static long dropped() {
		return dropped;
	}

	public static long version() {
		return version;
	}

	public static int size() {
		synchronized (LOCK) {
			return BUFFER.size();
		}
	}

	public static void clear() {
		synchronized (LOCK) {
			BUFFER.clear();
			dropped = 0;
			version++;
		}
	}

	public static List<Entry> snapshot() {
		synchronized (LOCK) {
			return new ArrayList<>(BUFFER);
		}
	}

	public static void d(String tag, String message) {
		log('D', tag, message, null);
	}

	public static void d(String tag, String message, Throwable error) {
		log('D', tag, message, error);
	}

	public static void i(String tag, String message) {
		log('I', tag, message, null);
	}

	public static void i(String tag, String message, Throwable error) {
		log('I', tag, message, error);
	}

	public static void w(String tag, String message) {
		log('W', tag, message, null);
	}

	public static void w(String tag, String message, Throwable error) {
		log('W', tag, message, error);
	}

	public static void e(String tag, String message, Throwable error) {
		log('E', tag, message, error);
	}

	private static void log(char level, String tag, String message, Throwable error) {
		String safeTag = tag == null ? "?" : tag;
		String safeMessage = limit(message == null ? "" : message, MAX_MESSAGE);
		String stack = error == null ? null : stackTrace(error);
		Entry entry = new Entry(System.currentTimeMillis(), level, safeTag,
				safeMessage, stack);

		synchronized (LOCK) {
			BUFFER.addLast(entry);
			while (BUFFER.size() > capacity) {
				BUFFER.removeFirst();
				dropped++;
			}
			version++;
		}

		/* Logcat es opcional: el búfer ya conserva también los DEBUG. */
		if (!verbose && level == 'D') {
			return;
		}
		Sink s = sink;
		if (s != null) {
			try {
				s.log(level, safeTag, safeMessage, error);
			} catch (Throwable ignored) {
				/* un sink defectuoso no debe romper el motor */
			}
		}
	}

	private static String limit(String value, int max) {
		if (value.length() <= max) {
			return value;
		}
		return value.substring(0, max) + "…";
	}

	private static String stackTrace(Throwable error) {
		try {
			StringWriter writer = new StringWriter();
			PrintWriter printer = new PrintWriter(writer);
			error.printStackTrace(printer);
			printer.flush();
			return limit(writer.toString(), MAX_STACK);
		} catch (Throwable ignored) {
			return error.toString();
		}
	}
}
