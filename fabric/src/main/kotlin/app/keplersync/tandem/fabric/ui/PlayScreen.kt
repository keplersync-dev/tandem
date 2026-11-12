package app.keplersync.tandem.fabric.ui

import app.keplersync.tandem.fabric.account.Account as KsAccount
import app.keplersync.tandem.fabric.sync.WorldSync
import gg.essential.elementa.UIComponent
import gg.essential.elementa.components.UIBlock
import gg.essential.elementa.components.UIContainer
import gg.essential.elementa.constraints.CenterConstraint
import gg.essential.elementa.constraints.SiblingConstraint
import gg.essential.elementa.dsl.basicHeightConstraint
import gg.essential.elementa.dsl.basicWidthConstraint
import gg.essential.elementa.dsl.childOf
import gg.essential.elementa.dsl.constrain
import gg.essential.elementa.dsl.minus
import gg.essential.elementa.dsl.percent
import gg.essential.elementa.dsl.pixels
import app.keplersync.tandem.fabric.TandemClient
import app.keplersync.tandem.fabric.Updates
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen
import gg.essential.universal.UKeyboard
import gg.essential.universal.UMatrixStack
import java.awt.Color
import kotlin.math.max
import kotlin.math.min

/**
 * Tandem's main screen, layout B · Split: worlds on the left, friends on the right. Real data when signed in, the
 * sample's otherwise. Elementa V8 because Tandem's components draw themselves in draw(); later versions render through Elementa's
 * extractor, not tried yet.
 */
class PlayScreen : TandemScreen() {
	private enum class Tab(val label: String) { PLAY(tr("tandem.play")), SYNCED(tr("tandem.synced_worlds")), STORAGE(tr("tandem.storage")), SETTINGS(tr("tandem.settings")) }

	private var tab = Tab.PLAY
	private val layers = Layers()
	private val dialogs = Dialogs(layers, ::build)
	private val pages = Pages(dialogs, ::build) { detail = null; build() }
	private val friendRows = mutableMapOf<String, () -> Unit>()
	/** The world whose details are open, and which of its tabs. */
	private var detail: World? = null
	private var detailTab = "over"

	/** Dev screenshots: things the runner clicks, by name. */
	internal val devTargets = mutableMapOf<String, UIComponent>()

	private val synced = SyncedPage(layers, dialogs, { build() }, ::showDetail, { act(it) }, ::openMenu, devTargets)

	init {
		window.onMouseClick { layers.closeMenu() }
	}

	private var onOpen: ((PlayScreen) -> Unit)? = null

	/** Something to open once the screen is up (a dialog, from an invite). */
	fun afterOpen(run: (PlayScreen) -> Unit) {
		onOpen = run
	}

	private val real = RealPlay(dialogs) { build() }

	override fun initScreen(width: Int, height: Int) = safely {
		super.initScreen(width, height)
		if (RealFriends.on) {
			// Nor the sample's friends in a signed-in run.
			if (Sample.friends.any { it.userId == null } || Sample.requests.any { it.userId == null }) { Sample.friends.clear(); Sample.requests.clear() }
			RealFriends.sync()
		}
		if (RealWorlds.on) {
			// The sample's worlds never show in a dev cloud run, not even for a frame.
			if (Sample.worlds.any { it.levelId == null && it.cloudId == null }) Sample.worlds.clear()
			refresh()
		}
		build()
		onOpen?.let { onOpen = null; it(this) }
	}

	/** Something changed while a dialog or menu was open: rebuilt once it closes, so nothing is missed. */
	private var stale = false
	private var seenUpdate = -1

	private fun rebuildWhenFree() {
		if (layers.busy) stale = true else build()
	}

	/** Reloads the real worlds list, then plays a world opened from Minecraft's own list, if there is one. */
	private fun refresh() = RealWorlds.refresh {
		rebuildWhenFree()
		RealWorlds.pendingLevel?.let { level ->
			// Its button's own action: a world whose link here went (left, or taken out of it) just opens.
			Sample.worlds.firstOrNull { it.levelId == level }?.let { RealWorlds.pendingLevel = null; real.act(it) }
		}
	}

	/** Each list's scroll position, so a rebuild (a friend coming online) leaves it where it was. */
	private val scrolls = mutableMapOf<String, Float>()
	private val made = mutableListOf<Pair<String, Scroller>>()
	private var lastPage = ""

	private fun kept(key: String, parent: UIComponent): UIContainer {
		val sc = Scroller() childOf parent
		scrolls[key]?.let { sc.position = it }
		made += key to sc
		return sc.content
	}

