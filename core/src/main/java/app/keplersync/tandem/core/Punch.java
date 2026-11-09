package app.keplersync.tandem.core;

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
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Two games connecting directly over UDP when neither can be reached on TCP (hole punching), the way Tailscale and
 * WebRTC do: the relay tells each game the other's public address, both send to each other at once so each router
 * lets the other's packets in, and the game's TCP stream then rides on KCP over that path. The relay carries none of
 * the game.
 *
 * Packets are `TP`, version 1, a type and an 8-byte token the relay gave this pair of games, then the body; packets
 * with an unknown version, type or token are dropped. Each local connection (the status ping, the game, a reconnect)
 * is its own KCP conversation, framed as `[u16 length][bytes]` with a length of 0 for its end.
 */
public final class Punch {
	static final byte HELLO = 1, SEEN = 2, PUNCH = 3, PEER = 4, NOPE = 5;
	static final byte PING = 16, PONG = 17, DATA = 18, KEEP = 19;
	/** Relay requests are padded to this (it never answers more than it was sent). */
	static final int REQUEST = 600;
	/** A 1200-byte UDP payload, less the 12-byte header. */
	static final int MTU = 1188;
	static final int WINDOW = 512;
	/** Like the relay's cap: a game needs far less. */
	static final int MAX_BYTES_PER_SECOND = 4 << 20;
	static final int MAX_STREAMS = 16;
	static final long KEEP_EVERY_MS = 15_000, SILENT_MS = 30_000, IDLE_MS = 60_000;
	private static final SecureRandom RANDOM = new SecureRandom();

	private Punch() {}

	static void configure(Kcp k) {
		// TCP-like timeouts: the low-latency ones fired on a router's queueing delay and threw the window away.
		k.nodelay(0, 10, 2, false);
		k.wndSize(WINDOW, WINDOW);
		k.setMtu(MTU);
	}

	/** Why punching didn't work, one of KeplerSync's reason words (relay_no_udp, both_hard_nat, …). */
	public static final class Failed extends IOException {
		public final String reason;

		Failed(String reason) {
			super("couldn't connect directly (" + reason + ")");
			this.reason = reason;
		}
	}

	/** Where packets go out and come in: a UDP socket, or in tests one behind a pretend router. */
	public interface Wire {
		void send(InetSocketAddress to, byte[] data, int off, int len) throws IOException;

		/** The next packet into [buf]; its length, its source in [from][0]. Blocks; throws once closed. */
		int receive(byte[] buf, InetSocketAddress[] from) throws IOException;

		int localPort();

		void close();
	}

	/** A plain UDP socket on every address of this PC (IPv4 and IPv6). */
	public static Wire udp(int port) throws IOException {
		DatagramSocket s = new DatagramSocket(port);
		return new Wire() {
			@Override
			public void send(InetSocketAddress to, byte[] data, int off, int len) throws IOException {
				s.send(new DatagramPacket(data, off, len, to));
			}

			@Override
			public int receive(byte[] buf, InetSocketAddress[] from) throws IOException {
				DatagramPacket p = new DatagramPacket(buf, buf.length);
				s.receive(p);
				from[0] = (InetSocketAddress) p.getSocketAddress();
				return p.getLength();
			}

			@Override
			public int localPort() {
				return s.getLocalPort();
			}

			@Override
			public void close() {
				s.close();
			}
		};
	}

	static byte[] relayPacket(byte type, String text) {
		byte[] body = text.getBytes(StandardCharsets.UTF_8);
		byte[] out = new byte[Math.max(REQUEST, 4 + body.length)];
		out[0] = 'T';
		out[1] = 'P';
		out[2] = 1;
		out[3] = type;
		System.arraycopy(body, 0, out, 4, body.length);
		return out;
	}

	static long token(String hex) {
		return Long.parseUnsignedLong(hex, 16);
	}

