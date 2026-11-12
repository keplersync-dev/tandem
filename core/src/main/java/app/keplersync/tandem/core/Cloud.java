package app.keplersync.tandem.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/** Where synced worlds live: KeplerSync's server ({@link ServerCloud}), or a dev cloud folder ({@link DevCloud}). */
public interface Cloud {
	String DOWNLOADING = "downloading";
	String HOSTING = "hosting";
	String UPLOADING = "uploading";

	/**
	 * People are ids here, never names: a display name is anyone's to change and can be the same as a friend's. A dev
	 * cloud uses each player's name as their id.
	 */
	final class World {
		public String id;
		public String name;
		public String mcVersion;
		/** The owner's id. */
		public String createdBy;
		public long created;
		/** The current snapshot; null until the first upload is committed. */
		public String head;
		/** {@code id@version} of every mod that isn't client-only. */
		public List<String> mods = new ArrayList<>();
		/** Who can see and host it, besides [createdBy], by id. */
		public List<String> sharedWith = new ArrayList<>();
		/** Snapshots that lost a race with another session: kept, never overwritten. */
		public List<String> branches = new ArrayList<>();
		/** Who are operators in the game, whoever hosts, by id, the owner among them. Empty for a dev cloud. */
		public List<String> operators = new ArrayList<>();
		/** What everyone in it (owner and members) is called on screen, by id; see {@link ServerCloud#label}. */
		public java.util.Map<String, String> names = new java.util.HashMap<>();
		/** Everyone's Minecraft account id, by id, when known. */
		public java.util.Map<String, String> minecraftIds = new java.util.HashMap<>();
		/** "keplersync", or "own": the owner's own R2 bucket. */
		public String storage = "keplersync";
		/** Moving to the other storage: where to, and how far along (bytes). Null when not moving. */
		public String movingTo;
		public long movedBytes;
		public long moveBytes;
		/** The owner's own storage stopped working, in words for whoever reads it. */
		public String storageProblem;
		/** The owner stopped syncing it: it's read-only until it's deleted, unless they resume. */
		public boolean stopped;
		/** It stops once whoever is hosting lets go. */
		public boolean stopWhenFree;
		/** The game it was last saved in, as the saver's Tandem described it; null for worlds saved before that. */
		public com.google.gson.JsonObject game;

		/** [person]'s name on screen. */
		public String nameOf(String person) {
			String n = person == null ? null : names.get(person);
			return n != null ? n : person;
		}
	}

	/** Where a world's file contents are on this PC, by sha256. */
	interface BlobSource {
		Path path(String sha);
	}

	/** A snapshot saved by the cloud: the id it gave it, and whether it became the head. */
	final class Committed {
		public final String id;
		public final boolean head;

		public Committed(String id, boolean head) {
			this.id = id;
			this.head = head;
		}
	}

	/** "Alex is hosting this world": one per world, and it always runs out unless renewed. */
	final class Lease {
		/** Who holds it, by id, and what they're called on screen. */
		public String holder;
		public String holderName;
		public String state;
		public float progress;
		public long expires;

		public boolean heldBy(String player) {
			return player.equals(holder);
		}
	}

	/** Worlds [player] synced or that were shared with them. */
	List<World> worlds(String player) throws IOException;

	World world(String id) throws IOException;

	/**
	 * Creates a world with no snapshot yet; its creator holds the lease, uploading. [key] is this PC's own name for the
	 * request: sent again (a lost answer, a game closed part way), it returns the world already made instead of a second.
	 */
	World createWorld(String key, String name, String mcVersion, List<String> mods, String player, List<String> sharedWith) throws IOException;

	/** Takes the lease if it's free or already [player]'s, and returns whoever holds it now. */
	Lease takeLease(String world, String player, String state) throws IOException;

	/** The live lease, or null when nobody holds it. */
	Lease lease(String world) throws IOException;

	/** Renews [player]'s lease; false if someone else holds it now. A lease that ran out and is still free is retaken. */
	boolean heartbeat(String world, String player, String state, float progress) throws IOException;

	void releaseLease(String world, String player) throws IOException;

	/** Uploads the files (sha256 → size) that [world] doesn't have yet, counting into [progress]. */
	void uploadBlobs(String world, Map<String, Long> sizes, BlobSource source, Progress progress) throws IOException;

	/** Gets ready to download these files of [world]; the server fetches their links in batches. */
	default void prepareDownloads(String world, Collection<String> shas) throws IOException {}

	InputStream openBlob(String world, String sha) throws IOException;

	/**
	 * Stores [snapshot] and makes it the head. When its parent wasn't the head (another session got there first) the
	 * old head is kept as a branch, so both sessions survive, and [Committed#head] is false.
	 */
	Committed commit(String world, Snapshot snapshot) throws IOException;

	Snapshot snapshot(String world, String id) throws IOException;

	/** [world]'s history, newest first: each snapshot without its files, with [Snapshot#totalBytes] set. */
	default List<Snapshot> history(String world) throws IOException {
		throw new IOException("History needs KeplerSync");
	}

	/**
	 * Makes snapshot [id] the latest again: a new snapshot with its contents, on top, so it can be undone. Keeping a split
	 * world's version that's already the latest only settles the split. Returns the head's id afterwards.
	 */
	default String restore(String world, String id, String player) throws IOException {
		throw new IOException("Restoring needs KeplerSync");
	}
}