	private fun build() {
		// The same page built again keeps its place; another page starts at the top.
		val page = "$tab|${detail?.name}|$detailTab"
		if (page == lastPage) made.forEach { (k, sc) -> scrolls[k] = sc.position } else scrolls.clear()
		made.clear()
		lastPage = page
		Scale.update()
		Focus.reset()
		window.clearChildren()
		UIBlock(Tok.dimmer).constrain { width = 100.percent; height = 100.percent } childOf window

		val panel = UIContainer().constrain {
			x = CenterConstraint(); y = CenterConstraint(); width = W.at; height = H.at
		} childOf window
		PanelBg(tab == Tab.PLAY && detail == null).constrain { width = 100.percent; height = 100.percent } childOf panel
		topBar(panel)
		val body = { UIContainer().constrain { x = 1.at; y = BODY_TOP.at; width = (W - 2).at; height = (H - BODY_TOP - 1).at } childOf panel }
		val open = detail
		if (open != null) {
			pages.details(kept("page", body()), open, detailTab, { detailTab = it; build() }, ::act)
		} else if (tab == Tab.SYNCED) {
			synced.build(kept("page", body()))
		} else if (tab == Tab.STORAGE) {
			pages.storage(kept("page", body()))
		} else if (tab == Tab.SETTINGS) {
			pages.settings(kept("page", body()))
		} else {
			worlds(UIContainer().constrain {
				x = 1.at; y = BODY_TOP.at; width = (W - 1 - SIDE).at; height = (H - BODY_TOP - 1).at
			} childOf panel)
			friends(UIContainer().constrain {
				x = (W - SIDE + 1).at; y = BODY_TOP.at; width = (SIDE - 2).at; height = (H - BODY_TOP - 1).at
			} childOf panel)
		}

		// Friends coming online, requests arriving: rebuilt as KeplerSync says so.
		if (RealFriends.on) Ticker { if (RealFriends.sync()) rebuildWhenFree() } childOf window
		Ticker { if (stale && !layers.busy) { stale = false; build() } } childOf window
		// Tandem's own update changing state (its progress bar moves by itself).
		Ticker {
			val now = Updates.changes
			if (now != seenUpdate) { seenUpdate = now; rebuildWhenFree() }
		} childOf window
		// Other games' changes: the server says when a world changed (and the list is checked every 10 s for leases
		// that simply ran out); a dev cloud folder is checked every 2 s.
		if (RealWorlds.on) {
			val server = WorldSync.get()?.server == true
			val every = if (server) 10_000 else 2_000
			var next = System.currentTimeMillis() + every
			var seen = KsAccount.get()?.worldsVersion ?: 0
			Ticker {
				val now = KsAccount.get()?.worldsVersion ?: 0
				if (now != seen || System.currentTimeMillis() > next) { seen = now; next = System.currentTimeMillis() + every; refresh() }
			} childOf window
		}
		// A first upload finishing turns the world Ready. A real one with no upload here is someone else's, still going.
		Ticker {
			val done = Sample.worlds.filter { it.state == WorldState.FIRST && it.upload.let { u -> if (u == null) it.cloudId == null else u.value >= 1f } }
			if (done.isNotEmpty()) {
				done.forEach { it.state = WorldState.READY; it.who = "you"; it.ago = tr("tandem.just_now"); it.upload = null }
				build()
			}
		} childOf window
		layers.attach(window)
	}

	private fun topBar(panel: UIComponent) {
		val bar = UIContainer().constrain { x = 1.at; y = 1.at; width = 100.percent - 2.at; height = 60.at } childOf panel
		val brand = row(10, Icon("logo", 22, Tok.violet), Label(tr("tandem.tandem"), 17, Weight.BOLD)) childOf bar
		brand.constrain { x = 22.at; y = CenterConstraint() }
		val tabs = UIContainer().constrain {
			x = SiblingConstraint(20.tp); y = CenterConstraint()
			width = basicWidthConstraint { c -> c.children.sumOf { it.getWidth().toDouble() }.toFloat() + 4.tp * (c.children.size - 1) }
			height = 38.at
		} childOf bar
		for (t in Tab.entries) {
			val on = t == tab && detail == null
			val b = box(10, if (on) Tok.raise2 else Tok.rail)
			val l = Label(t.label, 14, Weight.SEMIBOLD, if (on) Tok.ink else Tok.muted) childOf b
			l.constrain { x = 14.at; y = CenterConstraint() }
			b.constrain { x = SiblingConstraint(4.tp); width = basicWidthConstraint { l.getWidth() + 28.tp }; height = 100.percent }
			val select = { if (tab != t || detail != null) { tab = t; detail = null; build() } }
			b.onMouseEnter { l.tint = Tok.ink }
			b.onMouseLeave { l.tint = if (on) Tok.ink else Tok.muted }
			b.onMouseClick { if (it.mouseButton == 0) select() }
			Focus.add(b, b.corner, select)
			b childOf tabs
		}
		IconButton("close", 40, Tok.rail) { restorePreviousScreen() }.constrain { x = 14.fromEnd; y = CenterConstraint() } childOf bar
	}

	private fun worlds(area: UIComponent) {
		val list = kept("worlds", area)
		devTargets["worlds"] = area
		// A synced world that can't open in this game and isn't in its saves belongs to another pack: a friend hosting it
		// is joined from the friends list. Sample worlds (no cloud id) keep their rows.
		val shown = Sample.worlds.filter { it.state != WorldState.OTHER || it.levelId != null || it.cloudId == null }
		val (mine, other) = shown.partition { it.state != WorldState.OTHER }
		val banners = listOfNotNull(uploading(), update(), if (!Sample.reachable) unreachable() else null)
		banners.forEachIndexed { i, b -> b.constrain { x = 24.at; y = if (i == 0) 22.at else SiblingConstraint(12.tp) } childOf list }
		heading(tr("tandem.worlds"), tr("tandem.pick_one_to_play_invite_friends_from")).constrain {
			x = 24.at; y = if (banners.isEmpty()) 22.at else SiblingConstraint(12.tp)
		} childOf list
		if (shown.isEmpty()) emptyRow() childOf list
		for ((i, w) in mine.withIndex()) worldRow(w, if (i == 0) 12 else 8) childOf list
		if (other.isNotEmpty()) {
			Label(tr("tandem.different_mods_or_minecraft_version"), 14, Weight.BOLD, Tok.sec).constrain { x = 24.at; y = SiblingConstraint(24.tp) } childOf list
			for ((i, w) in other.withIndex()) worldRow(w, if (i == 0) 12 else 8) childOf list
		}
		UIContainer().constrain { y = SiblingConstraint(); height = 22.at } childOf list
	}

