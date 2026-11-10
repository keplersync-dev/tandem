package app.keplersync.tandem.fabric.account;

import app.keplersync.tandem.core.KeplerSyncApi;
import app.keplersync.tandem.fabric.TandemClient;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.CompletionStage;

/**
 * KeplerSync's live connection: friends' presence, and "something changed, fetch again". Says what this player is
 * doing, pings every 30 seconds, and reconnects with growing pauses when the connection drops.
 */
final class Live implements WebSocket.Listener {
	private static final Gson GSON = new Gson();

	private final Account account;
	private final HttpClient http = HttpClient.newHttpClient();
	private volatile WebSocket socket;
	private volatile boolean closed;
	private volatile int failures;
	private final StringBuilder partial = new StringBuilder();
	private volatile JsonObject presence = presenceMessage("online", null, null);

	Live(Account account) {
		this.account = account;
	}

	/** On the account thread. */
	void connect() {
		if (closed) return;
		String token;
		try {
			token = account.api.accessToken();
		} catch (Exception e) {
			retry();
			return;
		}
		http.newWebSocketBuilder()
				.header("Authorization", "Bearer " + token)
				.buildAsync(URI.create(account.api.socketUrl()), this)
				.whenComplete((ws, e) -> {
					if (e != null) {
						TandemClient.LOG.info("Tandem's live connection to KeplerSync failed: {}", e.getMessage());
						retry();
					}
				});
	}

	private void retry() {
		if (closed) return;
		long wait = Math.min(60, 2L << Math.min(failures++, 5));
		account.later(this::connect, wait);
	}

	@Override
	public void onOpen(WebSocket ws) {
		socket = ws;
		if (failures > 0) TandemClient.LOG.info("Tandem reconnected to KeplerSync");
		failures = 0;
		ws.sendText(presence.toString(), true);
		ws.request(1);
		// Anything missed while away.
		account.now(account::reload);
		ping(ws);
	}

	private void ping(WebSocket ws) {
		account.later(() -> {
			if (socket != ws || closed) return;
			ws.sendText("{\"type\":\"ping\",\"data\":{}}", true);
			ping(ws);
		}, 30);
	}

	@Override
	public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
		partial.append(data);
		if (last) {
			String text = partial.toString();
			partial.setLength(0);
			account.now(() -> handle(text));
		}
		ws.request(1);
		return null;
	}

	private void handle(String text) {
		JsonObject msg;
		try {
			msg = GSON.fromJson(text, JsonObject.class);
		} catch (RuntimeException e) {
			return;
		}
		String type = msg.has("type") ? msg.get("type").getAsString() : "";
		JsonObject data = msg.has("data") && msg.get("data").isJsonObject() ? msg.getAsJsonObject("data") : new JsonObject();
		switch (type) {
			case "presence.changed":
				account.presenceChanged(data.get("userId").getAsString(), GSON.fromJson(data.get("presence"), KeplerSyncApi.Presence.class));
				break;
			case "friends.changed":
				account.reload();
				break;
			case "world.changed":
				account.worldsVersion++;
				break;
			case "invite.received":
			case "invites.changed":
				account.reloadInvites();
				break;
			case "world.handoff":
				account.handoff(data.get("worldId").getAsString(), data.get("fromId").getAsString(), data.get("toId").getAsString());
				break;
			case "host.requested":
				account.hostRequests.add(new Account.HostAsk(data.get("worldId").getAsString(), data.get("fromId").getAsString()));
				break;
			case "host.declined":
				account.hostDeclines.add(new Account.HostAsk(data.get("worldId").getAsString(), data.get("byId").getAsString()));
				break;
			case "play.kick":
				app.keplersync.tandem.fabric.play.Hosting.kickedByOwner(data.get("sessionId").getAsString(), data.get("userId").getAsString());
				break;
			case "me.changed":
				account.reloadMe();
				break;
			case "session.revoked":
				TandemClient.LOG.info("KeplerSync ended Tandem's session; signing in again");
				try {
					account.api.signIn();
				} catch (Exception e) {
					TandemClient.LOG.warn("Tandem couldn't sign in again: {}", e.getMessage());
				}
				break;
			default:
				// hello, pong, and what this build doesn't know.
		}
	}

	@Override
	public CompletionStage<?> onClose(WebSocket ws, int status, String reason) {
		dropped(ws);
		return null;
	}

	@Override
	public void onError(WebSocket ws, Throwable error) {
		dropped(ws);
	}

	private void dropped(WebSocket ws) {
		if (socket != ws) return;
		socket = null;
		if (!closed) {
			TandemClient.LOG.info("Tandem's live connection to KeplerSync dropped; reconnecting");
			retry();
		}
	}

	/** What this player is doing now; sent at once, and again after every reconnect. */
	void presence(String state, String world, String minecraftVersion) {
		presence = presenceMessage(state, world, minecraftVersion);
		WebSocket ws = socket;
		if (ws != null) ws.sendText(presence.toString(), true);
	}

	private static JsonObject presenceMessage(String state, String world, String minecraftVersion) {
		JsonObject data = new JsonObject();
		data.addProperty("state", state);
		// Tandem runs inside Minecraft: friends see "In the menu", not "In Atlas".
		data.addProperty("inMinecraft", true);
		if (world != null) data.addProperty("instanceName", world);
		if (minecraftVersion != null) data.addProperty("minecraftVersion", minecraftVersion);
		JsonObject msg = new JsonObject();
		msg.addProperty("type", "presence.set");
		msg.add("data", data);
		return msg;
	}

	void close() {
		closed = true;
		WebSocket ws = socket;
		if (ws != null) ws.sendClose(WebSocket.NORMAL_CLOSURE, "Quit");
	}
}
