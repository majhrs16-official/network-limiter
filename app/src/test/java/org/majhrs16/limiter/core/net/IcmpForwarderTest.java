package org.majhrs16.limiter.core.net;

import org.junit.Test;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** El reenviador ICMP solo responde cuando la sonda real tuvo éxito. */
public class IcmpForwarderTest {

	private static byte[] ip(String literal) throws Exception {
		return InetAddress.getByName(literal).getAddress();
	}

	private static IpPacket echoRequest(byte[] src, byte[] dst) {
		byte[] payload = "ping-data".getBytes(StandardCharsets.US_ASCII);
		byte[] icmp = new byte[8 + payload.length];
		icmp[0] = 8; /* echo request */
		icmp[1] = 0;
		Checksum.putShort(icmp, 4, 0x1234); /* id */
		Checksum.putShort(icmp, 6, 7);      /* sequence */
		System.arraycopy(payload, 0, icmp, 8, payload.length);
		int sum = Checksum.sum(icmp, 0, icmp.length, 0);
		Checksum.putShort(icmp, 2, Checksum.finish(sum));

		byte[] packet = IpPacket.buildIpv4(src, dst, IpPacket.PROTO_ICMP,
				icmp, 0, icmp.length, 1);
		IpPacket parsed = IpPacket.parse(packet);
		assertNotNull(parsed);
		return parsed;
	}

	@Test
	public void successfulProbeBuildsRealEchoReply() throws Exception {
		byte[] local = ip("10.111.0.1");
		byte[] remote = ip("93.184.216.34");
		IpPacket request = echoRequest(local, remote);

		CountDownLatch latch = new CountDownLatch(1);
		AtomicReference<byte[]> reply = new AtomicReference<>();
		AtomicReference<FlowKey> flow = new AtomicReference<>();

		IcmpProbe probe = (dstIp, icmpPayload, timeoutMs) -> {
			assertArrayEquals(remote, dstIp);
			assertEquals(8 + "ping-data".length(), icmpPayload.length);
			return IcmpProbe.Result.reachable(12_000_000L, "test");
		};

		IcmpForwarder forwarder = new IcmpForwarder(probe, (packet, key) -> {
			reply.set(packet);
			flow.set(key);
			latch.countDown();
		});
		try {
			forwarder.start();
			forwarder.submit(request);
			assertTrue("la sonda debe responder", latch.await(2, TimeUnit.SECONDS));
		} finally {
			forwarder.stop();
		}

		assertNotNull(reply.get());
		IpPacket parsed = IpPacket.parse(reply.get());
		assertNotNull(parsed);
		assertEquals(IpPacket.PROTO_ICMP, parsed.protocol);
		assertEquals(0, parsed.raw[parsed.payloadOffset] & 0xFF); /* echo reply */
		assertArrayEquals(remote, parsed.src);
		assertArrayEquals(local, parsed.dst);

		byte[] expected = "ping-data".getBytes(StandardCharsets.US_ASCII);
		byte[] actual = java.util.Arrays.copyOfRange(parsed.raw,
				parsed.payloadOffset + 8, parsed.totalLength);
		assertArrayEquals(expected, actual);

		assertNotNull(flow.get());
		assertArrayEquals(remote, flow.get().srcIp);
		assertArrayEquals(local, flow.get().dstIp);
	}

	@Test
	public void failedProbeDoesNotInventReply() throws Exception {
		byte[] local = ip("10.111.0.1");
		byte[] remote = ip("93.184.216.34");
		IpPacket request = echoRequest(local, remote);

		AtomicReference<byte[]> reply = new AtomicReference<>();
		IcmpProbe probe = (dstIp, icmpPayload, timeoutMs) ->
				IcmpProbe.Result.unreachable("test", "sin respuesta");

		IcmpForwarder forwarder = new IcmpForwarder(probe, (packet, key) ->
				reply.set(packet));
		try {
			forwarder.start();
			forwarder.submit(request);
			Thread.sleep(300);
		} finally {
			forwarder.stop();
		}
		assertNull(reply.get());
	}

	@Test
	public void nonEchoPacketIsIgnored() throws Exception {
		byte[] local = ip("10.111.0.1");
		byte[] remote = ip("93.184.216.34");
		IpPacket request = echoRequest(local, remote);
		/* Convierte el tipo en echo reply: ya no es una petición. */
		request.raw[request.payloadOffset] = 0;

		AtomicReference<byte[]> reply = new AtomicReference<>();
		IcmpProbe probe = (dstIp, icmpPayload, timeoutMs) ->
				IcmpProbe.Result.reachable(1, "test");

		IcmpForwarder forwarder = new IcmpForwarder(probe, (packet, key) ->
				reply.set(packet));
		try {
			forwarder.start();
			forwarder.submit(request);
			Thread.sleep(200);
		} finally {
			forwarder.stop();
		}
		assertFalse(Icmp.isEchoRequest(request));
		assertNull(reply.get());
	}
}