	static InetSocketAddress address(String hostPort) {
		return RelayTunnel.address(hostPort);
	}

	static String text(InetSocketAddress a) {
		return Addresses.hostPort(a.getAddress(), a.getPort());
	}

	/** This game's own addresses on its home networks and its public IPv6 ones, for the other game to try too. */
	static List<String> candidates(int port) {
		List<String> out = new ArrayList<>();
		for (InetAddress a : Addresses.lanV4()) out.add(Addresses.hostPort(a, port));
		for (InetAddress a : Addresses.globalV6()) if (out.size() < 4) out.add(Addresses.hostPort(a, port));
		return out.size() > 4 ? out.subList(0, 4) : out;
	}

	/** The relay's view of this game from its two ports: easy (the same port both times), hard, or unknown. */
	static String nat(InetSocketAddress first, InetSocketAddress second) {
		if (first == null || second == null) return "unknown";
		return first.equals(second) ? "easy" : "hard";
	}

	/** One end: the UDP wire, the games it punched through to, and the streams to each. */
	abstract static class Game implements AutoCloseable {
		final Wire wire;
		final Map<Long, Peer> peers = new ConcurrentHashMap<>();
		volatile boolean closed;
		volatile InetSocketAddress relay1, relay2;
		volatile InetSocketAddress seen1, seen2;
		/** The relay's latest answer to an introduction: PEER or NOPE text, with its type first. */
		volatile String relayAnswer;

		Game(Wire wire) {
			this.wire = wire;
			RelayTunnel.daemon(this::receiveLoop, "punch receive");
			RelayTunnel.daemon(this::tickLoop, "punch tick");
		}

		final class Peer {
			final long token;
			final byte[] tokenBytes = new byte[8];
			final List<InetSocketAddress> candidates;
			final int[] pings;
			final Map<Integer, Stream> streams = new ConcurrentHashMap<>();
			volatile InetSocketAddress address;
			volatile boolean connected;
			volatile long heard = System.currentTimeMillis();
			volatile long sent;
			volatile long lastStream = System.currentTimeMillis();
			volatile long nextPing;
			volatile boolean everStreamed;

			Peer(long token, List<InetSocketAddress> candidates) {
				this.token = token;
				for (int i = 0; i < 8; i++) tokenBytes[i] = (byte) (token >>> (56 - 8 * i));
				this.candidates = candidates;
				this.pings = new int[candidates.size()];
			}

			void send(byte type, byte[] body, int off, int len, InetSocketAddress to) {
				if (to == null) return;
				byte[] out = new byte[12 + len];
				out[0] = 'T';
				out[1] = 'P';
				out[2] = 1;
				out[3] = type;
				System.arraycopy(tokenBytes, 0, out, 4, 8);
				if (len > 0) System.arraycopy(body, off, out, 12, len);
				try {
					wire.send(to, out, 0, out.length);
					sent = System.currentTimeMillis();
				} catch (IOException ignored) {
					// That address can't be reached from here (no IPv6, say); the others may.
				}
			}

			/** Pings each candidate every 200 ms, at most 15 times, until one answers. */
			void ping(long now) {
				if (connected || now < nextPing) return;
				nextPing = now + 200;
				for (int i = 0; i < candidates.size(); i++) {
					if (pings[i] < 15) {
						pings[i]++;
						send(PING, null, 0, 0, candidates.get(i));
					}
				}
			}

			Stream stream(int conv) {
				return streams.get(conv);
			}

			void close() {
				for (Stream s : streams.values()) s.close();
				streams.clear();
				peers.remove(token);
			}
		}

		/** One local connection carried to the other game. */
		final class Stream {
			final Peer peer;
			final int conv;
			final Kcp kcp;
			volatile Socket socket;
			volatile boolean endSent, endReceived, closed;

