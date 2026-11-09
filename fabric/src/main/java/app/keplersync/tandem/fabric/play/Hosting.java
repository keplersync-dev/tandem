package app.keplersync.tandem.fabric.play;

import app.keplersync.tandem.core.Cloud;
import app.keplersync.tandem.core.KeplerSyncApi;
import app.keplersync.tandem.core.RelayTunnel;
import app.keplersync.tandem.core.Addresses;
import app.keplersync.tandem.core.Ping;
import app.keplersync.tandem.core.PortMapper;
import app.keplersync.tandem.core.Punch;
import app.keplersync.tandem.fabric.Guard;
import app.keplersync.tandem.fabric.TandemClient;
import app.keplersync.tandem.fabric.account.Account;
import app.keplersync.tandem.fabric.sync.Session;
import app.keplersync.tandem.fabric.sync.WorldSync;
import com.mojang.authlib.GameProfile;
import com.mojang.util.UndashedUuid;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.HttpUtil;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;

/**
 * This game open to friends: Minecraft's own "Open to LAN" on a free port, a KeplerSync session that invites go
 * through, the router asked to open the port (UPnP), and the relay for friends who can't connect directly. Only the
 * Minecraft accounts of invited friends (or, for a synced world, its members) get in: anyone else who reaches the
 * port is turned away, since Minecraft itself knows nothing about invites.
 */
public final class Hosting {
	/** How a Just play world opens to friends, picked when the host first invites someone (like Open to LAN). */
	public static final class Settings {
		public final GameType gameMode;
		public final boolean cheats;
		public final Difficulty difficulty;

		public Settings(GameType gameMode, boolean cheats, Difficulty difficulty) {
			this.gameMode = gameMode;
			this.cheats = cheats;
			this.difficulty = difficulty;
		}
	}

	private static volatile Hosting current;
	/** An open still under way, and its game: invites made meanwhile wait for it rather than opening again. */
	private static CompletableFuture<Hosting> opening;
	private static volatile MinecraftServer openingServer;

	public final String sessionId;
	public final int port;
	/** The synced world being hosted, or null for Just play. */
	public final String worldId;
	private final MinecraftServer server;
	private volatile RelayTunnel.Host relay;
	/** Friends who can't reach this game on TCP punch through to it over UDP, introduced by the relay. */
	private volatile Punch.Host punch;
	/** The router port the relay is asked to check from outside, once it's connected. */
	private volatile int probe;
	/** The firewall notice shows once a launch. */
	private static volatile boolean toldFirewall;
	/** Ports the router forwards to this PC, renewed while the game is open. */
	private final List<PortMapper.Mapping> mappings = new java.util.concurrent.CopyOnWriteArrayList<>();
	/** Why the router didn't forward the game's port, or null when it did (or hasn't been asked yet). */
	public volatile String routerProblem;
	/** The addresses friends try before the relay: this PC's networks, then the router's once it opened the port. */
	private volatile List<String> direct;
	/** Minecraft account ids allowed in, besides the host. */
	private final Set<UUID> allowed = ConcurrentHashMap.newKeySet();
	/** KeplerSync ids of friends invited so far. */
	public final Set<String> invited = ConcurrentHashMap.newKeySet();
	/** Their Minecraft accounts, by KeplerSync id. */
	private final Map<String, UUID> accounts = new ConcurrentHashMap<>();
	/** Minecraft accounts the world's owner took out of this game: kept out even if they're members. */
	private final Set<UUID> removed = ConcurrentHashMap.newKeySet();
	/** The synced world's members among [allowed]: they come and go with the world's people, invites stay. */
	private final Set<UUID> members = ConcurrentHashMap.newKeySet();

	private Hosting(MinecraftServer server, String sessionId, int port, String worldId, List<String> direct) {
		this.server = server;
		this.sessionId = sessionId;
		this.port = port;
		this.worldId = worldId;
		this.direct = direct;
	}

	/** Null unless this game is open to friends. */
	public static Hosting get() {
		return current;
	}

	/** Whether [server] is being opened to friends right now. */
	public static synchronized boolean opening(MinecraftServer server) {
		return opening != null && openingServer == server;
	}

