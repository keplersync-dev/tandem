package app.keplersync.tandem.fabric.ui

import app.keplersync.tandem.core.KeplerSyncApi
import app.keplersync.tandem.core.LocalState
import app.keplersync.tandem.core.Progress
import app.keplersync.tandem.fabric.account.Account as KsAccount
import app.keplersync.tandem.fabric.sync.WorldSync
import net.minecraft.client.Minecraft
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/** [work] on the sync thread, then [ok] on the render thread, or a toast titled [what] saying what went wrong. */
private fun <T> io(layers: Layers, what: String, work: () -> T, failed: (Throwable) -> Unit = {}, ok: (T) -> Unit) {
	WorldSync.get()!!.async(work).whenComplete { v, e ->
		Minecraft.getInstance().execute {
			val cause = if (e is CompletionException && e.cause != null) e.cause!! else e
			if (cause != null) { layers.toast(what, cause.message ?: tr("tandem.try_again_2"), "warn"); failed(cause) } else ok(v)
		}
	}
}

private val api get() = KsAccount.get()!!.api

/** Your storage from KeplerSync (the Storage tab), and your own R2 bucket's keys: saving, testing, removing. */
object RealStorage {
	val on get() = RealFriends.signedIn && WorldSync.get()?.server == true

	@Volatile private var overview: KeplerSyncApi.StorageOverview? = null
	@Volatile private var loading = false
	/** KeplerSync's world-changed count when [overview] came in: anything newer means sizes or storage moved on. */
	private var seen = -1

	/** The Storage tab's numbers if they're in; otherwise they load, and [loaded] runs once they are. */
	fun of(loaded: () -> Unit): KeplerSyncApi.StorageOverview? {
		val now = KsAccount.get()?.worldsVersion ?: 0
		if (now != seen) { overview = null; seen = now }
		overview?.let { return it }
		if (!loading) {
			loading = true
			WorldSync.get()!!.async { api.storage() }.whenComplete { o, _ ->
				Minecraft.getInstance().execute { loading = false; if (o != null) { overview = o; loaded() } }
			}
		}
		return null
	}

	fun forget() {
		overview = null
	}

	/** Tests the keys and saves them when they work; [done] gets what was saved, or why not. */
	fun save(accountId: String, bucket: String, keyId: String, secret: String, done: (KeplerSyncApi.OwnStorage?, String?) -> Unit) {
		WorldSync.get()!!.async { api.saveOwnStorage(accountId, bucket, keyId, secret) }.whenComplete { o, e ->
			Minecraft.getInstance().execute {
				forget()
				done(o, if (o == null) (e?.cause ?: e)?.message ?: tr("tandem.keplersync_didnt_answer") else null)
			}
		}
	}

	fun test(layers: Layers, done: () -> Unit) = io(layers, tr("tandem.couldnt_test_your_storage"), { api.testOwnStorage() }) { o ->
		forget()
		if (o.problem == null) layers.toast(tr("tandem.your_storage_works"), if (o.scoped == false) tr("tandem.this_token_can_also_reach_your_other_3", o.bucket) else tr("tandem.tandem_can_save_read_and_delete_files", o.bucket))
		else layers.toast(tr("tandem.your_storage_still_needs_fixing"), o.problem, "warn")
		RealWorlds.refresh { }
		done()
	}

	fun remove(layers: Layers, done: () -> Unit) = io(layers, tr("tandem.couldnt_remove_the_keys"), { api.removeOwnStorage() }) {
		forget()
		layers.toast(tr("tandem.keys_removed"), tr("tandem.keplersync_no_longer_has_them_worlds_still"))
		RealWorlds.refresh { }
		done()
	}

	/** Deletes a stopped world's cloud copy now; its space is free at once. */
	fun deleteNow(layers: Layers, cloudId: String, name: String, done: () -> Unit) = io(layers, tr("tandem.couldnt_delete", name), { api.deleteWorld(cloudId) }) {
		forget()
		layers.toast(tr("tandem.is_deleted", name), tr("tandem.its_space_is_free_again"))
		done()
	}

