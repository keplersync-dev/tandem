package app.keplersync.tandem.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Signed jars made with the JDK's own keytool and jarsigner, a stand-in Modrinth, and the real swap helper. */
class UpdateTest {
	@TempDir
	Path dir;
	HttpServer server;
	String base;
	volatile byte[] served = new byte[0];
	volatile String versions = "[]";
	volatile String lastQuery;

	@BeforeEach
	void setUp() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/v2/project/tandem/version", ex -> {
			lastQuery = ex.getRequestURI().getRawQuery();
			reply(ex, 200, versions.getBytes(StandardCharsets.UTF_8));
		});
		server.createContext("/v2/project/nobody/version", ex -> reply(ex, 404, "{}".getBytes(StandardCharsets.UTF_8)));
		server.createContext("/files/tandem.jar", ex -> reply(ex, 200, served));
		server.start();
		base = "http://127.0.0.1:" + server.getAddress().getPort();
	}

	@AfterEach
	void tearDown() {
		server.stop(0);
	}

	private static void reply(com.sun.net.httpserver.HttpExchange ex, int status, byte[] body) throws IOException {
		ex.sendResponseHeaders(status, body.length);
		try (OutputStream out = ex.getResponseBody()) {
			out.write(body);
		}
	}

	private static void run(String... command) throws Exception {
		Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
		byte[] out = readAll(p.getInputStream());
		assertTrue(p.waitFor(60, TimeUnit.SECONDS));
		assertEquals(0, p.exitValue(), new String(out, StandardCharsets.UTF_8));
	}

	private static String tool(String name) {
		return Paths.get(System.getProperty("java.home"), "bin", name).toString();
	}

	/** A new key in its own keystore; returns its fingerprint as Tandem pins it. */
	private String key(String name) throws Exception {
		Path store = dir.resolve(name + ".p12");
		run(tool("keytool"), "-genkeypair", "-alias", "tandem", "-keyalg", "EC", "-groupname", "secp256r1", "-dname", "CN=" + name,
				"-validity", "2", "-keystore", store.toString(), "-storetype", "PKCS12", "-storepass", "secret-" + name);
		KeyStore ks = KeyStore.getInstance("PKCS12");
		try (FileInputStream in = new FileInputStream(store.toFile())) {
			ks.load(in, ("secret-" + name).toCharArray());
		}
		return Update.fingerprint(ks.getCertificate("tandem"));
	}

	private void sign(Path jar, String name) throws Exception {
		run(tool("jarsigner"), "-keystore", dir.resolve(name + ".p12").toString(), "-storetype", "PKCS12", "-storepass", "secret-" + name,
				jar.toString(), "tandem");
	}

	/** A jar laid out like Tandem's: a class, the mod's json, and a jar inside it. */
	private Path jar(String file) throws IOException {
		Path p = dir.resolve(file);
		try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(p))) {
			put(out, "fabric.mod.json", "{\"id\":\"tandem\",\"version\":\"0.1.2\"}".getBytes(StandardCharsets.UTF_8));
			put(out, "app/keplersync/tandem/Thing.class", new byte[] {(byte) 0xCA, (byte) 0xFE, 1, 2, 3});
			put(out, "META-INF/jars/core-0.1.2.jar", new byte[] {'P', 'K', 3, 4, 9, 9});
		}
		return p;
	}

	private static void put(JarOutputStream out, String name, byte[] bytes) throws IOException {
		out.putNextEntry(new JarEntry(name));
		out.write(bytes);
		out.closeEntry();
	}

	/** [jar] copied entry by entry, with [name] changed to [bytes] (or added, when it wasn't there). */
	private Path rewrite(Path jar, String file, String name, byte[] bytes) throws IOException {
		Path p = dir.resolve(file);
		boolean found = false;
		try (JarFile in = new JarFile(jar.toFile(), false); JarOutputStream out = new JarOutputStream(Files.newOutputStream(p))) {
			for (Enumeration<JarEntry> e = in.entries(); e.hasMoreElements();) {
				JarEntry entry = e.nextElement();
				byte[] body;
				try (InputStream s = in.getInputStream(entry)) {
					body = readAll(s);
				}
				if (entry.getName().equals(name)) {
					body = bytes;
					found = true;
				}
				put(out, entry.getName(), body);
			}
			if (!found) put(out, name, bytes);
		}
		return p;
	}

	private static byte[] readAll(InputStream in) throws IOException {
		java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
		byte[] buf = new byte[8192];
		int n;
		while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
		return out.toByteArray();
	}

	private static String sha512(byte[] bytes) throws Exception {
		StringBuilder s = new StringBuilder();
		for (byte b : MessageDigest.getInstance("SHA-512").digest(bytes)) s.append(String.format("%02x", b));
		return s.toString();
	}

	@Test
	void onlyAJarSignedWithTheKeyAndUnchangedIsTaken() throws Exception {
		String ours = key("ours");
		String theirs = key("theirs");
		Path signed = jar("signed.jar");
		sign(signed, "ours");
		Update.verify(signed, ours);

		assertThrows(IOException.class, () -> Update.verify(jar("unsigned.jar"), ours), "unsigned");
		Path other = jar("other.jar");
		sign(other, "theirs");
		assertThrows(IOException.class, () -> Update.verify(other, ours), "signed with another key");
		Update.verify(other, theirs);

		Path added = rewrite(signed, "added.jar", "app/keplersync/tandem/Evil.class", new byte[] {6, 6, 6});
		assertThrows(IOException.class, () -> Update.verify(added, ours), "a file added after signing");
		Path changed = rewrite(signed, "changed.jar", "META-INF/jars/core-0.1.2.jar", new byte[] {'P', 'K', 3, 4, 6, 6});
		assertThrows(IOException.class, () -> Update.verify(changed, ours), "a jar inside changed after signing");
	}

	@Test
	void theNewestReleaseForThisGameIsPicked() throws Exception {
		String file = base + "/files/";
		versions = "[" + version("0.1.3", "beta", file + "tandem.jar")
				+ "," + version("0.1.10", "release", "https://elsewhere.example/tandem.jar")
				+ "," + version("0.1.2", "release", file + "tandem.jar")
				+ "," + version("0.1.1", "release", file + "tandem.jar") + "]";
		Update.Release r = Update.newest(base, "tandem", "fabric", "1.21.1", "0.1.1", "test", file);
		assertEquals("0.1.2", r.version, "betas and files from anywhere but Modrinth's host don't count");
		assertTrue(lastQuery.contains("loaders=%5B%22fabric%22%5D") && lastQuery.contains("game_versions=%5B%221.21.1%22%5D"), lastQuery);
		assertNull(Update.newest(base, "tandem", "fabric", "1.21.1", "0.1.2", "test", file), "already the newest");
		assertNull(Update.newest(base, "nobody", "fabric", "1.21.1", "0.1.1", "test", file), "no such project");
	}

	private static String version(String number, String type, String url) {
		return "{\"version_number\":\"" + number + "\",\"version_type\":\"" + type + "\",\"files\":[{\"url\":\"" + url
				+ "\",\"filename\":\"tandem.jar\",\"primary\":true,\"size\":10,\"hashes\":{\"sha512\":\"ab\"}}]}";
	}

	@Test
	void versionsCompareByTheirNumbers() {
		assertTrue(Update.newer("0.1.10", "0.1.9"));
		assertTrue(Update.newer("0.2.0-beta.1", "0.1.9"));
		assertTrue(Update.newer("1.0", "0.9.9"));
		assertFalse(Update.newer("0.1.1", "0.1.1"));
		assertFalse(Update.newer("0.1.1", "0.1.2"));
	}

	@Test
	void aDownloadIsKeptOnlyWhenItsHashAndSignatureCheckOut() throws Exception {
		String ours = key("ours");
		Path signed = jar("signed.jar");
		sign(signed, "ours");
		served = Files.readAllBytes(signed);
		Path into = dir.resolve("update");
		Update.Release good = new Update.Release("0.1.2", base + "/files/tandem.jar", sha512(served), served.length);
		Path got = Update.download(good, into, "test", ours, new Progress());
		assertArrayEquals(served, Files.readAllBytes(got));

		Update.Release damaged = new Update.Release("0.1.3", base + "/files/tandem.jar", sha512(new byte[] {1}), served.length);
		assertThrows(IOException.class, () -> Update.download(damaged, into, "test", ours, new Progress()));
		served = Files.readAllBytes(jar("unsigned.jar"));
		Update.Release unsigned = new Update.Release("0.1.4", base + "/files/tandem.jar", sha512(served), served.length);
		assertThrows(IOException.class, () -> Update.download(unsigned, into, "test", ours, new Progress()));
		List<String> left = new ArrayList<>();
		try (java.util.stream.Stream<Path> files = Files.list(into)) {
			files.forEach(f -> left.add(f.getFileName().toString()));
		}
		assertEquals(java.util.Collections.singletonList("tandem-0.1.2.jar"), left, "refused downloads leave nothing behind");
	}

	private Process helper(Path target, Path staged) throws IOException {
		String java = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
		return new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), Swap.class.getName(), target.toString(), staged.toString())
				.redirectErrorStream(true).start();
	}

	@Test
	void theHelperSwapsOnlyOnceTheGameHasClosed() throws Exception {
		Path mods = Files.createDirectories(dir.resolve("mods"));
		Path target = Files.write(mods.resolve("tandem-fabric-0.1.1.jar"), "old".getBytes(StandardCharsets.UTF_8));
		Path staged = Files.write(dir.resolve("tandem-0.1.2.jar"), "new".getBytes(StandardCharsets.UTF_8));
		Process p = helper(target, staged);
		assertFalse(p.waitFor(1500, TimeUnit.MILLISECONDS), "waits while the game runs");
		assertEquals("old", new String(Files.readAllBytes(target), StandardCharsets.UTF_8));
		p.getOutputStream().close();
		assertTrue(p.waitFor(20, TimeUnit.SECONDS));
		assertEquals("new", new String(Files.readAllBytes(target), StandardCharsets.UTF_8), "the same file name, the new Tandem");
		assertFalse(Files.exists(staged));
		try (java.util.stream.Stream<Path> files = Files.list(mods)) {
			assertEquals(1, files.count(), "nothing else left beside it");
		}
	}

	@Test
	void tandemRemovedMeanwhileStaysRemoved() throws Exception {
		Path target = dir.resolve("mods").resolve("tandem.jar");
		Path staged = Files.write(dir.resolve("tandem-0.1.2.jar"), "new".getBytes(StandardCharsets.UTF_8));
		Process p = helper(target, staged);
		p.getOutputStream().close();
		assertTrue(p.waitFor(20, TimeUnit.SECONDS));
		assertFalse(Files.exists(target));
	}
}
