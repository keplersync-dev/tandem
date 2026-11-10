package app.keplersync.tandem.core;

import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * KeplerSync's REST API, as Tandem uses it. Signing in happens the way a Minecraft server checks a joining player:
 * the server hands out a one-time server id, the game joins it with Mojang ({@link Joiner}), and the server asks
 * Mojang who joined. The player's Minecraft token never reaches KeplerSync. Tokens live in memory only; an expired
 * one is refreshed, and when that fails Tandem simply signs in again.
 */
public final class KeplerSyncApi {
	/** The game's part of signing in: join a server id with Mojang, as when joining a server. */
	public interface Joiner {
		String username();

		void join(String serverId) throws IOException;
	}

	/** The server said no; [getMessage] is its own words, written for players. */
	public static final class Refused extends IOException {
		public final int status;
		public final String code;

		Refused(int status, String code, String message) {
			super(message);
			this.status = status;
			this.code = code;
		}

		/** KeplerSync only talks to a newer Tandem (its MIN_TANDEM_VERSION): updating is the only fix. */
		public boolean outdated() {
			return "client_outdated".equals(code);
		}
	}

	/** Mojang didn't confirm the game's own Minecraft account, so signing in stopped before KeplerSync was asked. */
	public static final class NotConfirmed extends IOException {
		public NotConfirmed(String message, Throwable cause) {
			super(message, cause);
		}
	}

	public static final class Me {
		public String id;
		public String username;
		public String displayName;
		public String skinUrl;
		public String minecraftUuid;
		public String minecraftUsername;
		public String friendCode;
		/** Anyone with it can send this account a friend request; the code is its secret part. */
		public String friendLink;
		public List<Identity> identities = new ArrayList<>();
	}

	/** A way this account signs in: "minecraft". */
	public static final class Identity {
		public String provider;
	}

	public static final class User {
		public String id;
		public String username;
		public String displayName;
		public String skinUrl;
		public String minecraftUuid;
		public String minecraftUsername;
	}

	public static final class Presence {
		/** offline, online or playing. */
		public String state;
		public String instanceName;
		public String minecraftVersion;
		/** Minecraft is open, not only the Atlas app. */
		public boolean inMinecraft;
		public String updatedAt;
	}

	public static final class Friend {
		public User user;
		public String friendsSince;
		public Presence presence;
	}

	public static final class FriendRequest {
		/** incoming or outgoing. */
		public String direction;
		public User user;
		public String createdAt;
	}

	private static final class Tokens {
		String accessToken;
		long expiresIn;
		String refreshToken;
		Me user;
	}

	private final String base;
	private final Joiner joiner;
	private volatile String agent = "Tandem";
	private String access;
	private String refresh;
	private long expiresAt;
	/** The account signed in to, from its last sign-in. */
	private volatile Me me;

	/** [server] is like {@code https://keplersync.app}; the API is under {@code /v1}. */
	public KeplerSyncApi(String server, Joiner joiner) {
		this.base = server.replaceAll("/+$", "") + "/v1";
		this.joiner = joiner;
	}

	/** What requests say they come from, like {@code Tandem/0.1.0 (Minecraft 1.21.1)}. */
	public void agent(String agent) {
		this.agent = agent;
	}

	/** The live connection's address, for the same server. */
	public String socketUrl() {
		return base.replaceFirst("^http", "ws") + "/ws";
	}

	public synchronized boolean signedIn() {
		return access != null;
	}

	public synchronized String accessToken() throws IOException {
		if (access == null) signIn();
		return access;
	}

	/** Signs in from scratch with the game's account. */
	public synchronized Me signIn() throws IOException {
		JsonObject challenge = Io.GSON.fromJson(send("POST", "/auth/minecraft/challenge", "{}", null), JsonObject.class);
		String serverId = challenge.get("serverId").getAsString();
		joiner.join(serverId);
		JsonObject body = new JsonObject();
		body.addProperty("serverId", serverId);
		body.addProperty("username", joiner.username());
		body.addProperty("client", "tandem");
		Tokens t = Io.GSON.fromJson(send("POST", "/auth/minecraft/verify", body.toString(), null), Tokens.class);
		keep(t);
		return t.user;
	}

	/** The account, signing in only if no one has yet: two threads asking at once make one session, not two. */
	public synchronized Me ensureSignedIn() throws IOException {
		if (access == null || me == null) signIn();
		return me;
	}

	/** The signed-in account's id, signing in first if need be. Off the game's threads. */
	public String userId() throws IOException {
		return ensureSignedIn().id;
	}