	/** Moves [worlds] to "own" storage or back to "keplersync"; the server copies them while they stay playable. */
	fun move(worlds: List<World>, to: String, layers: Layers, done: () -> Unit) {
		val ids = worlds.filter { it.mine && it.cloudId != null && it.storage != to && it.movingTo != to }.mapNotNull { it.cloudId }
		if (ids.isEmpty()) return done()
		io(layers, tr("tandem.couldnt_move", if (ids.size == 1) worlds.first { it.cloudId == ids[0] }.name else tr("tandem.your_worlds")), { ids.forEach { api.moveStorage(it, to) } }) {
			forget()
			val what = if (ids.size == 1) worlds.first { it.cloudId == ids[0] }.name else tr("tandem.n_worlds", ids.size)
			layers.toast(if (to == "own") tr("tandem.moving_to_your_storage", what) else tr("tandem.moving_back_to_keplersync_storage", what), tr("tandem.friends_can_keep_playing_while_it_moves"), "cloud")
			RealWorlds.refresh { }
			done()
		}
	}
}

/** Stopping, resuming and leaving real synced worlds, and making room when storage is full. */
object RealLeave {
	/** Worlds this PC stopped itself, so the list doesn't tell it what it just did. */
	val handled: MutableSet<String> = ConcurrentHashMap.newKeySet()
	/** Sessions that didn't upload because the storage is full: these worlds show "Saved on this PC only". */
	val full: MutableSet<String> = ConcurrentHashMap.newKeySet()

	/**
	 * A synced world stopped by its owner, noticed in the worlds list: told once, with its copy's age, whether the copy
	 * here has a session it never sent, and whether the latest copy is [fetching] in its place.
	 */
	class Stopped(val owner: String, val world: String, val cloudId: String, val levelId: String, val copyTime: Long?, val behind: Boolean, val unsent: Boolean) {
		var fetching = false
	}

	val stopped = ConcurrentLinkedQueue<Stopped>()
	/** Stopped worlds whose latest copy is coming down; their link here goes once it's in. */
	val fetching: MutableSet<String> = ConcurrentHashMap.newKeySet()

	/** Gets [s]'s latest copy in the background, then lets go of it: the copy here ends where the last session left off. */
	fun fetchLatest(d: WorldSync, s: Stopped) {
		if (!fetching.add(s.cloudId)) return
		d.getLatest(s.levelId, s.cloudId, s.world).future.whenComplete { _, e ->
			d.async { LocalState.drop(WorldSync.folder(s.levelId)) }.whenComplete { _, _ -> fetching -= s.cloudId }
			if (e == null) Ingame.notice(tr("tandem.got_the_latest_copy_of", s.world), tr("tandem.its_a_normal_world_on_this_pc"), "pc")
			else Ingame.notice(tr("tandem.couldnt_get_the_latest_copy_of", s.world), tr("tandem.your_own_copy_is_a_normal_world"), "warn")
		}
	}

