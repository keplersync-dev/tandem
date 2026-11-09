package app.keplersync.tandem.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * The home router's address (the default gateway), for PCP and NAT-PMP, which talk to it directly. Java has no way to
 * ask, so this reads the system's routing table, by its numbers rather than its headings (which Windows translates).
 * The .1 of each home network follows, as most routers sit there.
 */
final class Gateway {
	private Gateway() {}

	static Set<String> candidates() {
		Set<String> out = new LinkedHashSet<>();
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		try {
			Path linux = Paths.get("/proc/net/route");
			if (Files.isReadable(linux)) out.addAll(linux(new String(Files.readAllBytes(linux), StandardCharsets.US_ASCII)));
			else if (os.startsWith("windows")) out.addAll(windows(run("route", "print", "-4", "0.0.0.0")));
			else if (os.contains("mac") || os.contains("darwin")) out.addAll(mac(run("netstat", "-rn", "-f", "inet")));
		} catch (IOException | RuntimeException e) {
			// Fall back to guessing.
		}
		for (InetAddress a : Addresses.lanV4()) {
			byte[] b = a.getAddress();
			out.add((b[0] & 0xFF) + "." + (b[1] & 0xFF) + "." + (b[2] & 0xFF) + ".1");
		}
		return out;
	}

	/** /proc/net/route: the default route's gateway, in little-endian hex. */
	static Set<String> linux(String table) {
		Set<String> out = new LinkedHashSet<>();
		for (String line : table.split("\n")) {
			String[] f = line.trim().split("\\s+");
			if (f.length < 3 || !f[1].equals("00000000") || !f[2].matches("[0-9A-Fa-f]{8}") || f[2].equals("00000000")) continue;
			long g = Long.parseLong(f[2], 16);
			out.add((g & 0xFF) + "." + (g >> 8 & 0xFF) + "." + (g >> 16 & 0xFF) + "." + (g >> 24 & 0xFF));
		}
		return out;
	}

	/** `route print -4`: the row "0.0.0.0  0.0.0.0  <gateway>  <interface>  <metric>". */
	static Set<String> windows(String table) {
		Set<String> out = new LinkedHashSet<>();
		for (String line : table.split("\r?\n")) {
			String[] f = line.trim().split("\\s+");
			if (f.length >= 3 && f[0].equals("0.0.0.0") && f[1].equals("0.0.0.0") && ipv4(f[2])) out.add(f[2]);
		}
		return out;
	}

	/** `netstat -rn -f inet`: the row "default  <gateway>  ...". */
	static Set<String> mac(String table) {
		Set<String> out = new LinkedHashSet<>();
		for (String line : table.split("\n")) {
			String[] f = line.trim().split("\\s+");
			if (f.length >= 2 && f[0].equals("default") && ipv4(f[1])) out.add(f[1]);
		}
		return out;
	}

	private static boolean ipv4(String s) {
		if (!s.matches("\\d{1,3}(\\.\\d{1,3}){3}")) return false;
		try {
			return InetAddress.getByName(s) instanceof Inet4Address;
		} catch (IOException e) {
			return false;
		}
	}

	private static String run(String... command) throws IOException {
		Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
		try (InputStream in = p.getInputStream()) {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			byte[] buf = new byte[4096];
			long end = System.currentTimeMillis() + 2000;
			int n;
			while (System.currentTimeMillis() < end && (n = in.read(buf)) != -1) out.write(buf, 0, n);
			p.waitFor(1, TimeUnit.SECONDS);
			return new String(out.toByteArray(), StandardCharsets.UTF_8);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("interrupted");
		} finally {
			p.destroy();
		}
	}
}
