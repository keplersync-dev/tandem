package app.keplersync.tandem.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Synced worlds on KeplerSync's server: the worlds endpoints, with file contents going straight to and from storage
 * on presigned links. People are KeplerSync user ids, with each world's owner and members giving the names shown.
 */
public final class ServerCloud implements Cloud {
	/** Most files the server takes in one check, completion or links request. */
	static final int BATCH = 500;
	/**
	 * Most bytes in one upload batch: its links last 10 minutes, and completing it has the server read every file
	 * back. A whole 1.3 GB world in one batch timed out there; 64 MB goes in time on a 1 Mbps upload.
	 */
	static final long BATCH_BYTES = 64L << 20;

	private static final class User {
		String id;
		String username;
		String displayName;
		String minecraftUuid;
	}

	private static final class ServerLease {
		String holderId;
		String state;
		float progress;
		String expiresAt;
	}

	private static final class ServerWorld {
		String id;
		String name;
		String minecraftVersion;
		List<String> mods = new ArrayList<>();
		User owner;
		List<User> members = new ArrayList<>();
		String head;
		ServerLease lease;
		List<String> branches = new ArrayList<>();
		List<String> operators = new ArrayList<>();
		String storage;
		Move moving;
		String storageProblem;
		String stoppedAt;
		boolean stopWhenFree;
	}

	private static final class Move {
		String to;
		long doneBytes;
		long totalBytes;
	}

	private static final class Ticket {
		boolean alreadyExists;
		String uploadId;
		String url;
	}

	private static final class Link {
		final String url;
		final long until;

		Link(String url, long until) {
			this.url = url;
			this.until = until;
		}
	}

	private final KeplerSyncApi api;
	private final Supplier<List<String>> mods;
	/** User id → name on screen ({@link #label}), from every world seen. */
	private final Map<String, String> names = new ConcurrentHashMap<>();
	private final Map<String, ServerWorld> lastWorlds = new ConcurrentHashMap<>();
	private volatile long lastWorldsAt;
	/**
	 * Each world's last snapshot fetched: snapshots never change, and the worlds list asks for the head again and again.
	 * One per world, since a big world's file list takes megabytes and every save makes a new one.
	 */
	private final Map<String, Snapshot> snapshots = new ConcurrentHashMap<>();
	private final Map<String, Link> links = new ConcurrentHashMap<>();

	/** [mods] is the game's own list, saved with every session (the mods it was last played with). */
	public ServerCloud(KeplerSyncApi api, Supplier<List<String>> mods) {
		this.api = api;
		this.mods = mods;
	}

	private ServerWorld seen(ServerWorld w) {
		List<User> everyone = people(w);
		for (User u : everyone) names.put(u.id, label(u, everyone));
		lastWorlds.put(w.id, w);
		return w;
	}

	private static List<User> people(ServerWorld s) {
		List<User> everyone = new ArrayList<>(s.members);
		if (s.owner != null) everyone.add(s.owner);
		return everyone;
	}

	/**
	 * What [u] is called on screen: their display name, with their username beside it when someone else in [people]
	 * has the same one ("Sam (@sam_2)"). Usernames are unique; display names aren't.
	 */
	static String label(User u, List<User> people) {
		String shown = u.displayName != null && !u.displayName.isEmpty() ? u.displayName : u.username != null ? u.username : u.id;
		if (u.username == null) return shown;
		for (User o : people) {
			if (o != u && shown.equalsIgnoreCase(o.displayName)) return shown + " (@" + u.username + ")";
		}
		return shown;
	}

	private String name(String userId) {
		if (userId == null) return null;
		String n = names.get(userId);
		return n != null ? n : userId;
	}

