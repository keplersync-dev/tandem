package app.keplersync.tandem.fabric.ui

import app.keplersync.tandem.fabric.account.Prefs
import com.mojang.authlib.GameProfile
import net.minecraft.client.Minecraft
import net.minecraft.client.resources.DefaultPlayerSkin
import net.minecraft.resources.ResourceLocation
import java.awt.Color
import java.nio.file.Path
import java.util.UUID

/** Where a world stands. [rank] is the sort order: needs you, shared, this PC, different mods. */
enum class WorldState(val rank: Int) { VERSIONS(0), PC_ONLY(0), READY(1), FIRST(1), HOSTED(2), SAVING(2), LOCAL(3), OTHER(5) }

class World(
	val name: String,
	var state: WorldState,
	val bands: List<Pair<Float, Color>>,
	/** The person the state is about: who's hosting, saving or played last (see [hostId] for who it is). */
	var who: String = "",
	var ago: String = "",
	var pct: Int = 0,
	val mc: String? = null,
	/** What it would take to match this world: the host and the mods that differ. */
	val mods: Mods? = null,
) {
	var size = ""
	/** A real world's host or uploader, by KeplerSync id, when it's someone else. */
	var hostId: String? = null
	/** Who synced it ("You" or a friend's name); null while it's only on this PC. */
	var owner: String? = null
	/** Everyone it's shared with, "You" included. */
	val shared = mutableListOf<String>()
	/** Which History entry is current (0 = the latest). */
	var restored = 0
	/** A first upload under way, after Sync this world. */
	var upload: Prog? = null
	/** With a dev cloud: the world's folder in saves (null until it's downloaded here) and its cloud id. */
	var levelId: String? = null
	var cloudId: String? = null
	/** A real synced world's latest snapshot, and the versions kept beside it after two sessions raced. */
	var head: String? = null
	val branches = mutableListOf<String>()
	/** A real synced world's operators ("You" for this player), and everyone's KeplerSync ids by name. */
	val operators = mutableSetOf<String>()
	val userIds = mutableMapOf<String, String>()
	/** A real synced world's storage ("keplersync" or "own"), a move under way (where to, how far), what's wrong with its owner's own storage, and a stop waiting for the host. */
	var storage = "keplersync"
	var movingTo: String? = null
	var movePct = 0
	var storageProblem: String? = null
	var stopWhenFree = false
	/** Hosted by someone whose game stopped answering: free once their lease runs out. */
	var quiet = false
	/** Open in another game on this account (a second PC, or another Minecraft): playing it here too would split it. */
	var elsewhere = false
	/** The world's icon.png on this PC, if it has one. */
	var icon: Path? = null

	val mine get() = owner == "You"
	val synced get() = owner != null && state != WorldState.OTHER
}

/** Mods that differ from this game: name and version; for [different], yours then the world's. */
class Mods(val host: String, val missing: List<List<String>>, val different: List<List<String>>, val extra: List<List<String>>) {
	val total get() = missing.size + different.size + extra.size
}

/** One of the sessions a split world was played in. */
class Version(val who: String, val skinIndex: Int, val saved: String, val played: String, val changed: String, val note: String, val openNow: Boolean = false) {
	/** A real version: its snapshot, the line under its title, and the face of whoever played it. */
	var snapshotId: String? = null
	var line: String? = null
	var face: ResourceLocation? = null

	val skin: ResourceLocation get() = if (who == "You") yourSkin() else DefaultPlayerSkin.get(UUID(0L, skinIndex.toLong())).texture()
	val title get() = if (who == "You") tr("tandem.your_session") else tr("tandem.s_session", who)
}

class Person(
	val name: String, private val skinIndex: Int, val online: Boolean, val presence: String,
	val hosting: Boolean = false, val inWorld: Boolean = false,
	/** Connection strength, 1 to 3 bars, while in a game together. */
	val bars: Int = 2,
) {
	/** A real friend's KeplerSync id; null for the sample's people. */
	var userId: String? = null
	/** Their @username: their Minecraft name. Shown faint beside the name in every row. */
	var handle: String? = null
	/** A real friend's Minecraft profile, for their skin. */
	var profile: GameProfile? = null
	/** In your game through KeplerSync's relay, rather than connected straight to this PC. */
	var relayed = false
	/** Online in the Atlas app only, with Minecraft closed. */
	var atlasOnly = false

	// Minecraft's built-in skins stand in for the sample's people, so their faces need no network.
	val skin: ResourceLocation get() = profile?.let { Minecraft.getInstance().skinManager.getInsecureSkin(it).texture() }
		?: DefaultPlayerSkin.get(UUID(0L, skinIndex.toLong())).texture()

	/** Friend order: hosting, in a world, online, in Atlas only, offline. */
	val rank get() = if (!online) 4 else if (hosting) 0 else if (inWorld) 1 else if (atlasOnly) 3 else 2
}

