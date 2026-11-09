package app.keplersync.tandem.fabric.play;

import app.keplersync.tandem.core.KeplerSyncApi;
import app.keplersync.tandem.core.Addresses;
import app.keplersync.tandem.core.Ping;
import app.keplersync.tandem.core.Punch;
import app.keplersync.tandem.core.RelayTunnel;
import app.keplersync.tandem.fabric.Guard;
import app.keplersync.tandem.fabric.TandemClient;
import app.keplersync.tandem.fabric.account.Account;
import app.keplersync.tandem.fabric.sync.WorldSync;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.client.networking.v1.ClientLoginConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;

/**
 * Joining a friend's game: ask KeplerSync (which checks the invite), then race the ways in: the host directly over TCP
 * (the same network, a port its router opened, its IPv6 address) and hole punching over UDP, with the relay last.
 * Minecraft's own connect screen does the rest.
 */
public final class Joining {
	/** Where to connect, and what's known about the game there. */
	public static final class Target {
		public final KeplerSyncApi.JoinInfo info;
		public final String address;
		/** lan, router, ipv6, punched or relay. */
		public final String via;
		/** For the relay: why the direct ways didn't work. */
		public final String reason;
		public final boolean relayed;

		Target(KeplerSyncApi.JoinInfo info, String address, String via, String reason) {
			this.info = info;
			this.address = address;
			this.via = via;
			this.reason = reason;
			this.relayed = via.equals("relay");
		}
	}

	/** The join under way, until Minecraft is in (or gave up): then KeplerSync is told which way it went. */
	private static volatile Target pending;

