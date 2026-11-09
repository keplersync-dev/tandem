import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dev only: Mojang's session server for offline test accounts, so the cloudA and cloudB runs can sign in to a
 * local KeplerSync server. The game joins here (-Dminecraft.api.session.host) and the server checks here
 * (MOJANG_SESSION_URL). Every join is believed. Run with: java dev/FakeMojang.java [port] [address], the address
 * 0.0.0.0 for games on other PCs (an offline account on a Mac, say).
 */
public class FakeMojang {
	private static final Map<String, String> JOINED = new ConcurrentHashMap<>();
	private static final Map<String, String> NAMES = new ConcurrentHashMap<>();

	public static void main(String[] args) throws IOException {
		int port = args.length > 0 ? Integer.parseInt(args[0]) : 8090;
		String address = args.length > 1 ? args[1] : "127.0.0.1";
		HttpServer http = HttpServer.create(new InetSocketAddress(address, port), 0);
		http.createContext("/session/minecraft/join", ex -> {
			String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
			String profile = field(body, "selectedProfile");
			String server = field(body, "serverId");
			if (profile != null && server != null) JOINED.put(server, profile.replace("-", ""));
			reply(ex, 204, "");
		});
		http.createContext("/session/minecraft/hasJoined", ex -> {
			Map<String, String> q = query(ex.getRequestURI().getRawQuery());
			String name = q.get("username");
			String id = JOINED.remove(q.getOrDefault("serverId", ""));
			if (name == null || id == null) {
				reply(ex, 204, "");
				return;
			}
			NAMES.put(id, name);
			reply(ex, 200, profile(id, name));
		});
		http.createContext("/session/minecraft/profile/", ex -> {
			String id = ex.getRequestURI().getPath().replaceAll(".*/", "").replace("-", "");
			String name = NAMES.get(id);
			if (name == null) reply(ex, 204, "");
			else reply(ex, 200, profile(id, name));
		});
		http.createContext("/", ex -> reply(ex, 404, ""));
		http.start();
		System.out.println("Fake Mojang on http://" + address + ":" + port + "/");
	}

	private static String profile(String id, String name) {
		return "{\"id\":\"" + id + "\",\"name\":\"" + name + "\",\"properties\":[]}";
	}

	/** A string field from a small JSON object; enough for the join request. */
	private static String field(String json, String key) {
		Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
		return m.find() ? m.group(1) : null;
	}

	private static Map<String, String> query(String raw) {
		Map<String, String> out = new HashMap<>();
		if (raw == null) return out;
		for (String pair : raw.split("&")) {
			int eq = pair.indexOf('=');
			if (eq > 0) out.put(pair.substring(0, eq), URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
		}
		return out;
	}

	private static void reply(HttpExchange ex, int status, String body) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		ex.getResponseHeaders().set("Content-Type", "application/json");
		ex.sendResponseHeaders(status, status == 204 ? -1 : bytes.length);
		if (status != 204) {
			try (OutputStream out = ex.getResponseBody()) {
				out.write(bytes);
			}
		}
		ex.close();
	}
}
