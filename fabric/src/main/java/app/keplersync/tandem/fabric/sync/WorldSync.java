package app.keplersync.tandem.fabric.sync;

import app.keplersync.tandem.core.BlobCache;
import app.keplersync.tandem.core.Cloud;
import app.keplersync.tandem.core.DevCloud;
import app.keplersync.tandem.core.KeplerSyncApi;
import app.keplersync.tandem.core.ServerCloud;
import app.keplersync.tandem.fabric.account.Account;
import app.keplersync.tandem.fabric.play.Hosting;
import app.keplersync.tandem.fabric.ui.Ingame;
import app.keplersync.tandem.fabric.play.WorldRules;
import net.minecraft.client.server.IntegratedServer;
import app.keplersync.tandem.core.LocalState;
import app.keplersync.tandem.core.Manifest;
import app.keplersync.tandem.core.Progress;
import app.keplersync.tandem.core.Snapshot;
import app.keplersync.tandem.core.Sync;
import app.keplersync.tandem.core.WorldSwap;
import app.keplersync.tandem.fabric.Guard;
import app.keplersync.tandem.fabric.TandemClient;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import net.fabricmc.api.EnvType;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModEnvironment;
import net.minecraft.FileUtil;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Real syncing: through KeplerSync's server when the run signs in ({@code -Dtandem.server=<url>}), or through a dev
 * cloud folder ({@code -Dtandem.devcloud=<folder>}). Without either, Tandem shows sample data only. Transfers run one
 * at a time on a background thread; one that fails because KeplerSync can't be reached tries again by itself.
 */
public final class WorldSync {
	/** A transfer the screens show: the title tile, the pause card, the getting-latest dialog. */
	public static final class Transfer {
		public final String world;
		public final Progress progress = new Progress();
		public final CompletableFuture<Void> future = new CompletableFuture<>();
		/** KeplerSync couldn't be reached, so it waits to try again (Sync this world's first upload). */
		public volatile boolean waiting;

		Transfer(String world) {
			this.world = world;
		}

		public boolean done() {
			return future.isDone();
		}
	}

	private static WorldSync instance;
	/** How long a cache file nobody uses is kept: long enough for a stopped download to resume. */
	private static final long CACHE_GRACE_MS = 24 * 60 * 60 * 1000L;

