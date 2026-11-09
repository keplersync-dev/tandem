package app.keplersync.tandem.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Against a small fake KeplerSync that hands out numbered tokens. */
class KeplerSyncApiTest {
	HttpServer server;
	final AtomicInteger signIns = new AtomicInteger();
	final AtomicInteger refreshes = new AtomicInteger();
	final List<String> joined = new ArrayList<>();
	final List<String> verifyBodies = new ArrayList<>();
	/** The access token the server takes right now. */
	volatile String valid = "";
	volatile boolean refreshWorks = true;
	KeplerSyncApi api;

	@BeforeEach
	void setUp() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/v1/auth/minecraft/challenge", ex -> reply(ex, 200, "{\"serverId\":\"abc123\"}"));
		server.createContext("/v1/auth/minecraft/verify", ex -> {
			verifyBodies.add(body(ex));
			valid = "access-" + signIns.incrementAndGet();
			reply(ex, 200, tokens(valid));
		});
		server.createContext("/v1/auth/refresh", ex -> {
			refreshes.incrementAndGet();
			if (!refreshWorks) {
				reply(ex, 401, "{\"error\":{\"code\":\"not_signed_in\",\"message\":\"Sign in again\"}}");
				return;
			}
			valid = "refreshed-" + refreshes.get();
			reply(ex, 200, tokens(valid));
		});
		server.createContext("/v1/friends", ex -> {
			if (!("Bearer " + valid).equals(ex.getRequestHeaders().getFirst("Authorization"))) {
				reply(ex, 401, "{\"error\":{\"code\":\"not_signed_in\",\"message\":\"Sign in again\"}}");
				return;
			}
			reply(ex, 200, "[{\"user\":{\"id\":\"u2\",\"displayName\":\"Sam\"},\"presence\":{\"state\":\"playing\",\"instanceName\":\"Sky Base\"}}]");
		});
		server.createContext("/v1/friends/requests", ex -> reply(ex, 404,
				"{\"error\":{\"code\":\"not_found\",\"message\":\"Nobody has that friend code\"}}"));
		server.start();
		api = new KeplerSyncApi("http://127.0.0.1:" + server.getAddress().getPort() + "/", new KeplerSyncApi.Joiner() {
			@Override
			public String username() {
				return "Alex";
			}

			@Override
			public void join(String serverId) {
				joined.add(serverId);
			}
		});
	}

	@AfterEach
	void tearDown() {
		server.stop(0);
	}

	private static String tokens(String access) {
		return "{\"accessToken\":\"" + access + "\",\"expiresIn\":900,\"refreshToken\":\"r\",\"isNew\":false,"
				+ "\"user\":{\"id\":\"u1\",\"displayName\":\"Alex\",\"friendCode\":\"7KQ29XMB\"}}";
	}

	private static String body(HttpExchange ex) throws IOException {
		java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
		byte[] buf = new byte[1024];
		int n;
		while ((n = ex.getRequestBody().read(buf)) != -1) out.write(buf, 0, n);
		return new String(out.toByteArray(), StandardCharsets.UTF_8);
	}

	private static void reply(HttpExchange ex, int status, String body) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		ex.sendResponseHeaders(status, bytes.length);
		try (OutputStream out = ex.getResponseBody()) {
			out.write(bytes);
		}
	}

	@Test
	void signingInJoinsTheServersIdAsTandem() throws IOException {
		KeplerSyncApi.Me me = api.signIn();
		assertEquals("7KQ29XMB", me.friendCode);
		assertEquals(1, joined.size());
		assertEquals("abc123", joined.get(0));
		assertTrue(verifyBodies.get(0).contains("\"client\":\"tandem\""), verifyBodies.get(0));
		assertTrue(verifyBodies.get(0).contains("\"username\":\"Alex\""));
	}

	@Test
	void aRefusedTokenIsRefreshedOnce() throws IOException {
		api.signIn();
		valid = "something the server issued elsewhere";
		List<KeplerSyncApi.Friend> friends = api.friends();
		assertEquals("Sky Base", friends.get(0).presence.instanceName);
		assertEquals(1, refreshes.get());
		assertEquals(1, signIns.get());
	}

	@Test
	void aFailedRefreshSignsInAgain() throws IOException {
		api.signIn();
		valid = "gone";
		refreshWorks = false;
		assertEquals("Sam", api.friends().get(0).user.displayName);
		assertEquals(2, signIns.get());
	}

	@Test
	void theServersMessageReachesThePlayer() {
		KeplerSyncApi.Refused e = assertThrows(KeplerSyncApi.Refused.class, () -> api.addFriend("nope-nope"));
		assertEquals("Nobody has that friend code", e.getMessage());
		assertEquals("not_found", e.code);
		assertTrue(!e.outdated());
	}

	@Test
	void tooOldATandemIsToldSoAndNothingElseIs() throws IOException {
		server.removeContext("/v1/auth/minecraft/challenge");
		server.createContext("/v1/auth/minecraft/challenge", ex -> reply(ex, 426, "{\"error\":{\"code\":\"client_outdated\","
				+ "\"message\":\"This version of Tandem is too old. Update it to keep using your account.\",\"minVersion\":\"0.2.0\"}}"));
		KeplerSyncApi.Refused e = assertThrows(KeplerSyncApi.Refused.class, () -> api.signIn());
		assertTrue(e.outdated());
		assertEquals("This version of Tandem is too old. Update it to keep using your account.", e.getMessage());
		// Everything else needs a sign-in, so it hears the same.
		assertTrue(assertThrows(KeplerSyncApi.Refused.class, () -> api.friends()).outdated());
	}

	@Test
	void aServerThatIsDownOrRestartingIsNotARefusal() throws IOException {
		server.removeContext("/v1/auth/minecraft/challenge");
		// Cloudflare's own page while KeplerSync restarts (502) or is down (521): no answer from KeplerSync at all.
		for (int status : new int[] {500, 502, 503, 504, 521, 522, 530}) {
			server.createContext("/v1/auth/minecraft/challenge", ex -> reply(ex, status, "<html><body>Bad gateway</body></html>"));
			IOException e = assertThrows(IOException.class, () -> api.signIn());
			assertTrue(!(e instanceof KeplerSyncApi.Refused), status + " counts as no answer, so what was going on tries again");
			server.removeContext("/v1/auth/minecraft/challenge");
		}
		// KeplerSync saying it's at capacity is an answer: uploads are held, not retried every half minute.
		server.createContext("/v1/auth/minecraft/challenge", ex -> reply(ex, 503, "{\"error\":{\"code\":\"at_capacity\",\"message\":\"Busy\"}}"));
		assertEquals("at_capacity", assertThrows(KeplerSyncApi.Refused.class, () -> api.signIn()).code);
	}

	@Test
	void theLiveConnectionIsOnTheSameServer() {
		assertEquals("ws://127.0.0.1:" + server.getAddress().getPort() + "/v1/ws", api.socketUrl());
	}
}
