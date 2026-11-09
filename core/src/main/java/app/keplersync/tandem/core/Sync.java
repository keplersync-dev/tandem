package app.keplersync.tandem.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Moving a world between this PC and the cloud. The world on disk is only ever replaced by a complete, verified copy
 * ({@link WorldSwap}); everything downloaded or saved goes through the {@link BlobCache} first, so an interrupted
 * transfer resumes where it stopped.
 */
public final class Sync {
	/** Free space on the drive holding [dir]; tests replace it. */
	public interface FreeSpace {
		long usable(Path dir) throws IOException;
	}

	/** Not enough free disk space to get a world. Nothing was touched. */
	public static final class NotEnoughSpace extends IOException {
		public final long needed;
		public final long free;

		NotEnoughSpace(long needed, long free) {
			super("Needs " + needed + " bytes free, has " + free);
			this.needed = needed;
			this.free = free;
		}
	}

	/** A file kept arriving damaged. */
	public static final class Damaged extends IOException {
		Damaged(String sha) {
			super("Download failed, the file was damaged: " + sha);
		}
	}

	static final int TRIES = 3;

	private final Cloud cloud;
	private final BlobCache cache;
	private final String player;
	private final FreeSpace freeSpace;

	public Sync(Cloud cloud, BlobCache cache, String player) {
		this(cloud, cache, player, dir -> Files.getFileStore(dir).getUsableSpace());
	}

	Sync(Cloud cloud, BlobCache cache, String player, FreeSpace freeSpace) {
		this.cloud = cloud;
		this.cache = cache;
		this.player = player;
		this.freeSpace = freeSpace;
	}

	/**
	 * Reads the world as it is on disk. Files whose size and modified time match the last scan keep their known hash;
	 * the rest are hashed, and with [copy] also copied into the cache (a checkpoint). Updates [state]'s file stats.
	 */
	Manifest scan(final Path world, final LocalState state, final boolean copy, final Progress progress) throws IOException {
		final Map<String, Manifest.Entry> files = new HashMap<>();
		final Map<String, LocalState.Stat> stats = new HashMap<>();
		if (!Files.isDirectory(world)) return new Manifest(files);
		final Ignores ignores = new Ignores(world.getFileSystem(), state.ignores);
		Files.walkFileTree(world, new SimpleFileVisitor<Path>() {
			@Override
			public FileVisitResult visitFile(final Path file, BasicFileAttributes attrs) throws IOException {
				Path rel = world.relativize(file);
				if (ignores.matches(rel)) return FileVisitResult.CONTINUE;
				String key = WorldScanner.key(rel);
				if (!portable(key)) {
					skipped.accept(world.getFileName() + "/" + key);
					return FileVisitResult.CONTINUE;
				}
				long size = attrs.size();
				long modified = attrs.lastModifiedTime().toMillis();
				LocalState.Stat old = state.stats.get(key);
				String sha;
				if (old != null && old.size == size && old.modified == modified && (!copy || cache.has(old.sha256))) {
					sha = old.sha256;
				} else if (copy) {
					sha = cache.add(file, key, progress);
					// What was copied is what counts: a file the game was still finishing can have grown since.
					size = Files.size(cache.path(sha));
				} else {
					sha = Io.retryLocked(key, () -> WorldScanner.sha256(file));
				}
				files.put(key, new Manifest.Entry(size, sha));
				stats.put(key, new LocalState.Stat(size, modified, sha));
				return FileVisitResult.CONTINUE;
			}
		});
		// Names only a case-sensitive drive (Linux) tells apart are one file on Windows and macOS: the first stays.
		java.util.Set<String> lower = new java.util.HashSet<>();
		for (String key : new java.util.TreeSet<>(files.keySet())) {
			if (lower.add(key.toLowerCase(Locale.ROOT))) continue;
			files.remove(key);
			stats.remove(key);
			skipped.accept(world.getFileName() + "/" + key);
		}
		state.stats = stats;
		return new Manifest(files);
	}

	/** Told about each file left out of a world because Windows, macOS or Linux couldn't hold its name. */
	public static volatile java.util.function.Consumer<String> skipped = name -> {};

	/**
	 * Whether [key] can be a file on all three systems Tandem runs on, the rule KeplerSync also keeps: no {@code :} or
	 * {@code \ * ? " < > |}, no control characters, no name ending in a dot or space, no device name like CON.
	 */
	static boolean portable(String key) {
		for (int i = 0; i < key.length(); i++) {
			char c = key.charAt(i);
			if (Character.getType(c) == Character.CONTROL || "\\:*?\"<>|".indexOf(c) >= 0) return false;
		}
		for (String part : key.split("/", -1)) {
			if (part.isEmpty() || part.equals(".") || part.equals("..") || part.endsWith(".") || part.endsWith(" ")) return false;
			if (part.split("\\.", -1)[0].toUpperCase(Locale.ROOT).matches("CON|PRN|AUX|NUL|(COM|LPT)[0-9]")) return false;
		}
		return true;
	}

