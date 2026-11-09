package app.keplersync.tandem.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Against a small fake of KeplerSync's worlds endpoints and its storage. */
class ServerCloudTest {
	@TempDir
	Path tmp;
	HttpServer server;
	String base;
	/** What storage holds, by sha256. */
	final Map<String, byte[]> stored = new ConcurrentHashMap<>();
	final Map<String, String> ticketSha = new ConcurrentHashMap<>();
	final List<String> puts = new ArrayList<>();
	final List<String> completed = new ArrayList<>();
	final List<Integer> completeBatches = new ArrayList<>();
	String committedBody;
	ServerCloud cloud;
	/** The world's members, as the server sends them. */
	String members = "[{\"id\":\"u-sam\",\"username\":\"sam\",\"displayName\":\"Sam\",\"minecraftUuid\":\"mc-sam\"}]";

	@BeforeEach
	void setUp() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		base = "http://127.0.0.1:" + server.getAddress().getPort();
		server.createContext("/v1/auth/minecraft/challenge", ex -> reply(ex, 200, "{\"serverId\":\"s\"}"));
		server.createContext("/v1/auth/minecraft/verify", ex -> reply(ex, 200,
				"{\"accessToken\":\"t\",\"expiresIn\":900,\"refreshToken\":\"r\",\"user\":{\"id\":\"u-alex\",\"displayName\":\"Alex\"}}"));
		server.createContext("/v1/worlds", ex -> {
			String path = ex.getRequestURI().getPath();
			String body = read(ex.getRequestBody());
			if (path.endsWith("/blobs/check")) {
				JsonArray tickets = new JsonArray();
				for (com.google.gson.JsonElement f : Io.GSON.fromJson(body, JsonObject.class).getAsJsonArray("files")) {
					String sha = f.getAsJsonObject().get("sha256").getAsString();
					JsonObject t = new JsonObject();
					if (stored.containsKey(sha)) {
						t.addProperty("alreadyExists", true);
					} else {
						String id = "ticket-" + sha.substring(0, 8);
						ticketSha.put(id, sha);
						t.addProperty("alreadyExists", false);
						t.addProperty("uploadId", id);
						t.addProperty("url", base + "/storage/put/" + id);
					}
					tickets.add(t);
				}
				JsonObject out = new JsonObject();
				out.add("tickets", tickets);
				reply(ex, 200, out.toString());
			} else if (path.endsWith("/blobs/complete")) {
				JsonArray ids = Io.GSON.fromJson(body, JsonObject.class).getAsJsonArray("uploadIds");
				completeBatches.add(ids.size());
				for (com.google.gson.JsonElement id : ids) completed.add(id.getAsString());
				reply(ex, 200, "{\"sha256\":[]}");
			} else if (path.endsWith("/file-links")) {
				JsonObject links = new JsonObject();
				for (com.google.gson.JsonElement sha : Io.GSON.fromJson(body, JsonObject.class).getAsJsonArray("sha256")) {
					links.addProperty(sha.getAsString(), base + "/storage/get/" + sha.getAsString());
				}
				JsonObject out = new JsonObject();
				out.add("links", links);
				out.addProperty("expiresInSecs", 300);
				reply(ex, 200, out.toString());
			} else if (path.endsWith("/snapshots") && "POST".equals(ex.getRequestMethod())) {
				committedBody = body;
				reply(ex, 200, "{\"snapshot\":{\"id\":\"snap-from-server\",\"kind\":\"session\",\"totalBytes\":5,\"createdAt\":\"2026-11-02T10:00:00.000Z\"},\"head\":false}");
			} else if (path.contains("/snapshots/")) {
				reply(ex, 200, "{\"id\":\"snap-from-server\",\"parent\":null,\"authorId\":\"u-sam\",\"kind\":\"session\",\"totalBytes\":5,"
						+ "\"createdAt\":\"2026-11-02T10:00:00.000Z\",\"mods\":[],\"manifest\":{\"level.dat\":{\"sha256\":\"" + sha("hello") + "\",\"sizeBytes\":5}}}");
			} else if (path.endsWith("/lease/heartbeat")) {
				reply(ex, 409, "{\"error\":{\"code\":\"conflict\",\"message\":\"Someone else is hosting this world now\"}}");
			} else {
				// One world: Alex owns it, Sam is in it, Sam holds the lease. The list is that world in a list.
				String one = "{\"id\":\"w1\",\"name\":\"Sky Base\",\"minecraftVersion\":\"1.21.1\",\"mods\":[],"
						+ "\"owner\":{\"id\":\"u-alex\",\"username\":\"alex\",\"displayName\":\"Alex\"},\"members\":" + members + ","
						+ "\"operators\":[\"u-alex\",\"u-sam\"],"
						+ "\"head\":\"snap-from-server\",\"branches\":[],"
						+ "\"lease\":{\"holderId\":\"u-sam\",\"state\":\"hosting\",\"progress\":0,\"expiresAt\":\"2099-01-01T00:00:00.000Z\"}}";
				reply(ex, 200, path.equals("/v1/worlds") ? "[" + one + "]" : one);
			}
		});
		server.createContext("/storage/put/", ex -> {
			String id = ex.getRequestURI().getPath().replaceAll(".*/", "");
			stored.put(ticketSha.get(id), readBytes(ex.getRequestBody()));
			puts.add(id);
			reply(ex, 200, "");
		});
		server.createContext("/storage/get/", ex -> {
			byte[] bytes = stored.get(ex.getRequestURI().getPath().replaceAll(".*/", ""));
			ex.sendResponseHeaders(bytes == null ? 404 : 200, bytes == null ? -1 : bytes.length);
			if (bytes != null) try (OutputStream out = ex.getResponseBody()) {
				out.write(bytes);
			}
			ex.close();
		});
		server.start();
		KeplerSyncApi api = new KeplerSyncApi(base, new KeplerSyncApi.Joiner() {
			@Override
			public String username() {
				return "Alex";
			}

			@Override
			public void join(String serverId) {}
		});
		cloud = new ServerCloud(api, () -> Arrays.asList("create@0.5.1"));
	}

	@AfterEach
	void tearDown() {
		server.stop(0);
	}

	private static String sha(String s) {
		return Io.hex(Io.sha256().digest(s.getBytes(StandardCharsets.UTF_8)));
	}

	private static byte[] readBytes(InputStream in) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		byte[] buf = new byte[4096];
		int n;
		while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
		return out.toByteArray();
	}

	private static String read(InputStream in) throws IOException {
		return new String(readBytes(in), StandardCharsets.UTF_8);
	}

	private static void reply(HttpExchange ex, int status, String body) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		ex.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
		if (bytes.length > 0) try (OutputStream out = ex.getResponseBody()) {
			out.write(bytes);
		}
		ex.close();
	}

	@Test
	void onlyWhatTheWorldLacksIsUploaded() throws IOException {
		Path hello = Files.write(tmp.resolve("hello"), "hello".getBytes(StandardCharsets.UTF_8));
		Path there = Files.write(tmp.resolve("there"), "there!".getBytes(StandardCharsets.UTF_8));
		stored.put(sha("there!"), "there!".getBytes(StandardCharsets.UTF_8));
		Map<String, Long> sizes = new LinkedHashMap<>();
		sizes.put(sha("hello"), 5L);
		sizes.put(sha("there!"), 6L);
		Progress p = new Progress();
		cloud.uploadBlobs("w1", sizes, sha -> sha.equals(sha("hello")) ? hello : there, p);
		assertEquals(1, puts.size());
		assertEquals(puts, completed);
		assertEquals(5, p.total());
		assertEquals(5, p.done());
		assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), stored.get(sha("hello")));
	}

	@Test
	void bigFilesGoInBatchesSmallEnoughToFinishInTime() throws IOException {
		// The sizes are what the batches go by; the files themselves can stay small.
		Map<String, Long> sizes = new LinkedHashMap<>();
		Map<String, Path> files = new LinkedHashMap<>();
		long[] mb = {40, 20, 30, 100, 1};
		for (int i = 0; i < mb.length; i++) {
			String s = "file " + i;
			sizes.put(sha(s), mb[i] << 20);
			files.put(sha(s), Files.write(tmp.resolve("f" + i), s.getBytes(StandardCharsets.UTF_8)));
		}
		cloud.uploadBlobs("w1", sizes, files::get, new Progress());
		// 40+20, then 30 (100 would pass 64 MB), then 100 alone, then 1.
		assertEquals(Arrays.asList(2, 1, 1, 1), completeBatches);
		assertEquals(5, completed.size());
	}

	@Test
	void aCommitTakesTheServersIdAndSendsTheGamesMods() throws IOException {
		Map<String, Manifest.Entry> files = new LinkedHashMap<>();
		files.put("level.dat", new Manifest.Entry(5, sha("hello")));
		Snapshot s = new Snapshot("parent-1", "Alex", Snapshot.SESSION, new Manifest(files));
		Cloud.Committed c = cloud.commit("w1", s);
		assertEquals("snap-from-server", c.id);
		assertFalse(c.head);
		assertTrue(committedBody.contains("\"parent\":\"parent-1\""), committedBody);
		assertTrue(committedBody.contains("create@0.5.1"), committedBody);
		assertTrue(committedBody.contains("\"sizeBytes\":5"), committedBody);
	}

	@Test
	void downloadsComeFromTheirLinks() throws IOException {
		stored.put(sha("hello"), "hello".getBytes(StandardCharsets.UTF_8));
		cloud.prepareDownloads("w1", Arrays.asList(sha("hello")));
		try (InputStream in = cloud.openBlob("w1", sha("hello"))) {
			assertEquals("hello", read(in));
		}
	}

	@Test
	void peopleAreIdsWithTheirNamesBeside() throws IOException {
		Cloud.World w = cloud.worlds("u-alex").get(0);
		assertEquals("u-alex", w.createdBy);
		assertEquals(Arrays.asList("u-sam"), w.sharedWith);
		assertEquals(Arrays.asList("u-sam", "u-alex"), w.operators);
		assertEquals("Sam", w.nameOf("u-sam"));
		assertEquals("mc-sam", w.minecraftIds.get("u-sam"));
		Cloud.Lease l = cloud.lease("w1");
		assertEquals("u-sam", l.holder);
		assertEquals("Sam", l.holderName);
		assertTrue(l.heldBy("u-sam"));
		Snapshot s = cloud.snapshot("w1", "snap-from-server");
		assertEquals("u-sam", s.authorId);
		assertEquals("Sam", s.author);
		assertEquals(5, s.files.get("level.dat").size);
	}

	@Test
	void twoPeopleWithOneNameStayTwo() throws IOException {
		// A friend renamed to someone else's display name: ids keep them apart, usernames tell them apart on screen.
		members = "[{\"id\":\"u-sam\",\"username\":\"sam\",\"displayName\":\"Sam\",\"minecraftUuid\":\"mc-sam\"},"
				+ "{\"id\":\"u-copy\",\"username\":\"sam_2\",\"displayName\":\"sam\",\"minecraftUuid\":\"mc-copy\"}]";
		Cloud.World w = cloud.worlds("u-alex").get(0);
		assertEquals(Arrays.asList("u-sam", "u-copy"), w.sharedWith);
		assertEquals(Arrays.asList("u-sam", "u-alex"), w.operators);
		assertEquals("Sam (@sam)", w.nameOf("u-sam"));
		assertEquals("sam (@sam_2)", w.nameOf("u-copy"));
		assertEquals("Alex", w.nameOf("u-alex"));
		assertEquals("mc-copy", w.minecraftIds.get("u-copy"));
	}

	@Test
	void aLostLeaseIsFalseNotAnError() throws IOException {
		assertFalse(cloud.heartbeat("w1", "Alex", Cloud.HOSTING, 0));
	}
}
