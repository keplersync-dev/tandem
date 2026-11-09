package app.keplersync.tandem.core;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Both ends of a game passing through KeplerSync's relay (the server's {@code relay.rs} describes the lines). The
 * host keeps a control connection and, for each friend the relay announces, opens a connection that's piped to its
 * own game. A friend's game connects to a local port, piped through the relay.
 */
public final class RelayTunnel {
	private RelayTunnel() {}

	/** "host:port" → an address. */
	public static InetSocketAddress address(String hostPort) {
		int colon = hostPort.lastIndexOf(':');
		return new InetSocketAddress(hostPort.substring(0, colon), Integer.parseInt(hostPort.substring(colon + 1)));
	}

	static Socket connect(String hostPort) throws IOException {
		Socket s = new Socket();
		s.connect(address(hostPort), 10_000);
		s.setTcpNoDelay(true);
		return s;
	}

	static void send(Socket s, String line) throws IOException {
		OutputStream out = s.getOutputStream();
		// The ping thread and the game both write to the control connection: one whole line at a time.
		synchronized (s) {
			out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
			out.flush();
		}
	}

	/** One line, a byte at a time, so nothing after it is taken from the game's bytes. */
	static String line(InputStream in) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		int b;
		while ((b = in.read()) != -1 && b != '\n') {
			if (out.size() > 4096) throw new IOException("The relay sent too long a line");
			out.write(b);
		}
		if (b == -1 && out.size() == 0) throw new IOException("The relay closed the connection");
		return new String(out.toByteArray(), StandardCharsets.UTF_8).trim();
	}

	/** Copies both ways until either side closes, then closes both. Two daemon threads. */
	static void pipe(final Socket a, final Socket b, String name) {
		Runnable closeBoth = () -> {
			try {
				a.close();
			} catch (IOException ignored) {
			}
			try {
				b.close();
			} catch (IOException ignored) {
			}
		};
		daemon(() -> copy(a, b, closeBoth), name + " up");
		daemon(() -> copy(b, a, closeBoth), name + " down");
	}

	private static void copy(Socket from, Socket to, Runnable done) {
		byte[] buf = new byte[1 << 15];
		try {
			InputStream in = from.getInputStream();
			OutputStream out = to.getOutputStream();
			int n;
			while ((n = in.read(buf)) != -1) {
				out.write(buf, 0, n);
				out.flush();
			}
		} catch (IOException ignored) {
			// One side went away; the other is closed below.
		} finally {
			done.run();
		}
	}

	static Thread daemon(Runnable r, String name) {
		Thread t = new Thread(r, "Tandem " + name);
		t.setDaemon(true);
		t.start();
		return t;
	}

	/**
	 * The host's side: friends the relay sends are piped to the game on [gamePort]. Reconnects with growing pauses if
	 * the control connection drops, asking [ticket] for a fresh ticket each time (they run out after minutes).
	 */
	public static final class Host implements Closeable {
		/** Under the relay ticket's five minutes, with room for clocks that disagree a little. */
		static long TICKET_REUSE_MS = Long.getLong("tandem.ticketReuseMs", 4 * 60_000);

		private final String relay;
		private final Callable<String> ticket;
		private final int gamePort;
		private volatile boolean closed;
		private volatile Socket control;
		private volatile String current;
		private volatile long currentAt;
		/** This end of each friend's connection piped into the game, so the game can tell who came through the relay. */
		private final Set<Socket> games = ConcurrentHashMap.newKeySet();
		/**
		 * The control connection's other lines (`UDP`, `PUNCH`, `PROBE`), and [OK_LINE] each time it connects: a
		 * newer relay introduces games to each other through them.
		 */
		public volatile java.util.function.Consumer<String> onLine;
		public static final String OK_LINE = "OK";

		public Host(String relay, Callable<String> ticket, int gamePort) {
			this.relay = relay;
			this.ticket = ticket;
			this.gamePort = gamePort;
		}

		public Host start() {
			daemon(this::run, "relay host");
			return this;
		}

		/** Whether the game's connection from local port [port] came through the relay. */
		public boolean pipes(int port) {
			games.removeIf(Socket::isClosed);
			return games.stream().anyMatch(s -> s.getLocalPort() == port);
		}

		private void tell(String line) {
			java.util.function.Consumer<String> c = onLine;
			if (c == null) return;
			try {
				c.accept(line);
			} catch (RuntimeException ignored) {
				// A line it couldn't use.
			}
		}

		/** Sends [line] down the control connection, if it's connected. */
		public void tellRelay(String line) {
			Socket s = control;
			if (s == null) return;
			try {
				RelayTunnel.send(s, line);
			} catch (IOException ignored) {
				// It reconnects by itself.
			}
		}

		/** The relay's host name, for its UDP ports. */
		public String host() {
			return address(relay).getHostString();
		}

		/** Connected to the relay right now. */
		public boolean connected() {
			Socket s = control;
			return s != null && !s.isClosed();
		}

		private void run() {
			long wait = 1000;
			while (!closed) {
				try {
					current = ticket.call();
					currentAt = System.currentTimeMillis();
					Socket s = connect(relay);
					control = s;
					send(s, "HOST " + current);
					String ok = line(s.getInputStream());
					if (!ok.equals("OK")) throw new IOException("The relay said " + ok);
					wait = 1000;
					tell(OK_LINE);
					// Keeps routers from forgetting a quiet connection; the relay ignores it.
					daemon(() -> ping(s), "relay ping");
					while (!closed) {
						String l = line(s.getInputStream());
						if (l.startsWith("CONN ")) answer(l.substring(5));
						else tell(l);
					}
				} catch (Exception e) {
					// A refusal or a garbled line leaves the old connection open: close it, so its ping thread ends too.
					Socket dead = control;
					if (dead != null) {
						try {
							dead.close();
						} catch (IOException ignored) {
						}
					}
					if (closed) return;
					try {
						Thread.sleep(wait);
					} catch (InterruptedException ie) {
						return;
					}
					wait = Math.min(30_000, wait * 2);
				}
			}
		}

		private void ping(Socket s) {
			while (!closed && !s.isClosed()) {
				try {
					Thread.sleep(30_000);
					send(s, "PING");
				} catch (Exception e) {
					return;
				}
			}
		}

		private void answer(String id) {
			daemon(() -> {
				try {
					// Tickets run out after five minutes, and the relay checks this one too: a friend arriving later
					// than that gets an answer with a fresh one.
					if (System.currentTimeMillis() - currentAt > TICKET_REUSE_MS) {
						current = ticket.call();
						currentAt = System.currentTimeMillis();
					}
					Socket data = connect(relay);
					send(data, "DATA " + current + " " + id);
					Socket game = new Socket(InetAddress.getLoopbackAddress(), gamePort);
					game.setTcpNoDelay(true);
					games.add(game);
					pipe(data, game, "relay " + id);
				} catch (Exception ignored) {
					// That friend's connection fails; they can try again.
				}
			}, "relay answer");
		}

		@Override
		public void close() {
			closed = true;
			Socket s = control;
			if (s != null) {
				try {
					s.close();
				} catch (IOException ignored) {
				}
			}
		}
	}

	/** A friend's side: the game connects to {@link #port()} on this PC, and is piped through the relay to the host. */
	public static final class Guest implements Closeable {
		private final String relay;
		private final String ticket;
		private final ServerSocket local;

		public Guest(String relay, String ticket) throws IOException {
			this.relay = relay;
			this.ticket = ticket;
			this.local = new ServerSocket(0, 4, InetAddress.getLoopbackAddress());
			daemon(this::accept, "relay guest");
		}

		public int port() {
			return local.getLocalPort();
		}

		private void accept() {
			while (!local.isClosed()) {
				try {
					final Socket game = local.accept();
					game.setTcpNoDelay(true);
					daemon(() -> {
						try {
							Socket r = connect(relay);
							send(r, "JOIN " + ticket);
							String ok = line(r.getInputStream());
							if (!ok.equals("OK")) {
								r.close();
								game.close();
								return;
							}
							pipe(game, r, "relay guest");
						} catch (IOException e) {
							try {
								game.close();
							} catch (IOException ignored) {
							}
						}
					}, "relay join");
				} catch (IOException e) {
					return;
				}
			}
		}

		@Override
		public void close() throws IOException {
			local.close();
		}
	}
}
