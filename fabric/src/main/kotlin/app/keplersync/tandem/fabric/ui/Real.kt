package app.keplersync.tandem.fabric.ui

import app.keplersync.tandem.core.Cloud
import app.keplersync.tandem.core.KeplerSyncApi
import app.keplersync.tandem.fabric.TandemClient
import app.keplersync.tandem.fabric.account.Account as KsAccount
import com.mojang.authlib.GameProfile
import app.keplersync.tandem.core.LocalState
import app.keplersync.tandem.core.Snapshot
import app.keplersync.tandem.core.Sync
import app.keplersync.tandem.fabric.play.Joining
import app.keplersync.tandem.fabric.sync.WorldSync
import gg.essential.elementa.dsl.childOf
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.world.level.storage.LevelStorageException
import net.minecraft.world.level.storage.LevelResource
import net.minecraft.world.level.storage.LevelSummary
import java.awt.Color
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ConcurrentHashMap
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/** "41 MB", "1.08 GB" or "1 GB", in Atlas's units (a GB is 1024³ bytes) so both apps agree. */
fun bytesText(bytes: Long) = if (bytes >= 1L shl 30) "%.2f".format(bytes / (1L shl 30).toDouble()).replace(Regex("[.,]?0+$"), "") + " GB"
	else "${(bytes / (1L shl 20).toDouble()).roundToInt().coerceAtLeast(if (bytes > 0) 1 else 0)} MB"

/** A transfer's progress as the screens read it: it only shows full once the cloud has the snapshot. */
class Moving(private val t: WorldSync.Transfer) : Prog {
	override val value get() = if (t.done()) 1f else min(0.99f, t.progress.fraction())
	/** Waiting to try again because KeplerSync can't be reached. */
	val waiting get() = !t.done() && t.waiting
}

/** Worlds whose lease this game took to play them, while it gets them ready (downloading, or asking about them). */
val gettingReady: MutableSet<String> = ConcurrentHashMap.newKeySet()

/** A first upload that isn't running just now: none on this PC, or one waiting for KeplerSync. */
fun waitingToUpload(p: Prog?) = p == null || (p as? Moving)?.waiting == true

/**
 * This account holds [lease] in another game (a second PC, or another Minecraft on this one): not this game's world,
 * transfer or first upload, and answering (45 s or more left, as for a friend's game).
 */
fun inOtherGame(d: WorldSync, lease: Cloud.Lease, id: String, levelId: String?, name: String): Boolean {
	// Ready and open here: from now on the session says it's this game's.
	if (d.session?.worldId == id) gettingReady -= id
	if (!lease.heldBy(d.player()) || lease.expires - System.currentTimeMillis() < 45_000) return false
	if (lease.state == Cloud.DOWNLOADING && id in gettingReady) return false
	return d.session?.worldId != id && d.background?.let { !it.done() && it.world == levelId } != true && d.firstUploading(name) == null
}

/**
 * The worlds list from this PC and the dev cloud, in place of the sample's, when the run has a dev cloud
 * ([WorldSync]). Friends, Storage and Settings stay sample data.
 */
object RealWorlds {
	val on get() = WorldSync.get() != null
	private var last = ""
	@Volatile private var lastSummaries: List<LevelSummary>? = null
	/** The saves folder has been read once: with a world open, that read stands in. */
	val read get() = lastSummaries != null
	/** The read under way, if any. */
	@Volatile private var reading: CompletableFuture<*>? = null
	/**
	 * Until when a world is opening: reading the saves folder briefly locks every world's session.lock, which makes
	 * Minecraft fail to open the one it's taking just then ("Failed to access world").
	 */
	@Volatile private var holdUntil = 0L

	/** Opens [level] once no read of the saves folder is under way, and holds new reads meanwhile. */
	fun openAfterReads(open: () -> Unit) {
		holdUntil = System.currentTimeMillis() + 10_000
		val mc = Minecraft.getInstance()
		(reading ?: CompletableFuture.completedFuture(null)).handle { _, _ -> null }.thenRun { mc.execute(open) }
	}

	/** The world opened, or didn't: reads go on as usual. */
	fun opened() {
		holdUntil = 0
	}

	/** A synced world being opened, and its folder: if Minecraft can't load it, Tandem offers the version before. */
	var opening: Pair<World, String>? = null

	/** A level to play as soon as the list is in: a synced world opened from Minecraft's own Singleplayer list. */
	var pendingLevel: String? = null

	/**
	 * Minecraft's Singleplayer list is opening [levelId]: a synced world goes through Tandem instead (lease, latest
	 * copy, changes made outside Tandem). Returns true when Tandem took over.
	 */
	@JvmStatic
	fun openSynced(levelId: String): Boolean {
		if (!on || !LocalState.load(WorldSync.folder(levelId)).synced()) return false
		pendingLevel = levelId
		Minecraft.getInstance().setScreen(PlayScreen())
		return true
	}

	/**
	 * The newest reload: an older one finishing after it (it read the world before an upload started) is dropped, and
	 * the newest runs everyone's [refresh] callbacks.
	 */
	private var newest = 0
	private val waiting = mutableListOf<() -> Unit>()

	/** Reloads the list; [changed] runs on the render thread when anything about it is different. */
	fun refresh(changed: () -> Unit) {
		val d = WorldSync.get() ?: return
		val mc = Minecraft.getInstance()
		// With a world open, reading the saves folder makes Minecraft warn that the world is locked (by itself), so
		// the last read stands in until the world closes.
		val holding = System.currentTimeMillis() < holdUntil
		if (holding && lastSummaries == null) return
		val mine = ++newest
		waiting += changed
		val summaries: CompletableFuture<List<LevelSummary>> = if ((mc.singleplayerServer != null || holding) && lastSummaries != null) {
			CompletableFuture.completedFuture(lastSummaries)
		} else try {
			mc.levelSource.loadLevelSummaries(mc.levelSource.findLevelCandidates()).thenApply { it.also { l -> lastSummaries = l } }.also { reading = it }
		} catch (e: LevelStorageException) {
			CompletableFuture.completedFuture(emptyList())
		}
		summaries.thenCompose { list -> d.async { load(d, list) } }.whenComplete { worlds, e ->
			mc.execute {
				if (worlds == null) return@execute TandemClient.LOG.warn("Tandem couldn't load the worlds list: {}", (e?.cause ?: e)?.toString())
				if (mine != newest) return@execute
				val callbacks = waiting.toList().also { waiting.clear() }
				if (d.server) Sample.reachable = reachable
				val sig = "$reachable|" + worlds.joinToString("|") { "${it.name}:${it.state}:${it.who}:${it.ago}:${it.pct}:${it.levelId}:${it.operators}:${it.storage}:${it.movingTo}:${it.movePct}:${it.storageProblem}:${it.stopWhenFree}:${it.icon != null}:${it.quiet}:${it.elsewhere}:${it.savedAt}:${if (it.savedAt > 0) ago(it.savedAt) else ""}:${it.hostedBy}:${it.game?.contentKey}:${it.game?.pack?.name}" }
				if (sig == last && Sample.worlds.isNotEmpty()) return@execute
				last = sig
				Sample.worlds.clear()
				Sample.worlds.addAll(worlds)
				callbacks.forEach { it() }
			}
		}
	}

	/** KeplerSync answered the last time the list was loaded. */
	@Volatile private var reachable = true
	/** Not reachable because Mojang didn't confirm the Minecraft account, so Tandem never got to ask KeplerSync. */
	@Volatile var notConfirmed = false
		private set
	/** Not reachable because KeplerSync only talks to a newer Tandem. */
	@Volatile var outdated = false
		private set