	/** A 17 px title with a muted note, on one baseline. */
	private fun heading(title: String, note: String): UIComponent {
		val t = Label(title, 17, Weight.BOLD)
		val n = Label(note, 13, Weight.REGULAR, Tok.muted)
		val c = UIContainer()
		t.constrain { y = 0.at } childOf c
		// Baselines meet when the 13 px line sits on the 17 px line's baseline.
		n.constrain { x = SiblingConstraint(12.tp); y = ((17 - 13) * ASCENT_EM).at } childOf c
		c.constrain {
			width = basicWidthConstraint { t.getWidth() + 12.tp + n.getWidth() }
			height = basicHeightConstraint { t.getHeight() }
		}
		return c
	}

	/** No world to show: a dashed row the size of a world row, with Create a world. */
	private fun emptyRow(): UIComponent {
		val row = Dashed(12).constrain { x = 24.at; y = SiblingConstraint(12.tp); width = 100.percent - 48.at; height = ROW_H.at }
		val thumb = box(7, Tok.raise, Tok.line).constrain { x = 11.at; y = CenterConstraint(); width = 56.at; height = 42.at } childOf row
		Icon("plus", 18, Tok.muted).constrain { x = CenterConstraint(); y = CenterConstraint() } childOf thumb
		val create = Button(tr("tandem.create_a_world"), Kind.FILLED, Size.NORMAL, 148, Tok.panel) {
			val mc = Minecraft.getInstance()
			CreateWorldScreen.openFresh(mc, mc.screen)
		}.constrain { x = 14.fromEnd; y = CenterConstraint() } childOf row
		devTargets["create-world"] = create
		val info = Column(3).constrain { x = 81.at; y = CenterConstraint(); width = 100.percent - (81 + 14 + 148 + 14).at } childOf row
		info.add(Label(tr("tandem.no_worlds_in_this_game_yet"), 15, Weight.SEMIBOLD).fitWithin(info))
		info.add(Label(tr("tandem.create_one_or_join_a_friend_when"), 13, Weight.REGULAR, Tok.muted).fitWithin(info))
		return row
	}

	private fun worldRow(w: World, gap: Int): UIComponent {
		val need = w.state == WorldState.VERSIONS || w.state == WorldState.PC_ONLY
		val synced = w.state != WorldState.LOCAL && w.state != WorldState.OTHER
		val row = box(12, Tok.raise, if (need) Tok.alpha(Tok.warn, 0.55) else Tok.line).constrain {
			x = 24.at; y = SiblingConstraint(gap.tp); width = 100.percent - 48.at; height = ROW_H.at
		}
		Thumb(w.bands, 56, 42, 7, w.state == WorldState.OTHER, Tok.raise, w.icon).constrain { x = 11.at; y = CenterConstraint() } childOf row

		val info = UIContainer().constrain {
			x = 81.at; y = CenterConstraint(); width = 100.percent - (81 + 11 + 40 + 14 + 148 + 14).at
			height = basicHeightConstraint { c -> c.children.sumOf { it.getHeight().toDouble() }.toFloat() + 3.tp }
		} childOf row
		val name = Label(w.name, 15, Weight.SEMIBOLD).fitWithin(info) childOf info
		if (synced) {
			val open = { showDetail(w) }
			name.onMouseEnter { name.underline = true }
			name.onMouseLeave { name.underline = false }
			name.onMouseClick { it.stopPropagation(); open() }
			Focus.add(name, 4.tp, open)
		}
		statusLine(w, info).constrain { y = SiblingConstraint(3.tp); height = 18.at } childOf info
		if (w.state == WorldState.FIRST && w.upload != null) Progress(false, 4) { w.upload?.value ?: 0f }.constrain { y = SiblingConstraint(5.tp); width = 180.at } childOf info

		val (label, kind) = actionFor(w)
		devTargets["button-${w.name}"] = Button(label, kind, Size.NORMAL, 148, Tok.raise) { act(w) }
			// A first upload that isn't running (KeplerSync refused it) opens here; one under way, or a friend's, doesn't.
			.also { it.enabled = (Sample.reachable || !synced) && !(w.cloudId != null && w.state == WorldState.FIRST && (w.upload != null || w.levelId == null)) }
			.constrain { x = 65.fromEnd; y = CenterConstraint() } childOf row
		IconButton("more", 40, Tok.raise) { openMenu(w, row) }.constrain { x = 11.fromEnd; y = CenterConstraint() } childOf row
		return row
	}

	/** A world's … menu, under its [row]. */
	private fun openMenu(w: World, row: UIComponent) {
		val items = menuFor(w)
		// min-width 200 inside 6 px padding and a 1 px border; 8 px in from the row's right edge.
		val width = min(max(200.tp, items.maxOf { textWidth(it.title, 14, Weight.MEDIUM) } + 24.tp), 360.tp) + 14.tp
		layers.openMenu(row.getRight() - 8.tp - width, row.getBottom() + 4.tp, width, items, flipAbove = row.getTop() - 4.tp, owner = "world:${w.name}")
	}

	/** What a world's button does with the sample data. Your mods never match Sam's, so joining shows why. */
	private fun act(w: World) = if (real.act(w)) Unit else when (w.state) {
		WorldState.VERSIONS -> dialogs.versions(w)
		WorldState.HOSTED, WorldState.OTHER -> dialogs.mods(w)
		WorldState.READY -> dialogs.getLatest(w)
		WorldState.SAVING -> dialogs.waitFor(w)
		WorldState.PC_ONLY -> dialogs.full(w)
		// Sample worlds aren't on disk; a dev cloud run opens real ones (RealPlay).
		WorldState.LOCAL, WorldState.FIRST -> {}
	}