	/** The account's id once signed in, else null. Never waits. */
	public String knownUserId() {
		Me m = me;
		return m != null ? m.id : null;
	}

	/** Refreshes the access token if it runs out within two minutes; signs in again if that fails. */
	public synchronized void refreshIfDue() throws IOException {
		if (access != null && System.currentTimeMillis() < expiresAt - 120_000) return;
		refreshNow();
	}

	private void refreshNow() throws IOException {
		if (refresh == null) {
			signIn();
			return;
		}
		try {
			JsonObject body = new JsonObject();
			body.addProperty("refreshToken", refresh);
			keep(Io.GSON.fromJson(send("POST", "/auth/refresh", body.toString(), null), Tokens.class));
		} catch (Refused e) {
			signIn();
		}
	}

	private void keep(Tokens t) {
		access = t.accessToken;
		refresh = t.refreshToken;
		expiresAt = System.currentTimeMillis() + t.expiresIn * 1000;
		if (t.user != null) me = t.user;
	}

	/** Ends this session on the server, so sessions don't pile up. */
	public synchronized void logout() {
		if (access == null) return;
		try {
			send("POST", "/auth/logout", "{}", access);
		} catch (IOException ignored) {
			// The session runs out by itself.
		}
		access = null;
		refresh = null;
	}

	/** A signed-in call: a refused token is refreshed (or signed in again) once, then the call is retried. */
	String call(String method, String path, String body) throws IOException {
		String token;
		synchronized (this) {
			if (access == null) signIn();
			token = access;
		}
		try {
			return send(method, path, body, token);
		} catch (Refused e) {
			if (e.status != 401) throw e;
			synchronized (this) {
				if (token.equals(access)) refreshNow();
				token = access;
			}
			return send(method, path, body, token);
		}
	}

	public Me me() throws IOException {
		return Io.GSON.fromJson(call("GET", "/me", null), Me.class);
	}

	public List<Friend> friends() throws IOException {
		List<Friend> out = Io.GSON.fromJson(call("GET", "/friends", null), new TypeToken<List<Friend>>() {}.getType());
		return out != null ? out : new ArrayList<Friend>();
	}

	public List<FriendRequest> requests() throws IOException {
		List<FriendRequest> out = Io.GSON.fromJson(call("GET", "/friends/requests", null), new TypeToken<List<FriendRequest>>() {}.getType());
		return out != null ? out : new ArrayList<FriendRequest>();
	}

	/** Sends a friend request to [who]: a Minecraft name, or a friend link someone sent. */
	public void addFriend(String who) throws IOException {
		JsonObject body = new JsonObject();
		String code = linkCode(who);
		if (code != null) body.addProperty("code", code);
		else body.addProperty("name", name(who));
		call("POST", "/friends/requests", body.toString());
	}

	/** Who [who] is (a Minecraft name, or a friend link), before sending them a request. */
	public User findFriend(String who) throws IOException {
		String code = linkCode(who);
		String path = code != null ? "/friend-codes/" + code : "/friend-names/" + name(who);
		return Io.GSON.fromJson(call("GET", path, null), User.class);
	}

	private static final Pattern LINK = Pattern.compile("(?:/f/|atlas://friend/)([0-9A-Za-z-]+)/?$");

	/** The code in a pasted friend link, or null when [who] isn't one: then it's a Minecraft name. */
	static String linkCode(String who) {
		Matcher m = LINK.matcher(who.trim());
		if (!m.find()) return null;
		String code = m.group(1).toUpperCase(Locale.ROOT).replace("-", "");
		return code.length() == 8 ? code : null;
	}

	/** A Minecraft name as typed: without spaces or the @ in front. Only letters, digits and _ reach the path. */
	private static String name(String who) {
		String n = who.trim();
		if (n.startsWith("@")) n = n.substring(1);
		return n.replaceAll("[^A-Za-z0-9_]", "");
	}

	public void accept(String userId) throws IOException {
		call("POST", "/friends/requests/" + userId + "/accept", "{}");
	}

	public void decline(String userId) throws IOException {
		call("POST", "/friends/requests/" + userId + "/decline", "{}");
	}

	public void removeFriend(String userId) throws IOException {
		call("DELETE", "/friends/" + userId, null);
	}

	public void block(String userId) throws IOException {
		call("POST", "/blocks/" + userId, "{}");
	}

	/** Everyone this account blocked, newest first. */
	public List<User> blocked() throws IOException {
		List<User> out = Io.GSON.fromJson(call("GET", "/blocks", null), new TypeToken<List<User>>() {}.getType());
		return out != null ? out : new ArrayList<User>();
	}

