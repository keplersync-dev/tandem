package app.keplersync.tandem.fabric.sync;

import app.keplersync.tandem.core.GameList;
import app.keplersync.tandem.core.GamePack;
import app.keplersync.tandem.fabric.TandemClient;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModEnvironment;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;

/**
 * This game as the worlds saved in it remember it: the loader, the launcher profile it runs in and the modpack that came
 * from ({@link GamePack}), a fingerprint of everything it adds to a world, and every mod. Sent with each world and each
 * save, and noted in the list every Tandem game on this PC shares ({@link GameList}), so the Synced worlds tab can say
 * where a world plays.
 *
 * <p>What a player changed in a modpack isn't read from the launcher: CurseForge's own record lists mods it installed
 * later as if the player added them. The tab compares the world's mods with the player's own copy instead.
 */
public final class ThisGame {
	/** The shape below; a later Tandem that changes it bumps this, so an older description can be told apart. */
	static final int VERSION = 1;

	private static volatile JsonObject described;

	private ThisGame() {}

	public static Path dir() {
		return FabricLoader.getInstance().getGameDir().toAbsolutePath().normalize();
	}

	/** This game, worked out the first time it's asked for, once mods have registered everything. */
	public static JsonObject get() {
		JsonObject d = described;
		if (d != null) return d;
		synchronized (ThisGame.class) {
			if (described == null) described = describe();
			return described;
		}
	}

	private static JsonObject describe() {
		Set<String> registered = WorldSync.registered();
		JsonObject g = new JsonObject();
		g.addProperty("v", VERSION);
		g.addProperty("loader", FabricLoader.getInstance().isModLoaded("quilt_loader") ? "quilt" : "fabric");
		g.addProperty("mc", WorldSync.mcVersion());
		g.addProperty("contentKey", contentKey());
		List<ModContainer> top = new ArrayList<>();
		for (ModContainer m : FabricLoader.getInstance().getAllMods()) {
			if (!m.getContainingMod().isPresent() && !WorldSync.builtIn(m)) top.add(m);
		}
		top.sort(Comparator.comparing(m -> m.getMetadata().getName().toLowerCase(Locale.ROOT)));
		JsonArray mods = new JsonArray();
		for (ModContainer m : top) {
			JsonObject o = new JsonObject();
			o.addProperty("id", m.getMetadata().getId());
			o.addProperty("name", GamePack.clean(m.getMetadata().getName()));
			o.addProperty("version", m.getMetadata().getVersion().getFriendlyString());
			if (m.getMetadata().getEnvironment() != ModEnvironment.CLIENT && WorldSync.addsContent(m, registered)) o.addProperty("adds", true);
			mods.add(o);
		}
		g.add("mods", mods);
		try {
			JsonObject pack = GamePack.read(dir(), officialLauncher(), WorldSync.mcVersion());
			if (pack != null) g.add("pack", pack);
		} catch (IOException | RuntimeException e) {
			TandemClient.LOG.warn("Couldn't tell which launcher profile this game is", e);
		}
		return GameList.fit(g, MAX_CHARS);
	}

	/** Under KeplerSync's 256 KB for a world's game, which turns the whole save away when it's over. */
	static final int MAX_CHARS = 200_000;

	/**
	 * Everything this game adds to a world, as one fingerprint: every entry in Minecraft's registries (blocks, items,
	 * creatures, effects…) and every namespace with world generation in its data. Two games with the same one can open
	 * each other's worlds without anything vanishing, whatever screen-only and speed mods each has. A config that turns a
	 * block off changes it too.
	 */
	static String contentKey() {
		Set<String> keys = new TreeSet<>();
		for (Registry<?> r : BuiltInRegistries.REGISTRY) {
			String in = r.key().location().toString();
			for (ResourceLocation k : r.keySet()) keys.add(in + ' ' + k);
		}
		for (ModContainer m : FabricLoader.getInstance().getAllMods()) {
			for (Path root : m.getRootPaths()) {
				for (Path ns : WorldSync.list(root.resolve("data"))) {
					for (String gen : new String[] {"worldgen", "dimension", "dimension_type"}) {
						if (Files.isDirectory(ns.resolve(gen))) keys.add(gen + ' ' + WorldSync.name(ns));
					}
				}
			}
		}
		try {
			MessageDigest sha = MessageDigest.getInstance("SHA-256");
			for (String k : keys) {
				sha.update(k.getBytes(StandardCharsets.UTF_8));
				sha.update((byte) '\n');
			}
			StringBuilder hex = new StringBuilder();
			for (byte b : sha.digest()) hex.append(String.format("%02x", b));
			return hex.substring(0, 32);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	/** The Minecraft Launcher's own folder, where {@code launcher_profiles.json} names each profile's game folder. */
	private static Path officialLauncher() {
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		String home = System.getProperty("user.home");
		if (os.contains("win")) {
			String appdata = System.getenv("APPDATA");
			return appdata != null ? Paths.get(appdata, ".minecraft") : null;
		}
		if (os.contains("mac")) return Paths.get(home, "Library", "Application Support", "minecraft");
		return Paths.get(home, ".minecraft");
	}

	/** The list of this PC's Tandem games, shared by every launcher; {@code -Dtandem.games} moves it (dev runs). */
	public static Path listFile() {
		String set = System.getProperty("tandem.games");
		if (set != null && !set.isEmpty()) return Paths.get(set);
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		String home = System.getProperty("user.home");
		Path base;
		if (os.contains("win")) {
			String appdata = System.getenv("APPDATA");
			base = (appdata != null ? Paths.get(appdata) : Paths.get(home, "AppData", "Roaming")).resolve("KeplerSync").resolve("Tandem");
		} else if (os.contains("mac")) {
			base = Paths.get(home, "Library", "Application Support", "KeplerSync", "Tandem");
		} else {
			String data = System.getenv("XDG_DATA_HOME");
			base = (data != null && !data.isEmpty() ? Paths.get(data) : Paths.get(home, ".local", "share")).resolve("keplersync").resolve("tandem");
		}
		return base.resolve("games.json");
	}

	/** Notes this game in the PC's list, or brings its entry up to date. */
	public static void remember() {
		try {
			GameList.remember(listFile(), GameList.entry(dir(), get(), System.currentTimeMillis()));
		} catch (IOException e) {
			TandemClient.LOG.warn("Couldn't note this game in {}", listFile(), e);
		}
	}

	/** Every Tandem game noted on this PC, the most recently started first. */
	public static List<JsonObject> onThisPc() {
		return GameList.read(listFile());
	}
}