	private fun menuFor(w: World) = when (w.state) {
		WorldState.LOCAL -> listOf(MenuItem(tr("tandem.sync_this_world")) { syncWorld(w) })
		// A synced world from another game: its details, history and members work wherever it plays.
		WorldState.OTHER -> if (w.owner == null) listOf(MenuItem(tr("tandem.download_as_zip")) { pages.zip(w) })
			else listOf(MenuItem(tr("tandem.details")) { showDetail(w) }, MenuItem(tr("tandem.download_as_zip")) { pages.zip(w) }) + leaveItems(w)
		else -> listOf(MenuItem(tr("tandem.details")) { showDetail(w) }, MenuItem(tr("tandem.download_as_zip")) { pages.zip(w) }) + leaveItems(w)
	}

	/** Leaving a real synced world from its row, as in its Settings. */
	private fun leaveItems(w: World): List<MenuItem> = when {
		w.cloudId == null || w.state == WorldState.FIRST -> emptyList()
		w.mine && w.stopWhenFree -> listOf(MenuItem(tr("tandem.keep_syncing")) { RealLeave.keepSyncing(layers, w) { build() } })
		w.mine -> listOf(MenuItem(tr("tandem.stop_syncing")) { dialogs.stopSyncing(w) { refresh() } })
		else -> listOfNotNull(
			w.levelId?.let { MenuItem(tr("tandem.remove_from_this_pc")) { dialogs.removeFromPc(w) { refresh() } } },
			MenuItem(tr("tandem.leave_world")) { dialogs.leaveWorld(w) { refresh() } },
		)
	}

	private fun showDetail(w: World) {
		detail = w; detailTab = "over"; build()
	}

	/** Sync this world from the Play list: friends who played there are ticked (Jules, in the sample). */
	private fun syncWorld(w: World) {
		dialogs.sync(w.name, w.size, if (RealWorlds.on) emptyList() else listOf(tr("tandem.jules")), false, emptyList(), w.levelId?.let { WorldSync.folder(it) }) { picked ->
			if (RealWorlds.on) return@sync real.sync(w, picked)
			w.owner = "You"; w.shared.clear(); w.shared += "You"; w.shared += picked
			w.state = WorldState.FIRST; w.upload = Timed(0f, 14f)
			build()
		}
	}

	/** A banner: an icon, a line or two, and maybe one link on the right. */
	private fun banner(icon: String, tint: Color, text: String, action: String? = null, bar: (() -> Float)? = null, press: () -> Unit = {}): UIComponent {
		val b = box(12, Tok.over(Tok.panel, tint, 0.07), Tok.alpha(tint, 0.45)).constrain { width = 100.percent - 48.at }
		Icon(icon, 18, tint).constrain { x = 15.at; y = CenterConstraint() } childOf b
		// In GUI units: a link's own width can't be turned back into Tandem pixels exactly in a small window.
		var room = (W - 1 - SIDE - 10 - 48 - 2 - 30 - 18 - 10).tp
		if (action != null) {
			val l = link(action, press = press).constrain { x = 15.fromEnd; y = CenterConstraint() } childOf b
			room -= l.getWidth() + 15.tp
		}
		val t = wrapped(text, 13, Weight.REGULAR, Tok.sec, room, 1.45f)
			.constrain { x = (15 + 18 + 10).at; y = if (bar == null) CenterConstraint() else 13.at } childOf b
		// Drawn from the download's progress every frame, so it moves without rebuilding the screen.
		if (bar != null) Progress(false, 6, bar).constrain { x = (15 + 18 + 10).at; y = SiblingConstraint(8.tp); width = room.pixels } childOf b
		b.constrain { height = basicHeightConstraint { t.getHeight() + (if (bar == null) 26 else 40).tp } }
		return b
	}

	/** A session uploading after you left: one line above Worlds, its bar along the bottom edge, gone once it's done. */
	private fun uploading(): UIComponent? {
		val (world, to, run) = Sample.background ?: return null
		val b = object : Box(10.tp, Tok.live, Tok.alpha(Tok.violet, 0.5)) {
			override fun draw(matrixStack: UMatrixStack) {
				super.draw(matrixStack)
				val l = getLeft() + 10.tp; val r = getRight() - 10.tp; val y = getBottom() - 4.tp
				rounded(matrixStack, l, y, l + (r - l) * run.value.coerceIn(0f, 1f), y + 3.tp, 1.5f.tp, Tok.violet)
			}
		}.constrain { width = 100.percent - 48.at; height = 44.at }
		val friend = to?.let { n -> Sample.friends.firstOrNull { it.name == n } }
		val lead = if (friend != null) Face({ friend.skin }, 24, 6, null, Tok.live) else Icon("up", 18, Tok.violet)
		val keep = Label(tr("tandem.keep_minecraft_open"), 12.5, Weight.REGULAR, Tok.sec).constrain { x = 14.fromEnd; y = CenterConstraint() } childOf b
		val title = Label(if (to != null) tr("tandem.handing_off_to", world, to) else tr("tandem.saving_to_the_cloud", world), 13.5, Weight.SEMIBOLD)
		val state = Live(13, Weight.REGULAR, Tok.muted) { uploadedText(run) }
		row(10, lead, title, state).constrain { x = 12.at; y = CenterConstraint() } childOf b
		// A long world name ends in an ellipsis before the progress and Keep Minecraft open.
		title.room = { keep.getLeft() - 16.tp - state.getWidth() - 10.tp - title.getLeft() }
		Ticker { if (Sample.background == null) build() } childOf b
		return b
	}

