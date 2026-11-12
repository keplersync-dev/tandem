package app.keplersync.tandem.fabric.account;

import app.keplersync.tandem.fabric.TandemClient;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;

/** Tandem's own settings on this PC, kept in {@code config/tandem.json}. */
public final class Prefs {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static Prefs current;

	public boolean friendsOnline = false;
	public boolean inviteSound = true;
	/** Send logs by itself when something goes wrong. */
	public boolean autoLogs = true;
	/** The Tandem button on the title screen; off, the Open Tandem key still opens it. */
	public boolean titleButton = true;
	/** How each synced world ran the last time this PC hosted it: its id → milliseconds a tick, on average. */
	public java.util.Map<String, Float> hosted = new java.util.HashMap<>();
	/** Bytes a second this PC's last big upload reached at full speed; 0 until one has. */
	public long uploadSpeed;

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("tandem.json");
	}

	public static synchronized Prefs get() {
		if (current == null) {
			try {
				Path f = file();
				current = Files.exists(f) ? GSON.fromJson(new String(Files.readAllBytes(f), StandardCharsets.UTF_8), Prefs.class) : null;
			} catch (IOException | RuntimeException e) {
				TandemClient.LOG.info("Tandem's settings couldn't be read, so they're back to the defaults", e);
			}
			if (current == null) current = new Prefs();
		}
		return current;
	}

	public static synchronized void save() {
		try {
			Files.createDirectories(file().getParent());
			// Beside it, then over it: a game closed mid-write can't leave half a file.
			Path tmp = file().resolveSibling("tandem.json.tmp");
			Files.write(tmp, GSON.toJson(get()).getBytes(StandardCharsets.UTF_8));
			Files.move(tmp, file(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			TandemClient.LOG.info("Tandem couldn't save its settings", e);
		}
	}
}