	private fun load(d: WorldSync, summaries: List<LevelSummary>): List<World> {
		val cloud = try {
			d.cloud.worlds(d.player()).associateBy { it.id }.also { reachable = true; notConfirmed = false; outdated = false }
		} catch (e: java.io.IOException) {
			if (!WorldSync.unreachable(e)) throw e
			reachable = false
			notConfirmed = generateSequence<Throwable>(e) { it.cause }.any { it is KeplerSyncApi.NotConfirmed }
			outdated = generateSequence<Throwable>(e) { it.cause }.any { it is KeplerSyncApi.Refused && it.outdated() }
			emptyMap()
		}
		// Asked after the list, which is what signs in: this account's id is known from then on.
		val me = d.player()
		val out = mutableListOf<World>()
		val seen = mutableSetOf<String>()
		for (s in summaries) {
			val dir = WorldSync.folder(s.levelId)
			val st = LocalState.load(dir)
			val cw = st.worldId?.let { cloud[it] }
			if (cw != null && cw.stopped) {
				// Its owner stopped syncing it: the copy here is a normal world from now on.
				seen += cw.id
				if (RealLeave.handled.remove(cw.id)) LocalState.drop(dir)
				else if (cw.id !in RealLeave.fetching) {
					val open = Minecraft.getInstance().singleplayerServer?.getWorldPath(LevelResource.ROOT)?.normalize() == dir.normalize()
					val behind = st.base?.id != cw.head
					// Played here without Tandem since its last sync: those changes are kept, like an unsent session.
					val outside = behind && st.pending == null && !open && d.sync().localChanges(dir)?.let { it.added.size + it.changed.size + it.removed.size > 0 } == true
					val stop = RealLeave.Stopped(if (cw.createdBy == me) "You" else cw.nameOf(cw.createdBy), cw.name, cw.id, s.levelId, st.base?.time, behind, st.pending != null || outside)
					// Behind, with nothing of its own unsent: it gets where the last session left off before letting go.
					stop.fetching = stop.behind && !stop.unsent && cw.head != null && !open
					if (stop.fetching) RealLeave.fetchLatest(d, stop) else LocalState.drop(dir)
					RealLeave.stopped += stop
				}
				out += World(s.levelName, WorldState.LOCAL, bands(s.levelId), ago = ago(s.lastPlayed)).also { it.levelId = s.levelId; it.size = size(folderBytes(dir)) }
			} else if (cw == null && st.synced() && !reachable) {
				// A synced world while KeplerSync can't be reached: shown as it was last synced, not opened.
				// Made on this PC (its author is an id then) or by this account: "you".
				out += World(s.levelName, WorldState.READY, bands(st.worldId), who = st.base?.let { if (it.author == it.authorId || it.authorId == me) "you" else it.author } ?: "you",
					ago = st.base?.let { ago(it.time) } ?: ago(s.lastPlayed)).also { it.levelId = s.levelId; it.cloudId = st.worldId; it.owner = "?" }
			} else if (cw == null) {
				out += World(s.levelName, WorldState.LOCAL, bands(s.levelId), ago = ago(s.lastPlayed)).also { it.levelId = s.levelId; it.size = size(folderBytes(dir)) }
			} else {
				seen += cw.id
				out += synced(d, cw, s.levelId, me)
			}
		}
		// A world that arrived while a game was open (taken over in a hand-off) isn't in the saves folder's last read,
		// which stands in until the game closes: its own state file still says which cloud world it is.
		val unread = if (cloud.values.any { it.id !in seen && !it.stopped }) levelsByWorld(summaries) else emptyMap()
		for (cw in cloud.values) if (cw.id !in seen && !cw.stopped) out += synced(d, cw, unread[cw.id], me)
		val icons = summaries.associate { it.levelId to it.icon }
		for (w in out) w.levelId?.let { id -> w.icon = (icons[id] ?: WorldSync.folder(id).resolve("icon.png")).takeIf { Files.isRegularFile(it) } }
		return out.sortedBy { it.state.rank }
	}

	/** Cloud world id → its folder in saves, for synced worlds [summaries] doesn't list. */
	private fun levelsByWorld(summaries: List<LevelSummary>): Map<String, String> {
		val listed = summaries.map { it.levelId }.toSet()
		val work = WorldSync.saves().resolve(".tandem")
		if (!Files.isDirectory(work)) return emptyMap()
		return Files.list(work).use { dirs ->
			dirs.toList().map { it.fileName.toString() }.filter { it !in listed && Files.isDirectory(WorldSync.folder(it)) }
				.mapNotNull { level -> runCatching { LocalState.load(WorldSync.folder(level)).worldId }.getOrNull()?.let { it to level } }
				.toMap()
		}
	}

	private fun synced(d: WorldSync, cw: Cloud.World, levelId: String?, me: String): World {
		// People arrive as ids; the screens show names, "You" for this account.
		fun name(id: String?) = if (id == me) "You" else cw.nameOf(id) ?: ""
		val lease = d.cloud.lease(cw.id)
		val head = cw.head?.let { d.cloud.snapshot(cw.id, it) }
		val mods = modDiff(cw.mods, name(cw.createdBy))
		val otherVersion = cw.mcVersion != WorldSync.mcVersion()
		val bg = d.background?.takeIf { !it.done() && it.world == levelId } ?: d.firstUploading(cw.name)
		val w = when {
			mods != null || otherVersion -> World(cw.name, WorldState.OTHER, bands(cw.id), mc = if (otherVersion) cw.mcVersion else null, mods = mods)
			lease != null && !lease.heldBy(me) && lease.state == Cloud.UPLOADING ->
				World(cw.name, WorldState.SAVING, bands(cw.id), who = lease.holderName, pct = (lease.progress * 100).roundToInt())
			// Heartbeats come every 30 s and the lease lasts 90: with under 45 s left, the host's game has stopped answering.
			// shortcut: compares the server's expiry with this PC's clock, so a clock off by over 45 s misreads it; use the
			// server's Date header if that turns up.
			lease != null && !lease.heldBy(me) -> World(cw.name, WorldState.HOSTED, bands(cw.id), who = lease.holderName).also { it.quiet = lease.expires - System.currentTimeMillis() < 45_000 }
			// Two sessions raced: someone picks which one everyone plays from now.
			cw.branches.isNotEmpty() && head != null -> World(cw.name, WorldState.VERSIONS, bands(cw.id), who = if (head.authorId == me) "you" else head.author, ago = ago(head.time))
			head == null || bg != null -> World(cw.name, WorldState.FIRST, bands(cw.id)).also { w -> bg?.let { w.upload = Moving(it) } }
			else -> World(cw.name, WorldState.READY, bands(cw.id), who = if (head.authorId == me) "you" else head.author, ago = ago(head.time))
		}
		w.hostId = lease?.holder?.takeIf { it != me }
		if (w.state == WorldState.READY && lease != null && inOtherGame(d, lease, cw.id, levelId, cw.name)) w.elsewhere = true
		w.levelId = levelId
		w.cloudId = cw.id
		w.head = cw.head
		w.branches += cw.branches
		w.owner = name(cw.createdBy)
		w.shared += name(cw.createdBy)
		w.shared += cw.sharedWith.map(::name)
		w.operators += cw.operators.map(::name)
		for (id in listOf(cw.createdBy) + cw.sharedWith) if (id != null) w.userIds[name(id)] = id
		w.size = size(head?.files?.values?.sumOf { it.size } ?: 0)
		w.storage = cw.storage
		w.movingTo = cw.movingTo
		w.movePct = if (cw.moveBytes > 0) (cw.movedBytes * 100 / cw.moveBytes).toInt() else 0
		w.storageProblem = cw.storageProblem
		w.stopWhenFree = cw.stopWhenFree
		w.version = cw.mcVersion
		w.needs = cw.mods
		w.game = GameInfo.parse(cw.game)
		if (head != null) { w.savedBy = if (head.authorId == me) "you" else head.author ?: ""; w.savedAt = head.time }
		w.hostedBy = lease?.takeIf { !it.heldBy(me) && it.state == Cloud.HOSTING }?.holderName
		if (levelId == null) w.icon = cloudIcon(d, cw.id, head)
		if (cw.id in RealLeave.full) {
			if (levelId == null || LocalState.load(WorldSync.folder(levelId)).pending == null) RealLeave.full -= cw.id
			else if (w.state == WorldState.READY) w.state = WorldState.PC_ONLY
		}
		return w
	}

