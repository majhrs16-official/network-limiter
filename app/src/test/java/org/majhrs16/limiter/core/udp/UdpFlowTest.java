package org.majhrs16.limiter.core.udp;

import org.junit.Test;

import org.majhrs16.limiter.core.Stats;
import org.majhrs16.limiter.core.net.Delivery;
import org.majhrs16.limiter.core.net.FlowKey;
import org.majhrs16.limiter.core.net.IpPacket;
import org.majhrs16.limiter.core.net.UdpDatagram;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Dirección de los datagramas y NAT de subida/bajada. */
public class UdpFlowTest {

	private static final class Capture implements UdpFlow.Callbacks {
		final List<byte[]> downloads = new ArrayList<>();
		final ByteBuffer buffer = ByteBuffer.allocate(65535);
		final Stats stats = new Stats();
		volatile boolean closed;

		@Override
		public void enqueueDownload(byte[] packet, FlowKey flow, Delivery delivery) {
			downloads.add(packet);
		}

		@Override
		public void wakeup() {
		}

		@Override
		public int mtu() {
			return 1500;
		}

		@Override
		public Stats stats() {
			return stats;
		}

		@Override
		public ByteBuffer readBuffer() {
			return buffer;
		}

		@Override
		public void onFlowClosed(UdpFlow flow) {
			closed = true;
		}
	}

	@Test
	public void datagramsAreReversedAndForwarded() throws Exception {
		InetAddress loopback = InetAddress.getByName("127.0.0.1");
		DatagramSocket server = new DatagramSocket(0, loopback);
		server.setSoTimeout(3000);
		DatagramChannel channel = DatagramChannel.open();
		try {
			channel.configureBlocking(false);
			channel.connect(new InetSocketAddress(loopback, server.getLocalPort()));
			int clientPort = channel.socket().getLocalPort();

			byte[] device = InetAddress.getByName("10.111.0.1").getAddress();
			byte[] remote = loopback.getAddress();
			FlowKey key = new FlowKey(device, 5000, remote, server.getLocalPort(),
					IpPacket.PROTO_UDP);
			Capture cb = new Capture();
			UdpFlow flow = new UdpFlow(cb, key, channel, 0);

			byte[] reply = "respuesta".getBytes(StandardCharsets.US_ASCII);
			server.send(new DatagramPacket(reply, reply.length, loopback, clientPort));

			for (int i = 0; i < 100 && cb.downloads.isEmpty(); i++) {
				Thread.sleep(10);
				flow.onReadable(1_000_000L);
			}
			assertFalse("no llegó la respuesta", cb.downloads.isEmpty());

			IpPacket ip = IpPacket.parse(cb.downloads.get(0));
			assertNotNull(ip);
			assertArrayEquals(remote, ip.src);
			assertArrayEquals(device, ip.dst);
			UdpDatagram datagram = UdpDatagram.parse(ip);
			assertNotNull(datagram);
			assertEquals(server.getLocalPort(), datagram.srcPort);
			assertEquals(5000, datagram.dstPort);
			assertArrayEquals(reply, Arrays.copyOfRange(ip.raw, datagram.payloadOffset,
					datagram.payloadOffset + datagram.payloadLength));

			byte[] upload = "subida".getBytes(StandardCharsets.US_ASCII);
			flow.onUploadReady(upload);
			flow.onWritable(2_000_000L);

			byte[] buffer = new byte[64];
			DatagramPacket received = new DatagramPacket(buffer, buffer.length);
			server.receive(received);
			assertArrayEquals(upload,
					Arrays.copyOf(received.getData(), received.getLength()));

			flow.close(3_000_000L);
			assertTrue(cb.closed);
		} finally {
			channel.close();
			server.close();
		}
	}
}
