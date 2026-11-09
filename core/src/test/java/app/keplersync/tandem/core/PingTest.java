package app.keplersync.tandem.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** Against a fake game that answers the status request the way Minecraft does. */
class PingTest {
	private static ServerSocket game(String json) throws IOException {
		ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
		Thread t = new Thread(() -> {
			try (Socket s = server.accept()) {
				DataInputStream in = new DataInputStream(s.getInputStream());
				// The handshake, then the status request.
				in.readFully(new byte[Ping.readVarInt(in)]);
				in.readFully(new byte[Ping.readVarInt(in)]);
				byte[] body = json.getBytes(StandardCharsets.UTF_8);
				ByteArrayOutputStream packet = new ByteArrayOutputStream();
				Ping.varInt(packet, 0x00);
				Ping.varInt(packet, body.length);
				packet.write(body);
				OutputStream out = s.getOutputStream();
				Ping.varInt(out, packet.size());
				packet.writeTo(out);
				out.flush();
			} catch (IOException ignored) {
				// The test fails on its own side.
			}
		});
		t.setDaemon(true);
		t.start();
		return server;
	}

	@Test
	void aGameSaysItsStatus() throws IOException {
		// Long enough that its length takes two bytes.
		String json = "{\"description\":{\"text\":\"Play Base " + new String(new char[300]).replace('\0', 'x') + "\"}}";
		try (ServerSocket server = game(json)) {
			assertEquals(json, Ping.status("127.0.0.1:" + server.getLocalPort(), 2000));
		}
	}

	@Test
	void aNameWithAnApostropheIsFound() throws IOException {
		// Minecraft's Gson escapes ' & < > = in the reply.
		String json = "{\"version\":{\"name\":\"1.21.1\"},\"description\":{\"text\":\"Alex - Alex\\u0027s \\u0026 Sam\\u0027s World\"}}";
		try (ServerSocket server = game(json)) {
			assertEquals("Alex - Alex's & Sam's World", Ping.description(Ping.status("127.0.0.1:" + server.getLocalPort(), 2000)));
		}
	}

	@Test
	void aHugeReplyIsRefused() throws IOException {
		try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
			Thread t = new Thread(() -> {
				try (Socket s = server.accept()) {
					OutputStream out = s.getOutputStream();
					// A packet, its id, then a length of 2 GB.
					Ping.varInt(out, 6);
					Ping.varInt(out, 0x00);
					Ping.varInt(out, Integer.MAX_VALUE);
					out.flush();
					Thread.sleep(2000);
				} catch (IOException | InterruptedException ignored) {
					// The test fails on its own side.
				}
			});
			t.setDaemon(true);
			t.start();
			assertThrows(IOException.class, () -> Ping.status("127.0.0.1:" + server.getLocalPort(), 2000));
		}
	}

	@Test
	void nothingListeningFails() throws IOException {
		int port;
		try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
			port = s.getLocalPort();
		}
		assertThrows(IOException.class, () -> Ping.status("127.0.0.1:" + port, 1000));
	}
}