	private val fetchingIcons = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

	/**
	 * The picture of a synced world that isn't in this game's saves: its latest save's icon.png, fetched once on the sync
	 * thread and kept by world (saves/.tandem-icons/<world>/icon.png, so each has its own texture). The previous picture
	 * stands in while a newer one arrives; the next reload of the list shows it.
	 */
	private fun cloudIcon(d: WorldSync, world: String, head: Snapshot?): Path? {
		val dir = WorldSync.saves().resolve(".tandem-icons").resolve(world.replace(Regex("[^A-Za-z0-9_-]"), "_"))
		val file = dir.resolve("icon.png")
		val have = file.takeIf { Files.isRegularFile(it) }
		val entry = head?.files?.get("icon.png") ?: return have
		val known = try { String(Files.readAllBytes(dir.resolve("sha")), Charsets.UTF_8) } catch (e: java.io.IOException) { null }
		// A world picture is 64 × 64: anything big isn't one, and isn't fetched.
		if (known == entry.sha256 || entry.size > 256 * 1024 || !fetchingIcons.add(world)) return have
		d.async {
			try {
				val bytes = d.cloud.openBlob(world, entry.sha256).use { it.readNBytes(256 * 1024 + 1) }
				val sha = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
				if (sha == entry.sha256) {
					Files.createDirectories(dir)
					Files.write(file, bytes)
					Files.write(dir.resolve("sha"), sha.toByteArray(Charsets.UTF_8))
				}
			} catch (e: Exception) {
				TandemClient.LOG.info("Tandem couldn't fetch the picture of a synced world: {}", e.toString())
			} finally {
				fetchingIcons.remove(world)
			}
			null
		}
		return have
	}

	/** What differs between this game's mods and the world's, or null when they match. */
	fun modDiff(worldMods: List<String>, host: String): Mods? {
		fun parse(list: List<String>) = list.associate { it.substringBefore('@') to it.substringAfter('@', "") }
		val mine = parse(WorldSync.mods())
		val theirs = parse(worldMods)
		// Any mod installed here, not only those that count here: whether one counts depends on the game's other mods.
		fun here(id: String) = FabricLoader.getInstance().getModContainer(id).map { it.metadata.version.friendlyString }.orElse(null)
		val missing = theirs.filterKeys { here(it) == null }.map { (id, v) -> listOf(id, v) }
		val different = theirs.mapNotNull { (id, v) -> here(id)?.takeIf { it != v }?.let { listOf(id, it, v) } }
		val extra = mine.filterKeys { it !in theirs }.map { (id, v) -> listOf(id, v) }
		return if (missing.isEmpty() && different.isEmpty() && extra.isEmpty()) null else Mods(host, missing, different, extra)
	}

	/** A time within the next day or so, as people say it: "9:40 pm", "9:40 pm tomorrow". */
	fun at(millis: Long): String {
		val zone = java.time.ZoneId.systemDefault()
		val then = java.time.Instant.ofEpochMilli(millis).atZone(zone)
		val time = then.format(java.time.format.DateTimeFormatter.ofPattern("h:mm a", java.util.Locale.ENGLISH)).lowercase()
		val days = java.time.temporal.ChronoUnit.DAYS.between(java.time.LocalDate.now(zone), then.toLocalDate())
		return if (days <= 0L) time else if (days == 1L) tr("tandem.s_tomorrow", time) else then.format(java.time.format.DateTimeFormatter.ofPattern("d MMM", java.util.Locale.ENGLISH)) + ", " + time
	}

	fun ago(millis: Long): String {
		val mins = (System.currentTimeMillis() - millis) / 60_000
		return when {
			mins < 1 -> tr("tandem.just_now")
			mins < 60 -> tr("tandem.min_ago", mins)
			mins < 48 * 60 -> tr("tandem.h_ago", mins / 60)
			else -> tr("tandem.days_ago", mins / (24 * 60))
		}
	}

	private fun size(bytes: Long) = bytesText(bytes)

	private fun folderBytes(dir: Path): Long = try {
		Files.walk(dir).use { s -> s.filter { Files.isRegularFile(it) }.mapToLong { Files.size(it) }.sum() }
	} catch (e: Exception) {
		0
	}

	// The sample's picture colours, picked by name so a world keeps its picture.
	private val PICTURES = listOf(
		listOf(0.55f to Color(0x9ec3e6), 0.64f to Color(0xcfd8e3), 1f to Color(0x9ec3e6)),
		listOf(0.45f to Color(0x6f8fae), 0.7f to Color(0x4f7a3a), 1f to Color(0x6b4f33)),
		listOf(0.48f to Color(0x8fb3d6), 0.72f to Color(0x6a9a52), 1f to Color(0x806246)),
		listOf(0.4f to Color(0xa35d3a), 1f to Color(0x5a2f22)),
		listOf(0.5f to Color(0x7a9cbf), 1f to Color(0x557a45)),
	)

	fun bands(key: String) = PICTURES[abs(key.hashCode() % PICTURES.size)]
}

/** Playing, waiting for and syncing real worlds through the dev cloud, with the same dialogs as the sample. */
class RealPlay(private val dialogs: Dialogs, private val changed: () -> Unit) {
	private val d get() = WorldSync.get()!!
	private val mc get() = Minecraft.getInstance()
	private val layers get() = dialogs.layers

	private fun <T> later(f: CompletableFuture<T>, failed: (Throwable) -> Unit = ::failed, ok: (T) -> Unit) {
		f.whenComplete { v, e ->
			mc.execute {
				val cause = if (e is CompletionException && e.cause != null) e.cause!! else e
				if (cause != null) failed(cause) else ok(v)
			}
		}
	}

	private fun failed(e: Throwable) {
		if (e !is CancellationException) layers.toast(tr("tandem.something_went_wrong"), e.message ?: e.javaClass.simpleName, "warn")
		RealWorlds.refresh(changed)
	}

	/** True when the world is a real one and its button was handled here. */
	fun act(w: World): Boolean {
		if (w.levelId == null && w.cloudId == null) return false
		when (w.state) {
			WorldState.LOCAL -> open(w.levelId!!)
			WorldState.READY -> play(w)
			WorldState.SAVING -> waitFor(w)
			WorldState.OTHER -> dialogs.mods(w)
			WorldState.HOSTED -> joinHost(w)
			WorldState.VERSIONS -> chooseVersion(w)
			WorldState.PC_ONLY -> dialogs.full(w)
			// A first upload KeplerSync refused (storage full, uploads paused): still yours to play here, and the session
			// stays on this PC until it can go up. One in progress has nothing to press.
			WorldState.FIRST -> if (w.upload == null && w.levelId != null) open(w.levelId!!)
			else -> {}
		}
		return true
	}

	private fun open(level: String) {
		// A synced world that won't load comes back to Tandem with the version before it.
		val synced = Sample.worlds.firstOrNull { it.levelId == level && it.cloudId != null }
		synced?.let { RealWorlds.opening = it to level }
		RealWorlds.openAfterReads {
			if (!unlocked(level)) {
				// Minecraft would only say "Failed to access world": friends shouldn't see this PC as the host either.
				RealWorlds.opened()
				RealWorlds.opening = null
				synced?.let(::release)
				return@openAfterReads layers.toast(tr("tandem.couldnt_open", synced?.name ?: level), tr("tandem.its_files_are_in_use_for_a"), "warn")
			}
			d.openWorld(level) { RealWorlds.opened(); mc.setScreen(PlayScreen()) }
		}
	}