	private World view(ServerWorld s) {
		World w = new World();
		w.id = s.id;
		w.name = s.name;
		w.mcVersion = s.minecraftVersion;
		w.mods = s.mods;
		w.createdBy = s.owner != null ? s.owner.id : null;
		w.head = s.head;
		for (User m : s.members) w.sharedWith.add(m.id);
		w.branches = s.branches;
		if (s.storage != null) w.storage = s.storage;
		if (s.moving != null) {
			w.movingTo = s.moving.to;
			w.movedBytes = s.moving.doneBytes;
			w.moveBytes = s.moving.totalBytes;
		}
		w.storageProblem = s.storageProblem;
		w.stopped = s.stoppedAt != null;
		w.stopWhenFree = s.stopWhenFree;
		for (User u : people(s)) {
			w.names.put(u.id, name(u.id));
			if (u.minecraftUuid != null) w.minecraftIds.put(u.id, u.minecraftUuid);
			if (s.operators.contains(u.id)) w.operators.add(u.id);
		}
		return w;
	}

	@Override
	public List<World> worlds(String player) throws IOException {
		List<ServerWorld> list = Io.GSON.fromJson(api.call("GET", "/worlds", null), new TypeToken<List<ServerWorld>>() {}.getType());
		List<World> out = new ArrayList<>();
		lastWorlds.clear();
		for (ServerWorld s : list) out.add(view(seen(s)));
		lastWorldsAt = System.currentTimeMillis();
		return out;
	}

	private ServerWorld fetch(String id) throws IOException {
		return seen(Io.GSON.fromJson(api.call("GET", "/worlds/" + id, null), ServerWorld.class));
	}

	@Override
	public World world(String id) throws IOException {
		return view(fetch(id));
	}

	@Override
	public World createWorld(String key, String name, String mcVersion, List<String> worldMods, String player, List<String> sharedWith) throws IOException {
		JsonObject body = new JsonObject();
		body.addProperty("key", key);
		body.addProperty("name", name);
		body.addProperty("minecraftVersion", mcVersion);
		body.add("mods", Io.GSON.toJsonTree(worldMods));
		body.add("sharedWith", Io.GSON.toJsonTree(sharedWith));
		return view(seen(Io.GSON.fromJson(api.call("POST", "/worlds", body.toString()), ServerWorld.class)));
	}

	private Lease lease(ServerLease l) {
		if (l == null) return null;
		Lease out = new Lease();
		out.holder = l.holderId;
		out.holderName = name(l.holderId);
		out.state = l.state;
		out.progress = l.progress;
		out.expires = Instant.parse(l.expiresAt).toEpochMilli();
		return out;
	}

	private static String leaseBody(String state, float progress) {
		JsonObject body = new JsonObject();
		body.addProperty("state", state);
		body.addProperty("progress", progress);
		return body.toString();
	}

	@Override
	public Lease takeLease(String world, String player, String state) throws IOException {
		return lease(Io.GSON.fromJson(api.call("POST", "/worlds/" + world + "/lease", leaseBody(state, 0)), ServerLease.class));
	}

	/** Served from the last worlds list when it's fresh, so a list of worlds isn't one request per world. */
	@Override
	public Lease lease(String world) throws IOException {
		ServerWorld w = System.currentTimeMillis() - lastWorldsAt < 2000 ? lastWorlds.get(world) : null;
		if (w == null) w = fetch(world);
		return lease(w.lease);
	}

	@Override
	public boolean heartbeat(String world, String player, String state, float progress) throws IOException {
		try {
			api.call("POST", "/worlds/" + world + "/lease/heartbeat", leaseBody(state, progress));
			return true;
		} catch (KeplerSyncApi.Refused e) {
			if (e.status == 409) return false;
			throw e;
		}
	}

	@Override
	public void releaseLease(String world, String player) throws IOException {
		api.call("DELETE", "/worlds/" + world + "/lease", null);
	}

