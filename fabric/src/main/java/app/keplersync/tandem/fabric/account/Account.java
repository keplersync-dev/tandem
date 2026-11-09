package app.keplersync.tandem.fabric.account;

import app.keplersync.tandem.core.KeplerSyncApi;
import app.keplersync.tandem.fabric.Guard;
import app.keplersync.tandem.fabric.TandemClient;
import app.keplersync.tandem.fabric.play.Joining;
import com.mojang.authlib.exceptions.AuthenticationException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.SharedConstants;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.client.multiplayer.ServerData;

/**
 * Tandem signed in to KeplerSync with the account the game is playing on, switched on by
 * {@code -Dtandem.server=<url>}. Nothing is stored on disk: every launch signs in again (the game always has its
 * session), the access token is refreshed while the game runs, and the session is ended on quit. Without the
 * property, Tandem shows sample friends.
 */
public final class Account {
	private static Account instance;

	public final KeplerSyncApi api;
	private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "Tandem account");
		t.setDaemon(true);
		return t;
	});
	final Live live;

	public volatile KeplerSyncApi.Me me;
	public volatile List<KeplerSyncApi.Friend> friends = new ArrayList<>();
	public volatile List<KeplerSyncApi.FriendRequest> requests = new ArrayList<>();
	/** Why Tandem isn't signed in, in words for the player; null when it is. */
	public volatile String problem;
	/** Goes up whenever anything above changes, so screens know to rebuild. */
	public volatile int version;
	/** Goes up when the server says a synced world changed (a lease, a new save, its people). */
	public volatile int worldsVersion;
	/** Invites to friends' games still going, newest first. */
	public volatile List<KeplerSyncApi.PlayInvite> invites = new ArrayList<>();
	/** Invites that just arrived, for the toast. */
	public final ConcurrentLinkedQueue<KeplerSyncApi.PlayInvite> invited = new ConcurrentLinkedQueue<>();
	/** Invites already toasted, and ones used or stale, by {@link #key}: a reload doesn't bring them back. */
	private final Set<String> toasted = ConcurrentHashMap.newKeySet();
	private final Set<String> used = ConcurrentHashMap.newKeySet();

	/** A synced world's host handing it to someone: who takes over, and who follows them. */
	public static final class Handoff {
		public final String worldId;
		public final String fromId;
		public final String toId;

		Handoff(String worldId, String fromId, String toId) {
			this.worldId = worldId;
			this.fromId = fromId;
			this.toId = toId;
		}
	}

	/** Hand-offs just announced, for the game to act on once the host's world closes. */
	public final ConcurrentLinkedQueue<Handoff> handoffs = new ConcurrentLinkedQueue<>();
	/** Friends who just came online, for the toast. */
	public final ConcurrentLinkedQueue<String> cameOnline = new ConcurrentLinkedQueue<>();
	/** Live presence by user id, newer than the friends list. */
	final Map<String, KeplerSyncApi.Presence> presence = new ConcurrentHashMap<>();

	private Account(String server) {
		api = new KeplerSyncApi(server, new KeplerSyncApi.Joiner() {
			@Override
			public String username() {
				return Minecraft.getInstance().getUser().getName();
			}

			@Override
			public void join(String serverId) throws IOException {
				// The same call the game makes when joining any server: the token goes to Mojang only.
				User user = Minecraft.getInstance().getUser();
				try {
					Minecraft.getInstance().getMinecraftSessionService().joinServer(user.getProfileId(), user.getAccessToken(), serverId);
				} catch (AuthenticationException e) {
					throw new KeplerSyncApi.NotConfirmed("Mojang couldn't confirm your Minecraft account", e);
				}
			}
		});
		api.agent("Tandem/" + AutoLogs.version() + " (Minecraft " + SharedConstants.getCurrentVersion().getName() + ")");
		live = new Live(this);
	}

	/** Null unless this run talks to a KeplerSync server. */
	public static Account get() {
		return instance;
	}

	public static void init() {
		// Dev runs use sample data unless they name a server; the real game uses KeplerSync's.
		String server = System.getProperty("tandem.server", FabricLoader.getInstance().isDevelopmentEnvironment() ? "" : "https://keplersync.app");
		if (server.isEmpty()) return;
		Account a = instance = new Account(server);
		ClientLifecycleEvents.CLIENT_STARTED.register(c -> a.worker.execute(a::signIn));
		ClientLifecycleEvents.CLIENT_STOPPING.register(c -> Guard.run("signing out", a::stop));
		// What friends see: your own world or the Tandem game you joined by name; anyone else's server only as "Playing".
		ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> Guard.run("presence", () -> {
			Joining.Target joined = Joining.current;
			ServerData on = mc.getCurrentServer();
			String world = mc.getSingleplayerServer() != null ? mc.getSingleplayerServer().getWorldData().getLevelName()
					: joined != null && on != null && joined.address.equals(on.ip) ? joined.info.worldName : null;
			a.live.presence("playing", world, SharedConstants.getCurrentVersion().getName());
		}));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> Guard.run("presence", () -> a.live.presence("online", null, null)));
	}

	public boolean signedIn() {
		return me != null;
	}

	/** Sign-ins that failed in a row: the next try waits longer each time, from 30 seconds up to 10 minutes. */
	private int failedSignIns;

	private void signIn() {
		try {
			// The same session as a sync call that got there first, not a second one.
			me = api.ensureSignedIn();
			failedSignIns = 0;
			problem = null;
			TandemClient.LOG.info("Signed in to KeplerSync as {}", me.displayName);
			reload();
			reloadInvites();
			live.connect();
			AutoLogs.crashReports();
			worker.scheduleAtFixedRate(this::refresh, 60, 60, TimeUnit.SECONDS);
		} catch (IOException e) {
			problem = e.getMessage();
			version++;
			if (e instanceof KeplerSyncApi.Refused && ((KeplerSyncApi.Refused) e).outdated()) {
				// Asking again this launch can't help: only a newer Tandem gets in.
				TandemClient.LOG.info("KeplerSync needs a newer Tandem: {}", e.getMessage());
				return;
			}
			TandemClient.LOG.warn("Tandem couldn't sign in to KeplerSync: {}", e.getMessage());
			worker.schedule(this::signIn, Math.min(600, 30L << Math.min(failedSignIns++, 5)), TimeUnit.SECONDS);
		}
	}

	private void refresh() {
		try {
			api.refreshIfDue();
		} catch (IOException e) {
			TandemClient.LOG.warn("Tandem couldn't refresh its KeplerSync sign-in: {}", e.getMessage());
		}
	}

	/** Fetches friends and requests again. On the account thread. */
	void reload() {
		try {
			List<KeplerSyncApi.Friend> f = api.friends();
			requests = api.requests();
			for (KeplerSyncApi.Friend friend : f) presence.remove(friend.user.id);
			friends = f;
			problem = null;
		} catch (IOException e) {
			problem = e.getMessage();
		}
		version++;
	}

	/** Fetches invites again; new ones go to the toast. */
	public void reloadInvitesSoon() {
		worker.execute(this::reloadInvites);
	}

	void reloadInvites() {
		try {
			List<KeplerSyncApi.PlayInvite> now = api.playInvites();
			// A new invite to the same game (after a kick, say) has a new time, so it toasts again.
			toasted.removeIf(k -> now.stream().noneMatch(o -> key(o).equals(k)));
			used.removeIf(k -> now.stream().noneMatch(o -> key(o).equals(k)));
			List<KeplerSyncApi.PlayInvite> open = new ArrayList<>();
			for (KeplerSyncApi.PlayInvite i : now) {
				if (used.contains(key(i))) continue;
				open.add(i);
				if (toasted.add(key(i))) invited.add(i);
			}
			// Withdrawn, or the game ended: the toast for it goes too.
			invited.removeIf(i -> open.stream().noneMatch(o -> o.sessionId.equals(i.sessionId)));
			invites = open;
		} catch (IOException e) {
			TandemClient.LOG.warn("Tandem couldn't fetch invites: {}", e.getMessage());
		}
		version++;
	}

	void handoff(String worldId, String fromId, String toId) {
		handoffs.add(new Handoff(worldId, fromId, toId));
		worldsVersion++;
	}

	/** An invite that was used or turned out stale: no toast and no Join for it any more. */
	public void forgetInvite(String sessionId) {
		for (KeplerSyncApi.PlayInvite i : invites) if (i.sessionId.equals(sessionId)) used.add(key(i));
		invited.removeIf(i -> i.sessionId.equals(sessionId));
		List<KeplerSyncApi.PlayInvite> left = new ArrayList<>(invites);
		if (left.removeIf(i -> i.sessionId.equals(sessionId))) {
			invites = left;
			version++;
		}
	}

	private static String key(KeplerSyncApi.PlayInvite i) {
		return i.sessionId + "@" + i.invitedAt;
	}

	void reloadMe() {
		try {
			me = api.me();
		} catch (IOException e) {
			TandemClient.LOG.warn("Tandem couldn't fetch the KeplerSync account: {}", e.getMessage());
		}
		version++;
		// The space for synced worlds may have changed with it.
		worldsVersion++;
	}

	/** A friend's live presence; the toast when they come online. */
	void presenceChanged(String userId, KeplerSyncApi.Presence p) {
		KeplerSyncApi.Presence before = presenceOf(userId);
		presence.put(userId, p);
		// Opening Atlas alone isn't coming online here: only starting Minecraft is.
		if (!inMinecraft(before) && inMinecraft(p)) {
			for (KeplerSyncApi.Friend f : friends) if (f.user.id.equals(userId)) cameOnline.add(f.user.displayName);
		}
		version++;
	}

	private static boolean inMinecraft(KeplerSyncApi.Presence p) {
		return p != null && ("playing".equals(p.state) || "online".equals(p.state) && p.inMinecraft);
	}

	public KeplerSyncApi.Presence presenceOf(String userId) {
		KeplerSyncApi.Presence live = presence.get(userId);
		if (live != null) return live;
		for (KeplerSyncApi.Friend f : friends) if (f.user.id.equals(userId)) return f.presence;
		return null;
	}

	/** Runs [action] on the account thread, then fetches friends again. */
	public CompletableFuture<Void> run(IoAction action) {
		CompletableFuture<Void> f = new CompletableFuture<>();
		worker.execute(() -> {
			try {
				action.run(api);
				reload();
				f.complete(null);
			} catch (Throwable t) {
				f.completeExceptionally(t);
			}
		});
		return f;
	}

	public interface IoAction {
		void run(KeplerSyncApi api) throws IOException;
	}

	void later(Runnable r, long seconds) {
		worker.schedule(r, seconds, TimeUnit.SECONDS);
	}

	void now(Runnable r) {
		worker.execute(r);
	}

	private void stop() {
		AutoLogs.flush();
		live.close();
		api.logout();
	}
}
