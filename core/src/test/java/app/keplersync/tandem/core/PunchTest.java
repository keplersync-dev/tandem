package app.keplersync.tandem.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Two games punching through pretend routers, introduced by a pretend relay, all on this PC. */
class PunchTest {
	final List<AutoCloseable> open = new ArrayList<>();

	@AfterEach
	void tearDown() throws Exception {
		for (AutoCloseable c : open) c.close();
	}

	/**
	 * A home router in front of a game: packets leave from one outside port (easy) or a new one for each destination
	 * (hard); packets come in only from addresses the game sent to through that port, or from any port of an address
	 * it sent to when [byAddressOnly].
	 */
	final class Nat implements Punch.Wire, AutoCloseable {
		final boolean hard, byAddressOnly;
		final Map<InetSocketAddress, DatagramSocket> outside = new ConcurrentHashMap<>();
		final Map<DatagramSocket, Set<InetSocketAddress>> sentTo = new ConcurrentHashMap<>();
		final BlockingQueue<Object[]> in = new ArrayBlockingQueue<>(4096);
		volatile boolean closed;
		DatagramSocket single;

		Nat(boolean hard, boolean byAddressOnly) {
			this.hard = hard;
			this.byAddressOnly = byAddressOnly;
			open.add(this);
		}

		private synchronized DatagramSocket socketFor(InetSocketAddress to) throws IOException {
			if (!hard) {
				if (single == null) single = listen(new DatagramSocket(0, InetAddress.getLoopbackAddress()));
				return single;
			}
			DatagramSocket s = outside.get(to);
			if (s == null) {
				s = listen(new DatagramSocket(0, InetAddress.getLoopbackAddress()));
				outside.put(to, s);
			}
			return s;
		}

		private DatagramSocket listen(DatagramSocket s) {
			sentTo.put(s, ConcurrentHashMap.newKeySet());
			RelayTunnel.daemon(() -> {
				byte[] buf = new byte[2048];
				while (!closed) {
					try {
						DatagramPacket p = new DatagramPacket(buf, buf.length);
						s.receive(p);
						InetSocketAddress from = (InetSocketAddress) p.getSocketAddress();
						Set<InetSocketAddress> sent = sentTo.get(s);
						boolean allowed = byAddressOnly ? sentTo.values().stream().anyMatch(set -> set.stream().anyMatch(a -> a.getAddress().equals(from.getAddress())))
								: sent.contains(from);
						if (allowed) in.offer(new Object[] {java.util.Arrays.copyOf(buf, p.getLength()), from});
					} catch (IOException e) {
						return;
					}
				}
			}, "nat");
			return s;
		}

		@Override
		public void send(InetSocketAddress to, byte[] data, int off, int len) throws IOException {
			DatagramSocket s = socketFor(to);
			sentTo.get(s).add(to);
			s.send(new DatagramPacket(data, off, len, to));
		}

		@Override
		public int receive(byte[] buf, InetSocketAddress[] from) throws IOException {
			try {
				Object[] p;
				while ((p = in.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS)) == null) {
					if (closed) throw new IOException("closed");
				}
				byte[] d = (byte[]) p[0];
				System.arraycopy(d, 0, buf, 0, d.length);
				from[0] = (InetSocketAddress) p[1];
				return d.length;
			} catch (InterruptedException e) {
				throw new IOException("closed");
			}
		}

		@Override
		public int localPort() {
			return 1;
		}

