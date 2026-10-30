package org.majhrs16.limiter.core;

import java.util.Locale;

/** Formato humano de tasas y volúmenes. */
public final class Format {

	private Format() {
	}

	public static String rate(long bytesPerSecond) {
		if (bytesPerSecond < 1024) {
			return bytesPerSecond + " B/s";
		}
		if (bytesPerSecond < 1024L * 1024L) {
			return String.format(Locale.US, "%.1f KB/s", bytesPerSecond / 1024.0);
		}
		return String.format(Locale.US, "%.2f MB/s", bytesPerSecond / (1024.0 * 1024.0));
	}

	public static String kbps(long kbps) {
		if (kbps >= 1000) {
			return String.format(Locale.US, "%.1f Mbps", kbps / 1000.0);
		}
		return kbps + " kbps";
	}

	public static String bytes(long value) {
		if (value < 1024) {
			return value + " B";
		}
		if (value < 1024L * 1024L) {
			return String.format(Locale.US, "%.1f KB", value / 1024.0);
		}
		if (value < 1024L * 1024L * 1024L) {
			return String.format(Locale.US, "%.1f MB", value / (1024.0 * 1024.0));
		}
		return String.format(Locale.US, "%.2f GB", value / (1024.0 * 1024.0 * 1024.0));
	}

	public static String duration(long millis) {
		long seconds = millis / 1000;
		long h = seconds / 3600;
		long m = (seconds % 3600) / 60;
		long s = seconds % 60;
		if (h > 0) {
			return String.format(Locale.US, "%d:%02d:%02d", h, m, s);
		}
		return String.format(Locale.US, "%02d:%02d", m, s);
	}
}