	/** Whether Minecraft can take [level]'s lock now; a read of the saves folder holds it for a moment, so a few tries. */
	private fun unlocked(level: String): Boolean {
		repeat(5) {
			try {
				mc.levelSource.validateAndCreateAccess(level).close()
				return true
			} catch (e: Exception) {
				Thread.sleep(100)
			}
		}
		return false
	}

	/** Picking which version of a split world everyone plays from now. */
	private fun chooseVersion(w: World) {
		val me = d.player()
		later(d.async { d.cloud.history(w.cloudId!!) }) { history ->
			val ids = listOfNotNull(w.head) + w.branches
			val vs = ids.mapNotNull { id -> history.firstOrNull { it.id == id } }.sortedBy { it.time }.map { s ->
				val who = if (s.authorId == me) "You" else s.author ?: tr("tandem.someone")
				Version(who, 0, RealHistory.time(s.time), "", "", if (s.id == w.head) tr("tandem.the_latest_one") else tr("tandem.kept_beside_it")).also {
					it.snapshotId = s.id
					it.line = tr("tandem.saved_at", RealHistory.day(s.time).lowercase(), RealHistory.time(s.time), bytesText(s.totalBytes))
					it.face = if (who == "You") yourSkin() else Sample.friends.firstOrNull { f -> f.name == who }?.skin
				}
			}
			if (vs.size < 2) return@later RealWorlds.refresh(changed)
			dialogs.versions(w, vs) { v ->
				later(d.async { d.cloud.restore(w.cloudId!!, v.snapshotId!!, me) }) {
					RealHistory.forget(w)
					layers.toast(tr("tandem.everyone_now_plays_version", whose(v.who)),
						if (vs.size == 2) tr("tandem.the_other_is_in_history_where_it_can") else tr("tandem.the_others_are_in_history_where_they_can", vs.size - 1))
					RealWorlds.refresh(changed)
				}
			}
		}
	}

	/**
	 * [w] won't load: most likely a game closed while saving it. Offer the version before, restored on top as a
	 * new version (so the owner can undo it from History), then open it.
	 */
	fun wontLoad(w: World, level: String) {
		TandemClient.LOG.warn("Tandem: Minecraft couldn't load {}", w.name)
		val me = d.player()
		// The latest version as KeplerSync has it now: the list's may be from before the damaged one arrived.
		later(d.async { d.cloud.world(w.cloudId!!).head to d.cloud.history(w.cloudId!!) }, { release(w); failed(it) }) { (latest, history) ->
			val head = history.firstOrNull { it.id == latest } ?: history.firstOrNull()
			val before = head?.parent?.let { p -> history.firstOrNull { it.id == p } } ?: history.firstOrNull { it.id != head?.id }
			dialogs.wontLoad(w, before?.let { tr("tandem.save_from_at", whose(if (it.authorId == me) "You" else it.author), RealHistory.day(it.time).lowercase(), RealHistory.time(it.time)) }, cancel = { release(w) }) {
				val b = before ?: return@wontLoad
				later(d.async { d.cloud.restore(w.cloudId!!, b.id, me) }, { release(w); failed(it) }) {
					RealHistory.forget(w)
					w.levelId = level
					// Still this game's lease from opening it: not another game's.
					take(w)
				}
			}
		}
	}

	private fun release(w: World) {
		gettingReady -= w.cloudId!!
		d.async { d.cloud.releaseLease(w.cloudId!!, d.player()) }
	}

	/**
	 * Play, unless this account has [w] open in another game (a second PC, or another Minecraft on this one): KeplerSync
	 * holds leases by account, so it would be let through, and the world would split into two versions. A game that
	 * stopped answering or this game's own transfer doesn't count.
	 */
	fun play(w: World) {
		val id = w.cloudId!!
		later(d.async { d.cloud.lease(id) }) { now ->
			if (now != null && inOtherGame(d, now, id, w.levelId, w.name)) {
				TandemClient.LOG.info("Tandem: {} is open in another game on this account ({}), so it isn't played here", w.name, now.state)
				return@later dialogs.otherGame(w, now.state == Cloud.UPLOADING)
			}
			take(w)
		}
	}

	/** Take the lease, send anything this PC saved but didn't upload, ask about changes made outside Tandem, get the latest copy, open it. */
	private fun take(w: World) {
		val id = w.cloudId!!
		// Before asking, so a list read the moment KeplerSync says yes doesn't call this game's own lease another game's.
		gettingReady += id
		later(d.async { d.cloud.takeLease(id, d.player(), Cloud.DOWNLOADING) }) { lease ->
			if (!lease.heldBy(d.player())) {
				gettingReady -= id
				w.who = lease.holderName
				w.hostId = lease.holder
				if (lease.state == Cloud.UPLOADING) waitFor(w) else joinHost(w)
				RealWorlds.refresh(changed)
				return@later
			}
			val level = w.levelId ?: WorldSync.newFolder(w.name)
			if (LocalState.load(WorldSync.folder(level)).pending != null) {
				later(d.uploadPending(level, id, w.name, false).future, { release(w); failed(it) }) { get(w, level) }
				return@later
			}
			later(d.localChanges(level), { release(w); failed(it) }) { diff ->
				val n = if (diff == null) 0 else diff.added.size + diff.changed.size + diff.removed.size
				if (n == 0) get(w, level) else dialogs.changedOutside(w, n, cancel = { release(w) }, discard = { get(w, level) }) {
					later(d.async { d.sync().checkpoint(WorldSync.folder(level), Snapshot.SESSION) }, { release(w); failed(it) }) {
						later(d.uploadPending(level, id, w.name, false).future, { release(w); failed(it) }) { get(w, level) }
					}
				}
			}
		}
	}

	private fun get(w: World, level: String) {
		val t = d.getLatest(level, w.cloudId!!, w.name)
		var asked = false
		// Someone in the world who's online, to host instead if this download turns out slow (A2).
		val helper = Sample.friends.firstOrNull { it.online && it.userId != null && it.userId in w.userIds.values }
		dialogs.gettingLatest(w, t, helper, { h -> asked = true; t.progress.cancel(); askToHost(w, h) }) { t.progress.cancel(); release(w) }
		later(t.future, { e ->
			if (asked) return@later
			layers.closeDialog()
			release(w)
			if (e is Sync.NotEnoughSpace) dialogs.noSpace(w, e.needed, e.free, WorldSync.saves().root?.toString()?.trimEnd('\\', '/') ?: tr("tandem.this_pc_2")) { play(w) }
			else failed(e)
		}) {
			if (asked) return@later
			layers.closeDialog()
			open(level)
		}
	}

	/**
	 * This player's download was slow: the world is let go (what's downloaded stays for next time), [friend] is asked
	 * to host it, and this player joins them once they are. They can say they can't, and the wait ends.
	 */
	private fun askToHost(w: World, friend: Person) {
		release(w)
		val a = KsAccount.get() ?: return
		var status = tr("tandem.asking", friend.name)
		var stopped = false
		dialogs.askedToHost(friend, w.name, { status }) { stopped = true }
		later(d.async { a.api.requestHost(w.cloudId!!, friend.userId!!) }) { status = tr("tandem.waiting_for_to_open_it", friend.name) }
		val until = System.currentTimeMillis() + 15 * 60_000
		fun poll() {
			later(ask {
				val l = d.cloud.lease(w.cloudId!!)
				if (l != null && l.heldBy(friend.userId!!) && l.state == Cloud.HOSTING) runCatching { a.api.playForWorld(w.cloudId!!) }.getOrNull() else null
			}) { s ->
				if (stopped) return@later
				if (s != null) return@later RealJoin.join(s.id, friend.name, w.name, dialogs)
				if (a.hostDeclines.removeIf { it.worldId == w.cloudId && it.userId == friend.userId }) {
					layers.closeDialog()
					return@later layers.toast(tr("tandem.cant_host_right_now", friend.name), tr("tandem.play_it_to_get_the_latest_copy"), "warn")
				}
				if (System.currentTimeMillis() > until) {
					layers.closeDialog()
					return@later layers.toast(tr("tandem.isnt_hosting_yet", friend.name, w.name), tr("tandem.join_them_from_play_once_they_are"), "warn")
				}
				soon(::poll)
			}
		}
		poll()
	}

