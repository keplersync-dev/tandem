package app.keplersync.tandem.core;

import com.google.gson.JsonElement;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Minecraft's server list ping: asks a game at host:port for its status, the way the multiplayer list does. Tandem
 * uses it to check that a direct address really is the host's game before connecting to it.
 */
public final class Ping {
	private Ping() {}

	/** The status JSON (it holds the description, which for a world opened to friends names the world). */
	public static String status(String hostPort, int timeoutMs) throws IOException {
		try (Socket s = new Socket()) {
			s.connect(RelayTunnel.address(hostPort), timeoutMs);
			s.setSoTimeout(timeoutMs);
			OutputStream out = s.getOutputStream();
			ByteArrayOutputStream hello = new ByteArrayOutputStream();
			varInt(hello, 0x00);
			// Any version answers a status request.
			varInt(hello, -1);
			byte[] host = RelayTunnel.address(hostPort).getHostString().getBytes(StandardCharsets.UTF_8);
			varInt(hello, host.length);
			hello.write(host);
			int port = RelayTunnel.address(hostPort).getPort();
			hello.write(port >> 8);
			hello.write(port & 0xff);
			// Next: status.
			varInt(hello, 1);
			packet(out, hello.toByteArray());
			packet(out, new byte[] {0x00});
			DataInputStream in = new DataInputStream(s.getInputStream());
			readVarInt(in);
			if (readVarInt(in) != 0x00) throw new IOException("Not a Minecraft status reply");
			int length = readVarInt(in);
			// Minecraft's own limit is 32767 characters; anything far bigger isn't a game.
			if (length < 0 || length > 1 << 20) throw new IOException("Not a Minecraft status reply");
			byte[] json = new byte[length];
			in.readFully(json);
			return new String(json, StandardCharsets.UTF_8);
		}
	}

	/**
	 * The words in a status reply's description, whatever shape the game sent it in. Minecraft writes the JSON with
	 * HTML escaping, so the apostrophe in Alex's World arrives as an escape code: compare names against this.
	 */
	public static String description(String json) {
		StringBuilder out = new StringBuilder();
		JsonElement status = Io.GSON.fromJson(json, JsonElement.class);
		if (status != null && status.isJsonObject()) words(status.getAsJsonObject().get("description"), out);
		return out.toString();
	}

	private static void words(JsonElement e, StringBuilder out) {
		if (e == null || e.isJsonNull()) return;
		if (e.isJsonPrimitive()) out.append(e.getAsString());
		else if (e.isJsonArray()) for (JsonElement x : e.getAsJsonArray()) words(x, out);
		else for (Map.Entry<String, JsonElement> x : e.getAsJsonObject().entrySet()) words(x.getValue(), out);
	}

	private static void packet(OutputStream out, byte[] body) throws IOException {
		ByteArrayOutputStream framed = new ByteArrayOutputStream();
		varInt(framed, body.length);
		framed.write(body);
		out.write(framed.toByteArray());
		out.flush();
	}

	static void varInt(OutputStream out, int value) throws IOException {
		while ((value & ~0x7F) != 0) {
			out.write((value & 0x7F) | 0x80);
			value >>>= 7;
		}
		out.write(value);
	}

	static int readVarInt(InputStream in) throws IOException {
		int value = 0;
		for (int i = 0; i < 5; i++) {
			int b = in.read();
			if (b < 0) throw new IOException("The game closed the connection");
			value |= (b & 0x7F) << (7 * i);
			if ((b & 0x80) == 0) return value;
		}
		throw new IOException("A number too long");
	}
}