	/**
	 * Stop syncing [w]: [server] makes a zip laid out for a dedicated server first; otherwise this PC gets the
	 * latest copy if it's behind. Either way Tandem lets go of the world here. Someone hosting: it stops after them.
	 */
	fun stop(dialogs: Dialogs, w: World, server: Boolean, done: () -> Unit) {
		val layers = dialogs.layers
		val id = w.cloudId!!
		val host = w.who.takeIf { w.state == WorldState.HOSTED || w.state == WorldState.SAVING }
		if (host != null) return io(layers, tr("tandem.couldnt_stop_syncing", w.name), { api.stopSyncing(id, true) }) {
			w.stopWhenFree = true
			layers.toast(tr("tandem.stops_syncing_after_s_session", w.name, host), tr("tandem.everyones_copy_becomes_a_normal_world_then_2"), "clock", ms = 8000)
			done()
		}
		val d = WorldSync.get()!!
		handled += id
		io(layers, tr("tandem.couldnt_stop_syncing", w.name), { api.stopSyncing(id, false) }, { handled -= id }) {
			if (server) {
				val zip = RealHistory.downloads(w.name)
				layers.toast(tr("tandem.downloading", zip.fileName), tr("tandem.to_big_worlds_take_a_while", zip.parent), "up")
				io(layers, tr("tandem.couldnt_download", zip.fileName), {
					d.sync().exportZip(id, w.head ?: d.cloud.world(id).head, "world", zip, Progress())
					w.levelId?.let { LocalState.drop(WorldSync.folder(it)) }
				}) {
					layers.toast(tr("tandem.downloaded", zip.fileName), tr("tandem.in_unzip_it_in_the_servers_folder", zip.parent), ms = 12000)
					RealWorlds.refresh { }
					done()
				}
				return@io
			}
			val level = w.levelId ?: WorldSync.newFolder(w.name)
			val dir = WorldSync.folder(level)
			val behind = w.levelId == null || LocalState.load(dir).base?.id != w.head
			fun normal() = io(layers, tr("tandem.couldnt_finish_stopping", w.name), {
				LocalState.load(dir).also { LocalState.drop(dir) }
			}) { st ->
				layers.toast(tr("tandem.is_a_normal_world_now", w.name), tr("tandem.the_cloud_copy_and_its_history_are_2"), undo = { resume(layers, w, level, st) }, ms = 10000)
				RealWorlds.refresh { }
				done()
			}
			if (!behind) return@io normal()
			val t = d.getLatest(level, id, w.name)
			dialogs.gettingLatest(w, t) { t.progress.cancel() }
			t.future.whenComplete { _, e ->
				Minecraft.getInstance().execute {
					layers.closeDialog()
					// Cancelled or failed: the list tells this PC about it, with Get the latest copy.
					if (e != null) { handled -= id; RealWorlds.refresh { }; done() } else normal()
				}
			}
		}
	}

	/** Undo for Stop syncing: KeplerSync syncs it again, and this PC's copy is linked to it again. */
	private fun resume(layers: Layers, w: World, level: String, st: LocalState) = io(layers, tr("tandem.couldnt_keep_syncing", w.name), {
		api.resumeSyncing(w.cloudId!!)
		st.save(WorldSync.folder(level))
	}) {
		handled -= w.cloudId!!
		layers.toast(tr("tandem.syncing_again", w.name), tr("tandem.nothing_changed_for_anyone"))
		RealWorlds.refresh { }
	}

	/** Calls off a stop waiting for the host, from the world's Settings. */
	fun keepSyncing(layers: Layers, w: World, done: () -> Unit) = io(layers, tr("tandem.couldnt_keep_syncing", w.name), { api.resumeSyncing(w.cloudId!!) }) {
		w.stopWhenFree = false
		layers.toast(tr("tandem.keeps_syncing", w.name), tr("tandem.it_wont_stop_after_this_session"))
		done()
	}

	/** The world list found [s]: its copy here is a normal world now. Get the latest copy fetches where the owner left off. */
	fun latestCopy(dialogs: Dialogs, s: Stopped) {
		val d = WorldSync.get() ?: return
		val w = Sample.worlds.firstOrNull { it.levelId == s.levelId } ?: World(s.world, WorldState.LOCAL, RealWorlds.bands(s.levelId))
		val t = d.getLatest(s.levelId, s.cloudId, s.world)
		dialogs.gettingLatest(w, t) { t.progress.cancel() }
		t.future.whenComplete { _, e ->
			d.async { LocalState.drop(WorldSync.folder(s.levelId)) }
			Minecraft.getInstance().execute {
				dialogs.layers.closeDialog()
				if (e == null) dialogs.layers.toast(tr("tandem.got_the_latest_copy_of", s.world), tr("tandem.its_a_normal_world_on_this_pc_2"))
				else if (e !is java.util.concurrent.CancellationException && e.cause !is java.util.concurrent.CancellationException) dialogs.layers.toast(tr("tandem.couldnt_get_the_latest_copy"), (e.cause ?: e).message ?: tr("tandem.try_again_2"), "warn")
				RealWorlds.refresh { }
			}
		}
	}

