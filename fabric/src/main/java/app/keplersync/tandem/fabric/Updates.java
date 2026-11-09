package app.keplersync.tandem.fabric;

import app.keplersync.tandem.core.Progress;
import app.keplersync.tandem.core.Swap;
import app.keplersync.tandem.core.Update;
import app.keplersync.tandem.fabric.ui.Ingame;
import app.keplersync.tandem.fabric.ui.KitKt;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.VersionParsingException;
import net.fabricmc.loader.api.metadata.ModOrigin;
import net.fabricmc.loader.api.metadata.version.VersionPredicate;
import net.minecraft.SharedConstants;

/**
 * Tandem's own updates, like Essential's: once a launch it asks Modrinth for a newer Tandem for this game; Update
 * downloads it, checks it's signed by KeplerSync, and a small helper puts it in place of this jar once Minecraft
 * closes (see {@link Swap}). Nothing is installed without the player pressing Update.
 */
public final class Updates {
	/** SHA-256 of the public half of KeplerSync's Tandem signing key: only jars it signed are ever put in. */
	static final String KEY = "7b1972a8889d95acb7ae126cf5b19199b04200f7d7e889610bc6cdfaf40b90fd";
	// shortcut: looked up by slug until the Modrinth project exists; use its id once it's reserved.
	static final String PROJECT = "tandem";
	private static final String API = System.getProperty("tandem.updates.api", "https://api.modrinth.com");
	private static final String FILES = System.getProperty("tandem.updates.files", "https://cdn.modrinth.com/");

	public enum State { NONE, AVAILABLE, GETTING, READY, FAILED }

	public static volatile State state = State.NONE;
	/** The newer version, once known. */
	public static volatile String version;
	/** Why the last try failed, in words for the player. */
	public static volatile String problem;
	public static volatile Progress progress = new Progress();
	/** Goes up whenever the above changes, so screens rebuild. */
	public static volatile int changes;

	private static volatile Update.Release release;
	/** Kept so its stdin, which tells the helper the game closed, stays open until then. */
	private static Process helper;

	private Updates() {}

	public static void init() {
		boolean dev = FabricLoader.getInstance().isDevelopmentEnvironment();
		if (!Boolean.parseBoolean(System.getProperty("tandem.updates", dev ? "false" : "true"))) return;
		ClientLifecycleEvents.CLIENT_STARTED.register(c -> Guard.run("updates", () -> background(Updates::check)));
	}

	/** Whether Tandem can put an update in itself: it runs from a jar of its own in a folder it may write to. */
	public static boolean canInstall() {
		return ourJar() != null;
	}

	/** Starts getting the update. False when Tandem can't put it in itself, so the player is sent to the page. */
	public static synchronized boolean install() {
		if (state == State.GETTING || state == State.READY) return true;
		Update.Release r = release;
		Path jar = ourJar();
		if (r == null || jar == null) {
			TandemClient.LOG.info("Tandem update: can't install here (release {}, jar {}), sending the player to the page", r != null, jar);
			return false;
		}
		progress = new Progress();
		set(State.GETTING, null);
		background(() -> {
			try {
				long start = System.nanoTime();
				TandemClient.LOG.info("Tandem update: downloading {} ({} bytes) from {} to replace {}", r.version, r.size, r.url, jar);
				Path staged = Update.download(r, work(), agent(), KEY, progress);
				TandemClient.LOG.info("Tandem update: {} downloaded in {} ms, hash and KeplerSync signature check out: {}", r.version,
						(System.nanoTime() - start) / 1_000_000, staged);
				fits(staged, r.version);
				TandemClient.LOG.info("Tandem update: {} fits this game (Minecraft, Fabric Loader, Fabric API, Java)", r.version);
				Properties p = new Properties();
				p.setProperty("version", r.version);
				p.setProperty("staged", staged.toString());
				p.setProperty("target", jar.toString());
				try (Writer w = Files.newBufferedWriter(work().resolve("pending.properties"), StandardCharsets.UTF_8)) {
					p.store(w, null);
				}
				startHelper(jar, staged);
				TandemClient.LOG.info("Tandem {} is downloaded and goes in when Minecraft closes", r.version);
				set(State.READY, null);
				// Java can't swap a mod's code under a running game, so it's at the next start, as with Essential.
				Ingame.notice(KitKt.tr("tandem.tandem_s_is_downloaded", r.version), KitKt.tr("tandem.restart_minecraft_to_finish_updating"), "check");
			} catch (Exception e) {
				TandemClient.LOG.warn("Tandem couldn't update to {}", r.version, e);
				set(State.FAILED, e.getMessage() != null ? e.getMessage().replaceAll("\\.$", "") : e.getClass().getSimpleName());
			}
		});
		return true;
	}

