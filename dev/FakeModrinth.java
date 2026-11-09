import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;

/**
 * A stand-in for Modrinth's API and file host, for Tandem's update checks: one release of Tandem, the jar given.
 * {@code java dev/FakeModrinth.java <port> <jar> <version> [ms per 64 KB]}; the last makes downloads slow enough to
 * kill a game partway. Start the game with -Dtandem.updates.api=http://127.0.0.1:<port> and
 * -Dtandem.updates.files=http://127.0.0.1:<port>/.
 */
public class FakeModrinth {
	public static void main(String[] a) throws Exception {
		int port = Integer.parseInt(a[0]);
		byte[] jar = Files.readAllBytes(Paths.get(a[1]));
		String version = a[2];
		long slow = a.length > 3 ? Long.parseLong(a[3]) : 0;
		StringBuilder hash = new StringBuilder();
		for (byte b : MessageDigest.getInstance("SHA-512").digest(jar)) hash.append(String.format("%02x", b));
		String base = "http://127.0.0.1:" + port;
		String json = "[{\"version_number\":\"" + version + "\",\"version_type\":\"release\",\"files\":[{\"url\":\"" + base
				+ "/files/tandem.jar\",\"filename\":\"tandem-fabric-" + version + ".jar\",\"primary\":true,\"size\":" + jar.length
				+ ",\"hashes\":{\"sha512\":\"" + hash + "\"}}]}]";
		HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
		s.createContext("/v2/project/tandem/version", ex -> {
			System.out.println("versions asked by " + ex.getRequestHeaders().getFirst("User-Agent") + ": " + ex.getRequestURI().getRawQuery());
			byte[] b = json.getBytes("UTF-8");
			ex.sendResponseHeaders(200, b.length);
			try (OutputStream out = ex.getResponseBody()) {
				out.write(b);
			}
		});
		s.createContext("/files/tandem.jar", ex -> {
			System.out.println("jar asked for");
			ex.sendResponseHeaders(200, jar.length);
			try (OutputStream out = ex.getResponseBody()) {
				for (int i = 0; i < jar.length; i += 65536) {
					out.write(jar, i, Math.min(65536, jar.length - i));
					if (slow > 0) Thread.sleep(slow);
				}
				System.out.println("jar sent");
			} catch (Exception e) {
				System.out.println("jar cut off: " + e);
			}
		});
		s.start();
		System.out.println("FakeModrinth on " + base + " serving Tandem " + version);
	}
}
