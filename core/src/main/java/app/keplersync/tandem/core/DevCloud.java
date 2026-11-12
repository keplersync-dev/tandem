package app.keplersync.tandem.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * A folder standing in for KeplerSync while there's no server: {@code blobs/}, and per world {@code world.json},
 * {@code lease.json} and {@code snapshots/}. Two games on one PC can share it; every change happens under one lock
 * file, so only one of them can win a lease. Blobs are stored as they are; the server compresses them.
 */
public class DevCloud implements Cloud {
	static final long LEASE_MS = 90_000;
	/** A session still uploading keeps its lease a day, so friends see why they're waiting. */
	static final long UPLOADING_MS = 24 * 60 * 60 * 1000L;

	// A file lock is per process, so games sharing one JVM (the tests) also take this one.
	private static final Map<Path, Object> LOCKS = new ConcurrentHashMap<>();

	private final Path root;
	private final BlobCache blobs;
	private final LongSupplier clock;
	/** What this game says about itself, kept with each world and save as KeplerSync does; null keeps none. */
	private volatile java.util.function.Supplier<com.google.gson.JsonObject> game = () -> null;

	public DevCloud(Path root) {
		this(root, System::currentTimeMillis);
	}

	DevCloud(Path root, LongSupplier clock) {
		this.root = root.toAbsolutePath().normalize();
		this.blobs = new BlobCache(this.root.resolve("blobs"));
		this.clock = clock;
	}

	public DevCloud game(java.util.function.Supplier<com.google.gson.JsonObject> game) {
		this.game = game;
		return this;
	}

	public Path root() {
		return root;
	}

