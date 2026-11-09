package app.keplersync.tandem.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.CodeSigner;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.Certificate;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Tandem updating itself: the newest release on Modrinth for this game, downloaded, and checked against KeplerSync's
 * signing key before it's kept. {@link Swap} puts it in place once the game has closed.
 */
public final class Update {
	/** Tandem's jar is about 7 MB; anything near this isn't Tandem. */
	static final long MAX_BYTES = 64L << 20;

	private Update() {}

	public static final class Release {
		public final String version;
		public final String url;
		public final String sha512;
		public final long size;

		Release(String version, String url, String sha512, long size) {
			this.version = version;
			this.url = url;
			this.sha512 = sha512;
			this.size = size;
		}
	}

	/**
	 * The newest release on Modrinth for [loader] and [minecraft] that's newer than [current], or null. Only a jar
	 * served from [filesFrom] counts (Modrinth's own file host).
	 */
	public static Release newest(String api, String project, String loader, String minecraft, String current, String agent,
			String filesFrom) throws IOException {
		String query = "?loaders=" + URLEncoder.encode("[\"" + loader + "\"]", "UTF-8")
				+ "&game_versions=" + URLEncoder.encode("[\"" + minecraft + "\"]", "UTF-8");
		HttpURLConnection c = open(api + "/v2/project/" + project + "/version" + query, agent);
		int status = c.getResponseCode();
		if (status == 404) return null;
		if (status != 200) throw new IOException("Modrinth answered " + status);
		JsonArray versions;
		try (Reader r = new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8)) {
			versions = Io.GSON.fromJson(r, JsonArray.class);
		}
		Release best = null;
		for (JsonElement e : versions) {
			JsonObject v = e.getAsJsonObject();
			String number = text(v, "version_number");
			if (!"release".equals(text(v, "version_type")) || number == null) continue;
			if (!newer(number, best != null ? best.version : current)) continue;
			JsonObject file = jar(v.getAsJsonArray("files"));
			if (file == null || !text(file, "url").startsWith(filesFrom)) continue;
			long size = file.has("size") ? file.get("size").getAsLong() : -1;
			best = new Release(number, text(file, "url"), text(file.getAsJsonObject("hashes"), "sha512"), size);
		}
		return best;
	}

	/** The version's main jar: the one marked primary, else the first jar. */
	private static JsonObject jar(JsonArray files) {
		JsonObject first = null;
		for (JsonElement e : files) {
			JsonObject f = e.getAsJsonObject();
			String name = text(f, "filename");
			if (name == null || !name.endsWith(".jar") || text(f, "url") == null) continue;
			if (f.has("primary") && f.get("primary").getAsBoolean()) return f;
			if (first == null) first = f;
		}
		return first;
	}

	/**
	 * Downloads [r] into [dir] and returns the file, checked: the size and hash Modrinth gave, and every file inside
	 * signed by the key [fingerprint] names. Anything else is deleted and refused.
	 */
	public static Path download(Release r, Path dir, String agent, String fingerprint, Progress progress) throws IOException {
		if (r.size < 0 || r.size > MAX_BYTES || r.sha512 == null) throw new IOException("That download isn't Tandem.");
		Files.createDirectories(dir);
		String name = "tandem-" + r.version.replaceAll("[^A-Za-z0-9.+-]", "_");
		Path part = dir.resolve(name + ".jar.part");
		MessageDigest sha = digest("SHA-512");
		progress.total(r.size);
		try {
			HttpURLConnection c = open(r.url, agent);
			if (c.getResponseCode() != 200) throw new IOException("Modrinth answered " + c.getResponseCode());
			long got = 0;
			try (InputStream in = c.getInputStream(); OutputStream out = Files.newOutputStream(part)) {
				byte[] buf = new byte[1 << 16];
				int n;
				while ((n = in.read(buf)) != -1) {
					got += n;
					if (got > MAX_BYTES) throw new IOException("That download isn't Tandem.");
					sha.update(buf, 0, n);
					out.write(buf, 0, n);
					progress.add(n);
				}
			}
			if (!hex(sha.digest()).equalsIgnoreCase(r.sha512)) throw new IOException("The download was damaged. Try again.");
			verify(part, fingerprint);
			Path jar = dir.resolve(name + ".jar");
			Files.move(part, jar, StandardCopyOption.REPLACE_EXISTING);
			return jar;
		} finally {
			Files.deleteIfExists(part);
		}
	}

	/**
	 * Throws unless every file in [jar] is signed by the key whose SHA-256 is [fingerprint]: a jar someone changed,
	 * added to, or signed with any other key is never put in.
	 */
	public static void verify(Path jar, String fingerprint) throws IOException {
		Set<String> seen = new HashSet<>();
		int signed = 0;
		try (JarFile f = new JarFile(jar.toFile(), true)) {
			byte[] buf = new byte[1 << 16];
			for (Enumeration<JarEntry> e = f.entries(); e.hasMoreElements();) {
				JarEntry entry = e.nextElement();
				// Two entries by one name could be read differently by the game than here.
				if (!seen.add(entry.getName())) throw new IOException("It has " + entry.getName() + " twice.");
				if (entry.isDirectory() || signingFile(entry.getName())) continue;
				// Reading it is what checks it against its signature.
				try (InputStream in = f.getInputStream(entry)) {
					while (in.read(buf) != -1) {
						// read to the end
					}
				}
				if (!signedBy(entry.getCodeSigners(), fingerprint)) throw new IOException(entry.getName() + " isn't signed by KeplerSync.");
				signed++;
			}
		} catch (SecurityException e) {
			throw new IOException("A file in it was changed after it was signed.", e);
		}
		if (signed == 0) throw new IOException("It isn't signed by KeplerSync.");
	}

	/** The jar's own signature files, which aren't signed themselves. */
	static boolean signingFile(String name) {
		String n = name.toUpperCase(Locale.ROOT);
		if (!n.startsWith("META-INF/") || n.indexOf('/', "META-INF/".length()) >= 0) return false;
		return n.equals("META-INF/MANIFEST.MF") || n.endsWith(".SF") || n.endsWith(".DSA") || n.endsWith(".RSA")
				|| n.endsWith(".EC") || n.startsWith("META-INF/SIG-");
	}

	private static boolean signedBy(CodeSigner[] signers, String fingerprint) {
		if (signers == null) return false;
		for (CodeSigner s : signers) {
			List<? extends Certificate> path = s.getSignerCertPath().getCertificates();
			if (!path.isEmpty() && fingerprint.equalsIgnoreCase(fingerprint(path.get(0)))) return true;
		}
		return false;
	}

	/** SHA-256 of a certificate's public key, in hex: what Tandem pins, so a new certificate for the same key still works. */
	public static String fingerprint(Certificate cert) {
		return hex(digest("SHA-256").digest(cert.getPublicKey().getEncoded()));
	}

	/** Whether version [a] is newer than [b] by its numbers ("0.1.10" after "0.1.9"); anything after a - or + is ignored. */
	public static boolean newer(String a, String b) {
		int[] x = numbers(a);
		int[] y = numbers(b);
		for (int i = 0; i < Math.max(x.length, y.length); i++) {
			int p = i < x.length ? x[i] : 0;
			int q = i < y.length ? y[i] : 0;
			if (p != q) return p > q;
		}
		return false;
	}

	private static int[] numbers(String version) {
		String[] parts = version.split("[-+]", 2)[0].split("\\.");
		int[] out = new int[parts.length];
		for (int i = 0; i < parts.length; i++) {
			try {
				out[i] = Integer.parseInt(parts[i]);
			} catch (NumberFormatException e) {
				out[i] = 0;
			}
		}
		return out;
	}

	private static HttpURLConnection open(String url, String agent) throws IOException {
		// Plain http only to this PC, for the tests' stand-in Modrinth.
		if (!url.startsWith("https://") && !url.startsWith("http://127.0.0.1:")) throw new IOException("Not a safe address: " + url);
		HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
		c.setConnectTimeout(10_000);
		c.setReadTimeout(30_000);
		c.setRequestProperty("User-Agent", agent);
		return c;
	}

	private static String text(JsonObject o, String key) {
		return o != null && o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
	}

	private static MessageDigest digest(String algorithm) {
		try {
			return MessageDigest.getInstance(algorithm);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	private static String hex(byte[] bytes) {
		StringBuilder s = new StringBuilder(bytes.length * 2);
		for (byte b : bytes) s.append(String.format("%02x", b));
		return s.toString();
	}
}