	/** Undoes a block. They aren't friends again until one of you sends a request. */
	public void unblock(String userId) throws IOException {
		call("DELETE", "/blocks/" + userId, null);
	}

	/** A new display name: what friends see in Atlas and Tandem. The @username is the Minecraft name. */
	public Me updateProfile(String displayName) throws IOException {
		JsonObject body = new JsonObject();
		body.addProperty("displayName", displayName);
		// POST rather than PATCH: Java's own HTTP client can't send PATCH, so the server takes both.
		return Io.GSON.fromJson(call("POST", "/me", body.toString()), Me.class);
	}

	/** [reason] is the server's: abuse or other. */
	public void report(String userId, String reason, String details) throws IOException {
		JsonObject body = new JsonObject();
		body.addProperty("targetType", "user");
		body.addProperty("targetId", userId);
		body.addProperty("reason", reason);
		if (details != null && !details.trim().isEmpty()) body.addProperty("details", details.trim());
		call("POST", "/reports", body.toString());
	}

	// ── Just play ──

	public static final class RelayTicket {
		public String address;
		public String ticket;
	}

	public static final class PlaySession {
		public String id;
		/** Null when the server has no relay: friends can then only connect directly. */
		public RelayTicket relay;
	}

	public static final class PlayInvite {
		public String sessionId;
		public User host;
		public String worldName;
		public String minecraftVersion;
		public String invitedAt;
	}

	public static final class JoinInfo {
		public String sessionId;
		public User host;
		public String worldName;
		/** The synced world being hosted, or null for Just play. */
		public String worldId;
		public String minecraftVersion;
		public List<String> mods = new ArrayList<>();
		public List<String> direct = new ArrayList<>();
		public RelayTicket relay;
		/** For the relay to introduce this game to the host's over UDP, on its port and the next; given even without [relay]. */
		public RelayTicket punch;
		/** Why there's no relay ticket: "off" (KeplerSync turned it off for now) or "allowance" (this month's used). */
		public String relayOff;
	}

	/** How often a session uploads: while others are in the game, and alone. */
	public static final class Limits {
		public int uploadMinutesShared = 5;
		public int uploadMinutesAlone = 15;
	}

	public Limits limits() throws IOException {
		return Io.GSON.fromJson(call("GET", "/me/limits", null), Limits.class);
	}

	/** Starts hosting [world] (a synced world's id, or null for Just play), reachable directly at [direct]. */
	public PlaySession startPlay(String world, String worldId, String minecraftVersion, List<String> mods, List<String> direct) throws IOException {
		JsonObject body = new JsonObject();
		body.addProperty("worldName", world);
		if (worldId != null) body.addProperty("worldId", worldId);
		body.addProperty("minecraftVersion", minecraftVersion);
		body.add("mods", Io.GSON.toJsonTree(mods));
		body.add("direct", Io.GSON.toJsonTree(direct));
		return Io.GSON.fromJson(call("POST", "/play", body.toString()), PlaySession.class);
	}

	/** New direct addresses; the answer carries a fresh host ticket for the relay. */
	public PlaySession updatePlay(String session, List<String> direct) throws IOException {
		JsonObject body = new JsonObject();
		body.add("direct", Io.GSON.toJsonTree(direct));
		return Io.GSON.fromJson(call("POST", "/play/" + session + "/direct", body.toString()), PlaySession.class);
	}

	/** Makes a member of a synced world an operator, or not (the owner and operators may). */
	public void setOperator(String worldId, String userId, boolean operator) throws IOException {
		call("PUT", "/worlds/" + worldId + "/members/" + userId + "/operator", "{\"operator\":" + operator + "}");
	}

	/** Hands a synced world to [userId]: once this host's upload is done, the lease is theirs. */
	public void handoff(String worldId, String userId) throws IOException {
		JsonObject body = new JsonObject();
		body.addProperty("to", userId);
		call("POST", "/worlds/" + worldId + "/handoff", body.toString());
	}

	/** This player's download of [worldId] is slow: asks [userId], in the world too, to host it instead (A2). */
	public void requestHost(String worldId, String userId) throws IOException {
		JsonObject body = new JsonObject();
		body.addProperty("to", userId);
		call("POST", "/worlds/" + worldId + "/host-request", body.toString());
	}

