package app.keplersync.tandem.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Every game on this PC that has run Tandem, in one JSON file every game shares: each game's folder, launcher profile,
 * Minecraft version, loader, content fingerprint and the mods that add something. The Synced worlds tab reads it to say
 * where a world plays. Games write under a file lock, so two starting at once both get in.
 */
public final class GameList {
	/** Games kept; the ones not seen longest go first. */
	static final int MAX_GAMES = 60;

	private GameList() {}

	/** Puts [game] (with its {@code dir}) first, replacing its old entry, and drops games whose folder is gone. */
	public static void remember(Path file, JsonObject game) throws IOException {
		Files.createDirectories(file.toAbsolutePath().getParent());
		try (FileChannel lock = FileChannel.open(file.resolveSibling(file.getFileName() + ".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
				FileLock held = lock.lock()) {
			JsonArray kept = new JsonArray();
			kept.add(game);
			String mine = GamePack.str(game, "dir");
			for (JsonObject g : read(file)) {
				String dir = GamePack.str(g, "dir");
				if (dir == null || dir.equals(mine) || !Files.isDirectory(Paths.get(dir))) continue;
				if (kept.size() < MAX_GAMES) kept.add(g);
			}
			Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
			Files.write(tmp, kept.toString().getBytes(StandardCharsets.UTF_8));
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		}
	}

	/** The games in [file], most recently started first; none if it's missing or unreadable. */
	public static List<JsonObject> read(Path file) {
		List<JsonObject> out = new ArrayList<>();
		try {
			if (!Files.isRegularFile(file)) return out;
			JsonElement all = JsonParser.parseString(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
			if (!all.isJsonArray()) return out;
			for (JsonElement e : all.getAsJsonArray()) if (e.isJsonObject()) out.add(e.getAsJsonObject());
		} catch (IOException | RuntimeException e) {
			// Half written by a game that crashed: the next game to start writes it again.
		}
		return out;
	}

	/**
	 * What the list keeps of a game described as {@code ThisGame} describes it: where it is and when it last started, its
	 * loader, Minecraft version, content fingerprint and pack, and only the mods that add something.
	 */
	public static JsonObject entry(Path dir, JsonObject game, long now) {
		JsonObject e = new JsonObject();
		e.addProperty("dir", dir.toString());
		e.addProperty("seen", now);
		for (String k : new String[] {"loader", "mc", "contentKey", "pack"}) if (game.has(k)) e.add(k, game.get(k));
		JsonArray adds = new JsonArray();
		JsonElement mods = game.get("mods");
		if (mods != null && mods.isJsonArray()) {
			for (JsonElement m : mods.getAsJsonArray()) {
				if (m.isJsonObject() && m.getAsJsonObject().has("adds")) adds.add(m);
			}
		}
		e.add("mods", adds);
		return e;
	}
}
