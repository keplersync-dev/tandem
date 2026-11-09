package app.keplersync.tandem.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** A game, a stand-in relay speaking the same lines as the server's, and both ends of the tunnel. */
class RelayTunnelTest {
	ServerSocket game;
	ServerSocket relay;
	final Map<String, SynchronousQueue<Socket>> waiting = new ConcurrentHashMap<>();
	final AtomicInteger hostTickets = new AtomicInteger();
	volatile Socket control;
	final java.util.List<String> dataTickets = new java.util.concurrent.CopyOnWriteArrayList<>();

	@AfterEach
	void tearDown() throws IOException {
		if (game != null) game.close();
		if (relay != null) relay.close();
	}

	/** Echoes back whatever it's sent, like a game answering. */
	private void echoGame() throws IOException {
		game = new ServerSocket(0, 4, InetAddress.getLoopbackAddress());
		RelayTunnel.daemon(() -> {
			while (!game.isClosed()) {
				try {
					Socket s = game.accept();
					RelayTunnel.daemon(() -> {
						try (InputStream in = s.getInputStream(); OutputStream out = s.getOutputStream()) {
							byte[] buf = new byte[256];
							int n;
							while ((n = in.read(buf)) != -1) {
								out.write(buf, 0, n);
								out.flush();
							}
						} catch (IOException ignored) {
						}
					}, "echo");
				} catch (IOException e) {
					return;
				}
			}
		}, "game");
	}

	private void fakeRelay() throws IOException {
		relay = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
		AtomicInteger ids = new AtomicInteger();
		RelayTunnel.daemon(() -> {
			while (!relay.isClosed()) {
				try {
					Socket s = relay.accept();
					RelayTunnel.daemon(() -> {
						try {
							String l = RelayTunnel.line(s.getInputStream());
							if (l.startsWith("HOST ")) {
								control = s;
								RelayTunnel.send(s, "OK");
							} else if (l.startsWith("JOIN ")) {
								String id = String.valueOf(ids.incrementAndGet());
								SynchronousQueue<Socket> q = new SynchronousQueue<>();
								waiting.put(id, q);
								RelayTunnel.send(control, "CONN " + id);
								Socket host = q.poll(5, TimeUnit.SECONDS);
								RelayTunnel.send(s, "OK");
								RelayTunnel.pipe(s, host, "fake relay");
							} else if (l.startsWith("DATA ")) {
								dataTickets.add(l.split(" ")[1]);
								waiting.get(l.split(" ")[2]).put(s);
							}
						} catch (Exception ignored) {
						}
					}, "fake relay conn");
				} catch (IOException e) {
					return;
				}
			}
		}, "fake relay");
	}

	@Test
	void aFriendsGameReachesTheHostsGameThroughTheRelay() throws Exception {
		echoGame();
		fakeRelay();
		String relayAddress = "127.0.0.1:" + relay.getLocalPort();
		try (RelayTunnel.Host host = new RelayTunnel.Host(relayAddress, () -> "host-ticket-" + hostTickets.incrementAndGet(), game.getLocalPort()).start()) {
			for (int i = 0; i < 50 && !host.connected(); i++) Thread.sleep(50);
			assertTrue(host.connected());
			try (RelayTunnel.Guest guest = new RelayTunnel.Guest(relayAddress, "guest-ticket");
					Socket friend = new Socket(InetAddress.getLoopbackAddress(), guest.port())) {
				friend.setSoTimeout(5000);
				friend.getOutputStream().write("hello host".getBytes(StandardCharsets.UTF_8));
				byte[] back = new byte[10];
				int got = 0;
				while (got < back.length) got += friend.getInputStream().read(back, got, back.length - got);
				assertEquals("hello host", new String(back, StandardCharsets.UTF_8));
			}
		}
	}

	@Test
	void aFriendArrivingAfterTheTicketsTimeGetsAFreshOne() throws Exception {
		long before = RelayTunnel.Host.TICKET_REUSE_MS;
		RelayTunnel.Host.TICKET_REUSE_MS = 0;
		try {
			aFriendsGameReachesTheHostsGameThroughTheRelay();
		} finally {
			RelayTunnel.Host.TICKET_REUSE_MS = before;
		}
		// host-ticket-1 opened the control connection; the friend's answer asked for another.
		assertEquals(java.util.Collections.singletonList("host-ticket-2"), dataTickets);
	}

	@Test
	void addressesAreHostAndPort() {
		assertEquals(25600, RelayTunnel.address("relay.keplersync.app:25600").getPort());
		assertEquals("relay.keplersync.app", RelayTunnel.address("relay.keplersync.app:25600").getHostString());
	}
}
