package app.keplersync.tandem.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GamePackTest {
	@TempDir
	Path tmp;

	private static void write(Path file, String text) throws IOException {
		Files.createDirectories(file.getParent());
		Files.write(file, text.getBytes(StandardCharsets.UTF_8));
	}

	@Test
	void aCurseForgeProfileNamesItsModpackAndVersion() throws IOException {
		Path game = tmp.resolve("Instances").resolve("SkyFactory 4 (1)");
		write(game.resolve("minecraftinstance.json"), "{\"name\":\"SkyFactory 4   fixed\",\"installedModpack\":{\"addonID\":296062,"
				+ "\"name\":\"SkyFactory 4\",\"thumbnailUrl\":\"https://media.forgecdn.net/avatars/thumbnails/199/573/256/256/a.png\","
				+ "\"webSiteURL\":\"https://www.curseforge.com/minecraft/modpacks/skyfactory-4\","
				+ "\"installedFile\":{\"id\":3565687,\"fileName\":\"SkyFactory 4-4.2.4.zip\"}},\"manifest\":{\"version\":\"4.2.4\",\"files\":[]}}");
		JsonObject pack = GamePack.read(game, null);
		assertEquals("SkyFactory 4 fixed", pack.get("name").getAsString(), "names are kept as typed, spaces tidied");
		assertEquals("curseforge", pack.get("launcher").getAsString());
		JsonObject mp = pack.getAsJsonObject("modpack");
		assertEquals("296062", mp.get("project").getAsString());
		assertEquals("3565687", mp.get("version").getAsString());
		assertEquals("4.2.4", mp.get("label").getAsString());
		assertTrue(mp.get("icon").getAsString().startsWith("https://media.forgecdn.net/"));
		assertEquals("skyfactory-4", mp.get("slug").getAsString(), "for a link to this version's page");
	}

	@Test
	void aCurseForgeProfileMadeByHandHasNoModpack() throws IOException {
		Path game = tmp.resolve("Verity mods");
		write(game.resolve("minecraftinstance.json"), "{\"name\":\"Verity mods\",\"installedModpack\":null}");
		JsonObject pack = GamePack.read(game, null);
		assertEquals("Verity mods", pack.get("name").getAsString());
		assertFalse(pack.has("modpack"));
	}

	@Test
	void aCurseForgePictureFromAnywhereElseIsLeftOut() throws IOException {
		Path game = tmp.resolve("Odd");
		write(game.resolve("minecraftinstance.json"), "{\"name\":\"Odd\",\"installedModpack\":{\"addonID\":1,\"name\":\"Odd\","
				+ "\"thumbnailUrl\":\"https://tracker.example/pixel.png\",\"installedFile\":{\"id\":2,\"fileName\":\"Odd-1.0.zip\"}}}");
		JsonObject mp = GamePack.read(game, null).getAsJsonObject("modpack");
		assertFalse(mp.has("icon"), "only CurseForge's own image host");
		assertFalse(mp.has("slug"), "no page given");
		assertEquals("1.0", mp.get("label").getAsString(), "taken from the file name when there's no manifest");
	}

	@Test
	void aPrismInstanceNamesItsModrinthPack() throws IOException {
		Path root = tmp.resolve("PrismLauncher");
		write(root.resolve("prismlauncher.cfg"), "");
		Path instance = root.resolve("instances").resolve("cobble");
		write(instance.resolve("instance.cfg"), "[General]\nname=Cobblemon run\nManagedPack=true\nManagedPackType=modrinth\n"
				+ "ManagedPackID=5FFgwNNP\nManagedPackVersionID=abc123\nManagedPackName=Cobblemon Official Modpack\nManagedPackVersionName=1.6.1\n");
		Path game = instance.resolve("minecraft");
		Files.createDirectories(game);
		JsonObject pack = GamePack.read(game, null);
		assertEquals("Cobblemon run", pack.get("name").getAsString());
		assertEquals("prism", pack.get("launcher").getAsString());
		JsonObject mp = pack.getAsJsonObject("modpack");
		assertEquals("modrinth", mp.get("source").getAsString());
		assertEquals("5FFgwNNP", mp.get("project").getAsString());
		assertEquals("1.6.1", mp.get("label").getAsString());
	}

	@Test
	void aModrinthAppProfileIsItsFolder() throws IOException {
		Path app = tmp.resolve("ModrinthApp");
		Files.createDirectories(app.resolve("meta"));
		Path game = app.resolve("profiles").resolve("Aged");
		Files.createDirectories(game);
		JsonObject pack = GamePack.read(game, null);
		assertEquals("Aged", pack.get("name").getAsString());
		assertEquals("modrinth-app", pack.get("launcher").getAsString());
	}

	@Test
	void theMinecraftLauncherNamesTheProfileUsingTheFolder() throws IOException {
		Path official = tmp.resolve(".minecraft");
		Path custom = tmp.resolve("speedrun");
		Files.createDirectories(custom);
		write(official.resolve("launcher_profiles.json"), "{\"profiles\":{\"a\":{\"name\":\"Speedrun\",\"gameDir\":"
				+ com.google.gson.JsonParser.parseString("\"" + custom.toString().replace("\\", "\\\\") + "\"") + "},"
				+ "\"b\":{\"type\":\"latest-release\"}}}");
		assertEquals("Speedrun", GamePack.read(custom, official).get("name").getAsString());
		assertEquals("Minecraft Launcher", GamePack.read(official, official).get("name").getAsString(), "a vanilla profile can't be running Tandem");
	}

	@Test
	void profilesSharingTheLaunchersFolderArePickedByVersion() throws IOException {
		Path official = tmp.resolve(".minecraft");
		write(official.resolve("launcher_profiles.json"), "{\"profiles\":{"
				+ "\"r\":{\"type\":\"latest-release\",\"lastVersionId\":\"latest-release\",\"lastUsed\":\"2026-12-01T00:00:00.000Z\"},"
				+ "\"o\":{\"type\":\"custom\",\"name\":\"Old Fabric\",\"lastVersionId\":\"fabric-loader-0.16.9-1.17.1\",\"lastUsed\":\"2026-11-01T00:00:00.000Z\"},"
				+ "\"n\":{\"type\":\"custom\",\"name\":\"Newer\",\"lastVersionId\":\"fabric-loader-0.18.3-1.21.11\",\"lastUsed\":\"2026-11-09T00:00:00.000Z\"},"
				+ "\"f\":{\"type\":\"custom\",\"name\":\"Fabric 1.21.1\",\"lastVersionId\":\"fabric-loader-0.16.14-1.21.1\",\"lastUsed\":\"2026-11-05T00:00:00.000Z\"}}}");
		assertEquals("Fabric 1.21.1", GamePack.read(official, official, "1.21.1").get("name").getAsString(), "1.21.11 isn't 1.21.1");
		assertEquals("Newer", GamePack.read(official, official, null).get("name").getAsString(), "without a version, the one used last");
		assertEquals("Minecraft Launcher", GamePack.read(official, official, "1.20.1").get("name").getAsString(), "no profile for this version");
	}

	@Test
	void aPlainDotMinecraftIsNoProfile() throws IOException {
		Path game = tmp.resolve(".minecraft");
		Files.createDirectories(game);
		assertNull(GamePack.read(game, null));
	}

	@Test
	void versionsComeFromTheEndOfAModpacksFileName() {
		assertEquals("4.2.4", GamePack.versionIn("SkyFactory 4-4.2.4.zip"));
		assertEquals("2.9.3", GamePack.versionIn("RLCraft 1.12.2 - Release v2.9.3.zip"));
		assertEquals("2.0.4", GamePack.versionIn("ATM10SKY-2.0.4.zip"));
		assertNull(GamePack.versionIn("pack.zip"));
	}

	@Test
	void theListKeepsEachGameOnceAndForgetsGamesThatAreGone() throws IOException {
		Path file = tmp.resolve("Tandem").resolve("games.json");
		Path a = Files.createDirectories(tmp.resolve("a"));
		Path b = Files.createDirectories(tmp.resolve("b"));
		JsonObject game = com.google.gson.JsonParser.parseString("{\"mc\":\"1.21.1\",\"loader\":\"fabric\",\"contentKey\":\"k\","
				+ "\"mods\":[{\"id\":\"create\",\"version\":\"6\",\"adds\":true},{\"id\":\"sodium\",\"version\":\"0.6\"}]}").getAsJsonObject();
		GameList.remember(file, GameList.entry(a, game, 1));
		GameList.remember(file, GameList.entry(b, game, 2));
		GameList.remember(file, GameList.entry(a, game, 3));
		List<JsonObject> games = GameList.read(file);
		assertEquals(2, games.size(), "a game started again replaces its entry");
		assertEquals(a.toString(), games.get(0).get("dir").getAsString(), "the latest first");
		assertEquals(1, games.get(0).getAsJsonArray("mods").size(), "only mods that add something");
		Files.delete(b);
		GameList.remember(file, GameList.entry(a, game, 4));
		assertEquals(1, GameList.read(file).size(), "a game whose folder is gone is dropped");
	}

	@Test
	void aGameTooBigForKeplerSyncDropsItsOtherModsFirst() {
		JsonObject g = new JsonObject();
		g.addProperty("contentKey", "abc");
		com.google.gson.JsonArray mods = new com.google.gson.JsonArray();
		for (int i = 0; i < 3000; i++) {
			JsonObject m = new JsonObject();
			m.addProperty("id", "mod" + i);
			m.addProperty("name", "A mod with a fairly long name number " + i);
			m.addProperty("version", "1.2.3+build." + i);
			if (i % 10 == 0) m.addProperty("adds", true);
			mods.add(m);
		}
		g.add("mods", mods);
		assertTrue(g.toString().length() > 200_000);
		JsonObject fit = GameList.fit(g, 200_000);
		assertTrue(fit.toString().length() <= 200_000);
		assertEquals(300, fit.getAsJsonArray("mods").size(), "only the mods that add to a world");
		assertEquals("abc", fit.get("contentKey").getAsString());
		assertEquals(3000, g.getAsJsonArray("mods").size(), "the game it was given stays as it was");
		assertFalse(GameList.fit(g, 1000).has("mods"), "no list at all when even that is too much");
		assertTrue(GameList.fit(fit, 200_000) == fit, "one that fits comes back as it is");
	}
}