	/**
	 * Why [profile] may not join [server]'s game, or null when they may: the host, someone invited, or a member of the
	 * synced world. Asked at login ({@code PlayerListMixin}), before Minecraft puts them in the world.
	 */
	public static Component refusal(MinecraftServer server, GameProfile profile) {
		if (server.isSingleplayerOwner(profile)) return null;
		Hosting h = current;
		if (h == null || h.server != server) {
			// Minecraft has opened the port, but who may come in isn't known until the open finishes.
			return openingServer == server ? Component.literal("This game is still opening. Try again in a moment.") : null;
		}
		if (h.removed.contains(profile.getId())) return Component.literal("The world's owner removed you from this game.");
		if (h.allowed.contains(profile.getId())) return null;
		TandemClient.LOG.info("Tandem turned away {}, who wasn't invited", profile.getName());
		return Component.literal("You need an invite to join this game.");
	}

	/** The synced world's members now: newcomers may join, and someone taken out may not, unless invited. */
	void members(java.util.Collection<UUID> now) {
		for (UUID gone : members) if (!now.contains(gone) && !accounts.containsValue(gone)) allowed.remove(gone);
		members.retainAll(now);
		members.addAll(now);
		allowed.addAll(now);
	}

	/**
	 * {@link #refusal}, failing closed: if the check itself breaks, only the host gets in until the game restarts,
	 * never someone uninvited.
	 */
	public static Component checkedRefusal(MinecraftServer server, GameProfile profile) {
		Component cantCheck = server.isSingleplayerOwner(profile) ? null
				: Component.literal("Tandem couldn't check your invite. Ask the host to restart their game.");
		return Guard.get("invite check", cantCheck, () -> refusal(server, profile));
	}

