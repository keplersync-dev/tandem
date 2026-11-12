package app.keplersync.tandem.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The launcher profile a game folder belongs to, from the launcher's own files: its name, which launcher, and the modpack
 * it came from when the launcher keeps that in a file (CurseForge always, Prism for packs it installed). As JSON:
 * {@code {name, launcher, modpack?: {source, project, version?, title, label?, icon?, slug?}}}.
 */
public final class GamePack {
	private static final Pattern VERSION_IN_NAME = Pattern.compile("v?(\\d+(?:\\.\\d+)+)");

	private static final Pattern CURSEFORGE_PAGE = Pattern.compile("https://www\\.curseforge\\.com/minecraft/modpacks/([a-z0-9][a-z0-9-]{0,99})/?");

	private GamePack() {}

	public static JsonObject read(Path game, Path official) throws IOException {
		return read(game, official, null);
	}

	/**
	 * The profile [game] runs in, or null for a plain {@code .minecraft} no launcher names. [official] is the Minecraft
	 * Launcher's own folder (with {@code launcher_profiles.json}), or null. [mc] is the running Minecraft version, which
	 * picks between Minecraft Launcher profiles sharing one folder.
	 */
	public static JsonObject read(Path game, Path official, String mc) throws IOException {
		Path cf = game.resolve("minecraftinstance.json");
		if (Files.isRegularFile(cf)) return curseForge(cf);
		Path parent = game.getParent();
		if (parent != null && Files.isRegularFile(parent.resolve("instance.cfg"))) return prism(parent);
		if (parent != null && "profiles".equals(fileName(parent)) && parent.getParent() != null && Files.isDirectory(parent.getParent().resolve("meta"))) {
			return named(fileName(game), "modrinth-app");
		}
		JsonObject launcher = official != null ? minecraftLauncher(official, game, mc) : null;
		if (launcher != null) return launcher;
		String folder = fileName(game);
		return folder.equalsIgnoreCase(".minecraft") || folder.equalsIgnoreCase("minecraft") ? null : named(folder, "other");
	}

	private static JsonObject curseForge(Path file) throws IOException {
		JsonObject j = JsonParser.parseString(read(file)).getAsJsonObject();
		JsonObject pack = named(clean(str(j, "name")), "curseforge");
		JsonObject m = obj(j, "installedModpack");
		if (m == null || str(m, "addonID") == null) return pack;
		JsonObject mp = new JsonObject();
		mp.addProperty("source", "curseforge");
		mp.addProperty("project", str(m, "addonID"));
		JsonObject installed = obj(m, "installedFile");
		if (installed != null && str(installed, "id") != null) mp.addProperty("version", str(installed, "id"));
		mp.addProperty("title", clean(str(m, "name")));
		JsonObject manifest = obj(j, "manifest");
		String label = manifest != null ? str(manifest, "version") : null;
		if (label == null && installed != null) label = versionIn(str(installed, "fileName"));
		if (label != null) mp.addProperty("label", clean(label));
		String thumb = str(m, "thumbnailUrl");
		if (thumb != null && thumb.startsWith("https://media.forgecdn.net/")) mp.addProperty("icon", thumb);
		Matcher page = CURSEFORGE_PAGE.matcher(String.valueOf(str(m, "webSiteURL")));
		if (page.matches()) mp.addProperty("slug", page.group(1));
		pack.add("modpack", mp);
		return pack;
	}

