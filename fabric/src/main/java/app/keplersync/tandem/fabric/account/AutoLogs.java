package app.keplersync.tandem.fabric.account;

import app.keplersync.tandem.core.KeplerSyncApi;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;

/**
 * Send logs without anyone pressing it, since testers forget: when Tandem warns about something (a failed sync, join
 * or hand-off), the log goes to KeplerSync a little later, with what followed; and a crash report left by the last
 * game goes once the next one signs in. At most one automatic send every half hour (KeplerSync takes 10 a day each).
 */
public final class AutoLogs {
	/** Long enough to catch what happens right after the first warning. */
	private static final long WAIT_MS = 15_000;
	private static final long COOLDOWN_MS = 30 * 60_000;
	/** Under the server's limit for one log. */
	private static final int MAX_CHARS = 400_000;

	private static final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "Tandem logs");
		t.setDaemon(true);
		return t;
	});
	private static String reason;
	private static boolean scheduled;
	private static long nextAllowed;

	private AutoLogs() {}

	/** Tandem warned about [what], with [thrown] if any: send the log soon, unless it's an answer that's expected. */
	static synchronized void trouble(String what, Throwable thrown) {
		if (Account.get() == null || !Prefs.get().autoLogs || expected(thrown)) return;
		if (reason == null) reason = what;
		if (scheduled) return;
		scheduled = true;
		long delay = Math.max(WAIT_MS, nextAllowed - System.currentTimeMillis());
		timer.schedule(AutoLogs::send, delay, TimeUnit.MILLISECONDS);
	}

	/** KeplerSync saying the world is hosted or gone, or the player cancelling, isn't a problem to look into. */
	private static boolean expected(Throwable t) {
		for (Throwable c = t; c != null; c = c.getCause()) {
			if (c instanceof CancellationException) return true;
			if (c instanceof KeplerSyncApi.Refused && (((KeplerSyncApi.Refused) c).status == 404 || ((KeplerSyncApi.Refused) c).status == 409)) return true;
		}
		return false;
	}

	/** The game is closing with a send still waiting: send it now, since players often quit right after trouble. */
	static void flush() {
		synchronized (AutoLogs.class) {
			if (!scheduled) return;
		}
		send();
	}

	private static void send() {
		String why;
		synchronized (AutoLogs.class) {
			if (!scheduled) return;
			why = reason;
			reason = null;
			scheduled = false;
			nextAllowed = System.currentTimeMillis() + COOLDOWN_MS;
		}
		post("Automatic: " + why, LogBook.text());
	}

	/** Sends nothing that would warn again: a failure here only means KeplerSync isn't reachable right now. */
	private static void post(String note, String body) {
		Account a = Account.get();
		if (a == null || !a.signedIn() || body.trim().isEmpty()) return;
		try {
			a.api.sendLogs(version(), SharedConstants.getCurrentVersion().getName(), note, body.length() > MAX_CHARS ? body.substring(body.length() - MAX_CHARS) : body);
		} catch (IOException | RuntimeException ignored) {
			// Nothing more to do.
		}
	}

	static String version() {
		return FabricLoader.getInstance().getModContainer("tandem").map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("dev");
	}

	/**
	 * After signing in: the newest crash report since the last look, if the game crashed. The first look only notes the
	 * time, so reports from before Tandem are never sent.
	 */
	static void crashReports() {
		timer.execute(() -> {
			if (!Prefs.get().autoLogs) return;
			Path game = FabricLoader.getInstance().getGameDir();
			Path seen = FabricLoader.getInstance().getConfigDir().resolve("tandem-crash-seen.txt");
			try {
				long last = Files.exists(seen) ? Long.parseLong(new String(Files.readAllBytes(seen), StandardCharsets.UTF_8).trim()) : -1;
				Files.createDirectories(seen.getParent());
				Files.write(seen, Long.toString(System.currentTimeMillis()).getBytes(StandardCharsets.UTF_8));
				Path reports = game.resolve("crash-reports");
				if (last < 0 || !Files.isDirectory(reports)) return;
				Path newest = null;
				try (Stream<Path> files = Files.list(reports)) {
					for (Path f : (Iterable<Path>) files::iterator) {
						long t = Files.getLastModifiedTime(f).toMillis();
						if (t > last && (newest == null || t > Files.getLastModifiedTime(newest).toMillis())) newest = f;
					}
				}
				if (newest != null) post("Automatic: the game crashed (" + newest.getFileName() + ")", new String(Files.readAllBytes(newest), StandardCharsets.UTF_8));
			} catch (IOException | RuntimeException ignored) {
				// No crash report to send.
			}
		});
	}
}