	/** The owner stops waiting for an upload of [worldId] that stalled (no heartbeat for 90 s): it's free to play from the last one. */
	public void carryOn(String worldId) throws IOException {
		call("POST", "/worlds/" + worldId + "/carry-on", "{}");
	}

	/** Can't host [worldId] for [userId], who asked: they hear, and stop waiting. */
	public void declineHost(String worldId, String userId) throws IOException {
		JsonObject body = new JsonObject();
		body.addProperty("from", userId);
		call("POST", "/worlds/" + worldId + "/host-request/decline", body.toString());
	}

	/** This player won't host a world handed to them: it's freed instead, and whoever waited to join them hears. */
	public void declineHandoff(String worldId) throws IOException {
		call("POST", "/worlds/" + worldId + "/handoff/decline", "{}");
	}

	/** Send logs: Tandem's own lines and the game's errors, with what the player says happened. User names, links'
	 * signatures and tokens are removed first ({@link LogScrub}). */
	public void sendLogs(String tandemVersion, String minecraftVersion, String note, String log) throws IOException {
		JsonObject body = new JsonObject();
		body.addProperty("tandemVersion", tandemVersion);
		body.addProperty("minecraftVersion", minecraftVersion);
		body.addProperty("note", LogScrub.scrub(note));
		body.addProperty("body", LogScrub.scrub(log));
		call("POST", "/tandem/logs", body.toString());
	}

	public void endPlay(String session) throws IOException {
		call("DELETE", "/play/" + session, null);
	}

	public void invite(String session, String userId) throws IOException {
		call("PUT", "/play/" + session + "/invites/" + userId, null);
	}

	public void uninvite(String session, String userId) throws IOException {
		call("DELETE", "/play/" + session + "/invites/" + userId, null);
	}

	public List<PlayInvite> playInvites() throws IOException {
		List<PlayInvite> out = Io.GSON.fromJson(call("GET", "/play/invites", null), new TypeToken<List<PlayInvite>>() {}.getType());
		return out != null ? out : new ArrayList<PlayInvite>();
	}

	/** The game a synced world is hosted in right now. */
	public PlaySession playForWorld(String worldId) throws IOException {
		return Io.GSON.fromJson(call("GET", "/play/worlds/" + worldId, null), PlaySession.class);
	}

	public JoinInfo join(String session) throws IOException {
		return Io.GSON.fromJson(call("POST", "/play/" + session + "/join", "{}"), JoinInfo.class);
	}

	/** The owner of the synced world [session] hosts takes [userId] out of that game; its host kicks them. */
	public void kick(String session, String userId) throws IOException {
		call("POST", "/play/" + session + "/kick/" + userId, "{}");
	}

	/**
	 * How this game got into [session]'s game: [via] is lan, router, ipv6, punched, relay or failed; [reason] says why
	 * the relay was needed, or which way failed. KeplerSync only counts them per day.
	 */
	public void connected(String session, String via, String reason) throws IOException {
		JsonObject body = new JsonObject();
		body.addProperty("via", via);
		if (reason != null) body.addProperty("reason", reason);
		call("POST", "/play/" + session + "/connected", body.toString());
	}

	// ── Leaving and storage ──

	/** Stop syncing a world you own: read-only for 30 days, then deleted. With [whenFree], once whoever hosts lets go. */
	public void stopSyncing(String worldId, boolean whenFree) throws IOException {
		call("POST", "/worlds/" + worldId + "/stop", "{\"whenFree\":" + whenFree + "}");
	}

	/** Deletes a stopped world now rather than at the end of its 30 days. */
	public void deleteWorld(String worldId) throws IOException {
		call("DELETE", "/worlds/" + worldId, null);
	}

	/** Undoes {@link #stopSyncing} within the 30 days, or calls off one waiting for the host. */
	public void resumeSyncing(String worldId) throws IOException {
		call("POST", "/worlds/" + worldId + "/resume", "{}");
	}

	/** Leave a world shared with you: you're out of it, and your copy stays on your PC. */
	public void leaveWorld(String worldId, String myId) throws IOException {
		removeMember(worldId, myId);
	}

	/** The owner shares their world with a friend: they can play and host it from now on. */
	public void addMember(String worldId, String userId) throws IOException {
		call("PUT", "/worlds/" + worldId + "/members/" + userId, null);
	}

	/** The owner takes someone out of their world; their copy stays on their PC as a normal world. */
	public void removeMember(String worldId, String userId) throws IOException {
		call("DELETE", "/worlds/" + worldId + "/members/" + userId, null);
	}

