package app.keplersync.tandem.fabric.account;

import app.keplersync.tandem.core.KeplerSyncApi;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Links a Discord account the way Atlas does: KeplerSync's start page opens in the browser, Discord signs in there,
 * and the browser comes back to a one-off listener on this PC with a code. The code is only good with the PKCE
 * verifier, which never leaves this game.
 */
public final class DiscordLink {
	private static final int WAIT_MS = 5 * 60_000;

	private DiscordLink() {}

	/** Opens the browser with [open], waits for it to come back, and links the account. Runs off the game thread. */
	public static CompletableFuture<KeplerSyncApi.Me> start(Account account, Consumer<String> open) {
		CompletableFuture<KeplerSyncApi.Me> done = new CompletableFuture<>();
		Thread t = new Thread(() -> {
			try (ServerSocket listener = new ServerSocket(0, 4, InetAddress.getLoopbackAddress())) {
				byte[] secret = new byte[32];
				new SecureRandom().nextBytes(secret);
				String verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
				open.accept(account.api.discordStart(listener.getLocalPort(), challenge(verifier)));
				String code = waitForCode(listener);
				KeplerSyncApi.Me me = account.api.linkDiscord(code, verifier);
				account.me = me;
				account.version++;
				done.complete(me);
			} catch (Throwable e) {
				done.completeExceptionally(e);
			}
		}, "Tandem Discord link");
		t.setDaemon(true);
		t.start();
		return done;
	}

	static String challenge(String verifier) throws NoSuchAlgorithmException {
		byte[] hash = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
		return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
	}

	private static String waitForCode(ServerSocket listener) throws IOException {
		listener.setSoTimeout(WAIT_MS);
		while (true) {
			Socket s;
			try {
				s = listener.accept();
			} catch (SocketTimeoutException e) {
				throw new IOException("Linking timed out. Try again.");
			}
			try (Socket c = s) {
				c.setSoTimeout(5_000);
				URI uri = URI.create("http://127.0.0.1" + target(c.getInputStream()));
				if (!"/callback".equals(uri.getPath())) {
					respond(c.getOutputStream(), "404 Not Found", "");
					continue;
				}
				Map<String, String> q = query(uri.getRawQuery());
				if (q.containsKey("code")) {
					respond(c.getOutputStream(), "200 OK", page("Discord is linked", "You can close this tab and go back to Minecraft."));
					return q.get("code");
				}
				boolean cancelled = "cancelled".equals(q.get("error")) || "access_denied".equals(q.get("error"));
				respond(c.getOutputStream(), "200 OK", cancelled
						? page("Linking cancelled", "You can close this tab. Nothing was changed.")
						: page("Linking didn't work", "Close this tab and try again from Tandem's Settings."));
				throw new IOException(cancelled ? "Linking was cancelled." : "Linking didn't work. Try again.");
			} catch (IllegalArgumentException | SocketTimeoutException e) {
				// Not a request we understand (or nothing came): wait for the next one.
			}
		}
	}

	/** The request line's target, from what the browser sent. */
	private static String target(InputStream in) throws IOException {
		StringBuilder line = new StringBuilder();
		int b;
		while ((b = in.read()) != -1 && b != '\n' && line.length() < 8192) line.append((char) b);
		String[] parts = line.toString().trim().split(" ");
		if (parts.length < 2 || !parts[0].equals("GET")) throw new IllegalArgumentException("not a GET");
		return parts[1];
	}

	private static Map<String, String> query(String raw) throws IOException {
		Map<String, String> out = new HashMap<>();
		if (raw == null) return out;
		for (String pair : raw.split("&")) {
			int eq = pair.indexOf('=');
			if (eq > 0) out.put(URLDecoder.decode(pair.substring(0, eq), "UTF-8"), URLDecoder.decode(pair.substring(eq + 1), "UTF-8"));
		}
		return out;
	}

	private static void respond(OutputStream out, String status, String body) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		String head = "HTTP/1.1 " + status + "\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: " + bytes.length
				+ "\r\nConnection: close\r\nCache-Control: no-store\r\n\r\n";
		out.write(head.getBytes(StandardCharsets.US_ASCII));
		out.write(bytes);
		out.flush();
	}

	private static String page(String title, String text) {
		return "<!doctype html><meta charset=utf-8><title>" + title + " · Tandem</title>"
				+ "<body style=\"font:16px system-ui;max-width:28rem;margin:20vh auto;padding:0 1rem;background:#111418;color:#e8eaed\">"
				+ "<h1 style=\"font-size:1.25rem\">" + title + "</h1><p>" + text + "</p></body>";
	}
}