	/** Leave a world shared with you: out of it on KeplerSync, then this PC's copy is kept as a normal world or deleted. */
	fun leave(layers: Layers, w: World, delete: Boolean, done: () -> Unit) {
		val me = KsAccount.get()?.me?.id ?: return layers.toast(tr("tandem.couldnt_leave", w.name), tr("tandem.still_signing_in_to_keplersync_try_again"), "warn")
		io(layers, tr("tandem.couldnt_leave", w.name), {
			api.leaveWorld(w.cloudId!!, me)
			// Out of the world now: this PC's copy is a normal one, even if the game has it open and it can't be deleted yet.
			w.levelId?.let {
				LocalState.drop(WorldSync.folder(it))
				if (delete) deleteCopy(it)
			}
		}) {
			Sample.worlds.remove(w)
			layers.toast(tr("tandem.you_left", w.name), if (delete) tr("tandem.your_copy_is_deleted_and_the_others", w.owner) else tr("tandem.your_copy_is_a_normal_world_on"))
			RealWorlds.refresh { }
			done()
		}
	}

	/** Delete a synced world from this PC only: the cloud keeps it. Not while it has a session that hasn't uploaded. */
	fun removeFromPc(layers: Layers, w: World, done: () -> Unit) {
		val level = w.levelId ?: return done()
		io(layers, tr("tandem.couldnt_delete", w.name), {
			if (LocalState.load(WorldSync.folder(level)).pending != null) throw java.io.IOException(tr("tandem.this_pc_has_a_session_of_that", w.name))
			deleteCopy(level)
		}) {
			layers.toast(tr("tandem.is_off_this_pc", w.name), tr("tandem.your_friends_still_have_it_play_gets"))
			RealWorlds.refresh { }
			done()
		}
	}

	private fun deleteCopy(level: String) = WorldSync.deleteWorld(level)

	/** Storage full: the owner trims every world's old history, then this PC's session goes up. */
	fun trim(layers: Layers, w: World, done: () -> Unit) = io(layers, tr("tandem.couldnt_trim_history"), { api.trimHistory() }) { freed ->
		RealStorage.forget()
		if (freed <= 0) return@io layers.toast(tr("tandem.theres_no_old_history_to_trim"), tr("tandem.each_world_already_keeps_only_its_latest"), "warn")
		layers.toast(tr("tandem.trimmed_of_old_history", bytesText(freed)), tr("tandem.saving_now", w.name))
		upload(layers, w, done)
	}

	/** Tries the session this PC kept again. */
	fun upload(layers: Layers, w: World, done: () -> Unit) {
		val d = WorldSync.get() ?: return
		val level = w.levelId ?: return
		w.state = WorldState.FIRST
		val t = d.uploadPending(level, w.cloudId!!, w.name, true)
		w.upload = Moving(t)
		done()
		t.future.whenComplete { _, e ->
			Minecraft.getInstance().execute {
				if (e == null) full -= w.cloudId!! else layers.toast(tr("tandem.is_still_saved_on_this_pc_only", w.name), (e.cause ?: e).message ?: tr("tandem.try_again_2"), "warn")
				RealWorlds.refresh { }
				done()
			}
		}
	}

	/** A finished upload KeplerSync held back for now (uploads paused, or an unusual amount today): the world's name and
	 * KeplerSync's message. The session stays on this PC and goes up at the next launch. */
	fun refusedAtCapacity(t: WorldSync.Transfer): Pair<String, String>? {
		val e = t.future.handle { _, e -> e }.getNow(null) ?: return null
		val refused = generateSequence<Throwable>(e) { it.cause }.filterIsInstance<KeplerSyncApi.Refused>().firstOrNull() ?: return null
		if (refused.code != "at_capacity") return null
		val name = Sample.worlds.firstOrNull { it.levelId == t.world }?.name ?: t.world
		return name to (refused.message ?: "")
	}

	/** A finished upload that KeplerSync refused for lack of space: the world it was, so "storage full" can show. */
	fun refusedForSpace(t: WorldSync.Transfer): World? {
		val e = t.future.handle { _, e -> e }.getNow(null) ?: return null
		val refused = generateSequence<Throwable>(e) { it.cause }.filterIsInstance<KeplerSyncApi.Refused>().firstOrNull() ?: return null
		if (refused.code != "quota_exceeded") return null
		val w = Sample.worlds.firstOrNull { it.levelId == t.world && it.cloudId != null } ?: return null
		full += w.cloudId!!
		w.state = WorldState.PC_ONLY
		return w
	}
}