	/**
	 * Saves the world as it is into the cache and records it as the session not uploaded yet. Call it while nothing
	 * writes to the world (the game paused after a flush save, or closed). Returns null if nothing changed.
	 */
	public Snapshot checkpoint(Path world, String kind) throws IOException {
		synchronized (lock(world)) {
			LocalState st = LocalState.load(world);
			Manifest now = scan(world, st, true, null);
			Snapshot last = st.pending != null ? st.pending : st.base;
			if (last != null && last.manifest().diffTo(now).isEmpty()) {
				st.save(world);
				return null;
			}
			st.pending = new Snapshot(st.base != null ? st.base.id : null, player, kind, now);
			st.save(world);
			return st.pending;
		}
	}

	/**
	 * One lock per world for its notes: a checkpoint on the game's thread and an upload finishing on the sync thread both
	 * rewrite them, and without it one undoes the other.
	 */
	private static final Map<Path, Object> LOCKS = new java.util.concurrent.ConcurrentHashMap<>();

	private static Object lock(Path world) {
		return LOCKS.computeIfAbsent(world.toAbsolutePath().normalize(), k -> new Object());
	}

	/**
	 * Uploads the session not sent yet: the blobs the cloud doesn't have, then the snapshot. Returns false if another
	 * session got there first (both are kept). True when there was nothing to send.
	 */
	public boolean upload(Path world, Progress progress) throws IOException {
		LocalState st = LocalState.load(world);
		Snapshot s = st.pending;
		if (s == null) return true;
		Map<String, Long> sizes = new LinkedHashMap<>();
		for (Manifest.Entry e : s.files.values()) sizes.put(e.sha256, e.size);
		cloud.uploadBlobs(st.worldId, sizes, cache::path, progress);
		String key = s.id;
		Cloud.Committed done = cloud.commit(st.worldId, s);
		synchronized (lock(world)) {
			// Read again: a checkpoint may have been saved while this uploaded. It stays, built on what was just sent.
			LocalState now = LocalState.load(world);
			if (!st.worldId.equals(now.worldId)) return done.head;
			// The cloud names snapshots itself; the next session is built on that name.
			s.id = done.id;
			now.base = s;
			if (now.pending == null || key.equals(now.pending.id)) now.pending = null;
			else now.pending.parent = done.id;
			now.save(world);
		}
		return done.head;
	}

	/**
	 * Turns sync on for a world on this PC and uploads it. The caller holds the new world's lease afterwards. The request
	 * is noted on this PC first, so a game closed part way, or an answer that never came, finishes the same world rather
	 * than making a second; {@link #resumeFirstUpload} does that.
	 */
	public Cloud.World firstUpload(Path world, String name, String mcVersion, List<String> mods, List<String> sharedWith, Progress progress)
			throws IOException {
		Cloud.World w = create(world, name, mcVersion, mods, sharedWith);
		checkpoint(world, Snapshot.IMPORT);
		upload(world, progress);
		return w;
	}

	/**
	 * The cloud world for Sync this world, asked for with a key noted on this PC before asking. The copy comes after; the
	 * world isn't on its way until its notes name it.
	 */
	public Cloud.World create(Path world, String name, String mcVersion, List<String> mods, List<String> sharedWith) throws IOException {
		LocalState.Creating c;
		synchronized (lock(world)) {
			LocalState st = LocalState.load(world);
			if (st.creating == null) {
				st.creating = new LocalState.Creating(java.util.UUID.randomUUID().toString(), name, sharedWith);
				st.save(world);
			}
			c = st.creating;
		}
		Cloud.World w = cloud.createWorld(c.key, c.name, mcVersion, mods, player, c.sharedWith);
		synchronized (lock(world)) {
			LocalState st = LocalState.load(world);
			st.worldId = w.id;
			st.creating = null;
			st.base = null;
			st.pending = null;
			st.save(world);
		}
		return w;
	}

	/** A Sync this world the game closed on before its world was made: the same request again, then the upload. */
	public Cloud.World resumeFirstUpload(Path world, String mcVersion, List<String> mods, Progress progress) throws IOException {
		LocalState.Creating c = LocalState.load(world).creating;
		if (c == null) throw new IOException("Nothing to resume for " + world.getFileName());
		return firstUpload(world, c.name, mcVersion, mods, c.sharedWith, progress);
	}