			Stream(Peer peer, int conv) {
				this.peer = peer;
				this.conv = conv;
				this.kcp = new Kcp(conv, (data, len) -> peer.send(DATA, data, 0, len, peer.address));
				configure(kcp);
			}

			void start(Socket s) {
				socket = s;
				try {
					s.setTcpNoDelay(true);
				} catch (IOException ignored) {
					// Fine either way.
				}
				RelayTunnel.daemon(this::read, "punch up");
				RelayTunnel.daemon(this::write, "punch down");
			}

			/** The local connection's bytes into KCP, framed, at up to [MAX_BYTES_PER_SECOND], never far ahead of acks. */
			private void read() {
				byte[] frame = new byte[16 * 1024 + 2];
				long second = System.currentTimeMillis();
				long thisSecond = 0;
				try (InputStream in = socket.getInputStream()) {
					int n;
					while ((n = in.read(frame, 2, frame.length - 2)) != -1) {
						frame[0] = (byte) (n >> 8);
						frame[1] = (byte) n;
						long now = System.currentTimeMillis();
						if (now - second >= 1000) {
							second = now;
							thisSecond = 0;
						}
						thisSecond += n;
						if (thisSecond > MAX_BYTES_PER_SECOND) Thread.sleep(Math.max(1, 1000 - (now - second)));
						synchronized (kcp) {
							while (kcp.waitSnd() > 2 * WINDOW && !closed) kcp.wait(50);
							if (closed) return;
							kcp.send(frame, 0, n + 2);
						}
					}
				} catch (IOException | InterruptedException e) {
					// The connection ended.
				} finally {
					end();
				}
			}

			/** Sends the end of the stream once. */
			void end() {
				synchronized (kcp) {
					if (endSent) return;
					endSent = true;
					kcp.send(new byte[2], 0, 2);
				}
			}

			/** KCP's bytes out to the local connection, unframed; the end frame closes it. */
			private void write() {
				byte[] chunk = new byte[64 * 1024];
				byte[] out = new byte[64 * 1024 + 2];
				int need = -1;
				int have = 0;
				byte[] lengthBytes = new byte[2];
				int lengthHave = 0;
				try (OutputStream o = socket.getOutputStream()) {
					while (!closed) {
						int n;
						synchronized (kcp) {
							while ((n = kcp.recv(chunk)) < 0 && !closed) kcp.wait(50);
							kcp.notifyAll();
						}
						if (n < 0) return;
						int at = 0;
						int outLen = 0;
						while (at < n) {
							if (need < 0) {
								lengthBytes[lengthHave++] = chunk[at++];
								if (lengthHave == 2) {
									need = (lengthBytes[0] & 0xFF) << 8 | (lengthBytes[1] & 0xFF);
									lengthHave = 0;
									have = 0;
									if (need == 0) {
										if (outLen > 0) o.write(out, 0, outLen);
										o.flush();
										endReceived = true;
										return;
									}
								}
							} else {
								int take = Math.min(need - have, n - at);
								System.arraycopy(chunk, at, out, outLen, take);
								outLen += take;
								at += take;
								have += take;
								if (have == need) need = -1;
							}
						}
						if (outLen > 0) {
							o.write(out, 0, outLen);
							o.flush();
						}
					}
				} catch (IOException | InterruptedException e) {
					// The connection ended.
				} finally {
					try {
						socket.close();
					} catch (IOException ignored) {
						// Already.
					}
				}
			}

			boolean done() {
				synchronized (kcp) {
					return kcp.state == -1 || (endSent && (endReceived || closed) && kcp.waitSnd() == 0);
				}
			}

			void close() {
				closed = true;
				synchronized (kcp) {
					kcp.notifyAll();
				}
				Socket s = socket;
				if (s != null) {
					try {
						s.close();
					} catch (IOException ignored) {
						// Already.
					}
				}
			}
		}