	/** KeplerSync is unreachable, and what still works. */
	private fun unreachable(): UIComponent {
		val why = when {
			RealWorlds.outdated -> tr("tandem.this_version_of_tandem_is_too_old_for")
			RealWorlds.notConfirmed -> tr("tandem.mojang_couldnt_confirm_your_minecraft_account_so")
			else -> tr("tandem.keplersync_is_unreachable_so_synced_worlds_cant")
		}
		return banner("wifioff", Tok.warn, tr("tandem.your_other_worlds_still_work_your_copies", why),
			if (RealWorlds.outdated) tr("tandem.get_the_update") else null, press = ::getUpdate)
	}

	/** Tandem's own update: out, on its way, ready for when Minecraft closes, or why it couldn't come. */
	private fun update(): UIComponent? {
		val v = Updates.version ?: return null
		return when (Updates.state) {
			Updates.State.AVAILABLE ->
				banner("up", Tok.violet, tr("tandem.tandem_s_is_out_2", v), tr(if (Updates.canInstall()) "tandem.update" else "tandem.get_it"), press = ::getUpdate)
			Updates.State.GETTING -> banner("up", Tok.violet, tr("tandem.getting_tandem_s", v), bar = { Updates.progress.fraction() })
			Updates.State.READY -> banner("check", Tok.ok, tr("tandem.tandem_s_goes_in_when_you_close", v),
				if (Minecraft.getInstance().level == null) tr("tandem.quit_minecraft") else null) { Minecraft.getInstance().stop() }
			Updates.State.FAILED -> banner("warn", Tok.warn, tr("tandem.couldnt_update_tandem_s", Updates.problem), tr("tandem.try_again"), press = ::getUpdate)
			else -> null
		}
	}

	/** Tandem updates itself when it can; otherwise the player gets where to download it. */
	private fun getUpdate() {
		TandemClient.LOG.info("Tandem update: the player pressed it for {} (state {})", Updates.version, Updates.state.name)
		// shortcut: the beta's jars are posted in KeplerSync's Discord; point this at Modrinth once Tandem is published there.
		if (!Updates.install()) openLink("https://discord.gg/mXJnFe65Eq")
		build()
	}

	private fun friends(area: UIComponent) {
		val col = kept("friends", area)
		val online = Sample.friends.filter { it.online && !it.atlasOnly }
		val atlas = Sample.friends.filter { it.online && it.atlasOnly }
		val offline = Sample.friends.filter { !it.online }

		val head = UIContainer().constrain { x = 20.at; y = 22.at; width = 100.percent - 40.at; height = 34.at } childOf col
		val problem = if (!Sample.reachable) tr("tandem.friends_show_up_when_keplersync_is_reachable") else RealFriends.problem
		if (problem != null) {
			Label(tr("tandem.friends"), 17, Weight.BOLD).constrain { y = CenterConstraint() } childOf head
			wrapped(problem, 13, Weight.REGULAR, Tok.muted, 280.tp, 1.5f).constrain { x = 20.at; y = SiblingConstraint(16.tp) } childOf col
			return
		}
		row(12, Label(tr("tandem.friends"), 17, Weight.BOLD), Label(tr("tandem.n_online", online.size), 13, Weight.REGULAR, Tok.muted))
			.constrain { y = CenterConstraint() } childOf head
		IconButton("plus", 34, Tok.rail) { dialogs.addFriend() }.also { devTargets["add-friend"] = it }.constrain { x = 0.fromEnd; y = CenterConstraint() } childOf head

		for (r in Sample.requests) request(r) childOf col
		var gap = 16
		for (f in online) { friendRow(f, gap) childOf col; gap = 2 }
		if (atlas.isNotEmpty()) {
			Label(tr("tandem.in_atlas_2", atlas.size), 13, Weight.SEMIBOLD, Tok.sec).constrain { x = 20.at; y = SiblingConstraint(22.tp) } childOf col
			gap = 16
			for (f in atlas) { friendRow(f, gap) childOf col; gap = 2 }
		}
		if (offline.isNotEmpty()) {
			Label(tr("tandem.offline_2", offline.size), 13, Weight.SEMIBOLD, Tok.sec).constrain { x = 20.at; y = SiblingConstraint(22.tp) } childOf col
			gap = 16
			for (f in offline) { friendRow(f, gap) childOf col; gap = 2 }
		}
		UIContainer().constrain { y = SiblingConstraint(); height = 22.at } childOf col
	}

	private fun request(p: Person): UIComponent {
		val fill = Tok.over(Tok.rail, Tok.warn, 0.06)
		val card = box(12, fill, Tok.alpha(Tok.warn, 0.55)).constrain {
			x = 20.at; y = SiblingConstraint(16.tp); width = 100.percent - 40.at
		}
		// Contents sit inside the 1 px border and 12 px padding.
		Face({ p.skin }, 36, 8, null, fill).constrain { x = 13.at; y = CenterConstraint() } childOf card
		// Accept before ×, so Tab meets them in reading order.
		val accept = Button(tr("tandem.accept"), Kind.OUTLINE, Size.SMALL, null, fill) {
			if (p.userId != null) return@Button RealFriends.accept(layers, ::build, p)
			Sample.requests.remove(p)
			Sample.friends.add(Sample.friends.indexOfFirst { !it.online }.coerceAtLeast(0), Person(p.name, 14, true, tr("tandem.in_the_menu"), bars = 2))
			build()
			layers.toast(tr("tandem.you_and_are_friends", p.name), tr("tandem.invite_from_the_pause_menu", p.name))
		}.also { devTargets["accept-${p.name}"] = it }.constrain { x = (13 + 34 + 10).fromEnd; y = CenterConstraint() } childOf card
		IconButton("close", 34, fill) { if (p.userId != null) RealFriends.decline(layers, ::build, p) else { Sample.requests.remove(p); build() } }.constrain { x = 13.fromEnd; y = CenterConstraint() } childOf card
		// This line could wrap; it gets what's left of the 289 px card after border, padding, face, buttons and gaps.
		val room = (289 - 2 - 24 - 36 - 10 - 10 - 10 - 34).tp - accept.getWidth()
		val text = twoLines(p.name, wrapped(p.presence, 13, Weight.REGULAR, Tok.muted, room), p.handle) { it.fitBefore(accept, 10) }
			.constrain { x = (13 + 36 + 10).at; y = CenterConstraint() } childOf card
		card.constrain { height = basicHeightConstraint { max(36.tp, text.getHeight()) + 26.tp } }
		return card
	}