	/** Prism Launcher and MultiMC: {@code instance.cfg} beside the game folder. Prism notes a Modrinth or CurseForge pack. */
	private static JsonObject prism(Path instance) throws IOException {
		Map<String, String> cfg = new HashMap<>();
		for (String line : read(instance.resolve("instance.cfg")).split("\\R")) {
			int eq = line.indexOf('=');
			if (eq > 0) cfg.put(line.substring(0, eq).trim(), unquote(line.substring(eq + 1).trim()));
		}
		Path root = instance.getParent() != null ? instance.getParent().getParent() : null;
		boolean multimc = root != null && Files.isRegularFile(root.resolve("multimc.cfg")) && !Files.isRegularFile(root.resolve("prismlauncher.cfg"));
		JsonObject pack = named(clean(cfg.getOrDefault("name", fileName(instance))), multimc ? "multimc" : "prism");
		String type = cfg.getOrDefault("ManagedPackType", "");
		String source = type.equals("modrinth") ? "modrinth" : type.equals("flame") ? "curseforge" : null;
		if ("true".equalsIgnoreCase(cfg.get("ManagedPack")) && cfg.get("ManagedPackID") != null && source != null) {
			JsonObject mp = new JsonObject();
			mp.addProperty("source", source);
			mp.addProperty("project", cfg.get("ManagedPackID"));
			if (cfg.get("ManagedPackVersionID") != null) mp.addProperty("version", cfg.get("ManagedPackVersionID"));
			mp.addProperty("title", clean(cfg.getOrDefault("ManagedPackName", pack.get("name").getAsString())));
			if (cfg.get("ManagedPackVersionName") != null) mp.addProperty("label", clean(cfg.get("ManagedPackVersionName")));
			pack.add("modpack", mp);
		}
		return pack;
	}

	/**
	 * The Minecraft Launcher's profile whose game folder is [game], from {@code launcher_profiles.json} in [official].
	 * Every profile without a folder of its own shares the launcher's, so: never a vanilla one (Tandem can't be running in
	 * it), only one for [mc] when that's known, and the one used last.
	 */
	private static JsonObject minecraftLauncher(Path official, Path game, String mc) throws IOException {
		Path file = official.resolve("launcher_profiles.json");
		if (!Files.isRegularFile(file)) return null;
		JsonObject profiles = obj(JsonParser.parseString(read(file)).getAsJsonObject(), "profiles");
		boolean used = false;
		String name = null, last = null;
		if (profiles != null) {
			for (Map.Entry<String, JsonElement> e : profiles.entrySet()) {
				if (!e.getValue().isJsonObject()) continue;
				JsonObject p = e.getValue().getAsJsonObject();
				String dir = str(p, "gameDir");
				if (!same(dir != null ? Paths.get(dir) : official, game)) continue;
				used = true;
				String type = str(p, "type");
				if ("latest-release".equals(type) || "latest-snapshot".equals(type)) continue;
				String version = str(p, "lastVersionId");
				if (mc != null && (version == null || !(version.equals(mc) || version.endsWith("-" + mc) || version.startsWith(mc + "-")))) continue;
				String when = str(p, "lastUsed");
				if (name == null || (when != null && (last == null || when.compareTo(last) > 0))) {
					name = str(p, "name");
					last = when;
				}
			}
		}
		if (name != null && !name.trim().isEmpty()) return named(clean(name), "minecraft-launcher");
		return used || same(official, game) ? named("Minecraft Launcher", "minecraft-launcher") : null;
	}

	private static boolean same(Path a, Path b) {
		try {
			return Files.isDirectory(a) && Files.isSameFile(a, b);
		} catch (IOException | RuntimeException e) {
			return false;
		}
	}

	private static JsonObject named(String name, String launcher) {
		JsonObject p = new JsonObject();
		p.addProperty("name", name);
		p.addProperty("launcher", launcher);
		return p;
	}

	/** "SkyFactory 4-4.2.4.zip" → "4.2.4": the last version-like run of digits in a modpack's file name. */
	public static String versionIn(String fileName) {
		if (fileName == null) return null;
		Matcher m = VERSION_IN_NAME.matcher(fileName.replaceFirst("\\.(zip|mrpack)$", ""));
		String last = null;
		while (m.find()) last = m.group(1);
		return last;
	}

	/** Runs of spaces as one, at most 80 characters: launchers keep names as typed. */
	public static String clean(String s) {
		if (s == null) return "";
		String t = s.trim().replaceAll("\\s+", " ");
		return t.length() > 80 ? t.substring(0, 80) : t;
	}

	private static String unquote(String s) {
		return s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"") ? s.substring(1, s.length() - 1) : s;
	}

	private static String fileName(Path p) {
		Path n = p.getFileName();
		return n == null ? "" : n.toString();
	}

	private static String read(Path p) throws IOException {
		return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
	}

	/** [k] of [o] as text, or null when it's missing or not a plain value. */
	public static String str(JsonObject o, String k) {
		JsonElement e = o == null ? null : o.get(k);
		return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
	}

	public static JsonObject obj(JsonObject o, String k) {
		JsonElement e = o == null ? null : o.get(k);
		return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
	}
}