	private <T> T locked(Io.Action<T> action) throws IOException {
		synchronized (LOCKS.computeIfAbsent(root, k -> new Object())) {
			Files.createDirectories(root);
			try (FileChannel ch = FileChannel.open(root.resolve(".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
					FileLock ignored = ch.lock()) {
				return action.run();
			}
		}
	}

	private Path dir(String world) {
		return root.resolve("worlds").resolve(world);
	}

	private World read(String world) throws IOException {
		World w = Io.readJson(dir(world).resolve("world.json"), World.class);
		if (w == null) throw new IOException("No synced world " + world);
		return named(w);
	}

	/** Players' names are their ids here, so each one's name on screen is itself. */
	private static World named(World w) {
		if (w.createdBy != null) w.names.put(w.createdBy, w.createdBy);
		for (String p : w.sharedWith) w.names.put(p, p);
		return w;
	}

	private void write(World w) throws IOException {
		Io.writeJson(dir(w.id).resolve("world.json"), w);
	}

	private Lease live(String world) throws IOException {
		Lease l = Io.readJson(dir(world).resolve("lease.json"), Lease.class);
		if (l == null || l.expires < clock.getAsLong()) return null;
		l.holderName = l.holder;
		return l;
	}

	private void setLease(String world, String player, String state, float progress) throws IOException {
		Lease l = new Lease();
		l.holder = player;
		l.state = state;
		l.progress = progress;
		l.expires = clock.getAsLong() + (UPLOADING.equals(state) ? UPLOADING_MS : LEASE_MS);
		Io.writeJson(dir(world).resolve("lease.json"), l);
	}

	@Override
	public List<World> worlds(final String player) throws IOException {
		return locked(() -> {
			List<World> out = new ArrayList<>();
			Path all = root.resolve("worlds");
			if (!Files.isDirectory(all)) return out;
			try (DirectoryStream<Path> ds = Files.newDirectoryStream(all)) {
				for (Path d : ds) {
					World w = Io.readJson(d.resolve("world.json"), World.class);
					if (w != null && (player.equals(w.createdBy) || w.sharedWith.contains(player))) out.add(named(w));
				}
			}
			return out;
		});
	}

	@Override
	public World world(final String id) throws IOException {
		return locked(() -> read(id));
	}

	@Override
	public World createWorld(final String key, final String name, final String mcVersion, final List<String> mods, final String player,
			final List<String> sharedWith) throws IOException {
		return locked(() -> {
			// The same request again (its answer was lost, or the game closed): the world it made then.
			Path made = root.resolve("keys").resolve(key.replaceAll("[^A-Za-z0-9_-]", "_"));
			if (Files.exists(made)) return read(new String(Files.readAllBytes(made), java.nio.charset.StandardCharsets.UTF_8));
			World w = new World();
			String slug = name.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
			w.id = (slug.isEmpty() ? "world" : slug) + "-" + UUID.randomUUID().toString().substring(0, 6);
			w.name = name;
			w.mcVersion = mcVersion;
			w.mods = new ArrayList<>(mods);
			w.game = game.get();
			w.createdBy = player;
			w.created = clock.getAsLong();
			w.sharedWith = new ArrayList<>(sharedWith);
			write(w);
			setLease(w.id, player, UPLOADING, 0);
			Files.createDirectories(made.getParent());
			Files.write(made, w.id.getBytes(java.nio.charset.StandardCharsets.UTF_8));
			return named(w);
		});
	}

	@Override
	public Lease takeLease(final String world, final String player, final String state) throws IOException {
		return locked(() -> {
			read(world);
			Lease l = live(world);
			if (l == null || l.heldBy(player)) {
				setLease(world, player, state, 0);
				l = live(world);
			}
			return l;
		});
	}

	@Override
	public Lease lease(final String world) throws IOException {
		return locked(() -> live(world));
	}

	@Override
	public boolean heartbeat(final String world, final String player, final String state, final float progress) throws IOException {
		return locked(() -> {
			Lease l = live(world);
			if (l != null && !l.heldBy(player)) return false;
			setLease(world, player, state, progress);
			return true;
		});
	}

	@Override
	public void releaseLease(final String world, final String player) throws IOException {
		locked(() -> {
			Lease l = live(world);
			if (l != null && l.heldBy(player)) Files.delete(dir(world).resolve("lease.json"));
			return null;
		});
	}

	@Override
	public void uploadBlobs(String world, Map<String, Long> sizes, BlobSource source, Progress progress) throws IOException {
		Set<String> missing = new LinkedHashSet<>();
		long total = 0;
		for (Map.Entry<String, Long> e : sizes.entrySet()) {
			if (!blobs.has(e.getKey())) {
				missing.add(e.getKey());
				total += e.getValue();
			}
		}
		progress.total(total);
		for (String sha : missing) {
			progress.check();
			putBlob(sha, source.path(sha));
			progress.add(sizes.get(sha));
		}
	}

	/** Stores one blob as it is. Tests use it directly. */
	public void putBlob(String sha, Path file) throws IOException {
		String got = blobs.add(file, sha, null);
		if (!got.equals(sha)) throw new IOException("Blob " + sha + " arrived as " + got);
	}

	@Override
	public InputStream openBlob(String world, String sha) throws IOException {
		return Files.newInputStream(blobs.path(sha));
	}

	@Override
	public Committed commit(final String world, final Snapshot snapshot) throws IOException {
		return locked(() -> {
			for (Manifest.Entry e : snapshot.files.values()) {
				if (!blobs.has(e.sha256)) throw new IOException("Snapshot " + snapshot.id + " needs blob " + e.sha256 + ", which isn't uploaded");
			}
			World w = read(world);
			Path file = dir(world).resolve("snapshots").resolve(snapshot.id + ".json");
			// Sent again after its answer was lost: already saved.
			if (Files.exists(file)) return new Committed(snapshot.id, snapshot.id.equals(w.head));
			Io.writeJson(file, snapshot);
			boolean clean = Objects.equals(snapshot.parent, w.head);
			// Until someone picks, the newest session is the head and the one it raced is kept beside it.
			if (!clean) w.branches.add(w.head);
			w.head = snapshot.id;
			com.google.gson.JsonObject g = game.get();
			if (g != null) w.game = g;
			write(w);
			return new Committed(snapshot.id, clean);
		});
	}

	@Override
	public Snapshot snapshot(String world, String id) throws IOException {
		Snapshot s = Io.readJson(dir(world).resolve("snapshots").resolve(id + ".json"), Snapshot.class);
		if (s == null) throw new IOException("No snapshot " + id + " of " + world);
		return s;
	}
}