	/** [from] asked this player to host [worldId]: Play it, as from the list; they join once it's open. */
	fun hostFor(worldId: String) = world(worldId) { w -> play(w) }

	/**
	 * Join whoever is hosting [w]: members need no invite. Nobody hosting after all (their game closed and the lease ran
	 * out): the list is read again and the world played. Without a game open to friends, it says so.
	 */
	private fun joinHost(w: World) {
		val a = KsAccount.get() ?: return dialogs.joinLater(w)
		later(d.async { a.api.playForWorld(w.cloudId!!) }, { e ->
			if (e !is KeplerSyncApi.Refused || e.status != 404) return@later dialogs.joinLater(w)
			RealWorlds.refresh {
				changed()
				val now = Sample.worlds.firstOrNull { it.cloudId == w.cloudId }
				if (now != null && now.state == WorldState.READY) play(now) else dialogs.joinLater(now ?: w)
			}
		}) { s ->
			RealJoin.join(s.id, w.who, w.name, dialogs, unreachable = tr("tandem.s_game_isnt_answering_if_it_closed", w.who, w.name))
		}
	}

	/** Finds [worldId] in the list, loading it first if it isn't there yet. */
	private fun world(worldId: String, then: (World) -> Unit) {
		Sample.worlds.firstOrNull { it.cloudId == worldId }?.let { return then(it) }
		RealWorlds.refresh {
			changed()
			Sample.worlds.firstOrNull { it.cloudId == worldId }?.let(then)
		}
	}

	/** [from] handed [worldId] to this player: they're asked first, and nothing downloads until they say Host it. */
	fun takeOver(worldId: String, from: String) = world(worldId) { w ->
		dialogs.handedToYou(from, w.name, host = { host(w, from) }, decline = { decline(w) })
	}

	/**
	 * Hosting what [from] handed over: their upload, then KeplerSync gives the lease here, then the latest copy and
	 * Play as usual. Everyone else in the world joins once it's open here.
	 */
	private fun host(w: World, from: String) {
		var pct = 0f
		var status = tr("tandem.waiting_for_s_game_to_save_it", from)
		var stopped = false
		dialogs.takingOver(from, w.name, { status }, { pct }) { stopped = true; decline(w) }
		val until = System.currentTimeMillis() + 30 * 60_000
		fun poll() {
			later(ask { d.cloud.lease(w.cloudId!!) }) { l ->
				if (stopped) return@later
				if (l == null || l.heldBy(d.player())) {
					layers.closeDialog()
					// KeplerSync gave the lease here: it's this game's, not another game's.
					take(w)
				} else if (System.currentTimeMillis() > until) {
					layers.closeDialog()
					layers.toast(tr("tandem.s_game_didnt_finish_saving", from, w.name), tr("tandem.try_play_again_in_a_little_while"), "warn")
				} else {
					if (l.state == Cloud.UPLOADING) { pct = l.progress; status = tr("tandem.s_game_is_saving_it", from, (pct * 100).roundToInt()) }
					soon(::poll)
				}
			}
		}
		poll()
	}

	/** Not hosting after all: the world is freed, and those waiting to join this player stop waiting. */
	private fun decline(w: World) {
		val a = KsAccount.get() ?: return
		a.run { api -> api.declineHandoff(w.cloudId!!) }.whenComplete { _, e ->
			mc.execute {
				val cause = if (e is CompletionException && e.cause != null) e.cause!! else e
				if (cause != null) layers.toast(tr("tandem.something_went_wrong"), cause.message ?: cause.javaClass.simpleName, "warn")
				else layers.toast(tr("tandem.is_free_for_anyone", w.name), tr("tandem.anyone_its_shared_with_can_open_it"))
			}
		}
	}

	/**
	 * This game was in [hostId]'s game of [worldId] and lost the connection. Only when KeplerSync says their game stopped
	 * answering (under 45 s left on a 90 s lease that heartbeats renew every 30 s), or let the world go, is it the host's
	 * game: then this player can take over from the last save. A host still answering means this player's own connection
	 * dropped, and a host uploading is leaving the usual way: nothing to say then. Someone else taking over first is joined.
	 */
	fun hostStopped(worldId: String, hostId: String, hostName: String, others: List<String>) = world(worldId) { w ->
		val until = System.currentTimeMillis() + 75_000
		fun poll() {
			later(ask {
				val l = d.cloud.lease(w.cloudId!!)
				val saved = w.head?.let { h -> runCatching { d.cloud.snapshot(w.cloudId!!, h) }.getOrNull()?.time }
				l to saved
			}, { RealWorlds.refresh(changed) }) { (l, saved) ->
				if (l != null && !l.heldBy(hostId) && !l.heldBy(d.player())) return@later take(w)
				val gone = l == null || (l.heldBy(hostId) && l.state == Cloud.HOSTING && l.expires - System.currentTimeMillis() < 45_000)
				if (gone) {
					val host = Sample.friends.firstOrNull { it.userId == hostId } ?: Person(hostName, 0, false, "")
					dialogs.stopped(host, w.name, others, saved?.let(RealWorlds::ago), title = { layers.closeDialog(); mc.setScreen(TitleScreen()) }) {
						layers.closeDialog()
						takeOverStopped(w, hostId, hostName)
					}
				} else if (l?.state == Cloud.HOSTING && System.currentTimeMillis() < until) soon(::poll)
			}
		}
		poll()
	}

	/** Take over hosting after [hostName]'s game stopped: once KeplerSync lets the world go, as for Play. */
	private fun takeOverStopped(w: World, hostId: String, hostName: String) {
		var status = tr("tandem.waiting_for_keplersync_to_let_it_go")
		var stopped = false
		dialogs.freeing(hostName, w.name, { status }) { stopped = true }
		val until = System.currentTimeMillis() + 3 * 60_000
		fun poll() {
			later(ask { d.cloud.lease(w.cloudId!!) }) { l ->
				if (stopped) return@later
				if (l == null || !l.heldBy(hostId)) { layers.closeDialog(); return@later take(w) }
				if (System.currentTimeMillis() > until) {
					layers.closeDialog()
					return@later layers.toast(tr("tandem.is_hosting_again", hostName, w.name), tr("tandem.join_them_from_play"))
				}
				status = tr("tandem.free_in_about_s", ((l.expires - System.currentTimeMillis()) / 1000).coerceAtLeast(0))
				soon(::poll)
			}
		}
		poll()
	}

	/** [from] handed [worldId] to [to] (by KeplerSync id, [toName] on screen): join them as soon as they're hosting it. */
	fun follow(worldId: String, from: String, toName: String, to: String) = world(worldId) { w ->
		var status = tr("tandem.s_game_is_saving", from, w.name)
		var stopped = false
		dialogs.following(from, toName, w.name, { status }) { stopped = true }
		val a = KsAccount.get() ?: return@world
		val until = System.currentTimeMillis() + 30 * 60_000
		fun poll() {
			later(ask {
				val l = d.cloud.lease(w.cloudId!!)
				l to if (l != null && l.heldBy(to) && l.state == Cloud.HOSTING) runCatching { a.api.playForWorld(w.cloudId!!) }.getOrNull() else null
			}) { (l, s) ->
				if (stopped) return@later
				if (s != null) return@later RealJoin.join(s.id, toName, w.name, dialogs)
				// Nobody has it: they said no, or the host's game stopped before handing it over.
				if (l == null) {
					layers.closeDialog()
					return@later layers.toast(tr("tandem.isnt_hosting", toName, w.name), tr("tandem.its_free_now_open_it_from_play"), "warn")
				}
				if (System.currentTimeMillis() > until) {
					layers.closeDialog()
					return@later layers.toast(tr("tandem.isnt_hosting_yet", toName, w.name), tr("tandem.join_them_from_play_once_they_are"), "warn")
				}
				status = tr("tandem.waiting_for_to_open_it", toName)
				soon(::poll)
			}
		}
		poll()
	}