	/** Every world you own keeps only its latest version (and anything unsettled). Returns the bytes freed. */
	public long trimHistory() throws IOException {
		return Io.GSON.fromJson(call("POST", "/worlds/trim", "{}"), JsonObject.class).get("freedBytes").getAsLong();
	}

	/** Moves a world you own to "own" storage or back to "keplersync"; the server copies it in the background. */
	public void moveStorage(String worldId, String to) throws IOException {
		JsonObject body = new JsonObject();
		body.addProperty("to", to);
		call("POST", "/worlds/" + worldId + "/storage", body.toString());
	}

	public static final class OwnStorage {
		public String accountId;
		public String bucket;
		public String accessKeyId;
		public String checkedAt;
		/** What's wrong with it, from the last test; null when it works. */
		public String problem;
		/** After a test: false when the token can reach more buckets than this one. */
		public Boolean scoped;
	}

	public static final class WorldSpace {
		public String id;
		public String name;
		public String storage;
		public long bytes;
		/** Stopped: when it's deleted (an ISO date). It counts against the space until then. */
		public String deletedAt;
	}

	public static final class StorageOverview {
		public long usedBytes;
		public long totalBytes;
		public List<WorldSpace> worlds = new ArrayList<>();
		public OwnStorage own;
	}

	public StorageOverview storage() throws IOException {
		return Io.GSON.fromJson(call("GET", "/storage", null), StorageOverview.class);
	}

	/**
	 * Tests the keys (a write, a read and a delete in the bucket), then saves them; nothing is saved when the test fails,
	 * and the server says why. The secret is sent this once and never comes back.
	 */
	public OwnStorage saveOwnStorage(String accountId, String bucket, String accessKeyId, String secret) throws IOException {
		JsonObject body = new JsonObject();
		body.addProperty("accountId", accountId.trim());
		body.addProperty("bucket", bucket.trim());
		body.addProperty("accessKeyId", accessKeyId.trim());
		body.addProperty("secretAccessKey", secret.trim());
		return Io.GSON.fromJson(call("PUT", "/storage/own", body.toString()), OwnStorage.class);
	}

	public OwnStorage testOwnStorage() throws IOException {
		return Io.GSON.fromJson(call("POST", "/storage/own/test", "{}"), OwnStorage.class);
	}

	public void removeOwnStorage() throws IOException {
		call("DELETE", "/storage/own", null);
	}

	/** A new friend link; the old one stops working. */
	public Me newFriendCode() throws IOException {
		return Io.GSON.fromJson(call("POST", "/me/friend-code/reset", "{}"), Me.class);
	}

	private String send(String method, String path, String body, String token) throws IOException {
		HttpURLConnection c = (HttpURLConnection) new URL(base + path).openConnection();
		c.setRequestMethod(method);
		c.setConnectTimeout(10_000);
		c.setReadTimeout(30_000);
		c.setRequestProperty("Accept", "application/json");
		c.setRequestProperty("User-Agent", agent);
		if (token != null) c.setRequestProperty("Authorization", "Bearer " + token);
		if (body != null) {
			c.setDoOutput(true);
			c.setRequestProperty("Content-Type", "application/json");
			try (OutputStream out = c.getOutputStream()) {
				out.write(body.getBytes(StandardCharsets.UTF_8));
			}
		}
		int status = c.getResponseCode();
		String text = read(status >= 400 ? c.getErrorStream() : c.getInputStream());
		if (status >= 400) {
			String code = "unknown";
			String message = "KeplerSync answered " + status + ".";
			try {
				JsonObject error = Io.GSON.fromJson(text, JsonObject.class).getAsJsonObject("error");
				code = error.get("code").getAsString();
				message = error.get("message").getAsString();
			} catch (RuntimeException ignored) {
				// Not KeplerSync's error format (a proxy, or no server at all).
			}
			// Cloudflare's page while KeplerSync restarts or is down (502, 521, 522...), or the server failing for a
			// moment: the same as no answer, so sessions stay on this PC and transfers try again. At capacity is an answer.
			if (status >= 500 && !"at_capacity".equals(code)) throw new IOException("KeplerSync isn't answering right now (" + status + ")");
			throw new Refused(status, code, message);
		}
		return text.isEmpty() ? "null" : text;
	}

	private static String read(InputStream in) throws IOException {
		if (in == null) return "";
		try (InputStream i = in) {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			byte[] buf = new byte[8192];
			int n;
			while ((n = i.read(buf)) != -1) out.write(buf, 0, n);
			return new String(out.toByteArray(), StandardCharsets.UTF_8);
		}
	}
}