	/**
	 * What changed on disk since the copy came from the cloud, or was last saved by Tandem: someone opened it outside
	 * Tandem, or the game crashed before its session was saved. Null for a world never synced here.
	 */
	public Manifest.Diff localChanges(Path world) throws IOException {
		synchronized (lock(world)) {
			LocalState st = LocalState.load(world);
			Snapshot last = st.pending != null ? st.pending : st.base;
			if (last == null) return null;
			Manifest now = scan(world, st, false, null);
			st.save(world);
			return last.manifest().diffTo(now);
		}
	}

	/**
	 * Gets the cloud's latest copy of [worldId] into [world]: checks free space, downloads only blobs this PC doesn't
	 * have (checking each one's hash), builds the whole world in staging, then swaps it in. Local changes are replaced,
	 * so ask first ({@link #localChanges}). Cancelling keeps every finished blob for the next try.
	 */
	public void getLatest(final Path world, String worldId, Progress progress) throws IOException {
		LocalState st = LocalState.load(world);
		Cloud.World w = cloud.world(worldId);
		if (w.head == null) throw new IOException(w.name + " hasn't finished its first upload");
		Snapshot head = cloud.snapshot(worldId, w.head);
		st.worldId = worldId;
		Manifest local = scan(world, st, false, null);
		Manifest target = head.manifest();
		check(target, WorldSwap.stageDir(world));
		Manifest.Diff diff = local.diffTo(target);
		if (diff.isEmpty()) {
			st.base = head;
			st.pending = null;
			st.save(world);
			return;
		}

		// What the world already has (under any name) is linked from it, not downloaded or copied.
		final Map<String, String> onDisk = new HashMap<>();
		for (Map.Entry<String, Manifest.Entry> e : local.files().entrySet()) onDisk.put(e.getValue().sha256, e.getKey());

		// The download, then a staged copy of the changed files beside the old ones.
		long needed = diff.bytesToFetch * 2;
		Files.createDirectories(world.getParent());
		long free = freeSpace.usable(world.getParent());
		if (free < needed) throw new NotEnoughSpace(needed, free);

		Map<String, Long> missing = new LinkedHashMap<>();
		for (Manifest.Entry e : target.files().values()) {
			if (!cache.has(e.sha256) && !onDisk.containsKey(e.sha256)) missing.put(e.sha256, e.size);
		}
		long total = 0;
		for (long size : missing.values()) total += size;
		progress.total(total);
		fetchAll(worldId, missing, progress);

		final Path stage = WorldSwap.stageDir(world);
		if (Files.exists(stage)) WorldSwap.deleteTree(stage);
		Map<String, LocalState.Stat> stats = new HashMap<>();
		for (Map.Entry<String, Manifest.Entry> e : target.files().entrySet()) {
			progress.check();
			Path to = stage.resolve(e.getKey());
			Files.createDirectories(to.getParent());
			String sha = e.getValue().sha256;
			String same = onDisk.get(sha);
			if (same == null || !link(world.resolve(same), to)) Files.copy(cache.path(sha), to);
			stats.put(e.getKey(), new LocalState.Stat(e.getValue().size, Files.getLastModifiedTime(to).toMillis(), sha));
		}
		keepIgnored(world, stage, st);
		Files.write(stage.resolve(LocalState.MARKER), worldId.getBytes(java.nio.charset.StandardCharsets.UTF_8));

		// The notes that go with the new copy take effect with the swap itself, so a crash between the two can't leave
		// the new world described as the old one (which would read as local changes).
		st.stats = stats;
		st.base = head;
		st.pending = null;
		st.saveNext(world);
		WorldSwap.swap(world);
		st.save(world);
	}

	/**
	 * A hard link from [to] to the world's own [file]: the new copy and the old world share it until the swap deletes
	 * the old one. The game isn't in the world while this runs, so neither name is written to meanwhile. False where
	 * the drive can't link (FAT32, exFAT), and the caller copies instead.
	 */
	private static boolean link(Path file, Path to) {
		try {
			Files.createLink(to, file);
			return true;
		} catch (IOException | UnsupportedOperationException e) {
			return false;
		}
	}