/** The signed-in player's own skin, so "You" looks like you. */
fun yourSkin(): ResourceLocation = Minecraft.getInstance().let { it.skinManager.getInsecureSkin(it.gameProfile).texture() }

/** The sample's starting state. */
object Sample {
	private fun c(hex: Int) = Color(hex)

	val worlds = listOf(
		World("Old Survival", WorldState.VERSIONS, listOf(0.5f to c(0x7a9cbf), 1f to c(0x557a45))),
		World(
			"Create Nights", WorldState.HOSTED, listOf(0.45f to c(0x6f8fae), 0.7f to c(0x4f7a3a), 1f to c(0x6b4f33)), who = "Sam",
			mods = Mods(
				"Sam",
				listOf(listOf("Create", "0.5.1f"), listOf("Create: Steam 'n' Rails", "1.6.4"), listOf("Farmer's Delight", "1.2.4")),
				listOf(listOf("Supplementaries", "2.8.1", "2.8.4")),
				emptyList(),
			),
		),
		World("Sky Base", WorldState.READY, listOf(0.55f to c(0x9ec3e6), 0.64f to c(0xcfd8e3), 1f to c(0x9ec3e6)), who = "Sam", ago = "12 min ago"),
		World("Hardcore Attempt 4", WorldState.SAVING, listOf(0.4f to c(0xa35d3a), 1f to c(0x5a2f22)), who = "Alex", pct = 64),
		World(
			"Cobblemon Island", WorldState.OTHER, listOf(0.45f to c(0x79c6e8), 0.6f to c(0xe8d38a), 1f to c(0x4f9a5a)),
			mods = Mods(
				"Noor", genMods(312),
				genMods(9, 320).mapIndexed { i, m -> listOf(m[0], ver(i + 3), m[1]) },
				genMods(41, 340).map { listOf("Create " + it[0].substringAfterLast(' '), it[1]) },
			),
		),
		World(
			"Skyblock 2023", WorldState.OTHER, listOf(0.6f to c(0x5a6f9a), 0.7f to c(0x8c8c8c), 1f to c(0x2a3550)), mc = "1.20.1",
			mods = Mods("Noor", genMods(4, 200), emptyList(), emptyList()),
		),
		World("Survival 2024", WorldState.LOCAL, listOf(0.48f to c(0x8fb3d6), 0.72f to c(0x6a9a52), 1f to c(0x806246)), ago = "3 days ago"),
	).sortedBy { it.state.rank }.toMutableList()

	init {
		fun set(name: String, size: String, owner: String?, vararg shared: String) =
			worlds.first { it.name == name }.also { it.size = size; it.owner = owner; it.shared += shared }
		set("Old Survival", "1.2 GB", "Sam", "Sam", "You", "Alex")
		set("Create Nights", "3.4 GB", "You", "You", "Sam", "Noor", "Alex", "Jules")
		set("Sky Base", "2.1 GB", "You", "You", "Sam")
		set("Hardcore Attempt 4", "640 MB", "You", "You", "Sam", "Noor", "Alex", "Jules")
		set("Cobblemon Island", "1.6 GB", "Noor", "Noor", "You", "Sam", "Jules")
		set("Skyblock 2023", "900 MB", "Noor", "Noor", "You")
		set("Survival 2024", "820 MB", null)
	}

	fun world(name: String) = worlds.first { it.name == name }

	/** The sessions Old Survival split into. */
	val versions = listOf(
		Version("Sam", 0, "Tue 9:14 pm", "2 h 10 min", "38 areas of the map", "Played while connected"),
		Version("Alex", 4, "Tue 9:20 pm", "45 min", "6 areas of the map", "Played offline, uploaded Wednesday"),
		Version("You", 16, "Tue 10:02 pm", "1 h 5 min", "12 areas of the map", "Played offline, uploaded today", openNow = true),
	)

	val friends = listOf(
		Person("Sam", 0, true, "Hosting Create Nights", hosting = true, bars = 3),
		Person("Noor", 2, true, "In Sam's world, Create Nights", inWorld = true, bars = 2),
		Person("Alex", 4, true, "Saving Hardcore Attempt 4", bars = 2),
		Person("Jules", 6, true, "In the menu", bars = 3),
		Person("Ivy", 9, true, "In Atlas").also { it.atlasOnly = true },
		Person("Riley", 8, false, "Offline · yesterday", bars = 3),
		Person("Kai", 10, false, "Offline · 3 days ago"),
		Person("Mo", 12, false, "Offline · 2 weeks ago"),
	).onEach { it.handle = it.name.lowercase() + "_mc" }.sortedBy { it.rank }.toMutableList()

	fun person(name: String) = friends.first { it.name == name }

	val requests = mutableListOf(Person("Pat", 14, true, "Wants to be friends").also { it.handle = "patplays" })