	public final Cloud cloud;
	/** Through KeplerSync's server rather than a dev cloud folder. */
	public final boolean server;
	/** KeplerSync didn't answer the last heartbeat: play goes on, and uploads wait until it's back. */
	public volatile boolean offline;
	public final BlobCache cache;
	private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> daemon(r, "Tandem sync"));
	final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "Tandem lease"));

	/** The upload after leaving a world, or one left over from a game that closed before it finished. */
	public volatile Transfer background;
	/** The synced world being played, if any. */
	public volatile Session session;
	/** Sync this world copying an open world, which must not close mid-copy. */
	private volatile CountDownLatch copying;
	private volatile MinecraftServer copyingServer;

	private WorldSync(Cloud cloud, boolean server) {
		this.cloud = cloud;
		this.server = server;
		cache = new BlobCache(saves().resolve(".tandem-cache"));
	}

	/** Null unless this run syncs for real. */
	public static WorldSync get() {
		return instance;
	}

	/** After {@link Account#init}: a signed-in run syncs through the server. */
	public static void init() {
		Account account = Account.get();
		String dir = System.getProperty("tandem.devcloud");
		WorldSync d;
		if (account != null) {
			d = instance = new WorldSync(new ServerCloud(account.api, WorldSync::mods, ThisGame::get), true);
			TandemClient.LOG.info("Tandem syncs worlds through KeplerSync");
		} else if (dir != null && !dir.isEmpty()) {
			DevCloud folder = new DevCloud(Paths.get(dir)).game(ThisGame::get);
			d = instance = new WorldSync(folder, false);
			TandemClient.LOG.info("Tandem dev cloud: {}", folder.root());
		} else {
			return;
		}
		// Each its own piece: checkpoints failing must not stop a session's end from being saved. A throw while the world
		// stops would also skip Minecraft's own save of it.
		ServerLifecycleEvents.SERVER_STARTED.register(server -> Guard.run("starting synced sessions", () -> d.started(server)));
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> Guard.run("waiting for Sync this world", () -> d.waitForCopy(server)));
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> Guard.run("ending synced sessions", () -> d.stopped(server)));
		ServerTickEvents.END_SERVER_TICK.register(server -> Guard.run("checkpoints", () -> {
			Session s = d.session;
			if (s != null && s.server == server) s.tick();
		}));
		ClientLifecycleEvents.CLIENT_STARTED.register(c -> Guard.run("start-up cleanup", () -> {
			// On the sync thread: asking KeplerSync about each world would hold the title screen up.
			d.async(() -> {
				cleanTrash();
				d.resumeUploads(null);
				return null;
			});
			d.cleanCache();
		}));
	}

	private static Thread daemon(Runnable r, String name) {
		Thread t = new Thread(r, name);
		t.setDaemon(true);
		return t;
	}

	public static Path saves() {
		return TandemClient.savesDir();
	}

	public static Path folder(String levelId) {
		return saves().resolve(levelId);
	}

	/**
	 * Who this is to the cloud: the KeplerSync account's id (empty until signed in), or the Minecraft name with a dev
	 * cloud. Never waits, so the screens can ask.
	 */
	public String player() {
		Account account = Account.get();
		if (server && account != null) {
			String id = account.api.knownUserId();
			return id != null ? id : "";
		}
		return Minecraft.getInstance().getUser().getName();
	}

	/** {@link #player}, signing in first if need be. On the sync thread. */
	String playerId() throws IOException {
		Account account = Account.get();
		if (server && account != null) return account.api.userId();
		return Minecraft.getInstance().getUser().getName();
	}

	/** KeplerSync didn't answer at all (as opposed to answering no). */
	public static boolean unreachable(Throwable e) {
		// Too old a Tandem counts as unreachable: sessions stay on this PC and upload once it's updated.
		return e instanceof IOException && (!(e instanceof KeplerSyncApi.Refused) || ((KeplerSyncApi.Refused) e).outdated());
	}

	public Sync sync() {
		return new Sync(cloud, cache, player());
	}

	public static String mcVersion() {
		return SharedConstants.getCurrentVersion().getName();
	}

	private static volatile List<String> mods;

	/**
	 * {@code id@version} of every top-level mod a world needs: one that adds blocks, items, creatures or anything else
	 * in Minecraft's registries, or world generation. The rest can differ between players without harm: client-only
	 * mods, tools like Chunky, performance mods, Tandem itself.
	 */
	public static List<String> mods() {
		List<String> known = mods;
		if (known != null) return known;
		Set<String> registered = registered();
		List<String> out = new ArrayList<>();
		for (ModContainer m : FabricLoader.getInstance().getAllMods()) {
			if (m.getContainingMod().isPresent() || m.getMetadata().getEnvironment() == ModEnvironment.CLIENT || builtIn(m)) continue;
			if (addsContent(m, registered)) out.add(m.getMetadata().getId() + "@" + m.getMetadata().getVersion().getFriendlyString());
		}
		out.sort(null);
		return mods = Collections.unmodifiableList(out);
	}

	/** Every namespace with entries in Minecraft's registries, Minecraft's own left out. */
	static Set<String> registered() {
		Set<String> registered = new HashSet<>();
		for (Registry<?> r : BuiltInRegistries.REGISTRY) for (ResourceLocation k : r.keySet()) registered.add(k.getNamespace());
		registered.remove("minecraft");
		return registered;
	}

	/** Part of the game itself rather than a mod someone installed. */
	static boolean builtIn(ModContainer m) {
		String id = m.getMetadata().getId();
		return id.equals("java") || id.equals("minecraft") || id.equals("fabricloader");
	}

	/** [m] (or a mod inside its jar) has entries in Minecraft's registries, or world generation in its data. */
	static boolean addsContent(ModContainer m, Set<String> registered) {
		if (registered.contains(m.getMetadata().getId())) return true;
		for (Path root : m.getRootPaths()) {
			for (Path ns : list(root.resolve("assets"))) if (registered.contains(name(ns))) return true;
			for (Path ns : list(root.resolve("data"))) {
				if (registered.contains(name(ns))) return true;
				for (String gen : new String[] {"worldgen", "dimension", "dimension_type"}) if (Files.isDirectory(ns.resolve(gen))) return true;
			}
		}
		for (ModContainer inner : m.getContainedMods()) if (addsContent(inner, registered)) return true;
		return false;
	}

	static List<Path> list(Path dir) {
		List<Path> out = new ArrayList<>();
		if (!Files.isDirectory(dir)) return out;
		try (DirectoryStream<Path> s = Files.newDirectoryStream(dir)) {
			for (Path p : s) if (Files.isDirectory(p)) out.add(p);
		} catch (IOException e) {
			// An unreadable mod jar: it doesn't count.
		}
		return out;
	}

	static String name(Path p) {
		String n = p.getFileName().toString();
		return n.endsWith("/") ? n.substring(0, n.length() - 1) : n;
	}

	/** A free folder name in saves for a world arriving from the cloud. */
	public static String newFolder(String name) throws IOException {
		return FileUtil.findAvailableName(saves(), name, "");
	}

	/** Runs [work] on the sync thread. */
	public <T> CompletableFuture<T> async(Callable<T> work) {
		CompletableFuture<T> f = new CompletableFuture<>();
		worker.execute(() -> {
			try {
				f.complete(work.call());
			} catch (Throwable t) {
				TandemClient.LOG.warn("Tandem sync failed", t);
				f.completeExceptionally(t);
			}
		});
		return f;
	}

	/** Dev checks only: holds a transfer (-Dtandem.downloadPauseMs, -Dtandem.uploadPauseMs) so it can be caught midway. */
	private static void devPause(String what) {
		long ms = Long.getLong("tandem." + what + "PauseMs", 0L);
		if (ms <= 0) return;
		try {
			Thread.sleep(ms);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private interface Job {
		void run(Transfer t) throws Exception;
	}

	private Transfer transfer(String world, Job job) {
		Transfer t = new Transfer(world);
		worker.execute(() -> {
			try {
				job.run(t);
				t.future.complete(null);
			} catch (Throwable e) {
				TandemClient.LOG.warn("Tandem transfer of {} failed", world, e);
				t.future.completeExceptionally(e);
			}
		});
		return t;
	}

	/**
	 * A transfer that, when KeplerSync can't be reached, waits half a minute and starts again (the session is saved on
	 * this PC meanwhile). Each try is the new [background], so the title tile shows the one that's running.
	 */
	private Transfer retrying(String world, Job job) {
		Transfer t = transfer(world, job);
		t.future.whenComplete((ok, e) -> {
			Throwable cause = e instanceof java.util.concurrent.CompletionException && e.getCause() != null ? e.getCause() : e;
			if (cause != null && unreachable(cause)) {
				offline = true;
				TandemClient.LOG.info("Tandem will try uploading {} again when KeplerSync is back", world);
				timer.schedule(() -> {
					if (background == t) background = retrying(world, job);
				}, 30, java.util.concurrent.TimeUnit.SECONDS);
			} else if (cause == null) {
				offline = false;
			}
		});
		return t;
	}

	/**
	 * Sync this world: [copy] once (the cloud world is made and this one copied into the cache), then [send]. When
	 * KeplerSync or storage can't be reached, [send] starts again 30 s later in the same transfer, so the list and a world
	 * closing meanwhile keep waiting on it rather than seeing it fail.
	 */
	private Transfer firstSend(String world, Job copy, Job send) {
		Transfer t = new Transfer(world);
		Runnable[] attempt = new Runnable[1];
		attempt[0] = () -> worker.execute(() -> {
			try {
				if (t.waiting) t.progress.restart();
				t.waiting = false;
				send.run(t);
				offline = false;
				t.future.complete(null);
			} catch (Throwable e) {
				if (unreachable(e)) {
					offline = true;
					t.waiting = true;
					TandemClient.LOG.info("Tandem will try uploading {} again in 30 s: {}", world, e.toString());
					timer.schedule(attempt[0], 30, java.util.concurrent.TimeUnit.SECONDS);
				} else {
					TandemClient.LOG.warn("Tandem transfer of {} failed", world, e);
					t.future.completeExceptionally(e);
				}
			}
		});
		worker.execute(() -> {
			try {
				copy.run(t);
			} catch (Throwable e) {
				TandemClient.LOG.warn("Tandem transfer of {} failed", world, e);
				t.future.completeExceptionally(e);
				return;
			}
			attempt[0].run();
		});
		return t;
	}

	/** Gets the latest copy of [worldId] into saves/[levelId]. The caller already holds the lease. */
	public Transfer getLatest(String levelId, String worldId, String name) {
		return transfer(name, t -> {
			long start = System.nanoTime();
			devPause("download");
			sync().getLatest(folder(levelId), worldId, t.progress);
			TandemClient.LOG.info("Tandem got the latest {}: {} MB downloaded in {} s", name, t.progress.done() >> 20, (System.nanoTime() - start) / 1_000_000_000);
		});
	}

	/** Uploads a session this PC still has (a crash, or a quit before its upload finished), keeping the lease meanwhile. */
	public Transfer uploadPending(String levelId, String worldId, String name, boolean release) {
		return transfer(name, t -> {
			upload(worldId, folder(levelId), t);
			if (release) cloud.releaseLease(worldId, player());
		});
	}

	/**
	 * Uploads the session this PC has, holding the lease as uploading and telling it how far along it is every two
	 * seconds, so friends waiting see the percentage move.
	 */
	private void upload(String worldId, Path dir, Transfer t) throws IOException {
		String me = player();
		cloud.heartbeat(worldId, me, Cloud.UPLOADING, 0);
		java.util.concurrent.ScheduledFuture<?> report = timer.scheduleAtFixedRate(() -> {
			try {
				cloud.heartbeat(worldId, me, Cloud.UPLOADING, t.progress.fraction());
			} catch (IOException e) {
				TandemClient.LOG.debug("Tandem couldn't report upload progress", e);
			}
		}, 2, 2, java.util.concurrent.TimeUnit.SECONDS);
		try {
			devPause("upload");
			sync().upload(dir, t.progress);
		} finally {
			report.cancel(false);
		}
	}

	/** Changes on disk since Tandem last synced the world or saved a session of it. */
	public CompletableFuture<Manifest.Diff> localChanges(String levelId) {
		return async(() -> sync().localChanges(folder(levelId)));
	}

	/** Sync this world, for a world on this PC that isn't open: the first upload, then the lease is let go. */
	public synchronized Transfer firstUpload(String levelId, String name, List<String> sharedWith) {
		firstUploadServer = null;
		Path dir = folder(levelId);
		Cloud.World[] made = new Cloud.World[1];
		return firstUpload = firstSend(name, t -> {
			made[0] = sync().create(dir, name, mcVersion(), mods(), sharedWith);
			sync().checkpoint(dir, Snapshot.IMPORT);
		}, t -> {
			sendFirst(made[0].id, dir, t);
			cloud.releaseLease(made[0].id, player());
			synced(name);
		});
	}

	/**
	 * The first upload, held as uploading so friends see how far it has got and can Play when ready. One that can't go
	 * on (storage full) lets the world go, so nobody waits on it; one that can't reach KeplerSync is tried again.
	 */
	private void sendFirst(String worldId, Path dir, Transfer t) throws IOException {
		try {
			upload(worldId, dir, t);
		} catch (IOException e) {
			if (!unreachable(e)) {
				try {
					cloud.releaseLease(worldId, player());
				} catch (IOException ignored) {
					// It runs out by itself.
				}
			}
			throw e;
		}
	}

	/**
	 * Sync this world from the pause menu: a flush save on the server thread, then autosave stays off while the world
	 * is copied in the background, so nothing writes to it meanwhile and a big world doesn't freeze the game. Then the
	 * upload while you keep playing, then the session starts. A world closed during the copy waits for it.
	 */
	public synchronized Transfer firstUploadOpen(MinecraftServer server, String name, List<String> sharedWith) {
		Path dir = server.getWorldPath(LevelResource.ROOT).normalize();
		// Pressed again while the first is still going: the same upload, not a second world.
		Transfer going = firstUploading(server);
		if (going != null) return going;
		firstUploadServer = server;
		Cloud.World[] made = new Cloud.World[1];
		return firstUpload = firstSend(name, t -> {
			List<ServerLevel> paused = new ArrayList<>();
			server.executeBlocking(() -> {
				server.saveEverything(true, true, true);
				for (ServerLevel level : server.getAllLevels()) {
					if (level.noSave) continue;
					level.noSave = true;
					paused.add(level);
				}
			});
			CountDownLatch copied = new CountDownLatch(1);
			copying = copied;
			copyingServer = server;
			try {
				made[0] = sync().create(dir, name, mcVersion(), mods(), sharedWith);
				long start = System.nanoTime();
				sync().checkpoint(dir, Snapshot.IMPORT);
				TandemClient.LOG.info("Tandem copied {} with autosave off in {} s", name, (System.nanoTime() - start) / 1_000_000_000);
			} finally {
				copied.countDown();
				// Never runs if the world closed meanwhile; closing turns saving back on by itself.
				server.execute(() -> {
					for (ServerLevel level : paused) level.noSave = false;
				});
			}
		}, t -> {
			long sending = System.nanoTime();
			sendFirst(made[0].id, dir, t);
			TandemClient.LOG.info("Tandem uploaded {}: {} MB in {} s", name, t.progress.done() >> 20, (System.nanoTime() - sending) / 1_000_000_000);
			synced(name);
			// Closed meanwhile: the session played since the copy goes up next (stopped), and nobody is hosting.
			if (!server.isRunning()) return;
			cloud.heartbeat(made[0].id, player(), Cloud.HOSTING, 0);
			server.execute(() -> {
				session = new Session(this, server, dir, made[0].id);
				WorldRules.started(server, made[0].id);
				if (server instanceof IntegratedServer) Hosting.openSynced((IntegratedServer) server, made[0].id, sharedWith);
			});
		});
	}

	/** Closing a world while Sync this world copies it: the game's last save waits, so it never lands mid-copy. */
	private void waitForCopy(MinecraftServer server) {
		CountDownLatch c = copying;
		if (c == null || copyingServer != server) return;
		try {
			c.await();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	/** Sync this world finished, wherever it was pressed: said once, wherever the player is now. */
	private static void synced(String name) {
		Ingame.notice(name + " is synced", "Friends you shared it with can host it now.", "cloud");
	}

	private volatile Transfer firstUpload;
	private volatile MinecraftServer firstUploadServer;

	/** The first upload of the world named [name] (Sync this world, from the pause menu or the Play list), if one is going. */
	public Transfer firstUploading(String name) {
		Transfer t = firstUpload;
		return t != null && !t.done() && t.world.equals(name) ? t : null;
	}

	/** The first upload of the world [server] is running (Sync this world from the pause menu), if one is going. */
	public Transfer firstUploading(MinecraftServer server) {
		Transfer t = firstUpload;
		return t != null && !t.done() && firstUploadServer == server ? t : null;
	}

	public void openWorld(String levelId, Runnable onCancel) {
		Minecraft.getInstance().createWorldOpenFlows().openWorld(levelId, onCancel);
	}

	private void started(MinecraftServer server) {
		if (FabricLoader.getInstance().getEnvironmentType() != EnvType.CLIENT) return;
		Path dir = server.getWorldPath(LevelResource.ROOT).normalize();
		try {
			LocalState st = LocalState.load(dir);
			if (!st.synced()) return;
			Cloud.Lease l = cloud.takeLease(st.worldId, playerId(), Cloud.HOSTING);
			if (!l.heldBy(playerId())) TandemClient.LOG.warn("Tandem: {} holds {}'s lease; this session will be kept beside theirs", l.holderName, dir.getFileName());
			session = new Session(this, server, dir, st.worldId);
		} catch (IOException e) {
			TandemClient.LOG.warn("Tandem couldn't start syncing {}", dir.getFileName(), e);
		}
	}

	/** The world closed: its last session is saved from disk (nothing writes to it now) and uploaded in the background. */
	private void stopped(MinecraftServer server) {
		Transfer first = firstUploading(server);
		if (first != null) {
			// Left while Sync this world was still uploading: what was played after its copy goes up right behind it.
			Path dir = server.getWorldPath(LevelResource.ROOT).normalize();
			String level = dir.getFileName().toString();
			first.future.whenComplete((ok, e) -> {
				if (e == null) background = retrying(level, t -> {
					String worldId = LocalState.load(dir).worldId;
					sync().checkpoint(dir, Snapshot.SESSION);
					upload(worldId, dir, t);
					cloud.releaseLease(worldId, player());
				});
			});
			return;
		}
		Session s = session;
		if (s == null || s.server != server) return;
		session = null;
		s.stop();
		// A 30-minute upload may still be running: without this the lease could run out (and a stop waiting for this
		// session go through) before the last one is in.
		String me = player();
		timer.execute(() -> {
			try {
				cloud.heartbeat(s.worldId, me, Cloud.UPLOADING, 0);
			} catch (IOException e) {
				TandemClient.LOG.debug("Tandem couldn't mark {} as uploading", s.worldId, e);
			}
		});
		// Saved on this PC first: if the game closes now, the next launch finds the session and sends it.
		background = retrying(s.dir.getFileName().toString(), t -> {
			sync().checkpoint(s.dir, Snapshot.SESSION);
			upload(s.worldId, s.dir, t);
			cloud.releaseLease(s.worldId, player());
		});
		cleanCache();
	}

	/** Old copies in the cache that no synced world needs go, after the transfers queued before it. */
	private void cleanCache() {
		async(() -> {
			long freed = cache.prune(LocalState.inUse(saves()), CACHE_GRACE_MS);
			if (freed > 0) TandemClient.LOG.info("Tandem freed {} MB of old copies from its cache", freed >> 20);
			return null;
		});
	}

	/**
	 * At launch: whatever the game closed on goes on now. A Sync this world that hadn't finished; a session saved
	 * on this PC and not sent; or one whose lease still says this player is uploading, which closed before it was even
	 * saved, so it's saved from disk now. The lease is let go afterwards, so friends stop waiting. A lease KeplerSync
	 * couldn't be asked about is asked again a minute later; the transfers themselves retry by themselves.
	 */
	private void resumeUploads(Set<String> only) {
		Path work = saves().resolve(".tandem");
		if (!Files.isDirectory(work)) return;
		Set<String> again = new HashSet<>();
		try (DirectoryStream<Path> ds = Files.newDirectoryStream(work)) {
			for (Path d : ds) {
				String level = d.getFileName().toString();
				if (only != null && !only.contains(level)) continue;
				Path dir = folder(level);
				// One world KeplerSync can't answer about doesn't keep the others from going up.
				try {
					if (!Files.isDirectory(dir)) continue;
					LocalState st = LocalState.load(dir);
					if (st.creating != null && only == null) {
						TandemClient.LOG.info("Tandem: finishing Sync this world for {}, which the game closed on", level);
						background = retrying(level, t -> {
							Cloud.World w = sync().resumeFirstUpload(dir, mcVersion(), mods(), t.progress);
							cloud.releaseLease(w.id, player());
						});
						continue;
					}
					if (!st.synced()) continue;
					if (st.pending == null) {
						Cloud.Lease lease = cloud.lease(st.worldId);
						if (lease == null || !lease.heldBy(playerId()) || !Cloud.UPLOADING.equals(lease.state)) continue;
					} else if (only != null) {
						continue;
					}
					TandemClient.LOG.info("Tandem: finishing the last session of {}, which didn't finish uploading before the game closed", level);
					background = retrying(level, t -> {
						sync().checkpoint(dir, Snapshot.SESSION);
						upload(st.worldId, dir, t);
						cloud.releaseLease(st.worldId, player());
					});
				} catch (IOException e) {
					if (unreachable(e)) again.add(level);
					else TandemClient.LOG.warn("Tandem couldn't check {} for an unsent session", level, e);
				}
			}
		} catch (IOException e) {
			TandemClient.LOG.warn("Tandem couldn't look for unsent sessions", e);
		}
		if (!again.isEmpty()) {
			TandemClient.LOG.info("Tandem will check {} again when KeplerSync answers", again);
			timer.schedule(() -> async(() -> {
				resumeUploads(again);
				return null;
			}), 60, java.util.concurrent.TimeUnit.SECONDS);
		}
	}

	/**
	 * Deletes a world from this PC, unless the game has it open. Its notes go first (from then on it's a normal world),
	 * then it moves out of saves in one step and is deleted there, so a game closed part way never leaves half a world
	 * in the list.
	 */
	public static void deleteWorld(String levelId) throws IOException {
		Path dir = folder(levelId);
		// Taken and given back: proof the game hasn't got it open. Before the notes go, so a world in use stays synced.
		try {
			Minecraft.getInstance().getLevelSource().validateAndCreateAccess(levelId).close();
		} catch (Exception e) {
			throw new IOException(levelId + " is open or in use", e);
		}
		LocalState.drop(dir);
		Path trash = saves().resolve(".tandem-trash").resolve(java.util.UUID.randomUUID().toString());
		Files.createDirectories(trash.getParent());
		Files.move(dir, trash, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
		WorldSwap.deleteTree(trash);
	}

	/** At launch: worlds a closed game was deleting finish going. */
	private static void cleanTrash() {
		Path trash = saves().resolve(".tandem-trash");
		if (!Files.isDirectory(trash)) return;
		try (DirectoryStream<Path> ds = Files.newDirectoryStream(trash)) {
			for (Path p : ds) WorldSwap.deleteTree(p);
		} catch (IOException e) {
			TandemClient.LOG.warn("Tandem couldn't finish deleting a world", e);
		}
	}
}