	public static void init() {
		ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> Guard.run("join counts", () -> report(true)));
		ClientLoginConnectionEvents.DISCONNECT.register((handler, mc) -> Guard.run("join counts", () -> report(false)));
	}

	/** Tells KeplerSync how the pending join went, for its counts. Joining goes on without it. */
	private static void report(boolean in) {
		Target t = pending;
		pending = null;
		if (t == null || Account.get() == null) return;
		WorldSync.get().async(() -> {
			Account.get().api.connected(t.info.sessionId, in ? t.via : "failed", in ? t.reason : t.via);
			return null;
		});
	}

	/** The relay end for the game being joined; replaced by the next join. */
	private static RelayTunnel.Guest guest;
	/** The punched end for the game being joined; replaced by the next join. */
	private static Punch.Guest punched;
	/** Joins look for a way in on their own threads, so the sync thread isn't held for seconds. */
	private static final ExecutorService WAYS = Executors.newCachedThreadPool(r -> {
		Thread t = new Thread(r, "Tandem join");
		t.setDaemon(true);
		return t;
	});
	/** How long the direct addresses get before punching (already under way) or the relay takes over. */
	private static final int DIRECT_MS = 1500;
	/** How long punching gets, from the start of the join. */
	private static final int PUNCH_MS = 4000;
	/** The friend's game this one last connected to, for the pause card. */
	public static volatile Target current;

	private Joining() {}

	private static <T> CompletableFuture<T> async(Callable<T> work) {
		return CompletableFuture.supplyAsync(() -> {
			try {
				return work.call();
			} catch (Exception e) {
				throw new CompletionException(e);
			}
		}, WAYS);
	}

	/** Asks to join, then finds the way in, telling [status] each step. Off the game thread. */
	public static CompletableFuture<Target> prepare(String sessionId, Consumer<String> status) {
		return async(() -> {
			status.accept("Asking KeplerSync…");
			KeplerSyncApi.JoinInfo info = Account.get().api.join(sessionId);
			String host = info.host.displayName;
			boolean forceRelay = Boolean.getBoolean("tandem.forceRelay");
			boolean forcePunch = Boolean.getBoolean("tandem.forcePunch");
			status.accept("Looking for " + host + "'s game…");
			synchronized (Joining.class) {
				if (punched != null) punched.close();
				punched = null;
			}
			CompletableFuture<Punch.Guest> punching = forceRelay ? null : punch(info);
			if (!forceRelay && !forcePunch) {
				String direct = direct(info);
				if (direct != null) {
					if (punching != null) punching.thenAccept(Punch.Guest::close);
					TandemClient.LOG.info("Tandem: joining {} directly at {}", host, direct);
					return new Target(info, direct, way(direct), null);
				}
			}
			String why;
			if (punching == null) {
				why = forceRelay ? "forced" : "relay_no_udp";
			} else {
				status.accept("Connecting directly…");
				try {
					Punch.Guest g = punching.get(PUNCH_MS + 1000, TimeUnit.MILLISECONDS);
					String local = "127.0.0.1:" + g.port();
					if (reaches(local, info.worldName, 5000)) {
						synchronized (Joining.class) {
							punched = g;
						}
						TandemClient.LOG.info("Tandem: joining {} directly, punched through", host);
						return new Target(info, local, "punched", null);
					}
					g.close();
					why = "punch_timeout";
				} catch (java.util.concurrent.ExecutionException e) {
					why = e.getCause() instanceof Punch.Failed ? ((Punch.Failed) e.getCause()).reason : "punch_timeout";
				} catch (java.util.concurrent.TimeoutException e) {
					why = "punch_timeout";
				}
				TandemClient.LOG.info("Tandem: couldn't punch through to {} ({})", host, why);
				if (why.equals("punch_timeout") && !hasPublicAddress(info)) why = "no_public_addr";
			}
			if (forcePunch) throw new IOException("Couldn't punch through to " + host + "'s game (" + why + ").");
			if (info.relay == null && "allowance".equals(info.relayOff)) {
				throw new IOException("Couldn't reach " + host + "'s game directly, and you've used this month's relay allowance. It comes back on the 1st.");
			}
			if (info.relay == null && "off".equals(info.relayOff)) {
				throw new IOException("Couldn't reach " + host + "'s game directly, and KeplerSync's relay is paused for a moment. Try again soon.");
			}
			if (info.relay == null) throw new IOException(host + "'s game isn't answering. It may have just closed.");
			status.accept("Connecting through KeplerSync's relay…");
			synchronized (Joining.class) {
				if (guest != null) guest.close();
				guest = new RelayTunnel.Guest(info.relay.address, info.relay.ticket);
				String local = "127.0.0.1:" + guest.port();
				// The relay answers even when the host is gone, so ask the host's game itself before Minecraft tries.
				if (!reaches(local, info.worldName, 10_000)) throw new IOException(host + "'s game isn't answering. It may have just closed.");
				TandemClient.LOG.info("Tandem: joining {} through the relay ({})", host, why);
				return new Target(info, local, "relay", why);
			}
		});
	}

	/**
	 * The host's direct addresses, all tried at once: the first that answers as the host's game, or null. IPv6 ones
	 * only when this PC has IPv6.
	 */
	private static String direct(KeplerSyncApi.JoinInfo info) throws InterruptedException {
		boolean v6 = !Addresses.globalV6().isEmpty();
		List<CompletableFuture<String>> tries = new ArrayList<>();
		for (String d : info.direct) {
			if (d.startsWith("[") && !v6) continue;
			tries.add(async(() -> reaches(d, info.worldName, DIRECT_MS) ? d : null));
		}
		long end = System.currentTimeMillis() + DIRECT_MS + 300;
		while (System.currentTimeMillis() < end) {
			boolean pending = false;
			for (CompletableFuture<String> t : tries) {
				if (!t.isDone()) pending = true;
				else if (!t.isCompletedExceptionally() && t.join() != null) return t.join();
			}
			if (!pending) return null;
			Thread.sleep(10);
		}
		return null;
	}

	/** Punching towards the host, started at once: the guest end once a path works. Null without a punch ticket. */
	private static CompletableFuture<Punch.Guest> punch(KeplerSyncApi.JoinInfo info) {
		if (info.punch == null) return null;
		InetSocketAddress tcp = RelayTunnel.address(info.punch.address);
		InetSocketAddress first = new InetSocketAddress(tcp.getHostString(), tcp.getPort());
		InetSocketAddress second = new InetSocketAddress(tcp.getHostString(), tcp.getPort() + 1);
		return async(() -> Punch.Guest.connect(Punch.udp(0), first, second, info.punch.ticket, PUNCH_MS));
	}

	/** Whether the host offered an address the internet can reach (its router's, or IPv6). */
	private static boolean hasPublicAddress(KeplerSyncApi.JoinInfo info) {
		for (String d : info.direct) if (!way(d).equals("lan")) return true;
		return false;
	}

	/** Which direct way [address] is: this network (or this PC), IPv6, or a port the host's router opened. */
	static String way(String address) {
		if (address.startsWith("[")) return "ipv6";
		java.net.InetSocketAddress a = RelayTunnel.address(address);
		java.net.InetAddress ip = a.getAddress();
		return ip != null && (ip.isSiteLocalAddress() || ip.isLoopbackAddress() || ip.isLinkLocalAddress()) ? "lan" : "router";
	}

	/** A game answers at [address], and it's the world we were asked into (not some other game on the network). */
	private static boolean reaches(String address, String world, int timeoutMs) {
		try {
			return Ping.description(Ping.status(address, timeoutMs)).contains(world);
		} catch (IOException | RuntimeException e) {
			return false;
		}
	}

	/** Leaves the current world (its session saves as usual) and connects. On the render thread. */
	public static void connect(Target t) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level != null) {
			mc.level.disconnect();
			mc.disconnect(new TitleScreen());
		}
		current = t;
		pending = t;
		ServerData data = new ServerData(t.info.worldName, t.address, ServerData.Type.OTHER);
		ConnectScreen.startConnecting(new TitleScreen(), mc, ServerAddress.parseString(t.address), data, false, null);
	}
}