	@Override
	public void uploadBlobs(String world, Map<String, Long> sizes, BlobSource source, Progress progress) throws IOException {
		long total = 0;
		for (long size : sizes.values()) total += size;
		progress.total(total);
		List<String> all = new ArrayList<>(sizes.keySet());
		for (int from = 0, to; from < all.size(); from = to) {
			long bytes = sizes.get(all.get(from));
			for (to = from + 1; to < all.size() && to - from < BATCH && bytes + sizes.get(all.get(to)) <= BATCH_BYTES; to++) bytes += sizes.get(all.get(to));
			List<String> batch = all.subList(from, to);
			JsonArray files = new JsonArray();
			for (String sha : batch) {
				JsonObject f = new JsonObject();
				f.addProperty("sha256", sha);
				f.addProperty("sizeBytes", sizes.get(sha));
				files.add(f);
			}
			JsonObject check = new JsonObject();
			check.add("files", files);
			JsonObject answer = Io.GSON.fromJson(api.call("POST", "/worlds/" + world + "/blobs/check", check.toString()), JsonObject.class);
			List<Ticket> tickets = Io.GSON.fromJson(answer.get("tickets"), new TypeToken<List<Ticket>>() {}.getType());
			final Map<Ticket, String> send = new LinkedHashMap<>();
			for (int i = 0; i < batch.size(); i++) {
				String sha = batch.get(i);
				Ticket t = tickets.get(i);
				if (t.alreadyExists || t.uploadId == null) {
					// Already in this world: nothing to send, so it no longer counts towards the total.
					total -= sizes.get(sha);
					progress.total(total);
					continue;
				}
				send.put(t, sha);
			}
			Io.parallel(Io.TRANSFERS, send.keySet(), t -> put(t.url, source.path(send.get(t)), progress));
			JsonArray done = new JsonArray();
			for (Ticket t : send.keySet()) done.add(t.uploadId);
			if (done.size() > 0) {
				JsonObject complete = new JsonObject();
				complete.add("uploadIds", done);
				api.call("POST", "/worlds/" + world + "/blobs/complete", complete.toString());
			}
		}
	}

	/** One file to storage on its presigned link, streamed. */
	private static void put(String url, Path file, Progress progress) throws IOException {
		HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
		c.setRequestMethod("PUT");
		c.setDoOutput(true);
		c.setConnectTimeout(10_000);
		c.setReadTimeout(60_000);
		c.setFixedLengthStreamingMode(Files.size(file));
		byte[] buf = new byte[1 << 16];
		try (InputStream in = Files.newInputStream(file); OutputStream out = c.getOutputStream()) {
			int n;
			while ((n = in.read(buf)) != -1) {
				out.write(buf, 0, n);
				progress.add(n);
				progress.check();
			}
		}
		int status = c.getResponseCode();
		if (status >= 300) throw new IOException("Storage refused an upload (" + status + ")");
	}

	@Override
	public void prepareDownloads(String world, Collection<String> shas) throws IOException {
		List<String> all = new ArrayList<>(shas);
		for (int from = 0; from < all.size(); from += BATCH) {
			JsonObject body = new JsonObject();
			body.add("sha256", Io.GSON.toJsonTree(all.subList(from, Math.min(all.size(), from + BATCH))));
			JsonObject answer = Io.GSON.fromJson(api.call("POST", "/worlds/" + world + "/file-links", body.toString()), JsonObject.class);
			// A little before they really run out, so a slow download never starts on a dead link.
			long until = System.currentTimeMillis() + (answer.get("expiresInSecs").getAsLong() - 30) * 1000;
			for (Map.Entry<String, com.google.gson.JsonElement> e : answer.getAsJsonObject("links").entrySet()) {
				links.put(world + "/" + e.getKey(), new Link(e.getValue().getAsString(), until));
			}
		}
	}

	@Override
	public InputStream openBlob(String world, String sha) throws IOException {
		Link link = links.get(world + "/" + sha);
		if (link == null || link.until < System.currentTimeMillis()) {
			prepareDownloads(world, Collections.singletonList(sha));
			link = links.get(world + "/" + sha);
			if (link == null) throw new IOException("This world doesn't have that file any more");
		}
		HttpURLConnection c = (HttpURLConnection) new URL(link.url).openConnection();
		c.setConnectTimeout(10_000);
		c.setReadTimeout(60_000);
		int status = c.getResponseCode();
		// Used, or no good: either way it goes, so the links don't pile up all session. A damaged try asks again.
		links.remove(world + "/" + sha);
		if (status >= 300) throw new IOException("Storage refused a download (" + status + ")");
		final HttpURLConnection conn = c;
		return new FilterInputStream(c.getInputStream()) {
			@Override
			public void close() throws IOException {
				super.close();
				conn.disconnect();
			}
		};
	}