		private void receiveLoop() {
			byte[] buf = new byte[2048];
			InetSocketAddress[] from = new InetSocketAddress[1];
			while (!closed) {
				int n;
				try {
					n = wire.receive(buf, from);
				} catch (IOException e) {
					if (closed) return;
					continue;
				}
				try {
					onPacket(buf, n, from[0]);
				} catch (RuntimeException ignored) {
					// A malformed packet; drop it.
				}
			}
		}

		void onPacket(byte[] buf, int n, InetSocketAddress from) {
			if (n < 4 || buf[0] != 'T' || buf[1] != 'P' || buf[2] != 1) return;
			byte type = buf[3];
			if (type == SEEN || type == PEER || type == NOPE) {
				if (!from.equals(relay1) && !from.equals(relay2)) return;
				String text = new String(buf, 4, n - 4, StandardCharsets.UTF_8).trim();
				if (type == SEEN) {
					if (from.equals(relay1)) seen1 = address(text);
					else seen2 = address(text);
				} else {
					relayAnswer = (type == PEER ? "PEER " : "NOPE ") + text;
				}
				return;
			}
			if (n < 12) return;
			long token = 0;
			for (int i = 0; i < 8; i++) token = token << 8 | (buf[4 + i] & 0xFF);
			Peer peer = peers.get(token);
			if (peer == null) return;
			// Whichever address the other game's packets come from now is where it's answered.
			peer.address = from;
			peer.heard = System.currentTimeMillis();
			if (type == PING) {
				peer.connected = true;
				peer.send(PONG, null, 0, 0, from);
			} else if (type == PONG) {
				peer.connected = true;
			} else if (type == DATA && n >= 12 + Kcp.OVERHEAD) {
				peer.connected = true;
				int conv = Kcp.le32(buf, 12);
				Stream s = peer.stream(conv);
				if (s == null) s = newStream(peer, conv);
				if (s == null) return;
				synchronized (s.kcp) {
					s.kcp.input(buf, 12, n - 12);
					s.kcp.notifyAll();
				}
			}
		}

		/** A conversation the other game started; only the host accepts those. */
		Stream newStream(Peer peer, int conv) {
			return null;
		}

		private void tickLoop() {
			while (!closed) {
				try {
					Thread.sleep(10);
				} catch (InterruptedException e) {
					return;
				}
				long now = System.currentTimeMillis();
				for (Peer p : peers.values()) {
					p.ping(now);
					for (Stream s : p.streams.values()) {
						synchronized (s.kcp) {
							s.kcp.update((int) now);
							s.kcp.notifyAll();
						}
						if (s.done()) {
							s.close();
							p.streams.remove(s.conv);
							p.lastStream = now;
						}
					}
					if (p.connected && now - p.sent > KEEP_EVERY_MS) p.send(KEEP, null, 0, 0, p.address);
					boolean silent = now - p.heard > SILENT_MS;
					boolean idle = p.streams.isEmpty() && now - p.lastStream > IDLE_MS;
					if (silent || idle) p.close();
				}
				tick(now);
			}
		}

		void tick(long now) {}

		/** Waits up to [ms] for the relay to have answered from both ports, re-sending [hello] every 300 ms. */
		void hello(String hello, long ms) throws InterruptedException {
			long end = System.currentTimeMillis() + ms;
			byte[] p = relayPacket(HELLO, hello);
			while (System.currentTimeMillis() < end && (seen1 == null || seen2 == null)) {
				try {
					wire.send(relay1, p, 0, p.length);
					wire.send(relay2, p, 0, p.length);
				} catch (IOException ignored) {
					// Try again.
				}
				long wait = Math.min(300, end - System.currentTimeMillis());
				for (long t = 0; t < wait && (seen1 == null || seen2 == null); t += 10) Thread.sleep(10);
			}
		}

		@Override
		public void close() {
			closed = true;
			for (Peer p : peers.values()) p.close();
			wire.close();
		}
	}