	private fun friendRow(p: Person, gap: Int): UIComponent {
		val row = box(12, Tok.rail).constrain {
			x = 20.at; y = SiblingConstraint(gap.tp); width = 100.percent - 40.at; height = 56.at
		}
		val face = Face({ p.skin }, 36, 8, if (p.atlasOnly) null else p.online, Tok.rail).constrain { x = 11.at; y = CenterConstraint() } childOf row
		val meta = if (p.hosting) Label(p.presence, 13, Weight.MEDIUM, Tok.violetHi) else Label(p.presence, 13, Weight.REGULAR, Tok.muted)
		twoLines(p.name, meta, p.handle) { it.fitWithin(row, 11 + 16 + 10) }.constrain { x = (11 + 36 + 12).at; y = CenterConstraint() } childOf row
		Icon("chev", 16, Tok.muted).constrain { x = 11.fromEnd; y = CenterConstraint() } childOf row
		row.onMouseEnter { row.fill = Tok.raise; row.border = Tok.line; face.under = Tok.raise }
		row.onMouseLeave { row.fill = Tok.rail; row.border = null; face.under = Tok.rail }
		val open = { friendMenu(p, row) }
		friendRows[p.name] = open
		devTargets["friend-${p.name}"] = row
		row.onMouseClick { it.stopPropagation(); open() }
		Focus.add(row, row.corner, open)
		return row
	}

	/** The friend menu: Join their world when there is one, Report…, Remove friend, Block. */
	private fun friendMenu(p: Person, row: UIComponent) {
		// The world they host themselves: by account for real friends, by name in the sample.
		val world = Sample.worlds.firstOrNull { it.state == WorldState.HOSTED && if (p.userId != null) it.hostId == p.userId else it.who == p.name }
		val items = mutableListOf<MenuItem>()
		val invite = p.userId?.let { id -> KsAccount.get()?.invites?.firstOrNull { it.host.id == id } }
		if (invite != null) {
			items += MenuItem(tr("tandem.join_s_world", p.name), "host", invite.worldName) {
				RealJoin.join(invite.sessionId, p.name, invite.worldName, dialogs)
			}
		} else if (p.online && world != null) {
			val small = if (world.who == p.name) world.name else tr("tandem.with", world.name, p.name)
			items += MenuItem(tr("tandem.join_s_world", world.who), "host", small) { if (!real.act(world)) dialogs.mods(world) }
		}
		items += MenuItem(tr("tandem.report_2"), "warn") { dialogs.report(p) }
		items += MenuItem(tr("tandem.remove_friend"), "close") {
			unfriend(p, false, tr("tandem.removed", p.name), tr("tandem.isnt_told_worlds_you_share_stay_shared", p.name))
		}
		items += MenuItem(tr("tandem.block"), "lock", tr("tandem.they_cant_see_invite_or_add_you")) {
			unfriend(p, true, tr("tandem.blocked_2", p.name), tr("tandem.unblock_any_time_in_settings"))
		}
		val note = when {
			items.size == 4 -> null
			p.online -> tr("tandem.isnt_in_a_world_you_can_join", p.name)
			else -> tr("tandem.is_offline", p.name)
		}
		layers.openMenu(row.getLeft() + 8.tp, row.getBottom() + 2.tp, row.getWidth() - 16.tp, items, note, row.getTop() - 2.tp, owner = "friend:${p.name}")
	}

	private fun unfriend(p: Person, blocking: Boolean, title: String, detail: String) {
		if (p.userId != null) return if (blocking) RealFriends.block(layers, ::build, p) else RealFriends.remove(layers, ::build, p)
		val at = Sample.friends.indexOf(p)
		Sample.friends.remove(p)
		if (blocking) Sample.blocked += p
		build()
		layers.toast(title, detail, undo = { Sample.friends.add(at, p); Sample.blocked.remove(p); build() })
	}

	/** A name over a second line. */
	/** A name over a line about them; [fit] says where both end in an ellipsis. */
	/** A name over a line about them. [handle] is their @username, faint after the name: it gives way first. */
	private fun twoLines(name: String, meta: UIComponent, handle: String? = null, fit: ((Label) -> Unit)? = null): UIComponent {
		val c = UIContainer()
		val n = Label(name, 15, Weight.SEMIBOLD)
		val h = handle?.let { Label("@$it", 13, Weight.REGULAR, Tok.muted) }
		val top = (if (h != null) row(6, n, h) else n) childOf c
		meta.constrain { y = SiblingConstraint(3.tp) } childOf c
		if (fit != null) {
			fit(n)
			h?.let(fit)
			(meta as? Label)?.let(fit)
		}
		c.constrain {
			width = basicWidthConstraint { max(top.getWidth(), meta.getWidth()) }
			height = basicHeightConstraint { top.getHeight() + 3.tp + meta.getHeight() }
		}
		return c
	}

	/** Dev checks: whether a dialog or menu is open. */
	internal fun devBusy() = layers.busy

