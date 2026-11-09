package app.keplersync.tandem.core;

import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/** A world at one moment: every file's hash, the snapshot it was built on, who made it and why. */
public final class Snapshot {
	public static final String IMPORT = "import";
	public static final String CHECKPOINT = "checkpoint";
	public static final String SESSION = "session";
	/** An older snapshot made the latest again (History's Restore, picking a version, the version before a broken one). */
	public static final String RESTORE = "restore";

	public String id;
	/** The snapshot this one was built on; null for a world's first. */
	public String parent;
	/** Who made it, by id, and what they're called on screen (the same, for one made on this PC). */
	public String authorId;
	public String author;
	public String kind;
	public long time;
	public Map<String, Manifest.Entry> files = new TreeMap<>();
	/** In a history list, where [files] isn't fetched: how big the world was. */
	public long totalBytes;

	public Snapshot() {}

	public Snapshot(String parent, String author, String kind, Manifest manifest) {
		this.id = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
		this.parent = parent;
		this.authorId = author;
		this.author = author;
		this.kind = kind;
		this.time = System.currentTimeMillis();
		this.files = new TreeMap<>(manifest.files());
	}

	public Manifest manifest() {
		return new Manifest(files);
	}
}