	/**
	 * The host's end: registered with the relay over UDP (which keeps its router's mapping open), it answers the
	 * relay's introductions and pipes each stream into the game's LAN port.
	 */
	public static final class Host extends Game {
		private final int gamePort;
		private volatile String key;
		private volatile long nextHello;
		/** This end of each connection into the game, so the game can tell who came in this way. */
		private final java.util.Set<Socket> games = ConcurrentHashMap.newKeySet();

		public Host(Wire wire, int gamePort) {
			super(wire);
			this.gamePort = gamePort;
		}

		/** The relay's `UDP <key> <port> <port2>` line: where to register, on the relay's host [relayHost]. */
		public void register(String relayHost, String line) {
			String[] w = line.trim().split(" ");
			if (w.length < 4 || !w[0].equals("UDP")) return;
			relay1 = new InetSocketAddress(relayHost, Integer.parseInt(w[2]));
			relay2 = new InetSocketAddress(relayHost, Integer.parseInt(w[3]));
			seen1 = seen2 = null;
			key = w[1];
			nextHello = 0;
		}

		public int localPort() {
			return wire.localPort();
		}

		/** The public address the relay sees this game's UDP at, once it has answered. */
		public InetSocketAddress publicAddress() {
			return seen1;
		}

		public String nat() {
			return Punch.nat(seen1, seen2);
		}

		private volatile String natSent;

		@Override
		void tick(long now) {
			String k = key;
			// Again at once when the router's kind becomes known, so introductions pass on the right one.
			if (k == null || (now < nextHello && nat().equals(natSent))) return;
			nextHello = now + 20_000;
			natSent = nat();
			byte[] p = relayPacket(HELLO, "H " + k + " " + nat() + " " + String.join(" ", candidates(wire.localPort())));
			try {
				wire.send(relay1, p, 0, p.length);
				wire.send(relay2, p, 0, p.length);
			} catch (IOException ignored) {
				// The next one.
			}
		}

		/** The relay's `PUNCH <token> <guest> <nat> <candidates…>` line: start punching towards that guest. */
		public void introduce(String line) {
			String[] w = line.trim().split(" ");
			if (w.length < 3 || !w[0].equals("PUNCH")) return;
			long token = token(w[1]);
			if (peers.containsKey(token)) return;
			List<InetSocketAddress> to = new ArrayList<>();
			to.add(address(w[2]));
			for (int i = 4; i < w.length; i++) to.add(address(w[i]));
			peers.put(token, new Peer(token, to));
			// Every introduction refreshes the relay's view of this game too.
			nextHello = 0;
		}

		@Override
		Stream newStream(Peer peer, int conv) {
			if (peer.streams.size() >= MAX_STREAMS) return null;
			Stream s = new Stream(peer, conv);
			peer.streams.put(conv, s);
			peer.everStreamed = true;
			RelayTunnel.daemon(() -> {
				try {
					Socket game = new Socket(InetAddress.getLoopbackAddress(), gamePort);
					games.add(game);
					s.start(game);
				} catch (IOException e) {
					s.close();
				}
			}, "punch game");
			return s;
		}

		/** Whether the game's connection from local port [port] came through a punched path. */
		public boolean pipes(int port) {
			games.removeIf(Socket::isClosed);
			return games.stream().anyMatch(s -> s.getLocalPort() == port);
		}
	}

	/** The guest's end: introduced to the host by the relay, it gives Minecraft a local port that reaches the host. */
	public static final class Guest extends Game {
		private final ServerSocket local;
		private final AtomicInteger nextConv = new AtomicInteger(RANDOM.nextInt() & 0x3FFFFFFF);
		private volatile Peer host;
		private volatile long emptySince = System.currentTimeMillis();

		private Guest(Wire wire) throws IOException {
			super(wire);
			local = new ServerSocket(0, 4, InetAddress.getLoopbackAddress());
		}

