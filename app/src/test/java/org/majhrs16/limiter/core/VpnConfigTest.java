package org.majhrs16.limiter.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class VpnConfigTest {

	@Test
	public void clampsRatesRttAndMtu() {
		VpnConfig low = new VpnConfig(1, 1, 1, 1);
		assertEquals(VpnConfig.MIN_KBPS, low.upKbps);
		assertEquals(VpnConfig.MIN_KBPS, low.downKbps);
		assertEquals(VpnConfig.MIN_RTT_MS, low.rttMs);
		assertEquals(576, low.mtu);

		VpnConfig high = new VpnConfig(200_000, 200_000, 2_000, 20_000);
		assertEquals(VpnConfig.MAX_KBPS, high.upKbps);
		assertEquals(VpnConfig.MAX_KBPS, high.downKbps);
		assertEquals(VpnConfig.MAX_RTT_MS, high.rttMs);
		assertEquals(9000, high.mtu);
	}

	@Test
	public void clampsSplitModeToKnownValues() {
		assertEquals(VpnConfig.SPLIT_ALL, VpnConfig.clampSplitMode(-1));
		assertEquals(VpnConfig.SPLIT_ALL, VpnConfig.clampSplitMode(99));
		assertEquals(VpnConfig.SPLIT_INCLUDE, VpnConfig.clampSplitMode(VpnConfig.SPLIT_INCLUDE));
		assertEquals(VpnConfig.SPLIT_EXCLUDE, VpnConfig.clampSplitMode(VpnConfig.SPLIT_EXCLUDE));
	}

	@Test
	public void copyKeepsSplitModeAndClonesPackageSet() {
		VpnConfig config = new VpnConfig();
		config.splitMode = VpnConfig.SPLIT_EXCLUDE;
		config.splitPackages.add("org.example.one");

		VpnConfig copy = config.copy();
		assertEquals(VpnConfig.SPLIT_EXCLUDE, copy.splitMode);
		assertTrue(copy.splitPackages.contains("org.example.one"));

		copy.splitPackages.add("org.example.two");
		assertFalse(config.splitPackages.contains("org.example.two"));
	}
}
