package app.keplersync.tandem.fabric.ui

import app.keplersync.tandem.core.GamePack
import app.keplersync.tandem.fabric.sync.ThisGame
import com.google.gson.JsonObject
import java.nio.file.Files

/** A mod as a game's description lists it. [adds] is true when it puts something in a world (blocks, items, creatures, terrain). */
class GameMod(val id: String, val name: String, val version: String, val adds: Boolean)

/** The modpack a launcher profile came from, by its Modrinth or CurseForge ids. */
class Modpack(val source: String, val project: String, val version: String?, val title: String, val label: String?, val icon: String?, val slug: String? = null) {
	val key get() = "$source:$project"

	/** Its page on CurseForge or Modrinth, at its version where the ids allow, built from its ids, never from an address a game sent. */
	val page: String?
		get() = when (source) {
			"curseforge" -> when {
				slug != null && SLUG.matches(slug) -> "https://www.curseforge.com/minecraft/modpacks/$slug" + (version?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.let { "/files/$it" } ?: "")
				project.isNotEmpty() && project.all(Char::isDigit) -> "https://www.curseforge.com/projects/$project"
				else -> null
			}
			"modrinth" -> if (ID.matches(project)) "https://modrinth.com/project/$project" + (version?.takeIf { ID.matches(it) }?.let { "/version/$it" } ?: "") else null
			else -> null
		}

	val siteName get() = if (source == "modrinth") "Modrinth" else "CurseForge"

	private companion object {
		val ID = Regex("[A-Za-z0-9]{1,64}")
		val SLUG = Regex("[a-z0-9][a-z0-9-]{0,99}")
	}
}

/** The launcher profile a game runs in, by the name its launcher shows. */
class PackInfo(val name: String, val launcher: String, val modpack: Modpack?) {
	val launcherName: String
		get() = when (launcher) {
			"curseforge" -> "CurseForge"
			"prism" -> "Prism Launcher"
			"multimc" -> "MultiMC"
			"modrinth-app" -> "Modrinth App"
			"minecraft-launcher" -> "Minecraft Launcher"
			else -> ""
		}
}

/**
 * A game, as a world's last save described it or as this PC's list of Tandem games keeps it ([dir] set): its loader,
 * Minecraft version, a fingerprint of what it adds, its mods and its launcher profile.
 */
class GameInfo(val loader: String, val mc: String?, val contentKey: String?, val mods: List<GameMod>, val pack: PackInfo?, val dir: String? = null) {
	/** The mods that put something in a world: what a world made here needs. */
	val adds get() = mods.filter { it.adds }

	/** What it's called: the modpack's name, else the profile's. */
	val title get() = pack?.modpack?.title?.ifBlank { null } ?: pack?.name?.ifBlank { null }

	companion object {
		/** From a description another Tandem wrote: anything missing or odd is left out, and long text is cut. */
		fun parse(o: JsonObject?, dir: String? = null): GameInfo? {
			if (o == null) return null
			val mods = o.getAsJsonArrayOrNull("mods")?.mapNotNull { e ->
				val m = if (e.isJsonObject) e.asJsonObject else return@mapNotNull null
				val id = GamePack.str(m, "id")?.take(64) ?: return@mapNotNull null
				GameMod(id, GamePack.str(m, "name")?.take(80)?.ifBlank { null } ?: id, GamePack.str(m, "version")?.take(48) ?: "", m.get("adds")?.let { it.isJsonPrimitive && it.asBoolean } == true)
			}?.take(5000) ?: emptyList()
			val pack = GamePack.obj(o, "pack")?.let { p ->
				val mp = GamePack.obj(p, "modpack")?.let { m ->
					val source = GamePack.str(m, "source") ?: return@let null
					val project = GamePack.str(m, "project")?.take(64) ?: return@let null
					Modpack(source, project, GamePack.str(m, "version")?.take(64), GamePack.str(m, "title")?.take(80) ?: "", GamePack.str(m, "label")?.take(40),
						GamePack.str(m, "icon")?.takeIf { it.startsWith("https://media.forgecdn.net/") && it.length < 300 }, GamePack.str(m, "slug"))
				}
				PackInfo(GamePack.str(p, "name")?.take(80) ?: "", GamePack.str(p, "launcher") ?: "other", mp)
			}
			return GameInfo(GamePack.str(o, "loader")?.take(16) ?: "fabric", GamePack.str(o, "mc")?.take(32), GamePack.str(o, "contentKey")?.take(64), mods, pack, dir)
		}

		private fun JsonObject.getAsJsonArrayOrNull(k: String) = get(k)?.takeIf { it.isJsonArray }?.asJsonArray
	}
}

/** The difference between a copy of a modpack on this PC and the game a world was last saved in, in mods that add to a world. */
class ModDiff(val onlyYours: List<GameMod>, val onlyTheirs: List<GameMod>, val otherVersion: List<Pair<GameMod, String>>) {
	val total get() = onlyYours.size + onlyTheirs.size + otherVersion.size
}