	internal fun devDialogs() = dialogs

	/** A sample friend by name, even when the list is empty (KeplerSync unreachable). */
	private fun someone(name: String, skin: Int) = Sample.friends.firstOrNull { it.name == name } ?: Person(name, skin, true, "")

	/** A synced world Minecraft couldn't load: offer the version before it. */
	fun wontLoad(w: World, level: String) = real.wontLoad(w, level)

	/** A synced world's host handed it over: take it over (it was handed to this player) or follow whoever got it. */
	fun handoff(worldId: String, from: String, to: Pair<String, String>?) {
		if (to == null) real.takeOver(worldId, from) else real.follow(worldId, from, to.first, to.second)
	}

	/** A friend asked this player to host a synced world: Play it. */
	fun hostFor(worldId: String) = real.hostFor(worldId)

	/** Thrown out of a friend's synced world: if KeplerSync says their game stopped, offer to take over (B2). */
	fun hostStopped(worldId: String, hostId: String, hostName: String, others: List<String>) = real.hostStopped(worldId, hostId, hostName, others)

	/** Opens on Settings, as from Mod Menu's Settings button. */
	fun showSettings() {
		tab = Tab.SETTINGS; detail = null; build()
	}

	/** Dev screenshots: open one of the dialogs or menus by name. */
	internal fun dev(what: String) {
		fun world(name: String) = Sample.worlds.first { it.name == name }
		if (what.startsWith("sync-level:")) {
			val level = what.substringAfter(':')
			return dialogs.sync(level, "", emptyList(), false, emptyList(), WorldSync.folder(level)) { }
		}
		when (what) {
			"versions" -> dialogs.versions(world(tr("tandem.old_survival")))
			"get" -> dialogs.getLatest(world(tr("tandem.sky_base")))
			"wait" -> dialogs.waitFor(world(tr("tandem.hardcore_attempt_4")))
			"mods" -> dialogs.mods(world(tr("tandem.create_nights")))
			"mods-big" -> dialogs.mods(world(tr("tandem.cobblemon_island")))
			"mods-version" -> dialogs.mods(world(tr("tandem.skyblock_2023")))
			"storage" -> { tab = Tab.STORAGE; build() }
			"settings" -> { tab = Tab.SETTINGS; build() }
			"synced" -> { SyncedPage.folded.clear(); SyncedPage.query = ""; SyncedPage.whose = 0; tab = Tab.SYNCED; build() }
			"synced-many" -> { Sample.many(true); SyncedPage.folded.clear(); SyncedPage.query = ""; SyncedPage.whose = 0; tab = Tab.SYNCED; build() }
			"synced-many-off" -> { Sample.many(false); build() }
			"synced-folded" -> { tab = Tab.SYNCED; build(); SyncedPage.folded.clear(); SyncedPage.folded += Sample.foldedForShots; build() }
			"how-differs" -> { tab = Tab.SYNCED; build(); synced.howToPlay(world("Skyblock")) }
			"how-version" -> { tab = Tab.SYNCED; build(); synced.howToPlay(world("Moon base")) }
			"how-update" -> { tab = Tab.SYNCED; build(); synced.howToPlay(world("Sky base 2")) }
			"how-get" -> { tab = Tab.SYNCED; build(); synced.howToPlay(world("Skyblock 2023")) }
			"how-own" -> { tab = Tab.SYNCED; build(); synced.howToPlay(world("Snowy base")) }
			"how-own-list" -> { tab = Tab.SYNCED; build(); synced.howToPlayList(world("Snowy base")) }
			"how-differs-list" -> { tab = Tab.SYNCED; build(); synced.howToPlayList(world("Skyblock")) }
			"sync" -> syncWorld(world(tr("tandem.survival_2024")))
			"plans" -> { tab = Tab.STORAGE; build(); dialogs.plans() }
			"r2" -> { tab = Tab.STORAGE; build(); dialogs.ownStorage() }
			"report" -> dialogs.report(Sample.person(tr("tandem.sam")))
			"code" -> { tab = Tab.SETTINGS; build(); dialogs.newCode() }
			"add" -> dialogs.addFriend()
			"full" -> dialogs.full(world(tr("tandem.sky_base")))
			"stop" -> dialogs.stopSyncing(world(tr("tandem.sky_base")))
			"remove" -> dialogs.removeFromPc(world(tr("tandem.old_survival"))) {}
			"slow" -> { Sample.slow = true; dialogs.getLatest(world(tr("tandem.sky_base"))); Sample.slow = false }
			"disk" -> { Sample.diskFull = true; dialogs.getLatest(world(tr("tandem.sky_base"))); Sample.diskFull = false }
			"waitquit" -> { Sample.alexQuit = true; dialogs.waitFor(world(tr("tandem.hardcore_attempt_4"))); Sample.alexQuit = false }
			"handed" -> dialogs.handedToYou(tr("tandem.sam"), tr("tandem.create_nights"), {}, {})
			"stopped" -> dialogs.stopped(someone(tr("tandem.sam"), 0), tr("tandem.create_nights"), listOf(tr("tandem.noor")), tr("tandem.22_min_ago"), {}, {})
			"freeing" -> dialogs.freeing(tr("tandem.sam"), tr("tandem.create_nights"), { tr("tandem.free_in_about_s", 42) }) {}
			"asking" -> dialogs.askedToHost(someone(tr("tandem.sam"), 0), tr("tandem.sky_base"), { tr("tandem.waiting_for_to_open_it", tr("tandem.sam")) }) {}
			"askedyou" -> dialogs.askedYouToHost(someone("Alex", 4), tr("tandem.sky_base"), {}, {})
			"unreachable" -> { Sample.reachable = false; build(); Sample.reachable = true }
			// Real mod ids, so their icons come from the mods installed here and from Modrinth.
			"mods-real" -> dialogs.mods(World("big", WorldState.OTHER, world(tr("tandem.sky_base")).bands, mods = Mods(
				"Haloxx",
				listOf(listOf("create", "6.0.6"), listOf("cobblemon", "1.6.1"), listOf("terralith", "2.5.8"), listOf("supplementaries", "3.1.3"), listOf("haloxx_private_pack", "1.0")),
				listOf(listOf("fabric-api", "0.116.17+1.21.1", "0.116.20+1.21.1")),
				emptyList(),
			)))
			"toast-short" -> layers.toast(tr("tandem.copied_support_keplersync_app"))
			"toast-names" -> layers.toast(tr("tandem.is_online", "李小龙 🔥"), "Мой мир 世界 🌍✨ · محمد", "person")
			"toast-long" -> layers.toast(tr("tandem.stops_syncing_after_s_session", "Hardcore Attempt 4", "xX_EnderWalker_Xx"), tr("tandem.everyones_copy_becomes_a_normal_world_then_2"), "clock")
			"storage-stopped" -> {
				Pages.devStorage = app.keplersync.tandem.core.KeplerSyncApi.StorageOverview().apply {
					usedBytes = 639L shl 20; totalBytes = 2L shl 30
					fun add(n: String, mb: Long, days: Long?) = worlds.add(app.keplersync.tandem.core.KeplerSyncApi.WorldSpace().also {
						it.id = "dev-$n-$mb"; it.name = n; it.storage = "keplersync"; it.bytes = mb shl 20
						it.deletedAt = days?.let { d -> java.time.Instant.now().plus(java.time.Duration.ofDays(d)).minusSeconds(60).toString() }
					})
					add("New World", 20, null); add("asdasd", 98, 29); add("test2", 63, 12); add("test3", 240, 1)
				}
				tab = Tab.STORAGE; build()
			}
			"rebuild" -> build()
			else -> when {
				what.startsWith("friend-") -> friendRows[what.removePrefix("friend-")]?.invoke()
				what.startsWith("details-") -> {
					detail = world(what.substringAfter("details-").substringBeforeLast("/")); detailTab = what.substringAfterLast("/"); build()
				}
			}
		}
	}