	private static void check() {
		String current = current();
		Path pending = work().resolve("pending.properties");
		TandemClient.LOG.info("Tandem update: this is {} from {}; can install itself: {}; asking {} for project {}", current, ourJar(),
				ourJar() != null, API, PROJECT);
		// Nothing waiting: whatever is here is a download the game closed on (a half file, say).
		if (!Files.isRegularFile(pending)) clean();
		else {
			Properties p = new Properties();
			try (Reader r = Files.newBufferedReader(pending, StandardCharsets.UTF_8)) {
				p.load(r);
			} catch (IOException e) {
				TandemClient.LOG.info("Tandem couldn't read its waiting update: {}", e.getMessage());
			}
			String waiting = p.getProperty("version", "");
			Path jar = ourJar();
			if (!Update.newer(waiting, current)) {
				// It went in at the last close (or this Tandem is newer anyway).
				TandemClient.LOG.info("Tandem update: {} was waiting and {} is running, so the update is done", waiting, current);
				clean();
				if (waiting.equals(current)) Ingame.notice(KitKt.tr("tandem.updated_tandem_to", current), KitKt.tr("tandem.whats_new_is_on_modrinth"), "check");
			} else if (jar != null && jar.toString().equals(p.getProperty("target"))) {
				// Downloaded last time, but the swap didn't happen (the helper was stopped): it goes in at this close.
				try {
					Path staged = Paths.get(p.getProperty("staged", ""));
					Update.verify(staged, KEY);
					startHelper(jar, staged);
					version = waiting;
					set(State.READY, null);
					return;
				} catch (IOException e) {
					TandemClient.LOG.info("Tandem's waiting update can't go in: {}", e.getMessage());
					clean();
				}
			} else {
				clean();
			}
		}
		try {
			Update.Release r = Update.newest(API, PROJECT, "fabric", SharedConstants.getCurrentVersion().getName(), current, agent(), FILES);
			if (r == null) {
				TandemClient.LOG.info("Tandem update: {} is the newest for Fabric {}", current, SharedConstants.getCurrentVersion().getName());
				return;
			}
			release = r;
			version = r.version;
			TandemClient.LOG.info("Tandem {} is out (this is {})", r.version, current);
			set(State.AVAILABLE, null);
			Ingame.notice(KitKt.tr("tandem.tandem_s_is_out", r.version), KitKt.tr("tandem.open_tandem_to_update_it", TandemClient.OPEN.getTranslatedKeyMessage().getString()), "up");
			// For checks: -Dtandem.updates.install=true presses Update by itself.
			if (Boolean.getBoolean("tandem.updates.install")) {
				install();
				if (Boolean.getBoolean("tandem.updates.installTwice")) install();
			}
		} catch (IOException | RuntimeException e) {
			// Not a problem worth sending logs about: Modrinth or the network being down.
			TandemClient.LOG.info("Tandem couldn't check for updates: {}", e.toString());
		}
	}