	/** Play when ready: the friend's upload, from their lease, then Play by itself. */
	private val DAY = 24 * 60 * 60_000L

	private fun waitFor(w: World) {
		var pct = w.pct / 100f
		var free = false
		dialogs.waitingFor(w, { pct }) { free }
		fun poll() {
			later(ask { d.cloud.lease(w.cloudId!!) }) { l ->
				if (!layers.busy) return@later
				if (l == null || l.state != Cloud.UPLOADING) { free = true; layers.closeDialog(); RealWorlds.refresh(changed); play(w) }
				// An upload's lease lasts a day past its last heartbeat (every 30 s): none for 90 s, and they quit before it
				// finished (A4). It finishes when they're back; the day running out lets the group carry on (A5).
				else if (System.currentTimeMillis() - (l.expires - DAY) > 90_000) {
					free = true
					layers.closeDialog()
					later(ask { w.head?.let { h -> runCatching { d.cloud.snapshot(w.cloudId!!, h) }.getOrNull()?.time } }) { t ->
						val owner = if (w.mine) { { carryOn(w) } } else null
						dialogs.waitQuit(w, l.progress, RealWorlds.at(l.expires), t?.let(RealWorlds::ago) ?: tr("tandem.its_last_save"), owner)
					}
				}
				else { pct = l.progress; soon(::poll) }
			}
		}
		poll()
	}

	/** The owner stops waiting for a friend's upload that stalled: KeplerSync lets the world go, and it opens from the last one. */
	private fun carryOn(w: World) {
		val a = KsAccount.get() ?: return
		later(ask { a.api.carryOn(w.cloudId!!) }) { RealWorlds.refresh(changed); play(w) }
	}

	/** A quick question for the cloud, off the sync thread: that may be busy with a transfer for minutes. */
	private fun <T> ask(work: () -> T): CompletableFuture<T> = CompletableFuture.supplyAsync(work)

	/**
	 * Runs [next] on the render thread once KeplerSync says a world changed, or after 10 s, whichever is first: waiting
	 * dialogs ask again then, not every second. A dev cloud sends nothing, so it's every 2 s there.
	 */
	private fun soon(next: () -> Unit) {
		val seen = KsAccount.get()?.worldsVersion
		val until = System.currentTimeMillis() + if (seen == null) 2_000 else 10_000
		fun tick() {
			if (KsAccount.get()?.worldsVersion != seen || System.currentTimeMillis() >= until) mc.execute(next)
			else CompletableFuture.delayedExecutor(250, TimeUnit.MILLISECONDS).execute(::tick)
		}
		tick()
	}

	/** Sync this world from the Play list: the first upload, in the background. */
	fun sync(w: World, picked: List<String>) {
		// The picker shows names; KeplerSync shares by account.
		val ids = picked.mapNotNull { n -> Sample.friends.firstOrNull { it.name == n }?.userId }
		val t = d.firstUpload(w.levelId!!, w.name, ids)
		w.owner = "You"; w.shared.clear(); w.shared += "You"; w.shared += picked
		w.state = WorldState.FIRST; w.upload = Moving(t)
		changed()
		later(t.future) { RealWorlds.refresh(changed) }
	}
}

/**
 * Friends, requests and the account's name and friend code from KeplerSync, in place of the sample's, when this run
 * signs in ([KsAccount]). The screens keep using [Sample.friends] and [Sample.requests]; this fills them.
 */
object RealFriends {
	val on get() = KsAccount.get() != null
	val signedIn get() = KsAccount.get()?.signedIn() == true
	/** Why the friends list is empty: still signing in, or KeplerSync can't be reached. */
	val problem get() = KsAccount.get()?.let { it.problem ?: if (!it.signedIn()) tr("tandem.signing_in_to_keplersync") else null }

	private var seen = -1
	// Filled with textures off the render thread, so real skins show once they're known.
	private val profiles = ConcurrentHashMap<UUID, GameProfile>()

	/** Copies the account into the screens' lists when it changed. Returns true when it did. */
	fun sync(): Boolean {
		val a = KsAccount.get() ?: return false
		if (a.version == seen) return false
		seen = a.version
		a.me?.let { me ->
			Account.name = me.displayName
			me.username?.let { Account.handle = it }
			me.friendLink?.let { Account.link = it }
		}
		Sample.friends.clear()
		Sample.friends.addAll(a.friends.map { person(it.user, a.presenceOf(it.user.id)) }.sortedBy { it.rank })
		Sample.requests.clear()
		Sample.requests.addAll(a.requests.filter { it.direction == "incoming" }.map { person(it.user, null, tr("tandem.wants_to_be_friends")) })
		return true
	}

	private fun label(u: KeplerSyncApi.User): String = u.displayName ?: u.username ?: u.id

	private fun person(u: KeplerSyncApi.User, p: KeplerSyncApi.Presence?, note: String? = null): Person {
		val state = p?.state ?: "offline"
		val invite = KsAccount.get()?.invites?.firstOrNull { it.host.id == u.id }
		val text = note ?: if (invite != null) tr("tandem.hosting", invite.worldName) else when (state) {
			"playing" -> p?.instanceName?.let { tr("tandem.playing", it) } ?: tr("tandem.playing_2")
			"online" -> if (p?.inMinecraft == true) tr("tandem.in_the_menu") else tr("tandem.in_atlas")
			else -> tr("tandem.offline") + (p?.updatedAt?.let { " · " + RealWorlds.ago(Instant.parse(it).toEpochMilli()) } ?: "")
		}
		return Person(label(u), abs(u.id.hashCode() % 16), note != null || state != "offline", text, hosting = invite != null, inWorld = state == "playing").also {
			it.userId = u.id
			it.handle = u.username
			it.atlasOnly = invite == null && note == null && state == "online" && p?.inMinecraft != true
			it.profile = u.minecraftUuid?.let { id -> profile(UUID.fromString(dashed(id)), u.minecraftUsername ?: u.displayName) }
		}
	}

	/** Someone found by name or link, as the add-a-friend dialog shows them. */
	fun found(u: KeplerSyncApi.User): Person = person(u, null, "")

	private fun dashed(id: String) = if (id.contains('-')) id else "${id.substring(0, 8)}-${id.substring(8, 12)}-${id.substring(12, 16)}-${id.substring(16, 20)}-${id.substring(20)}"

	private fun profile(id: UUID, name: String): GameProfile = profiles.getOrPut(id) {
		CompletableFuture.supplyAsync { Minecraft.getInstance().minecraftSessionService.fetchProfile(id, false)?.profile() }
			.thenAccept { full -> if (full != null) { profiles[id] = full; seen = -1 } }
		GameProfile(id, name)
	}

	private fun act(layers: Layers, changed: () -> Unit, done: String?, detail: String?, action: KsAccount.IoAction) {
		val mc = Minecraft.getInstance()
		KsAccount.get()!!.run(action).whenComplete { _, e ->
			mc.execute {
				val cause = if (e is CompletionException && e.cause != null) e.cause!! else e
				if (cause != null) layers.toast(tr("tandem.something_went_wrong"), cause.message ?: cause.javaClass.simpleName, "warn")
				else if (done != null) layers.toast(done, detail)
				sync(); changed()
			}
		}
	}