	override fun onKeyPressed(keyCode: Int, typedChar: Char, modifiers: UKeyboard.Modifiers?) = safely {
		val typing = window.focusedComponent is Field
		when {
			// The Open Tandem key closes Tandem again.
			!typing && keyCode != 0 && TandemClient.OPEN.matches(keyCode, 0) -> restorePreviousScreen()
			keyCode == UKeyboard.KEY_TAB -> { window.unfocus(); Focus.move(modifiers?.isShift == true) }
			keyCode == UKeyboard.KEY_ESCAPE -> if (!layers.escape()) { window.unfocus(); super.onKeyPressed(keyCode, typedChar, modifiers) }
			!typing && (keyCode == UKeyboard.KEY_ENTER || keyCode == UKeyboard.KEY_SPACE) -> if (!Focus.press()) super.onKeyPressed(keyCode, typedChar, modifiers)
			else -> super.onKeyPressed(keyCode, typedChar, modifiers)
		}
	}

	override fun onMouseClicked(mouseX: Double, mouseY: Double, mouseButton: Int) = safely {
		Focus.hide()
		super.onMouseClicked(mouseX, mouseY, mouseButton)
	}

	/**
	 * The panel's surfaces in one shape: a 1 px border, the rail colour for the top bar and friends column, and
	 * the panel colour for the worlds list with only its bottom-left corner rounded. With [split], Play's two columns;
	 * otherwise (Storage, Settings, a world's details) one panel-coloured body.
	 */
	private class PanelBg(private val split: Boolean) : UIComponent() {
		override fun draw(matrixStack: UMatrixStack) {
			beforeDraw(matrixStack)
			val l = getLeft(); val t = getTop(); val r = getRight(); val b = getBottom()
			val one = 1.tp
			val top = t + BODY_TOP.tp
			val split = if (this.split) r - SIDE.tp else r - one - 17.tp - one
			val rad = 17.tp
			rounded(matrixStack, l, t, r, b, 18.tp, Tok.line)
			rounded(matrixStack, l + one, t + one, r - one, b - one, rad, Tok.rail)
			if (!this.split) {
				rounded(matrixStack, l + one, top, r - one, b - one, rad, Tok.panel)
				rounded(matrixStack, l + one, top, r - one, top + rad, 0f, Tok.panel)
				rounded(matrixStack, l + one, top - one, r - one, top, 0f, Tok.line)
				super.draw(matrixStack)
				return
			}
			rounded(matrixStack, l + one, top, split + rad + one, b - one, rad, Tok.panel)
			rounded(matrixStack, l + one, top, l + one + rad, top + rad, 0f, Tok.panel)
			rounded(matrixStack, split, top, split + rad + one, b - one, 0f, Tok.rail)
			rounded(matrixStack, l + one, top - one, r - one, top, 0f, Tok.line)
			rounded(matrixStack, split, top, split + one, b - one, 0f, Tok.line)
			super.draw(matrixStack)
		}
	}

	private companion object {
		/** A 1040 × 600 panel plus its 1 px border. */
		const val W = 1042
		const val H = 602
		/** A world row: 60 px tall inside 8 px padding and a 1 px border. */
		const val ROW_H = 78
		/** Where the body starts: panel border, 60 px top bar, its 1 px border. */
		const val BODY_TOP = 62
		/** The friends column, including its 1 px left border. */
		const val SIDE = 341
	}
}
