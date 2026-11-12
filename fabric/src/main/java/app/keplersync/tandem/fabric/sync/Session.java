package app.keplersync.tandem.fabric.sync;

import app.keplersync.tandem.core.Cloud;
import app.keplersync.tandem.core.KeplerSyncApi;
import app.keplersync.tandem.core.Progress;
import app.keplersync.tandem.core.Snapshot;
import app.keplersync.tandem.fabric.TandemClient;
import app.keplersync.tandem.fabric.account.Account;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import net.minecraft.server.MinecraftServer;

/**
 * Playing a synced world: the lease renewed every 30 seconds, a local checkpoint every 5 minutes (a flush save, then
 * a copy of the changed files while the game waits), and an upload as often as KeplerSync says: every 5 minutes while
 * friends are in the game (a friend who takes over then starts from at most 5 minutes ago), every 15 alone.
 */
public final class Session {
	// Shorter in dev runs with -Dtandem.checkpointSeconds, so a test doesn't wait five minutes.
	static final long CHECKPOINT_MS = Long.getLong("tandem.checkpointSeconds", 300) * 1000;
	// The same for uploads; set, it wins over what KeplerSync says.
	static final Long UPLOAD_SECONDS = Long.getLong("tandem.uploadSeconds");

	private final WorldSync sync;
	public final MinecraftServer server;
	final Path dir;
	public final String worldId;
	private final ScheduledFuture<?> heartbeat;
	private long lastCheckpoint = System.currentTimeMillis();
	private volatile long lastUpload = System.currentTimeMillis();
	private volatile long sharedMs = 5 * 60_000L, aloneMs = 15 * 60_000L;
	/** When the copy on disk last matched the cloud: the session started from the latest copy. */
	public volatile long savedAt = System.currentTimeMillis();
	/** Someone else holds the lease now: this session will be kept beside theirs. */
	public volatile boolean leaseLost;

	Session(WorldSync sync, MinecraftServer server, Path dir, String worldId) {
		this.sync = sync;
		this.server = server;
		this.dir = dir;
		this.worldId = worldId;
		String player = sync.player();
		if (UPLOAD_SECONDS != null) {
			sharedMs = aloneMs = UPLOAD_SECONDS * 1000;
		} else {
			Account account = Account.get();
			if (account != null) sync.async(() -> {
				KeplerSyncApi.Limits l = account.api.limits();
				sharedMs = l.uploadMinutesShared * 60_000L;
				aloneMs = l.uploadMinutesAlone * 60_000L;
				return null;
			});
		}
		heartbeat = sync.timer.scheduleAtFixedRate(() -> {
			try {
				leaseLost = !sync.cloud.heartbeat(worldId, player, Cloud.HOSTING, 0);
				if (sync.offline) {
					sync.offline = false;
					TandemClient.LOG.info("KeplerSync is back; uploading what {} saved meanwhile", dir.getFileName());
					lastUpload = 0;
				}
			} catch (IOException e) {
				// Play goes on: checkpoints stay on this PC and go up once KeplerSync answers again.
				if (WorldSync.unreachable(e) && !sync.offline) TandemClient.LOG.info("KeplerSync can't be reached; {} keeps saving on this PC", dir.getFileName());
				sync.offline = WorldSync.unreachable(e) || sync.offline;
			}
		}, 30, 30, TimeUnit.SECONDS);
	}

	public long nextUploadAt() {
		return lastUpload + uploadEvery();
	}

	/** More often while anyone else is in the game. */
	private long uploadEvery() {
		return server.getPlayerCount() > 1 ? sharedMs : aloneMs;
	}

	/** On the server thread, after every tick. */
	void tick() {
		long now = System.currentTimeMillis();
		if (now - lastCheckpoint >= CHECKPOINT_MS) {
			lastCheckpoint = now;
			checkpoint();
		}
		if (now - lastUpload >= uploadEvery() && !sync.offline) {
			lastUpload = now;
			sync.async(() -> {
				sync.sendPaced(dir, new Progress());
				savedAt = System.currentTimeMillis();
				TandemClient.LOG.info("Tandem uploaded a checkpoint of {}", dir.getFileName());
				return null;
			});
		}
	}

	/** Runs on the server thread, so the game waits while the changed files are copied. */
	private void checkpoint() {
		long start = System.nanoTime();
		server.saveEverything(true, true, true);
		long saved = System.nanoTime();
		try {
			Snapshot s = sync.sync().checkpoint(dir, Snapshot.CHECKPOINT);
			long end = System.nanoTime();
			TandemClient.LOG.info("Tandem checkpoint of {}: {}; save {} ms, copy {} ms", dir.getFileName(),
					s == null ? "nothing changed" : s.files.size() + " files", (saved - start) / 1_000_000, (end - saved) / 1_000_000);
		} catch (IOException e) {
			TandemClient.LOG.warn("Tandem checkpoint of {} failed", dir.getFileName(), e);
		}
	}

	void stop() {
		heartbeat.cancel(false);
	}
}