		/**
		 * Asks the relay (UDP on [relay1], and [relay2] for the router test) to introduce this game to the host the
		 * punch [ticket] is for, punches through, and returns once the path works, within [ms]. Throws [Failed] with
		 * the reason it didn't.
		 */
		public static Guest connect(Wire wire, InetSocketAddress relay1, InetSocketAddress relay2, String ticket, long ms) throws IOException {
			Guest g = new Guest(wire);
			try {
				g.punch(relay1, relay2, ticket, ms);
				RelayTunnel.daemon(g::accept, "punch guest");
				return g;
			} catch (IOException | RuntimeException e) {
				g.close();
				throw e;
			} catch (InterruptedException e) {
				g.close();
				Thread.currentThread().interrupt();
				throw new Failed("punch_timeout");
			}
		}

		private void punch(InetSocketAddress r1, InetSocketAddress r2, String ticket, long ms) throws IOException, InterruptedException {
			long end = System.currentTimeMillis() + ms;
			relay1 = r1;
			relay2 = r2;
			hello("G " + ticket, 1000);
			if (seen1 == null && seen2 == null) throw new Failed("relay_no_udp");
			String nat = nat(seen1, seen2);
			byte[] ask = relayPacket(PUNCH, ticket + " " + nat + " " + String.join(" ", candidates(wire.localPort())));
			long askUntil = System.currentTimeMillis() + 1500;
			while (relayAnswer == null && System.currentTimeMillis() < askUntil) {
				wire.send(r1, ask, 0, ask.length);
				for (int t = 0; t < 400 && relayAnswer == null; t += 10) Thread.sleep(10);
			}
			String answer = relayAnswer;
			if (answer == null) throw new Failed("relay_no_udp");
			String[] w = answer.split(" ");
			if (w[0].equals("NOPE")) throw new Failed(w.length > 1 && w[1].equals("host_gone") ? "host_no_udp" : w.length > 1 ? w[1] : "host_no_udp");
			List<InetSocketAddress> to = new ArrayList<>();
			to.add(address(w[2]));
			String hostNat = w.length > 3 ? w[3] : "unknown";
			for (int i = 4; i < w.length; i++) to.add(address(w[i]));
			Peer p = new Peer(token(w[1]), to);
			host = p;
			peers.put(p.token, p);
			while (!p.connected && System.currentTimeMillis() < end) Thread.sleep(10);
			if (p.connected) return;
			boolean hostHard = hostNat.equals("hard"), meHard = nat.equals("hard");
			throw new Failed(hostHard && meHard ? "both_hard_nat" : hostHard ? "hard_nat_host" : meHard ? "hard_nat_guest" : "punch_timeout");
		}

		/** The local port Minecraft connects to. */
		public int port() {
			return local.getLocalPort();
		}

		private void accept() {
			while (!closed) {
				try {
					Socket s = local.accept();
					Peer p = host;
					if (p == null || !peers.containsKey(p.token) || p.streams.size() >= MAX_STREAMS) {
						s.close();
						continue;
					}
					Stream st = new Stream(p, nextConv.incrementAndGet());
					p.streams.put(st.conv, st);
					p.everStreamed = true;
					st.start(s);
				} catch (IOException e) {
					if (closed) return;
				}
			}
		}

		@Override
		void tick(long now) {
			Peer p = host;
			if (p == null) return;
			if (!p.streams.isEmpty()) emptySince = now;
			// Thirty seconds after the last stream ends (or the host went away), this end is done.
			if (!peers.containsKey(p.token) || (p.everStreamed && now - emptySince > SILENT_MS)) close();
		}

		@Override
		public void close() {
			super.close();
			try {
				local.close();
			} catch (IOException ignored) {
				// Already.
			}
		}
	}

	static String hex(long token) {
		String s = Long.toHexString(token);
		char[] pad = new char[16 - s.length()];
		Arrays.fill(pad, '0');
		return new String(pad) + s;
	}

}