	/**
	 * Throws unless [jar] is Tandem [version] and everything it needs is here at a version it takes (this Minecraft,
	 * this Fabric Loader and Fabric API, Java), so the next launch can't stop at a missing dependency.
	 */
	private static void fits(Path jar, String version) throws IOException {
		JsonObject meta;
		try (JarFile f = new JarFile(jar.toFile())) {
			ZipEntry e = f.getEntry("fabric.mod.json");
			if (e == null) throw new IOException("That download isn't a Fabric mod.");
			try (InputStream in = f.getInputStream(e); Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
				meta = JsonParser.parseReader(r).getAsJsonObject();
			}
		}
		// By number only: Modrinth's version may carry a +1.21.1-fabric the jar's own version doesn't, or the other way round.
		String inJar = meta.get("version").getAsString();
		if (!"tandem".equals(meta.get("id").getAsString()) || Update.newer(inJar, version) || Update.newer(version, inJar)) {
			throw new IOException("That download isn't Tandem " + version + ".");
		}
		if (!meta.has("depends")) return;
		for (Map.Entry<String, JsonElement> dep : meta.getAsJsonObject("depends").entrySet()) {
			ModContainer have = FabricLoader.getInstance().getModContainer(dep.getKey()).orElse(null);
			if (have == null) throw new IOException("Tandem " + version + " needs " + dep.getKey() + ", which isn't installed.");
			List<String> wants = new ArrayList<>();
			if (dep.getValue().isJsonArray()) dep.getValue().getAsJsonArray().forEach(v -> wants.add(v.getAsString()));
			else wants.add(dep.getValue().getAsString());
			Version v = have.getMetadata().getVersion();
			boolean ok = false;
			for (String w : wants) {
				try {
					ok |= VersionPredicate.parse(w).test(v);
				} catch (VersionParsingException ex) {
					throw new IOException("Tandem " + version + " asks for " + dep.getKey() + " " + w + ", which can't be read.");
				}
			}
			if (!ok) throw new IOException("Tandem " + version + " needs " + have.getMetadata().getName() + " " + String.join(" or ", wants)
					+ ", and this game has " + v.getFriendlyString() + ". Update it in your launcher first.");
		}
	}

	/** This Tandem's own jar, if it runs from one in a folder it may write to; null in dev runs or inside another jar. */
	static Path ourJar() {
		ModContainer m = FabricLoader.getInstance().getModContainer("tandem").orElse(null);
		if (m == null || m.getOrigin().getKind() != ModOrigin.Kind.PATH || m.getOrigin().getPaths().size() != 1) return null;
		Path p = m.getOrigin().getPaths().get(0);
		return Files.isRegularFile(p) && p.getFileName().toString().endsWith(".jar") && Files.isWritable(p.getParent()) ? p : null;
	}

	/**
	 * Starts the helper that swaps the jars once this game closes, from its .class file alone (a jar it ran from would
	 * stay locked). One at a time: a newer download replaces the one waiting.
	 */
	private static synchronized void startHelper(Path target, Path staged) throws IOException {
		if (helper != null) helper.destroy();
		Path dir = work().resolve("helper");
		Path cls = dir.resolve(Swap.class.getName().replace('.', '/') + ".class");
		Files.createDirectories(cls.getParent());
		try (InputStream in = Swap.class.getResourceAsStream("Swap.class")) {
			if (in == null) throw new IOException("Tandem's update helper is missing from its jar.");
			Files.copy(in, cls, StandardCopyOption.REPLACE_EXISTING);
		}
		Path bin = Paths.get(System.getProperty("java.home"), "bin");
		Path java = Files.isRegularFile(bin.resolve("javaw.exe")) ? bin.resolve("javaw.exe")
				: Files.isRegularFile(bin.resolve("java.exe")) ? bin.resolve("java.exe") : bin.resolve("java");
		helper = new ProcessBuilder(java.toString(), "-cp", dir.toString(), Swap.class.getName(), target.toString(), staged.toString())
				.redirectErrorStream(true)
				.redirectOutput(work().resolve("swap.log").toFile())
				.start();
		TandemClient.LOG.info("Tandem update: helper {} waits for Minecraft to close, then puts {} in place of {} (its log: {})",
				helper.pid(), staged.getFileName(), target, work().resolve("swap.log"));
	}

	private static void clean() {
		try (java.util.stream.Stream<Path> files = Files.walk(work())) {
			files.sorted(java.util.Comparator.reverseOrder()).forEach(f -> f.toFile().delete());
		} catch (IOException ignored) {
			// Nothing left to clean.
		}
	}

	/** Under .tandem/, which Atlas never puts in a shared pack: the notes here name folders on this PC. */
	private static Path work() {
		return FabricLoader.getInstance().getGameDir().resolve(".tandem").resolve("update");
	}

	private static String current() {
		return FabricLoader.getInstance().getModContainer("tandem").map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("0");
	}

	private static String agent() {
		return "KeplerSync-Tandem/" + current() + " (keplersync.app)";
	}

	private static void set(State s, String why) {
		problem = why;
		state = s;
		changes++;
	}

	private static void background(Runnable r) {
		Thread t = new Thread(r, "Tandem updates");
		t.setDaemon(true);
		t.start();
	}
}
