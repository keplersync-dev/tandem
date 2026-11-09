package app.keplersync.tandem.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What this PC knows about one synced world, kept in {@code saves/.tandem/<world>/state.json}: which cloud world it
 * is, the snapshot the copy on disk came from, a session not uploaded yet, and each file's size and modified time
 * when it was last hashed, so a checkpoint only reads files that changed.
 */
public final class LocalState {
	public static final class Stat {
		public long size;
		public long modified;
		public String sha256;

		public Stat() {}

		Stat(long size, long modified, String sha256) {
			this.size = size;
			this.modified = modified;
			this.sha256 = sha256;
		}
	}

	/** The cloud world's id; null for a world that isn't synced. */
	public String worldId;
	/** The snapshot the copy on disk was last in step with. */
	public Snapshot base;
	/** Saved on this PC but not uploaded yet: the latest checkpoint, or a session that ended before its upload did. */
	public Snapshot pending;
	public Map<String, Stat> stats = new HashMap<>();
	/** This world's own extra ignore globs, on top of {@link Ignores#DEFAULTS}. */
	public List<String> ignores = new ArrayList<>();
	/** Sync this world asked for and not answered yet: sent again as the same request until the cloud world exists. */
	public Creating creating;

	public static final class Creating {
		/** This PC's own name for the request, so asking again returns the same world. */
		public String key;
		public String name;
		/** Friends to share it with, by id. */
		public List<String> sharedWith = new ArrayList<>();

		public Creating() {}

		Creating(String key, String name, List<String> sharedWith) {
			this.key = key;
			this.name = name;
			this.sharedWith = new ArrayList<>(sharedWith);
		}
	}

	/**
	 * A file inside a synced world naming its cloud world. These notes live outside the world folder, so they outlive a
	 * world deleted in Minecraft: a new world made later in the same folder lacks the file, and isn't taken for it.
	 */
	public static final String MARKER = ".tandem-world";

	public static Path file(Path world) {
		return WorldSwap.workDir(world).resolve("state.json");
	}

	public static LocalState load(Path world) throws IOException {
		LocalState s = Io.readJson(file(world), LocalState.class);
		if (s == null) return new LocalState();
		// The world was deleted (or replaced by a new one): what was noted about it, unsent sessions included, isn't its.
		if (s.worldId != null) {
			if (!Files.isDirectory(world)) return new LocalState();
			String m = marker(world);
			if (m == null ? !s.recognises(world) : !m.equals(s.worldId)) return new LocalState();
		} else if (s.creating != null && (!Files.isDirectory(world) || !s.mark().equals(marker(world)))) {
			return new LocalState();
		}
		return s;
	}

	/** What the marker inside the world says: the cloud world's id, or the request that's making it. */
	private String mark() {
		return worldId != null ? worldId : creating != null ? "creating " + creating.key : null;
	}

	/**
	 * Worlds synced before the marker existed: still this world if a file is exactly as last noted (size and modified
	 * time), which a new world made in the same folder can't be.
	 */
	private boolean recognises(Path world) {
		for (Map.Entry<String, Stat> e : stats.entrySet()) {
			Path f = world.resolve(e.getKey());
			try {
				if (Files.isRegularFile(f) && Files.size(f) == e.getValue().size && Files.getLastModifiedTime(f).toMillis() == e.getValue().modified) return true;
			} catch (IOException ignored) {
				// Not this one.
			}
		}
		return false;
	}

	private static String marker(Path world) {
		Path m = world.resolve(MARKER);
		try {
			return Files.exists(m) ? new String(Files.readAllBytes(m), StandardCharsets.UTF_8).trim() : null;
		} catch (IOException e) {
			return null;
		}
	}

	public void save(Path world) throws IOException {
		Io.writeJson(file(world), this);
		String m = mark();
		if (m != null && Files.isDirectory(world)) Files.write(world.resolve(MARKER), m.getBytes(StandardCharsets.UTF_8));
	}

	/**
	 * Saved to take effect with the next {@link WorldSwap#swap}: what's true of the world once the staged copy is in
	 * place. Dropped if the swap never happens.
	 */
	void saveNext(Path world) throws IOException {
		Io.writeJson(WorldSwap.nextState(world), this);
	}

	/** Tandem lets go of this world: it's a normal world from now on, and its notes and marker go. */
	public static void drop(Path world) throws IOException {
		// The notes first: once they're gone the world is a normal one, whatever else a closed game left behind.
		Files.deleteIfExists(file(world));
		Files.deleteIfExists(world.resolve(MARKER));
		Path work = WorldSwap.workDir(world);
		if (!Files.isDirectory(work)) return;
		try (java.util.stream.Stream<Path> files = Files.walk(work)) {
			for (Path f : (Iterable<Path>) files.sorted(java.util.Comparator.reverseOrder())::iterator) Files.delete(f);
		}
	}

	/** Every file a synced world in [saves] still needs from the cache: its last snapshot's and its unsent session's. */
	public static java.util.Set<String> inUse(Path saves) throws IOException {
		java.util.Set<String> shas = new java.util.HashSet<>();
		Path work = saves.resolve(".tandem");
		if (!Files.isDirectory(work)) return shas;
		try (java.nio.file.DirectoryStream<Path> worlds = Files.newDirectoryStream(work)) {
			for (Path w : worlds) {
				LocalState st = load(saves.resolve(w.getFileName().toString()));
				for (Snapshot s : new Snapshot[] {st.base, st.pending}) {
					if (s != null) for (Manifest.Entry e : s.files.values()) shas.add(e.sha256);
				}
			}
		}
		return shas;
	}

	public boolean synced() {
		return worldId != null;
	}
}