/** Where a synced world plays, from its last save's game and the Tandem games on this PC. */
sealed class Where {
	/** In this game. */
	object Here : Where()

	/** In another game on this PC. */
	class OnPc(val game: GameInfo) : Where()

	/** It needs no mods, so any game of its Minecraft version plays it; [game] is one on this PC, if any. */
	class Vanilla(val game: GameInfo?) : Where()

	/** The same modpack is on this PC ([game]), at another version than [need]. */
	class Update(val game: GameInfo, val need: Modpack) : Where()

	/** The same modpack and version is on this PC ([game]), with different mods. */
	class Differs(val game: GameInfo, val diff: ModDiff) : Where()

	/** A modpack that isn't on this PC with Tandem. */
	class GetIt(val modpack: Modpack) : Where()

	/** Someone's own pack, not a modpack, that isn't on this PC; [pack] null when it has no profile name. */
	class Own(val pack: PackInfo?) : Where()

	/** Saved before Tandem described games: only its mods are known. */
	object Unknown : Where()
}

/** This game, the other Tandem games on this PC, and where each synced world plays. */
object Games {
	private var listRead = 0L
	private var listAt = -1L
	private var list: List<GameInfo> = emptyList()

	/** This game; the sample's in a run without KeplerSync. */
	val here: GameInfo
		get() = if (RealWorlds.on) real else Sample.hereGame

	private val real by lazy { GameInfo.parse(ThisGame.get(), ThisGame.dir().toString())!! }

	/** The other Tandem games on this PC, the most recently started first. Read again at most every few seconds. */
	fun others(): List<GameInfo> {
		if (!RealWorlds.on) return Sample.games
		val now = System.currentTimeMillis()
		if (now - listRead > 3000) {
			listRead = now
			val file = ThisGame.listFile()
			val at = try { Files.getLastModifiedTime(file).toMillis() } catch (e: Exception) { 0L }
			if (at != listAt) {
				listAt = at
				val mine = ThisGame.dir().toString()
				list = ThisGame.onThisPc().mapNotNull { o -> GamePack.str(o, "dir")?.takeIf { it != mine }?.let { GameInfo.parse(o, it) } }
			}
		}
		return list
	}

	fun where(w: World): Where {
		if (w.state != WorldState.OTHER) return Where.Here
		val g = w.game
		val version = w.version ?: return Where.Unknown
		val loader = g?.loader ?: "fabric"
		val needs = w.needs
		val pcs = others()
		fun plays(c: GameInfo) = c.mc == version && (needs.isEmpty() || c.loader == loader) && same(c.adds, needs) &&
			(c.contentKey == null || g?.contentKey == null || c.contentKey == g.contentKey)
		if (needs.isEmpty()) return Where.Vanilla(pcs.firstOrNull(::plays))
		pcs.firstOrNull(::plays)?.let { return Where.OnPc(it) }
		val mp = g?.pack?.modpack
		if (mp != null) {
			val copies = (listOf(here) + pcs).filter { it.mc == version && it.loader == loader && it.pack?.modpack?.key == mp.key }
			copies.firstOrNull { it.pack?.modpack?.version == mp.version }?.let { return Where.Differs(it, diff(it, w)) }
			copies.firstOrNull()?.let { return Where.Update(it, mp) }
			return Where.GetIt(mp)
		}
		return if (g == null) Where.Unknown else Where.Own(g.pack)
	}

	private fun same(adds: List<GameMod>, needs: List<String>) = adds.map { it.id + "@" + it.version }.toSet() == needs.toSet()

	/** What [copy] has that the world's game didn't, what it lacks, and what's at another version: mods that add to a world only. */
	fun diff(copy: GameInfo, w: World): ModDiff {
		val mine = copy.adds.associateBy { it.id }
		val theirs = w.needs.associate { it.substringBefore('@') to it.substringAfter('@', "") }
		val names = w.game?.mods?.associate { it.id to it.name } ?: emptyMap()
		return ModDiff(
			mine.values.filter { it.id !in theirs },
			theirs.filter { it.key !in mine }.map { (id, v) -> GameMod(id, names[id] ?: id, v, true) },
			theirs.mapNotNull { (id, v) -> mine[id]?.takeIf { it.version != v }?.let { it to v } },
		)
	}

	/** "1.21.11" after "1.21.1", and both after "1.20.1": versions compared number by number. */
	val newestFirst = Comparator<String> { a, b ->
		val x = a.split('.', '-'); val y = b.split('.', '-')
		for (i in 0 until maxOf(x.size, y.size)) {
			val p = x.getOrNull(i)?.toIntOrNull() ?: -1
			val q = y.getOrNull(i)?.toIntOrNull() ?: -1
			if (p != q) return@Comparator q.compareTo(p)
		}
		b.compareTo(a)
	}

	fun loaderName(loader: String) = when (loader) {
		"forge" -> "Forge"
		"neoforge" -> "NeoForge"
		"quilt" -> "Quilt"
		else -> "Fabric"
	}
}
