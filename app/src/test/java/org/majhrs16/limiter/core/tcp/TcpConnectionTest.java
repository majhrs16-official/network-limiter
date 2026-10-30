package org.majhrs16.limiter.core.tcp;

import org.junit.Test;

import org.majhrs16.limiter.core.Stats;
import org.majhrs16.limiter.core.net.Delivery;
import org.majhrs16.limiter.core.net.FlowKey;
import org.majhrs16.limiter.core.net.IpPacket;
import org.majhrs16.limiter.core.net.TcpSegment;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Verifica la dirección de los paquetes y el handshake sin depender de
 * Android. Es la prueba que habría detectado invertir origen/destino.
 */
public class TcpConnectionTest {

	private static final class Capture implements TcpCallbacks {
		final List<byte[]> downloads = new ArrayList<>();
		final List<byte[]> uploads = new ArrayList<>();
		final Stats stats = new Stats();
		volatile boolean closed;

		@Override
		public void enqueueDownload(byte[] packet, FlowKey flow, Delivery delivery) {
			downloads.add(packet);
		}

		@Override
		public void enqueueUpload(TcpConnection connection, byte[] payload, int wireLength) {
			uploads.add(payload);
		}

		@Override
		public void onConnectionClosed(TcpConnection connection) {
			closed = true;
		}

		@Override
		public boolean protect(SocketChannel channel) {
			return true;
		}

		@Override
		public int mtu() {
			return 1500;
		}

		@Override
		public long receiveWindowCap() {
			return 65536;
		}

		@Override
		public int sendBufferBytes() {
			return 65536;
		}

		@Override
		public int receiveBufferBytes() {
			return 65536;
		}

		@Override
		public Stats stats() {
			return stats;
		}

		@Override
		public void wakeup() {
		}
	}

	private static ServerSocket listener() throws IOException {
		ServerSocket server = new ServerSocket(0, 1,
				InetAddress.getByName("127.0.0.1"));
		Thread acceptor = new Thread(() -> {
			try {
				Socket socket = server.accept();
				socket.close();
			} catch (IOException ignored) {
				/* el test ya terminó */
			}
		}, "test-acceptor");
		acceptor.setDaemon(true);
		acceptor.start();
		return server;
	}

	private static TcpConnection connect(Capture cb, FlowKey key, IpPacket synIp,
			TcpSegment syn, long now) {
		TcpConnection connection = new TcpConnection(cb, key, synIp, syn, now);
		connection.open(now);
		connection.onConnectable(now + 1_000_000L);
		return connection;
	}

	@Test
	public void synAckAndAcksGoFromServerToDevice() throws Exception {
		ServerSocket server = listener();
		try {
			byte[] device = InetAddress.getByName("10.111.0.1").getAddress();
			byte[] remote = InetAddress.getByName("127.0.0.1").getAddress();
			int port = server.getLocalPort();
			long clientIsn = 1000;

			byte[] synPacket = TcpSegment.build(device, remote, 33333, port, clientIsn, 0,
					TcpSegment.SYN, 65535, null, 0, 0, 1460, 7, false);
			IpPacket synIp = IpPacket.parse(synPacket);
			TcpSegment syn = TcpSegment.parse(synIp);
			assertNotNull(syn);

			FlowKey key = new FlowKey(device, 33333, remote, port, IpPacket.PROTO_TCP);
			Capture cb = new Capture();
			TcpConnection connection = connect(cb, key, synIp, syn, 0);

			assertEquals(1, cb.downloads.size());
			IpPacket synAckIp = IpPacket.parse(cb.downloads.get(0));
			assertNotNull(synAckIp);
			assertArrayEquals(remote, synAckIp.src);
			assertArrayEquals(device, synAckIp.dst);

			TcpSegment synAck = TcpSegment.parse(synAckIp);
			assertNotNull(synAck);
			assertEquals(port, synAck.srcPort);
			assertEquals(33333, synAck.dstPort);
			assertTrue(synAck.has(TcpSegment.SYN));
			assertTrue(synAck.has(TcpSegment.ACK));
			assertEquals(clientIsn + 1, synAck.ack);
			assertEquals(1460, synAck.mss);
			assertEquals(4, synAck.windowScale);

			byte[] ackPacket = TcpSegment.build(device, remote, 33333, port,
					clientIsn + 1, synAck.seq + 1, TcpSegment.ACK, 65535,
					null, 0, 0, 0, -1, false);
			IpPacket ackIp = IpPacket.parse(ackPacket);
			connection.onSegment(ackIp, TcpSegment.parse(ackIp), 2_000_000L);
			assertFalse(cb.closed);

			byte[] payload = "hola".getBytes(StandardCharsets.US_ASCII);
			byte[] dataPacket = TcpSegment.build(device, remote, 33333, port,
					clientIsn + 1, synAck.seq + 1, TcpSegment.ACK | TcpSegment.PSH,
					65535, payload, 0, payload.length, 0, -1, false);
			IpPacket dataIp = IpPacket.parse(dataPacket);
			connection.onSegment(dataIp, TcpSegment.parse(dataIp), 3_000_000L);
			assertEquals(1, cb.uploads.size());
			assertArrayEquals(payload, cb.uploads.get(0));

			boolean sawAck = false;
			for (byte[] packet : cb.downloads) {
				IpPacket ip = IpPacket.parse(packet);
				TcpSegment segment = TcpSegment.parse(ip);
				if (segment != null && segment.has(TcpSegment.ACK)
						&& segment.ack == clientIsn + 1 + payload.length) {
					assertArrayEquals(remote, ip.src);
					assertArrayEquals(device, ip.dst);
					assertEquals(port, segment.srcPort);
					assertEquals(33333, segment.dstPort);
					sawAck = true;
				}
			}
			assertTrue("faltó el ACK del dato de subida", sawAck);

			connection.close(false, 4_000_000L);
			assertTrue(cb.closed);
		} finally {
			server.close();
		}
	}

	@Test
	public void windowScaleIsNotAdvertisedIfClientDoesNotOfferIt() throws Exception {
		ServerSocket server = listener();
		try {
			byte[] device = InetAddress.getByName("10.111.0.1").getAddress();
			byte[] remote = InetAddress.getByName("127.0.0.1").getAddress();
			int port = server.getLocalPort();

			byte[] synPacket = TcpSegment.build(device, remote, 44444, port, 77, 0,
					TcpSegment.SYN, 8192, null, 0, 0, 1400, -1, false);
			IpPacket synIp = IpPacket.parse(synPacket);
			TcpSegment syn = TcpSegment.parse(synIp);
			assertNotNull(syn);

			FlowKey key = new FlowKey(device, 44444, remote, port, IpPacket.PROTO_TCP);
			Capture cb = new Capture();
			TcpConnection connection = connect(cb, key, synIp, syn, 0);

			IpPacket synAckIp = IpPacket.parse(cb.downloads.get(0));
			TcpSegment synAck = TcpSegment.parse(synAckIp);
			assertNotNull(synAck);
			assertEquals(-1, synAck.windowScale);

			connection.close(false, 1_000_000L);
		} finally {
			server.close();
		}
	}
}