	/** Who's blocked (Settings lists them, with Unblock). */
	val blocked = mutableListOf<Person>()

	/** Sample situations, set here with /tandem in game. */
	var reachable = true
	var slow = false
	var diskFull = false
	var alexQuit = false
	var storageFull = false
	var offline = false
	var usesAtlas = true

	/** Saving a world in the background after you leave it (shown on the title tile), and who it's handed to. */
	var background: Triple<String, String?, Prog>? = null
	var quitWhenDone = false

	/** What the title button's count shows: worlds that need you plus friend requests. */
	val needsYou get() = worlds.count { it.state == WorldState.VERSIONS } + requests.size
}

// Made-up mod names: real ones first, then "Better Biomes" style names, each with a version.
private val REAL = listOf(
	"Cobblemon", "Cobblemon Integrations", "Fight or Flight", "Cobblemounts", "Cobbleloots", "MegaShowdown", "Cobblemon Capture XP",
	"Terralith", "Tectonic", "Lithostitched", "TerraBlender", "Waystones", "Balm", "Travelers Backpack", "Traveler's Titles", "Croptopia",
	"Storage Drawers", "Tom's Simple Storage", "Macaw's Bridges", "Macaw's Doors", "Macaw's Windows", "Chipped", "Athena", "Resourceful Lib",
	"Handcrafted", "Moonlight Lib", "Amendments", "Comforts", "Trinkets", "Accessories", "YUNG's API", "YUNG's Better Dungeons",
	"YUNG's Better Mineshafts", "Towns and Towers", "Repurposed Structures", "Explorify", "Dungeons and Taverns", "Friends and Foes",
	"Naturalist", "Snow Real Magic",
)
private val PRE = listOf("Better", "Simple", "Tiny", "Extra", "More", "Immersive", "Simply", "Cozy", "Epic", "Lush")
private val NOUN = listOf(
	"Biomes", "Lanterns", "Storage", "Ores", "Villagers", "Furniture", "Trees", "Paths", "Mobs", "Boats", "Signs", "Maps", "Caves", "Weapons",
	"Bees", "Fences", "Crops", "Lights", "Ships", "Wolves", "Stairs", "Torches", "Banners", "Arrows", "Barrels", "Lamps", "Rails", "Chests",
	"Roads", "Shields", "Flowers", "Rocks", "Hats", "Horses", "Kitchens", "Gardens",
)

private fun ver(i: Int) = "${1 + i % 3}.${(i * 7) % 12}.${(i * 13) % 9}"

private fun genMods(n: Int, off: Int = 0) = List(n) { i ->
	val j = i + off
	listOf(if (j < REAL.size) REAL[j] else PRE[j % PRE.size] + " " + NOUN[(j / PRE.size) % NOUN.size], ver(j))
}

/** The signed-in KeplerSync account, the same in Atlas and Tandem. */
object Account {
	var name = "Robin"
	/** The @username: the Minecraft name, which can't be changed here. KeplerSync's once signed in. */
	var handle = ""
		get() = field.ifEmpty { minecraftName.lowercase() }
	/** Anyone with it can send you a friend request. */
	var link = "https://keplersync.app/f/KEPL4821"
	var friendsOnline
		get() = Prefs.get().friendsOnline
		set(v) { Prefs.get().friendsOnline = v; Prefs.save() }
	var inviteSound
		get() = Prefs.get().inviteSound
		set(v) { Prefs.get().inviteSound = v; Prefs.save() }
	var autoLogs
		get() = Prefs.get().autoLogs
		set(v) { Prefs.get().autoLogs = v; Prefs.save() }
	val minecraftName: String get() = Minecraft.getInstance().user.name
}

/** Storage: the plan and where synced worlds are kept. */
object Storage {
	var plan = "Plus"
	var gb = 25
	var ownR2 = false
	const val HISTORY_GB = 0.8
	const val ATLAS_GB = 0.3

	fun worldsGb() = Sample.worlds.filter { it.mine && it.state != WorldState.LOCAL }.sumOf { gb(it.size) }
	fun usedGb() = Math.round((worldsGb() + HISTORY_GB + if (Sample.usesAtlas) ATLAS_GB else 0.0) * 10) / 10.0
	fun gb(size: String) = if (size.endsWith("MB")) size.removeSuffix(" MB").toDouble() / 1000 else size.removeSuffix(" GB").toDouble()

	/** Free, Plus and Group: name, GB, monthly, yearly, what it keeps. */
	val tiers = listOf(
		listOf("Free", "1", "0", "0", "Last 3 saves of each world · up to 500 MB of it for Atlas"),
		listOf("Plus", "25", "2.99", "24", "30 days of history, and always the last 3 saves"),
		listOf("Group", "100", "5.99", "48", "90 days of history · shared with people you invite"),
	)

	const val DISCLAIMER = "Not an official Minecraft product or service. Not approved by or associated with Mojang or Microsoft."
}
