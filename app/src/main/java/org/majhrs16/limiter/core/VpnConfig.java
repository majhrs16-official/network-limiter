package org.majhrs16.limiter.core;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.Set;

/**
 * Configuración persistida. Los ritmos se guardan en kbit/s para que la UI y
 * el archivo de preferencias sean legibles; el motor trabaja en bytes/s.
 */
public final class VpnConfig {

	public static final String PREFS = "limiter";
	private static final String KEY_UP = "up_kbps";
	private static final String KEY_DOWN = "down_kbps";
	private static final String KEY_RTT = "rtt_ms";
	private static final String KEY_MTU = "mtu";
	private static final String KEY_SPLIT_MODE = "split_mode";
	private static final String KEY_SPLIT_PACKAGES = "split_packages";

	public static final long MIN_KBPS = 64;
	public static final long MAX_KBPS = 100_000;
	public static final long DEFAULT_UP_KBPS = 2_000;
	public static final long DEFAULT_DOWN_KBPS = 10_000;
	public static final int DEFAULT_RTT_MS = 80;
	public static final int MIN_RTT_MS = 5;
	public static final int MAX_RTT_MS = 1000;
	public static final int DEFAULT_MTU = 1500;

	/** Sin filtro: todas las apps pasan por la VPN. */
	public static final int SPLIT_ALL = 0;
	/** Solo las apps de {@link #splitPackages} pasan por la VPN. */
	public static final int SPLIT_INCLUDE = 1;
	/** Todas las apps menos las de {@link #splitPackages} pasan por la VPN. */
	public static final int SPLIT_EXCLUDE = 2;

	public long upKbps;
	public long downKbps;
	public int rttMs;
	public int mtu;
	public int splitMode = SPLIT_ALL;
	public final Set<String> splitPackages = new HashSet<>();

	public VpnConfig() {
		this(DEFAULT_UP_KBPS, DEFAULT_DOWN_KBPS, DEFAULT_RTT_MS, DEFAULT_MTU);
	}

	public VpnConfig(long upKbps, long downKbps, int rttMs, int mtu) {
		this.upKbps = clampKbps(upKbps);
		this.downKbps = clampKbps(downKbps);
		this.rttMs = clamp(rttMs, MIN_RTT_MS, MAX_RTT_MS);
		this.mtu = clamp(mtu, 576, 9000);
	}

	public static VpnConfig load(Context context) {
		SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
		VpnConfig config = new VpnConfig(
				p.getLong(KEY_UP, DEFAULT_UP_KBPS),
				p.getLong(KEY_DOWN, DEFAULT_DOWN_KBPS),
				p.getInt(KEY_RTT, DEFAULT_RTT_MS),
				p.getInt(KEY_MTU, DEFAULT_MTU));
		config.splitMode = clampSplitMode(p.getInt(KEY_SPLIT_MODE, SPLIT_ALL));
		Set<String> saved = p.getStringSet(KEY_SPLIT_PACKAGES, null);
		if (saved != null) {
			config.splitPackages.addAll(saved);
		}
		return config;
	}

	public void save(Context context) {
		context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
				.edit()
				.putLong(KEY_UP, upKbps)
				.putLong(KEY_DOWN, downKbps)
				.putInt(KEY_RTT, rttMs)
				.putInt(KEY_MTU, mtu)
				.putInt(KEY_SPLIT_MODE, splitMode)
				.putStringSet(KEY_SPLIT_PACKAGES, new HashSet<>(splitPackages))
				.apply();
	}

	public long upBytesPerSecond() {
		return upKbps * 1000L / 8L;
	}

	public long downBytesPerSecond() {
		return downKbps * 1000L / 8L;
	}

	public VpnConfig copy() {
		VpnConfig copy = new VpnConfig(upKbps, downKbps, rttMs, mtu);
		copy.splitMode = splitMode;
		copy.splitPackages.addAll(splitPackages);
		return copy;
	}

	public static long clampKbps(long kbps) {
		return Math.max(MIN_KBPS, Math.min(MAX_KBPS, kbps));
	}

	public static int clampSplitMode(int mode) {
		return mode == SPLIT_INCLUDE || mode == SPLIT_EXCLUDE ? mode : SPLIT_ALL;
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}
}