	@Override
	public Committed commit(String world, Snapshot snapshot) throws IOException {
		JsonObject manifest = new JsonObject();
		for (Map.Entry<String, Manifest.Entry> e : snapshot.files.entrySet()) {
			JsonObject f = new JsonObject();
			f.addProperty("sha256", e.getValue().sha256);
			f.addProperty("sizeBytes", e.getValue().size);
			manifest.add(e.getKey(), f);
		}
		JsonObject body = new JsonObject();
		body.addProperty("parent", snapshot.parent);
		body.addProperty("kind", snapshot.kind);
		body.add("manifest", manifest);
		body.add("mods", Io.GSON.toJsonTree(mods.get()));
		// Tandem's own name for it: if this answer is lost and the commit is sent again, the server keeps one.
		body.addProperty("key", snapshot.id);
		JsonObject answer = Io.GSON.fromJson(api.call("POST", "/worlds/" + world + "/snapshots", body.toString()), JsonObject.class);
		String id = answer.getAsJsonObject("snapshot").get("id").getAsString();
		return new Committed(id, answer.get("head").getAsBoolean());
	}

	@Override
	public List<Snapshot> history(String world) throws IOException {
		JsonArray list = Io.GSON.fromJson(api.call("GET", "/worlds/" + world + "/snapshots", null), JsonArray.class);
		List<Snapshot> out = new ArrayList<>();
		// Someone who left the world stays unknown: their people are fetched once, not for each of their saves.
		boolean fetched = false;
		for (com.google.gson.JsonElement el : list) {
			JsonObject s = el.getAsJsonObject();
			Snapshot h = new Snapshot();
			h.id = s.get("id").getAsString();
			h.parent = s.has("parent") && !s.get("parent").isJsonNull() ? s.get("parent").getAsString() : null;
			String author = s.has("authorId") && !s.get("authorId").isJsonNull() ? s.get("authorId").getAsString() : null;
			if (author != null && !names.containsKey(author) && !fetched) {
				fetch(world);
				fetched = true;
			}
			h.authorId = author;
			h.author = name(author);
			h.kind = s.get("kind").getAsString();
			h.time = Instant.parse(s.get("createdAt").getAsString()).toEpochMilli();
			h.totalBytes = s.get("totalBytes").getAsLong();
			out.add(h);
		}
		return out;
	}

	@Override
	public String restore(String world, String id, String player) throws IOException {
		JsonObject body = new JsonObject();
		body.addProperty("snapshotId", id);
		JsonObject done = Io.GSON.fromJson(api.call("POST", "/worlds/" + world + "/restore", body.toString()), JsonObject.class);
		return done.getAsJsonObject("snapshot").get("id").getAsString();
	}

	@Override
	public Snapshot snapshot(String world, String id) throws IOException {
		Snapshot cached = snapshots.get(world);
		if (cached != null && cached.id.equals(id)) return cached;
		JsonObject s = Io.GSON.fromJson(api.call("GET", "/worlds/" + world + "/snapshots/" + id, null), JsonObject.class);
		Snapshot out = new Snapshot();
		out.id = s.get("id").getAsString();
		out.parent = s.has("parent") && !s.get("parent").isJsonNull() ? s.get("parent").getAsString() : null;
		String author = s.has("authorId") && !s.get("authorId").isJsonNull() ? s.get("authorId").getAsString() : null;
		if (author != null && !names.containsKey(author)) fetch(world);
		out.authorId = author;
		out.author = name(author);
		out.kind = s.get("kind").getAsString();
		out.time = Instant.parse(s.get("createdAt").getAsString()).toEpochMilli();
		for (Map.Entry<String, com.google.gson.JsonElement> e : s.getAsJsonObject("manifest").entrySet()) {
			JsonObject f = e.getValue().getAsJsonObject();
			out.files.put(e.getKey(), new Manifest.Entry(f.get("sizeBytes").getAsLong(), f.get("sha256").getAsString()));
		}
		snapshots.put(world, out);
		return out;
	}
}