	/**
	 * Writes snapshot [snapshotId] of [worldId] as a zip at [zip], the world in a folder called [folder]: a plain
	 * world for any PC or server. Files this PC doesn't have yet are downloaded first. Works over quota: it only
	 * reads.
	 */
	public void exportZip(String worldId, String snapshotId, String folder, Path zip, Progress progress) throws IOException {
		Snapshot snap = cloud.snapshot(worldId, snapshotId);
		check(snap.manifest(), zip.resolveSibling(folder));
		Map<String, Long> missing = new LinkedHashMap<>();
		long total = 0;
		for (Manifest.Entry e : snap.files.values()) {
			total += e.size;
			if (!cache.has(e.sha256)) missing.put(e.sha256, e.size);
		}
		// Downloading counts once, writing the zip once more.
		long fetching = 0;
		for (long size : missing.values()) fetching += size;
		progress.total(fetching + total);
		fetchAll(worldId, missing, progress);
		Path part = zip.resolveSibling(zip.getFileName() + ".part");
		Files.createDirectories(zip.getParent());
		try (java.util.zip.ZipOutputStream out = new java.util.zip.ZipOutputStream(Files.newOutputStream(part))) {
			for (Map.Entry<String, Manifest.Entry> e : snap.files.entrySet()) {
				progress.check();
				out.putNextEntry(new java.util.zip.ZipEntry(folder + "/" + e.getKey()));
				Files.copy(cache.path(e.getValue().sha256), out);
				out.closeEntry();
				progress.add(e.getValue().size);
			}
		} catch (IOException | RuntimeException e) {
			Files.deleteIfExists(part);
			throw e;
		}
		Files.move(part, zip, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
	}

	/**
	 * A manifest from the cloud, checked before anything is fetched or written. Any member of a world writes its
	 * manifests, so every name must stay inside [dir] (no absolute path, drive letter or {@code ..}) and every hash be one.
	 */
	static void check(Manifest m, Path dir) throws IOException {
		Path base = dir.toAbsolutePath().normalize();
		for (Map.Entry<String, Manifest.Entry> e : m.files().entrySet()) {
			Path to = null;
			try {
				to = base.resolve(e.getKey()).normalize();
			} catch (InvalidPathException ignored) {
				// Not a name a file on this PC can have.
			}
			if (to == null || to.equals(base) || !to.startsWith(base) || !e.getValue().sha256.matches("[0-9a-f]{64}")) {
				throw new IOException(e.getKey() + " isn't a file inside a world");
			}
		}
	}

	/** Downloads [missing] into the cache, a few at a time. */
	private void fetchAll(final String worldId, Map<String, Long> missing, final Progress progress) throws IOException {
		cloud.prepareDownloads(worldId, missing.keySet());
		Io.parallel(Io.TRANSFERS, missing.keySet(), sha -> fetch(worldId, sha, progress));
	}

	/** Downloads one blob into the cache, refetching a damaged one up to three times in all. */
	private void fetch(String worldId, String sha, Progress progress) throws IOException {
		for (int i = 0; i < TRIES; i++) {
			// Counted apart from the other downloads running beside it, so a damaged try takes back only its own bytes.
			Progress mine = new Progress();
			try (InputStream in = cloud.openBlob(worldId, sha)) {
				boolean ok = cache.addVerified(new Counted(in, progress, mine), sha, null);
				if (ok) return;
			}
			progress.add(-mine.done());
		}
		throw new Damaged(sha);
	}

	/** Passes each read on to [progress] and [mine], and checks for Cancel. */
	private static final class Counted extends java.io.FilterInputStream {
		private final Progress progress;
		private final Progress mine;

		Counted(InputStream in, Progress progress, Progress mine) {
			super(in);
			this.progress = progress;
			this.mine = mine;
		}

		@Override
		public int read() throws IOException {
			int b = super.read();
			if (b >= 0) count(1);
			return b;
		}

		@Override
		public int read(byte[] buf, int off, int len) throws IOException {
			int n = super.read(buf, off, len);
			if (n > 0) count(n);
			return n;
		}

		private void count(int n) {
			progress.check();
			progress.add(n);
			mine.add(n);
		}
	}

	/** Files that never sync (level-of-detail data, gigabytes at times) stay with this PC's copy across a swap. */
	private void keepIgnored(final Path world, final Path stage, LocalState st) throws IOException {
		if (!Files.isDirectory(world)) return;
		final Ignores ignores = new Ignores(world.getFileSystem(), st.ignores);
		Files.walkFileTree(world, new SimpleFileVisitor<Path>() {
			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
				Path rel = world.relativize(file);
				if (ignores.matches(rel) && !rel.toString().equals("session.lock")) {
					Path to = stage.resolve(rel.toString());
					Files.createDirectories(to.getParent());
					if (!link(file, to)) Files.copy(file, to, StandardCopyOption.REPLACE_EXISTING);
				}
				return FileVisitResult.CONTINUE;
			}
		});
	}
}