		@Override
		public void close() {
			closed = true;
			if (single != null) single.close();
			outside.values().forEach(DatagramSocket::close);
		}
	}

	/** The relay's UDP side: tells games their address, and introduces a guest to the host. */
	final class Relay implements AutoCloseable {
		final DatagramSocket first, second;
		volatile Punch.Host host;
		volatile InetSocketAddress hostAt;
		volatile String hostNat = "unknown";
		volatile boolean closed;

		Relay() throws IOException {
			first = new DatagramSocket(0, InetAddress.getLoopbackAddress());
			second = new DatagramSocket(0, InetAddress.getLoopbackAddress());
			serve(first, true);
			serve(second, false);
			open.add(this);
		}

		String udpLine() {
			return "UDP key " + first.getLocalPort() + " " + second.getLocalPort();
		}

		InetSocketAddress at(DatagramSocket s) {
			return new InetSocketAddress(InetAddress.getLoopbackAddress(), s.getLocalPort());
		}

		private void serve(DatagramSocket s, boolean isFirst) {
			RelayTunnel.daemon(() -> {
				byte[] buf = new byte[2048];
				while (!closed) {
					try {
						DatagramPacket p = new DatagramPacket(buf, buf.length);
						s.receive(p);
						if (p.getLength() < Punch.REQUEST) continue;
						InetSocketAddress from = (InetSocketAddress) p.getSocketAddress();
						String[] w = new String(buf, 4, p.getLength() - 4, StandardCharsets.UTF_8).trim().split("\\s+");
						byte type = buf[3];
						if (type == Punch.HELLO) {
							if (w[0].equals("H") && isFirst) {
								hostAt = from;
								hostNat = w[2];
							}
							reply(s, Punch.SEEN, Punch.text(from), from);
						} else if (type == Punch.PUNCH && isFirst) {
							if (hostAt == null) {
								reply(s, Punch.NOPE, "host_no_udp", from);
								continue;
							}
							String token = "00000000000000a7";
							host.introduce("PUNCH " + token + " " + Punch.text(from) + " " + w[1]);
							reply(s, Punch.PEER, token + " " + Punch.text(hostAt) + " " + hostNat, from);
						}
					} catch (IOException e) {
						return;
					}
				}
			}, "relay");
		}

		private void reply(DatagramSocket s, byte type, String text, InetSocketAddress to) throws IOException {
			byte[] b = text.getBytes(StandardCharsets.UTF_8);
			byte[] out = new byte[4 + b.length];
			out[0] = 'T';
			out[1] = 'P';
			out[2] = 1;
			out[3] = type;
			System.arraycopy(b, 0, out, 4, b.length);
			s.send(new DatagramPacket(out, out.length, to));
		}

		@Override
		public void close() {
			closed = true;
			first.close();
			second.close();
		}
	}

	/** A game on the host's LAN port: echoes, or answers "kick" with 300 KB and closes. */
	ServerSocket game() throws IOException {
		ServerSocket game = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
		open.add(game);
		RelayTunnel.daemon(() -> {
			while (!game.isClosed()) {
				try {
					Socket s = game.accept();
					RelayTunnel.daemon(() -> {
						try (InputStream in = s.getInputStream(); OutputStream out = s.getOutputStream()) {
							byte[] buf = new byte[8192];
							int n;
							while ((n = in.read(buf)) != -1) {
								if (new String(buf, 0, n, StandardCharsets.UTF_8).equals("kick")) {
									out.write(bigBlock());
									out.flush();
									return;
								}
								out.write(buf, 0, n);
								out.flush();
							}
						} catch (IOException ignored) {
							// Done.
						} finally {
							try {
								s.close();
							} catch (IOException ignored) {
								// Already.
							}
						}
					}, "game connection");
				} catch (IOException e) {
					return;
				}
			}
		}, "game");
		return game;
	}

	static byte[] bigBlock() {
		byte[] b = new byte[300_000];
		new Random(9).nextBytes(b);
		return b;
	}

	/** A host and a guest behind their routers, the guest punched through; or the reason it couldn't. */
	Punch.Guest punch(Nat hostNat, Nat guestNat, int gamePort) throws Exception {
		Relay relay = new Relay();
		Punch.Host host = new Punch.Host(hostNat, gamePort);
		open.add(host);
		relay.host = host;
		host.register("127.0.0.1", relay.udpLine());
		// A host registers long before anyone joins: by then its router's kind is known.
		for (int i = 0; i < 200 && (relay.hostAt == null || relay.hostNat.equals("unknown")); i++) Thread.sleep(10);
		Punch.Guest g = Punch.Guest.connect(guestNat, relay.at(relay.first), relay.at(relay.second), "ticket", 3000);
		open.add(g);
		return g;
	}

	byte[] roundTrip(int port, byte[] send) throws IOException {
		try (Socket s = new Socket(InetAddress.getLoopbackAddress(), port)) {
			s.setSoTimeout(10_000);
			s.getOutputStream().write(send);
			s.getOutputStream().flush();
			byte[] back = new byte[send.length];
			int got = 0;
			while (got < back.length) {
				int n = s.getInputStream().read(back, got, back.length - got);
				if (n < 0) break;
				got += n;
			}
			return back;
		}
	}

	@Test
	void easyRoutersOnBothSidesPunchThroughAndCarryTwoStreams() throws Exception {
		ServerSocket game = game();
		Punch.Guest g = punch(new Nat(false, false), new Nat(false, false), game.getLocalPort());
		byte[] ping = "status?".getBytes(StandardCharsets.UTF_8);
		assertArrayEquals(ping, roundTrip(g.port(), ping), "the status ping");
		byte[] big = new byte[2_000_000];
		new Random(3).nextBytes(big);
		assertArrayEquals(big, roundTrip(g.port(), big), "then the game, on its own conversation");
	}

	@Test
	void theEndOfAStreamComesAfterEverythingBeforeIt() throws Exception {
		ServerSocket game = game();
		Punch.Guest g = punch(new Nat(false, false), new Nat(false, false), game.getLocalPort());
		try (Socket s = new Socket(InetAddress.getLoopbackAddress(), g.port())) {
			s.setSoTimeout(10_000);
			s.getOutputStream().write("kick".getBytes(StandardCharsets.UTF_8));
			s.getOutputStream().flush();
			ByteArrayOutputStream got = new ByteArrayOutputStream();
			byte[] buf = new byte[8192];
			int n;
			while ((n = s.getInputStream().read(buf)) != -1) got.write(buf, 0, n);
			assertArrayEquals(bigBlock(), got.toByteArray(), "the whole kick message, then the end");
		}
	}

	@Test
	void aHardHostGetsThroughToAGuestWhoseRouterOnlyChecksAddresses() throws Exception {
		ServerSocket game = game();
		Punch.Guest g = punch(new Nat(true, false), new Nat(false, true), game.getLocalPort());
		byte[] hi = "hello".getBytes(StandardCharsets.UTF_8);
		assertArrayEquals(hi, roundTrip(g.port(), hi));
	}

	@Test
	void twoHardRoutersCantAndSaySo() throws Exception {
		ServerSocket game = game();
		Punch.Failed f = assertThrows(Punch.Failed.class, () -> punch(new Nat(true, false), new Nat(true, false), game.getLocalPort()));
		assertEquals("both_hard_nat", f.reason);
	}

	@Test
	void aHostThatNeverRegisteredIsReported() throws Exception {
		Relay relay = new Relay();
		Punch.Failed f = assertThrows(Punch.Failed.class,
				() -> Punch.Guest.connect(new Nat(false, false), relay.at(relay.first), relay.at(relay.second), "ticket", 3000));
		assertEquals("host_no_udp", f.reason);
	}

	@Test
	void noRelayOnUdpIsReported() throws Exception {
		DatagramSocket silent = new DatagramSocket(0, InetAddress.getLoopbackAddress());
		open.add(silent);
		InetSocketAddress at = new InetSocketAddress(InetAddress.getLoopbackAddress(), silent.getLocalPort());
		Punch.Failed f = assertThrows(Punch.Failed.class, () -> Punch.Guest.connect(new Nat(false, false), at, at, "ticket", 3000));
		assertEquals("relay_no_udp", f.reason);
	}

	@Test
	void packetsWithAnUnknownTokenAreIgnored() throws Exception {
		ServerSocket game = game();
		Punch.Wire wire = Punch.udp(0);
		Punch.Host host = new Punch.Host(wire, game.getLocalPort());
		open.add(host);
		try (DatagramSocket stranger = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
			byte[] forged = new byte[12 + Kcp.OVERHEAD + 5];
			forged[0] = 'T';
			forged[1] = 'P';
			forged[2] = 1;
			forged[3] = Punch.DATA;
			for (byte type : new byte[] {Punch.PING, Punch.DATA}) {
				forged[3] = type;
				stranger.send(new DatagramPacket(forged, forged.length, new InetSocketAddress(InetAddress.getLoopbackAddress(), wire.localPort())));
			}
			Thread.sleep(200);
			assertTrue(host.peers.isEmpty(), "nobody was let in");
		}
	}
}
