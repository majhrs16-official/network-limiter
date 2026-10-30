package org.majhrs16.limiter.core;

/**
 * Logging mínimo para que el núcleo (testeable en JVM) no dependa de Android.
 */
public final class Diag {

	public interface Sink {
		void log(char level, String tag, String message, Throwable error);
	}

	private static volatile Sink sink;
	private static volatile boolean verbose = true;

	private Diag() {
	}

	public static void setSink(Sink value) {
		sink = value;
	}

	public static void setVerbose(boolean value) {
		verbose = value;
	}

	public static void d(String tag, String message) {
		log('D', tag, message, null);
	}

	public static void i(String tag, String message) {
		log('I', tag, message, null);
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
		if (!verbose && level == 'D') {
			return;
		}
		Sink s = sink;
		if (s != null) {
			s.log(level, tag, message, error);
		}
	}
}
