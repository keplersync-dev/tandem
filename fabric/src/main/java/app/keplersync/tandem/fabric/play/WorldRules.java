package app.keplersync.tandem.fabric.play;

import app.keplersync.tandem.core.Cloud;
import app.keplersync.tandem.core.LocalState;
import app.keplersync.tandem.fabric.Guard;
import app.keplersync.tandem.fabric.TandemClient;
import app.keplersync.tandem.fabric.account.Account;
import app.keplersync.tandem.fabric.sync.WorldSync;
import com.mojang.authlib.GameProfile;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

/**
 * A synced world's own rules while this PC hosts it, the same whoever hosts: everyone keeps their own inventory,
 * place and game mode (Minecraft would otherwise hand the host whatever the last host saved in level.dat), and
 * operators are the world's owner and whoever they or other operators picked. The mixins in {@code mixin/} ask
 * here; outside a synced world they leave Minecraft alone.
 */
public final class WorldRules {
	private static volatile WorldRules current;

	final MinecraftServer server;
	public final String worldId;
	/** Minecraft account → KeplerSync user id, for everyone in the world. */
	private final Map<UUID, String> people = new ConcurrentHashMap<>();
	private final Set<UUID> operators = ConcurrentHashMap.newKeySet();
	private volatile UUID owner;
	private int seen = -1;

	private WorldRules(MinecraftServer server, String worldId) {
		this.server = server;
		this.worldId = worldId;
	}

	/** The rules for [server], or null when it isn't running a synced world. */
	public static WorldRules of(MinecraftServer server) {
		WorldRules r = current;
		return r != null && r.server == server ? r : null;
	}

	public static void init() {
		ServerLifecycleEvents.SERVER_STARTING.register(server -> Guard.run("synced world rules", () -> {
			WorldSync sync = WorldSync.get();
			if (sync == null || !sync.server) return;
			LocalState st;
			try {
				st = LocalState.load(server.getWorldPath(LevelResource.ROOT).normalize());
			} catch (java.io.IOException e) {
				return;
			}
			if (!st.synced()) return;
			WorldRules r = new WorldRules(server, st.worldId);
			current = r;
			r.refresh();
		}));
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			if (of(server) != null) current = null;
		});
		// Someone was made an operator (here or anywhere else): the world changed.
		ClientTickEvents.END_CLIENT_TICK.register(mc -> Guard.run("synced world rules", () -> {
			WorldRules r = current;
			Account a = Account.get();
			if (r != null && a != null && a.worldsVersion != r.seen) {
				r.seen = a.worldsVersion;
				r.refresh();
			}
		}));
	}

	/** A synced world started from the pause menu (Sync this world) gets its rules once it's synced. */
	public static void started(MinecraftServer server, String worldId) {
		if (of(server) != null) return;
		WorldRules r = new WorldRules(server, worldId);
		current = r;
		r.refresh();
	}

	private void refresh() {
		WorldSync sync = WorldSync.get();
		sync.async(() -> sync.cloud.world(worldId)).thenAccept(this::apply).exceptionally(e -> {
			TandemClient.LOG.info("Tandem couldn't fetch {}'s operators: {}", worldId, e.getMessage());
			return null;
		});
	}

	private void apply(Cloud.World w) {
		// By KeplerSync id: a display name is anyone's to pick, so it never decides who owns or runs commands here.
		Map<UUID, String> now = new java.util.HashMap<>();
		Set<UUID> ops = ConcurrentHashMap.newKeySet();
		for (Map.Entry<String, String> e : w.minecraftIds.entrySet()) {
			UUID id = Hosting.minecraftId(e.getValue());
			if (id == null) continue;
			now.put(id, e.getKey());
			if (w.operators.contains(e.getKey())) ops.add(id);
			if (e.getKey().equals(w.createdBy)) owner = id;
		}
		people.keySet().retainAll(now.keySet());
		people.putAll(now);
		operators.retainAll(ops);
		operators.addAll(ops);
		// Someone added to the world (or taken out) while it's open: whether they may join this game follows.
		Hosting h = Hosting.get();
		if (h != null && worldId.equals(h.worldId)) h.members(now.keySet());
		// Players already in the game get their commands (or lose them) now.
		server.execute(() -> {
			for (ServerPlayer p : server.getPlayerList().getPlayers()) server.getPlayerList().sendPlayerPermissionLevel(p);
		});
	}

	public boolean isOperator(UUID player) {
		return operators.contains(player);
	}

	public boolean isOwner(UUID player) {
		return player != null && player.equals(owner);
	}

	/** In the world (owner or member), as opposed to someone invited to just this game. */
	public boolean isMember(UUID player) {
		return people.containsKey(player);
	}

	public String userId(UUID player) {
		return people.get(player);
	}

	/** The Minecraft account of the world's member [userId], or null. */
	public UUID player(String userId) {
		for (Map.Entry<UUID, String> e : people.entrySet()) if (e.getValue().equals(userId)) return e.getKey();
		return null;
	}

	/** The world's owner's Minecraft account, once the world's people are in. */
	public UUID owner() {
		return owner;
	}

	/** Operators get commands; the one hosting gets everything, as a singleplayer owner does. */
	public int permission(GameProfile profile, boolean hosting) {
		if (!isOperator(profile.getId())) return 0;
		return hosting ? 4 : 3;
	}

	/** Makes [player] an operator or not, through KeplerSync, so it holds whoever hosts next. */
	public CompletableFuture<Void> setOperator(UUID player, boolean operator) {
		String user = people.get(player);
		Account a = Account.get();
		if (user == null || a == null) {
			CompletableFuture<Void> f = new CompletableFuture<>();
			f.completeExceptionally(new IllegalStateException("They aren't in this world, so they can't be an operator"));
			return f;
		}
		return WorldSync.get().async(() -> {
			a.api.setOperator(worldId, user, operator);
			return null;
		}).thenRun(this::refresh);
	}

	/** /op and /deop in a synced world: the world's operators change, or the host hears why not. */
	public void request(GameProfile profile, boolean operator) {
		setOperator(profile.getId(), operator).whenComplete((ok, e) -> {
			if (e == null) return;
			Throwable cause = e.getCause() != null ? e.getCause() : e;
			Minecraft mc = Minecraft.getInstance();
			mc.execute(() -> mc.gui.getChat().addMessage(Component.literal("Tandem couldn't change " + profile.getName() + ": " + cause.getMessage())
					.withStyle(ChatFormatting.RED)));
		});
	}
}