	fun accept(layers: Layers, changed: () -> Unit, p: Person) =
		act(layers, changed, tr("tandem.you_and_are_friends", p.name), tr("tandem.invite_from_the_pause_menu", p.name)) { it.accept(p.userId) }

	fun decline(layers: Layers, changed: () -> Unit, p: Person) = act(layers, changed, null, null) { it.decline(p.userId) }

	fun remove(layers: Layers, changed: () -> Unit, p: Person) =
		act(layers, changed, tr("tandem.removed", p.name), tr("tandem.isnt_told_worlds_you_share_stay_shared", p.name)) { it.removeFriend(p.userId) }

	fun block(layers: Layers, changed: () -> Unit, p: Person) =
		act(layers, changed, tr("tandem.blocked_2", p.name), tr("tandem.theyre_out_of_the_worlds_you_own")) { it.block(p.userId); blockedPeople = null }

	/** Who this account blocked, for Settings; read once, and again after a block. */
	@Volatile private var blockedPeople: List<Person>? = null
	@Volatile var blockedProblem: String? = null
		private set
	@Volatile private var loadingBlocked = false
	/** The account's version when loading failed: tried again once something changes (a reconnect), not on every rebuild. */
	@Volatile private var blockedFailedAt = -1

	/** The people you blocked, or null while they load from KeplerSync; [loaded] runs once they're in. */
	fun blocked(loaded: () -> Unit): List<Person>? {
		blockedPeople?.let { return it }
		val a = KsAccount.get() ?: return null
		if (!loadingBlocked && a.version != blockedFailedAt) {
			loadingBlocked = true
			CompletableFuture.supplyAsync { a.api.blocked() }.whenComplete { list, e ->
				Minecraft.getInstance().execute {
					loadingBlocked = false
					if (list != null) { blockedPeople = list.map { person(it, null) }; blockedProblem = null }
					else { blockedProblem = e?.let { (it.cause ?: it).message ?: it.javaClass.simpleName }; blockedFailedAt = a.version }
					loaded()
				}
			}
		}
		return null
	}

	fun unblock(layers: Layers, changed: () -> Unit, p: Person) =
		act(layers, changed, tr("tandem.unblocked", p.name), tr("tandem.they_arent_added_back_as_a_friend")) { api ->
			api.unblock(p.userId)
			blockedPeople = blockedPeople?.filter { it.userId != p.userId }
		}

	/** A new display name, the same in Atlas: KeplerSync checks it (1 to 32 characters, not someone else's). */
	fun rename(layers: Layers, changed: () -> Unit, name: String) =
		act(layers, changed, tr("tandem.name_changed"), tr("tandem.friends_see_it_in_atlas_and_tandem")) { KsAccount.get()?.me = it.updateProfile(name) }

	/** [who] is a Minecraft name, or a friend link. */
	fun addFriend(layers: Layers, changed: () -> Unit, who: String) =
		act(layers, changed, tr("tandem.friend_request_sent"), tr("tandem.they_show_up_here_once_they_accept")) { it.addFriend(who) }

	/** [reason] is the dialog's wording; KeplerSync files it as abuse unless it's "Something else". */
	fun report(layers: Layers, changed: () -> Unit, p: Person, reason: String, details: String, block: Boolean) =
		act(layers, changed, tr("tandem.report_sent"), if (block) tr("tandem.thanks_is_blocked_too", p.name) else tr("tandem.thanks_keplersync_looks_at_every_report")) {
			it.report(p.userId, if (reason == tr("tandem.something_else")) "other" else "abuse", "$reason. $details")
			if (block) { it.block(p.userId); blockedPeople = null }
		}

	fun newCode(layers: Layers, changed: () -> Unit) = act(layers, changed, null, null) { api ->
		val me = api.newFriendCode()
		Minecraft.getInstance().execute {
			me.friendLink?.let { Account.link = it }
			layers.toast(tr("tandem.you_have_a_new_friend_link"), tr("tandem.the_old_one_no_longer_works"))
		}
	}
}

/** A real synced world's History and Download as zip. */
object RealHistory {
	private val cache = ConcurrentHashMap<String, List<Snapshot>>()
	private val loading = ConcurrentHashMap.newKeySet<String>()
	/** KeplerSync's world-changed count when the cache was filled: anything newer means history moved on. */
	private var seen = -1
	private val mc get() = Minecraft.getInstance()

	/** [w]'s history if it's loaded; otherwise it starts loading and [loaded] runs once it's in. */
	fun of(w: World, loaded: () -> Unit): List<Snapshot>? {
		val id = w.cloudId ?: return null
		val now = KsAccount.get()?.worldsVersion ?: 0
		if (now != seen) { cache.clear(); seen = now }
		cache[id]?.let { return it }
		val d = WorldSync.get() ?: return null
		if (loading.add(id)) d.async { d.cloud.history(id) }.whenComplete { h, _ ->
			mc.execute {
				loading.remove(id)
				if (h != null) { cache[id] = h; loaded() }
			}
		}
		return null
	}

	fun forget(w: World) {
		w.cloudId?.let(cache::remove)
	}

	/** The owner and operators restore. */
	fun canRestore(w: World) = w.mine || "You" in w.operators

	fun restore(w: World, s: Snapshot, layers: Layers, done: () -> Unit) {
		val d = WorldSync.get() ?: return
		d.async { d.cloud.restore(w.cloudId!!, s.id, d.player()) }.whenComplete { _, e ->
			mc.execute {
				forget(w)
				if (e != null) layers.toast(tr("tandem.couldnt_restore", w.name), (e.cause ?: e).message ?: tr("tandem.try_again_2"), "warn")
				else layers.toast(tr("tandem.restored"), tr("tandem.its_the_latest_version_of_now_for", w.name))
				RealWorlds.refresh { }
				done()
			}
		}
	}

	/**
	 * Download as zip: [snapshotId] (the latest when null) of a synced world, or a world only on this PC, as a
	 * plain world in the Downloads folder. Free, and fine over quota: it only reads.
	 */
	fun zip(w: World, snapshotId: String?, layers: Layers) {
		val d = WorldSync.get() ?: return
		val zip = downloads(w.name)
		layers.toast(tr("tandem.downloading", zip.fileName), tr("tandem.to_big_worlds_take_a_while", zip.parent), "up")
		val job = if (w.cloudId != null) d.async {
			val id = snapshotId ?: w.head ?: d.cloud.world(w.cloudId!!).head ?: throw java.io.IOException(tr("tandem.hasnt_finished_its_first_upload", w.name))
			d.sync().exportZip(w.cloudId!!, id, safeName(w.name), zip, app.keplersync.tandem.core.Progress())
		} else d.async { zipFolder(WorldSync.folder(w.levelId!!), safeName(w.name), zip) }
		job.whenComplete { _, e ->
			mc.execute {
				if (e != null) layers.toast(tr("tandem.couldnt_download_zip", w.name), (e.cause ?: e).message ?: tr("tandem.try_again_2"), "warn")
				// A notice, not the screen's toast: the screen may be closed by now.
				else Ingame.notice(tr("tandem.downloaded", zip.fileName), tr("tandem.in_a_plain_world_ready_for_any", zip.parent), "check")
			}
		}
	}