	public static void init() {
		// Also once they're in, for anyone let in before an invite was withdrawn or a kick came through.
		ServerPlayConnectionEvents.JOIN.register((handler, sender, srv) -> {
			Component no = checkedRefusal(srv, handler.getPlayer().getGameProfile());
			if (no != null) handler.disconnect(no);
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(srv -> Guard.run("closing games to friends", () -> {
			Hosting h = current;
			if (h != null && h.server == srv) h.close();
		}));
		// A synced world opens to its members once the host is in it: Minecraft can't open to LAN before that.
		ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> Guard.run("opening synced worlds to members", () -> {
			IntegratedServer srv = mc.getSingleplayerServer();
			WorldSync sync = WorldSync.get();
			Session s = sync != null ? sync.session : null;
			if (srv != null && s != null && s.server == srv) openSynced(srv, s.worldId, List.of());
		}));
	}

	/**
	 * Opens this game to friends if it isn't already, and returns it. [worldId] is the synced world's id (its members
	 * may join), or null for Just play, which opens with [settings]. [members] may join from the start.
	 */
	public static synchronized CompletableFuture<Hosting> open(IntegratedServer server, String worldName, String worldId, List<UUID> members, Settings settings) {
		Hosting h = current;
		if (h != null && h.server == server) {
			if (worldId != null) h.members(members);
			return CompletableFuture.completedFuture(h);
		}
		if (opening != null && openingServer == server) return opening.thenApply(o -> {
			if (worldId != null) o.members(members);
			return o;
		});
		Account account = Account.get();
		WorldSync sync = WorldSync.get();
		if (account == null || sync == null) return failed(new IOException("Tandem isn't signed in to KeplerSync"));
		int port = server.isPublished() ? server.getPort() : HttpUtil.getAvailablePort();
		CompletableFuture<Boolean> published = new CompletableFuture<>();
		Minecraft.getInstance().execute(() -> {
			try {
				if (settings != null) server.setDifficulty(settings.difficulty, true);
				// A synced world's game modes and commands come from WorldRules, not from here.
				GameType mode = settings != null ? settings.gameMode : server.getDefaultGameType();
				published.complete(server.isPublished() || server.publishServer(mode, settings != null && settings.cheats, port));
			} catch (RuntimeException e) {
				published.completeExceptionally(e);
			}
		});
		CompletableFuture<Hosting> result = published.thenCompose(ok -> {
			if (!ok) return failed(new IOException("Minecraft couldn't open this world to friends"));
			return sync.async(() -> {
				KeplerSyncApi api = account.api;
				List<String> lan = lanAddresses(port);
				KeplerSyncApi.PlaySession session = api.startPlay(worldName, worldId, WorldSync.mcVersion(), WorldSync.mods(), lan);
				Hosting made = new Hosting(server, session.id, port, worldId, lan);
				made.members(members);
				if (session.relay != null) {
					final String[] first = {session.relay.ticket};
					// The first ticket from starting; later ones (a reconnect, a friend arriving minutes on) are fresh.
					made.relay = new RelayTunnel.Host(session.relay.address, () -> {
						String t = first[0];
						if (t != null) {
							first[0] = null;
							return t;
						}
						return api.updatePlay(session.id, made.direct).relay.ticket;
					}, port);
					made.punch(session.relay.address);
					made.relay.start();
				}
				current = made;
				TandemClient.LOG.info("Tandem opened {} to friends on port {}{}", worldName, port, made.relay != null ? ", with the relay" : "");
				made.openRouterPort();
				return made;
			});
		});
		opening = result;
		openingServer = server;
		result.whenComplete((x, e) -> {
			synchronized (Hosting.class) {
				if (opening == result) {
					opening = null;
					openingServer = null;
				}
			}
		});
		return result;
	}

	/**
	 * A synced world opens to its members straight away: they join from Play, no invite needed. Those in [invite], by
	 * KeplerSync id (just ticked in Sync this world), get an invite too, so they hear about it.
	 */
	public static void openSynced(IntegratedServer server, String worldId, List<String> invite) {
		Account account = Account.get();
		WorldSync sync = WorldSync.get();
		if (account == null || sync == null || !sync.server) return;
		sync.async(() -> sync.cloud.world(worldId)).thenCompose(w -> {
			List<UUID> members = new ArrayList<>();
			for (String id : w.minecraftIds.values()) {
				UUID mc = minecraftId(id);
				if (mc != null) members.add(mc);
			}
			// The game names itself by its level, which may have been renamed since the world was synced: guests look
			// for that name when they check they've reached the right game.
			return open(server, server.getWorldData().getLevelName(), worldId, members, null).thenApply(h -> {
				for (String user : invite) h.invite(user, minecraftId(w.minecraftIds.get(user)));
				return h;
			});
		}).exceptionally(e -> {
			TandemClient.LOG.warn("Tandem couldn't open {} to its members", worldId, e);
			return null;
		});
	}

	private static <T> CompletableFuture<T> failed(Throwable e) {
		CompletableFuture<T> f = new CompletableFuture<>();
		f.completeExceptionally(e);
		return f;
	}

	/** Starts the UDP end for punching, fed by the relay's control lines. Without it, guests use TCP or the relay. */
	private void punch(String relayAddress) {
		try {
			Punch.Host p = new Punch.Host(Punch.udp(0), port);
			punch = p;
			relay.onLine = line -> {
				if (line.equals(RelayTunnel.Host.OK_LINE)) {
					if (probe > 0) relay.tellRelay("PROBE " + probe);
				} else if (line.startsWith("UDP ")) {
					p.register(relay.host(), line);
				} else if (line.startsWith("PUNCH ")) {
					p.introduce(line);
				} else if (line.startsWith("PROBE ")) {
					probed(line.substring(6));
				}
			};
		} catch (IOException e) {
			TandemClient.LOG.info("Tandem can't listen on UDP, so friends can't punch through: {}", e.getMessage());
		}
	}

	/** The relay's check of the router port from outside: closed or silent means something on this PC blocks it. */
	private void probed(String result) {
		TandemClient.LOG.info("Tandem: KeplerSync checked port {} from outside: {}", probe, result);
		if (result.equals("tcp=open") || toldFirewall) return;
		toldFirewall = true;
		app.keplersync.tandem.fabric.ui.Ingame.INSTANCE.notice("Friends can't reach your game directly",
				"Windows Firewall or security software is blocking Java. Friends can still join: Tandem connects around it, or through KeplerSync's relay.", "warn");
	}

	/**
	 * In the background: asks the router to forward the game's port (UPnP, else PCP or NAT-PMP), and finds this PC's
	 * public IPv6 addresses. Each that friends elsewhere can reach is added to the addresses KeplerSync hands them.
	 * The mapping is renewed every half hour while the game is open.
	 */
	private void openRouterPort() {
		Thread t = new Thread(() -> {
			List<String> more = new ArrayList<>();
			PortMapper.Attempt a = PortMapper.open(port, "TCP", "Tandem");
			if (a.mapping == null) {
				routerProblem = a.why;
				TandemClient.LOG.info("Tandem: the router didn't forward port {} ({})", port, a.why);
			} else if (!a.mapping.reachable()) {
				routerProblem = "behind another NAT";
				mappings.add(a.mapping);
				TandemClient.LOG.info("Tandem: the router forwarded port {} ({}), but it's behind another NAT, so friends can't reach it", port, a.mapping);
			} else if (!sameAsRelaySees(a.mapping.externalIp)) {
				routerProblem = "behind another NAT";
				mappings.add(a.mapping);
				TandemClient.LOG.info("Tandem: the router forwarded port {} ({}), but KeplerSync sees another public address, so friends can't reach it", port, a.mapping);
			} else {
				mappings.add(a.mapping);
				more.add(a.mapping.address());
				TandemClient.LOG.info("Tandem: the router forwarded port {} ({})", port, a.mapping);
				probe = a.mapping.externalPort;
				RelayTunnel.Host r = relay;
				if (r != null) r.tellRelay("PROBE " + probe);
			}
			// The UDP end too: friends' punches then get in even through strict routers.
			Punch.Host p = punch;
			if (p != null) {
				PortMapper.Attempt u = PortMapper.open(p.localPort(), "UDP", "Tandem");
				if (u.mapping != null) {
					mappings.add(u.mapping);
					TandemClient.LOG.info("Tandem: the router forwarded UDP port {} ({})", p.localPort(), u.mapping);
				}
			}
			more.addAll(ipv6Addresses(port, worldName()));
			if (current != this) {
				PortMapper.closeAll(mappings);
				return;
			}
			if (!more.isEmpty()) {
				List<String> all = new ArrayList<>(direct);
				all.addAll(more);
				direct = all;
				try {
					Account.get().api.updatePlay(sessionId, all);
				} catch (IOException e) {
					TandemClient.LOG.info("Tandem couldn't tell KeplerSync about the router's port: {}", e.getMessage());
				}
			}
			while (current == this && !mappings.isEmpty()) {
				try {
					Thread.sleep(30 * 60_000);
				} catch (InterruptedException e) {
					return;
				}
				for (PortMapper.Mapping m : mappings) {
					try {
						m.renew();
					} catch (IOException e) {
						TandemClient.LOG.info("Tandem couldn't renew {}: {}", m, e.getMessage());
					}
				}
			}
		}, "Tandem router");
		t.setDaemon(true);
		t.start();
	}

	/** Whether [ip] is the public address the relay sees this PC at; true while that isn't known yet. */
	private boolean sameAsRelaySees(String ip) {
		Punch.Host p = punch;
		for (int i = 0; i < 20 && p != null && p.publicAddress() == null; i++) {
			try {
				Thread.sleep(100);
			} catch (InterruptedException e) {
				return true;
			}
		}
		java.net.InetSocketAddress seen = p == null ? null : p.publicAddress();
		// Only a public IPv4 view counts (a relay on this PC, in the dev checks, sees 127.0.0.1).
		if (seen == null || !(seen.getAddress() instanceof java.net.Inet4Address) || !Addresses.isPublic(seen.getAddress().getHostAddress())) return true;
		return seen.getAddress().getHostAddress().equals(ip);
	}

	private String worldName() {
		return server.getWorldData().getLevelName();
	}

	/**
	 * This PC's public IPv6 addresses for the game, once the game is known to answer over IPv6 (a launcher that turns
	 * IPv6 off in Java leaves it listening on IPv4 only). Most home routers keep unasked IPv6 out; some don't.
	 */
	static List<String> ipv6Addresses(int port, String world) {
		List<String> out = new ArrayList<>();
		List<InetAddress> v6 = Addresses.globalV6();
		if (v6.isEmpty()) return out;
		try {
			if (!Ping.description(Ping.status("[::1]:" + port, 2000)).contains(world)) return out;
		} catch (IOException | RuntimeException e) {
			TandemClient.LOG.info("Tandem: the game doesn't answer over IPv6 ({})", e.getMessage());
			return out;
		}
		for (InetAddress a : v6) if (out.size() < 3) out.add(Addresses.hostPort(a, port));
		return out;
	}

	/** This PC's addresses on its own networks, for friends at the same place. */
	static List<String> lanAddresses(int port) {
		List<String> out = new ArrayList<>();
		for (InetAddress a : Addresses.lanV4()) out.add(Addresses.hostPort(a, port));
		// The same PC (two games, as in the dev checks).
		out.add("127.0.0.1:" + port);
		return out.size() > 8 ? out.subList(out.size() - 8, out.size()) : out;
	}

	/** Invites a friend: KeplerSync tells them, and their Minecraft account may join. */
	public CompletableFuture<Void> invite(String userId, UUID minecraftId) {
		if (minecraftId != null) {
			allowed.add(minecraftId);
			accounts.put(userId, minecraftId);
		}
		invited.add(userId);
		return WorldSync.get().async(() -> {
			Account.get().api.invite(sessionId, userId);
			return null;
		});
	}

	/** Withdraws an invite that hasn't been used: KeplerSync tells them, and their account can't get in. */
	public CompletableFuture<Void> uninvite(String userId, UUID minecraftId) {
		invited.remove(userId);
		// A member of the synced world being hosted stays welcome without an invite.
		WorldRules rules = WorldRules.of(server);
		if (minecraftId != null && (rules == null || !rules.isMember(minecraftId))) allowed.remove(minecraftId);
		return WorldSync.get().async(() -> {
			Account.get().api.uninvite(sessionId, userId);
			return null;
		});
	}

	/** Kicks a player and withdraws their invite: they'd need a new one to come back. */
	public void kick(String name, String userId) {
		ServerPlayer p = server.getPlayerList().getPlayerByName(name);
		if (p != null) {
			allowed.remove(p.getUUID());
			p.connection.disconnect(Component.literal("The host removed you from this game."));
		}
		if (userId != null) {
			invited.remove(userId);
			WorldSync.get().async(() -> {
				Account.get().api.uninvite(sessionId, userId);
				return null;
			});
		}
	}

	/**
	 * The world's owner, playing in this game, took [userId] out of it through KeplerSync: they're kicked and can't
	 * come back to this game, member or not.
	 */
	public static void kickedByOwner(String sessionId, String userId) {
		Hosting h = current;
		if (h == null || !h.sessionId.equals(sessionId)) return;
		h.server.execute(() -> {
			h.invited.remove(userId);
			WorldRules rules = WorldRules.of(h.server);
			UUID id = rules != null && rules.player(userId) != null ? rules.player(userId) : h.accounts.get(userId);
			if (id == null) return;
			h.allowed.remove(id);
			h.removed.add(id);
			ServerPlayer p = h.server.getPlayerList().getPlayer(id);
			if (p != null) p.connection.disconnect(Component.literal("The world's owner removed you from this game."));
		});
	}

	/** A KeplerSync user's Minecraft account id, as the server gives it (with or without dashes). */
	public static UUID minecraftId(String id) {
		if (id == null) return null;
		try {
			return id.contains("-") ? UUID.fromString(id) : UndashedUuid.fromStringLenient(id);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	/** Whether [player] came in over a punched UDP path rather than straight to this PC. */
	public boolean punched(ServerPlayer player) {
		Punch.Host p = punch;
		return p != null && player.connection.getRemoteAddress() instanceof InetSocketAddress a && p.pipes(a.getPort());
	}

	/** Whether [player] joined through KeplerSync's relay rather than straight to this PC. */
	public boolean relayed(ServerPlayer player) {
		RelayTunnel.Host r = relay;
		return r != null && player.connection.getRemoteAddress() instanceof InetSocketAddress a && r.pipes(a.getPort());
	}

	public boolean relayConnected() {
		RelayTunnel.Host r = relay;
		return r != null && r.connected();
	}

	private void close() {
		current = null;
		RelayTunnel.Host r = relay;
		if (r != null) r.close();
		Punch.Host p = punch;
		if (p != null) p.close();
		PortMapper.closeAll(mappings);
		Thread t = new Thread(() -> {
			try {
				Account.get().api.endPlay(sessionId);
			} catch (IOException ignored) {
				// The session runs out by itself after a day.
			}
		}, "Tandem stop hosting");
		t.setDaemon(true);
		t.start();
	}
}