	/**
	 * The player's Downloads folder on each system: on Windows where Explorer keeps it (it can be moved), on Linux where
	 * the desktop's user-dirs says (often translated, like ~/Téléchargements), ~/Downloads otherwise and on macOS.
	 */
	private fun downloadsFolder(): Path {
		val home = Path.of(System.getProperty("user.home"))
		try {
			when (net.minecraft.Util.getPlatform()) {
				net.minecraft.Util.OS.WINDOWS -> {
					val out = ProcessBuilder("reg", "query", "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\User Shell Folders",
						"/v", "{374DE290-123F-4565-9164-39C4925E467B}").redirectErrorStream(true).start().let { p ->
						p.inputStream.bufferedReader().use { it.readText() }.also { p.waitFor(2, TimeUnit.SECONDS) }
					}
					val raw = Regex("REG_(?:EXPAND_)?SZ\\s+(.+)").find(out)?.groupValues?.get(1)?.trim()
					val expanded = raw?.replace(Regex("%([^%]+)%")) { m -> System.getenv(m.groupValues[1]) ?: m.value }
					if (expanded != null && !expanded.contains('%')) return Path.of(expanded)
				}
				net.minecraft.Util.OS.LINUX -> {
					val dirs = home.resolve(".config/user-dirs.dirs")
					if (Files.isRegularFile(dirs)) Files.readAllLines(dirs).firstNotNullOfOrNull { Regex("^XDG_DOWNLOAD_DIR=\"(.+)\"$").find(it.trim())?.groupValues?.get(1) }
						?.let { return Path.of(it.replace("\$HOME", home.toString())) }
				}
				else -> {}
			}
		} catch (e: Exception) {
			TandemClient.LOG.info("Tandem couldn't find the Downloads folder, so it uses ~/Downloads: {}", e.toString())
		}
		return home.resolve("Downloads")
	}

	/**
	 * A world's name as a file or folder name: the owner picks it, so nothing in it may reach outside the folder it's put
	 * in (slashes, `..`), or be a name Windows refuses.
	 */
	private fun safeName(name: String) = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().trimEnd('.').ifEmpty { tr("tandem.world") }

	fun downloads(name: String): Path {
		// Dev checks save somewhere of their own.
		val dir = System.getProperty("tandem.downloads")?.let { Path.of(it) } ?: downloadsFolder()
		Files.createDirectories(dir)
		val safe = safeName(name)
		var f = dir.resolve("$safe.zip")
		var n = 2
		while (Files.exists(f)) f = dir.resolve("$safe (${n++}).zip")
		return f
	}

	private fun zipFolder(dir: Path, name: String, zip: Path) {
		val part = zip.resolveSibling("${zip.fileName}.part")
		java.util.zip.ZipOutputStream(Files.newOutputStream(part)).use { out ->
			Files.walk(dir).use { files ->
				for (f in files.filter { Files.isRegularFile(it) && it.fileName.toString() != "session.lock" }.toList()) {
					out.putNextEntry(java.util.zip.ZipEntry("$name/" + dir.relativize(f).toString().replace('\\', '/')))
					Files.copy(f, out)
					out.closeEntry()
				}
			}
		}
		Files.move(part, zip, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
	}

	fun day(millis: Long): String {
		val date = java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
		val today = java.time.LocalDate.now()
		return when (date) {
			today -> tr("tandem.today")
			today.minusDays(1) -> tr("tandem.yesterday")
			else -> date.format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM"))
		}
	}

	fun time(millis: Long): String = java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault())
		.format(java.time.format.DateTimeFormatter.ofPattern("h:mm a")).lowercase()
}

/** Operators of a real synced world, from its Shared with page: they keep commands whoever hosts. */
object RealOperators {
	/** The owner and operators may change who's an operator; nobody changes the owner. */
	fun canChange(w: World, name: String) = w.cloudId != null && "You" in w.operators && name != w.owner && name != "You" && w.userIds[name] != null

	fun set(w: World, name: String, on: Boolean, layers: Layers, done: () -> Unit) {
		val mc = Minecraft.getInstance()
		val account = KsAccount.get() ?: return
		WorldSync.get()!!.async { account.api.setOperator(w.cloudId!!, w.userIds.getValue(name), on) }.whenComplete { _, e ->
			mc.execute {
				if (e != null) layers.toast(tr("tandem.couldnt_change", name), (e.cause ?: e).message ?: tr("tandem.try_again_2"), "warn")
				else {
					if (on) w.operators += name else w.operators -= name
					layers.toast(if (on) tr("tandem.is_an_operator_now", name) else tr("tandem.isnt_an_operator_now", name), tr("tandem.in_whoever_hosts_it", w.name))
				}
				done()
			}
		}
	}
}

/** Who a real synced world is shared with, changed by its owner from the Shared with tab. */
object RealMembers {
	fun add(w: World, names: List<String>, layers: Layers, done: () -> Unit) {
		val ids = names.mapNotNull { n -> Sample.friends.firstOrNull { it.name == n }?.userId }
		call(layers, tr("tandem.couldnt_share", w.name), { api -> ids.forEach { api.addMember(w.cloudId!!, it) } }) {
			w.shared += names
			layers.toast(tr("tandem.shared_with_2", Pages.names(names)), tr("tandem.they_can_play_and_host_it_when", w.name))
			done()
		}
	}

	fun remove(w: World, name: String, layers: Layers, done: () -> Unit) {
		val id = w.userIds[name] ?: return
		call(layers, tr("tandem.couldnt_remove", name), { api -> api.removeMember(w.cloudId!!, id) }) {
			w.shared -= name
			layers.toast(tr("tandem.cant_play_any_more", name, w.name), tr("tandem.their_copy_stays_on_their_pc_as"), undo = { add(w, listOf(name), layers, done) })
			done()
		}
	}

	private fun call(layers: Layers, failed: String, work: (KeplerSyncApi) -> Unit, ok: () -> Unit) {
		val account = KsAccount.get() ?: return layers.toast(failed, tr("tandem.still_signing_in_to_keplersync_try_again"), "warn")
		WorldSync.get()!!.async { work(account.api) }.whenComplete { _, e ->
			Minecraft.getInstance().execute {
				if (e != null) layers.toast(failed, (e.cause ?: e).message ?: tr("tandem.try_again_2"), "warn") else ok()
				RealWorlds.refresh { }
			}
		}
	}
}

/** Joining a friend's game for real: KeplerSync's yes, matching mods, then Minecraft's own connect screen. */
object RealJoin {
	/**
	 * Joins [session], showing each step in a dialog in [dialogs], and what went wrong in its place; [unreachable]
	 * words it when the host's game doesn't answer.
	 */
	fun join(session: String, host: String, world: String, dialogs: Dialogs, unreachable: String? = null) {
		// Hosting friends here: hand off or close for them first, as with Save and Quit.
		PausePanel.beforeLeaving(dialogs) { go(session, host, world, dialogs, unreachable) }
	}

	private fun go(session: String, host: String, world: String, dialogs: Dialogs, unreachable: String?) {
		val mc = Minecraft.getInstance()
		KsAccount.get()?.forgetInvite(session)
		var step = tr("tandem.asking_keplersync")
		var cancelled = false
		dialogs.joining(host, world, { step }) { cancelled = true }
		fun fail(why: String) = dialogs.couldntJoin(host, why)
		Joining.prepare(session) { step = it }.whenComplete { t, e ->
			mc.execute {
				if (cancelled) return@execute
				val cause = if (e is CompletionException && e.cause != null) e.cause!! else e
				if (t == null) {
					val why = cause?.message ?: tr("tandem.keplersync_didnt_answer")
					return@execute fail(if (unreachable != null && why.contains(tr("tandem.isnt_answering"))) unreachable else why)
				}
				val name = t.info.host.displayName
				if (t.info.minecraftVersion != WorldSync.mcVersion()) return@execute fail(tr("tandem.is_playing_minecraft_and_this_game_is", name, t.info.minecraftVersion, WorldSync.mcVersion()))
				val diff = RealWorlds.modDiff(t.info.mods, name)
				if (diff != null) dialogs.mods(World(t.info.worldName, WorldState.HOSTED, RealWorlds.bands(t.info.sessionId), who = name, mods = diff))
				else {
					step = tr("tandem.connecting")
					Joining.connect(t)
				}
			}
		}
	}
}
