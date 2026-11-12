package app.keplersync.tandem.fabric.ui

import gg.essential.elementa.ElementaVersion
import gg.essential.elementa.UIComponent
import gg.essential.elementa.components.UIContainer
import gg.essential.elementa.components.Window
import gg.essential.elementa.constraints.CenterConstraint
import gg.essential.elementa.dsl.basicHeightConstraint
import gg.essential.elementa.dsl.basicWidthConstraint
import gg.essential.elementa.dsl.childOf
import gg.essential.elementa.dsl.constrain
import gg.essential.elementa.dsl.minus
import gg.essential.elementa.dsl.percent
import gg.essential.elementa.dsl.pixels
import gg.essential.universal.UMatrixStack
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents
import net.fabricmc.fabric.api.client.screen.v1.Screens
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.AbstractButton
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.screens.PauseScreen
import app.keplersync.tandem.core.KeplerSyncApi
import app.keplersync.tandem.fabric.Guard
import app.keplersync.tandem.fabric.TandemClient
import app.keplersync.tandem.fabric.account.Account as KsAccount
import app.keplersync.tandem.fabric.play.Hosting
import app.keplersync.tandem.fabric.play.WorldRules
import app.keplersync.tandem.fabric.play.Joining
import app.keplersync.tandem.fabric.sync.WorldSync
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.contents.TranslatableContents
import org.lwjgl.glfw.GLFW
import java.awt.Color
import kotlin.math.max
import java.util.concurrent.CompletableFuture
import java.util.function.Supplier

/**
 * Tandem's panel on the pause screen: a compact card in the top right, only
 * as tall as what's in it, with the world and its save time, who's playing, invites and hand-off. The header's
 * button hides it to a pill, and that's remembered while the game runs. It never moves Minecraft's buttons or any
 * other mod's: it goes in the first free spot, then falls back to the pill, then to nothing (the Open Tandem key
 * still works). Sample data: a world named like one of the sample's synced worlds shows as synced, any other as Just
 * play.
 */
object PausePanel {
	enum class Mode { JUST_PLAY, HOSTING, GUEST }

	/** One play session's sample state, kept while the same world is open. */
	private class Session(val world: String, var mode: Mode) {
		val players = mutableListOf<Person>()
		/** A first upload under way, after Sync this world. */
		var firstUpload: Prog? = null
		val invited = mutableSetOf<String>()
		var inviting = false
		var joining: Pair<String, Long>? = null
		/** Signed in to KeplerSync: the players are the game's own, and invites are real. */
		var real = false
		var host = tr("tandem.sam")
		/** Your own connection, as bars. */
		var bars = 2
	}

	private var session: Session? = null
	private var hidden = false
	private val layers = Layers()
	private val dialogs = Dialogs(layers) {}

	/** Dev screenshots: force a mode, add a player as if they'd joined, and things the runner clicks. */
	var devMode: Mode? = null

	/** The last world handed off and who it went to: its upload after leaving says who takes over. */
	var handedWorld: String? = null
	var handingTo: String? = null
	/** Dev screenshots: who owns the world ("You" or a player's name), and the long line under the title. */
	var devOwner: String? = null
	var devLongLine = false
	val devTargets = mutableMapOf<String, UIComponent>()

	/** Dev checks: who Hand off… offers right now, without opening the pause screen. */
	fun devCanHandOff() = handOffTo(realPlayers()).map { it.name }

	fun devJoin(name: String) = devJoin(Sample.person(name))

	fun devJoin(p: Person) {
		session?.players?.add(p)
	}

	/** Called once Minecraft and every other mod have laid out the pause screen (Tandem's late phase). */
	@JvmStatic
	fun attach(mc: Minecraft, screen: PauseScreen, width: Int, height: Int) {
		if (!screen.showsPauseMenu() || mc.player == null) return
		RealFriends.sync()
		val name = mc.singleplayerServer?.worldData?.levelName ?: mc.currentServer?.name ?: tr("tandem.this_world")
		val firstUp = mc.singleplayerServer?.let { WorldSync.get()?.firstUploading(it) }
		val synced = if (RealWorlds.on) WorldSync.get()?.session != null || firstUp != null
		else Sample.worlds.any { it.name.equals(name, true) && it.state != WorldState.LOCAL && it.state != WorldState.OTHER }
		val mode = devMode ?: if (RealFriends.on && mc.singleplayerServer == null) Mode.GUEST else if (synced) Mode.HOSTING else Mode.JUST_PLAY
		if (session?.world != name || session?.mode != mode) session = Session(name, mode)
		// Sync this world is still uploading: the card shows it, rather than offering Sync this world again.
		if (firstUp != null && session!!.firstUpload == null) session!!.firstUpload = Moving(firstUp)
		if (RealFriends.on && devMode == null) session!!.let { s ->
			s.real = true
			s.host = Joining.current?.info?.host?.displayName ?: name
			val now = realPlayers()
			// Someone came in while the menu was closed: the invite list has done its job.
			if (now.size > s.players.size) s.inviting = false
			s.players.clear(); s.players += now
			// The host has no ping to the game; a guest's is in Minecraft's player list.
			s.bars = if (mc.singleplayerServer != null) 3 else mc.connection?.getPlayerInfo(mc.user.profileId)?.latency?.let(::bars) ?: 2
		}
		Scale.update()

		val others = Screens.getButtons(screen).toList()
		val quit = others.firstOrNull { (it.message.contents as? TranslatableContents)?.key in setOf("menu.returnToMenu", "menu.disconnect") } as? AbstractButton
		quitButton = quit
		val window = Window(ElementaVersion.V8)
		current = window
		var bounds: FloatArray? = null
		fun rebuild() {
			// Minecraft's buttons first in the Tab order, then Tandem's panel.
			Focus.reset()
			for (b in others) if (b is AbstractButton && b.active && b.visible) Focus.add(b) { if (!(b === quit && leaving(b, rebuild = ::rebuild))) b.onPress() }
			window.clearChildren()
			devTargets.clear()
			bounds = build(window, others, width.toFloat(), height.toFloat(), ::rebuild)
			layers.attach(window)
		}
		rebuild()
		window.onMouseClick { layers.closeMenu() }

		fun inside(x: Double, y: Double) = bounds?.let { x >= it[0] && x <= it[2] && y >= it[1] && y <= it[3] } == true
		// Each guarded: if the card breaks, the pause screen goes on as Minecraft's own (see Guard).
		ScreenEvents.afterRender(screen).register { _, gfx, _, _, _ ->
			Guard.run("pause card") {
				gfx.flush()
				window.draw(UMatrixStack(gfx.pose()))
			}
		}
		ScreenMouseEvents.allowMouseClick(screen).register { _, x, y, button ->
			Guard.get("pause card", true) {
				Focus.hide()
				if (!layers.busy && quit != null && button == 0 && quit.isMouseOver(x, y) && leaving(quit, rebuild = ::rebuild)) return@get false
				val ours = layers.busy || inside(x, y)
				if (ours) window.mouseClick(x, y, button)
				!ours
			}
		}
		ScreenMouseEvents.allowMouseRelease(screen).register { _, _, _, _ -> Guard.get("pause card", true) { window.mouseRelease(); true } }
		// The wheel too, or lists inside Tandem's dialogs (Sync this world's friends) can't scroll.
		ScreenMouseEvents.allowMouseScroll(screen).register { _, x, y, _, vertical ->
			Guard.get("pause card", true) {
				val ours = layers.busy || inside(x, y)
				if (ours) window.mouseScroll(vertical.coerceIn(-1.0, 1.0))
				!ours
			}
		}
		ScreenKeyboardEvents.allowKeyPress(screen).register { _, key, _, mods ->
			Guard.get("pause card", true) {
				// In one of Tandem's fields (Send logs, Report…) keys are the field's: Backspace, Ctrl+V, Space.
				val typing = window.focusedComponent is Field
				when {
					key == GLFW.GLFW_KEY_ESCAPE -> !layers.escape()
					key == GLFW.GLFW_KEY_TAB -> { window.unfocus(); Focus.move(mods and GLFW.GLFW_MOD_SHIFT != 0); false }
					typing -> { window.keyType(0.toChar(), key); false }
					key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER || key == GLFW.GLFW_KEY_SPACE -> !Focus.press()
					else -> true
				}
			}
		}
	}

	/** The open pause screen's Tandem window. */
	private var current: Window? = null

	/** A letter typed on the pause screen (KeyboardHandlerMixin): Tandem's field takes it if one has focus. */
	@JvmStatic
	fun typed(codePoint: Int): Boolean {
		val w = current ?: return false
		if (Minecraft.getInstance().screen !is PauseScreen || w.focusedComponent !is Field) return false
		for (c in Character.toChars(codePoint)) w.keyType(c, 0)
		return true
	}

	/**
	 * Before Minecraft's Save and Quit: with friends still in the world, ask (hand off or close for everyone; for Just
	 * play, that it closes for them). Afterwards, or with nobody else here, a synced world keeps saving in the
	 * background, shown on the title tile. Returns true when a dialog opened instead of quitting.
	 */
	private fun leaving(quit: AbstractButton, rebuild: () -> Unit): Boolean {
		val s = session ?: return false
		fun go(to: Person?) {
			if (s.mode == Mode.HOSTING) startBackground(s.world, to?.name)
			quit.onPress()
		}
		if (s.mode == Mode.GUEST || s.players.isEmpty() || !Sample.reachable) {
			if (s.mode == Mode.HOSTING) startBackground(s.world, null)
			return false
		}
		if (s.mode == Mode.HOSTING && s.real) {
			val can = handOffTo(s.players)
			if (can.isEmpty()) return false
			dialogs.leave(can) { to -> layers.closeDialog(); if (to != null) handOff(s.world, to, layers) { quit.onPress() } else quit.onPress() }
		} else if (s.mode == Mode.HOSTING) dialogs.leave(s.players.toList()) { to -> layers.closeDialog(); go(to) }
		else dialogs.leaveJust(s.players.toList(), s.world, { layers.closeDialog(); go(null) }) { syncThisWorld(s, rebuild) }
		return true
	}

	/**
	 * Leaving this game some other way than Save and Quit (joining a friend's): the same question, with whoever is in
	 * it right now, in [dialogs]. [proceed] runs once the player chose to leave, or at once with nobody else here.
	 */
	fun beforeLeaving(dialogs: Dialogs, proceed: () -> Unit) {
		val server = Minecraft.getInstance().singleplayerServer
		val players = if (server != null && RealFriends.on) realPlayers() else emptyList()
		if (server == null || players.isEmpty()) return proceed()
		val world = server.worldData.levelName
		if (WorldRules.of(server) != null) {
			val can = handOffTo(players)
			if (can.isEmpty()) return proceed()
			dialogs.leave(can) { to -> if (to != null) handOff(world, to, dialogs.layers, proceed) else proceed() }
		} else dialogs.leaveJust(players, world, proceed, null)
	}

	/** After leaving a synced world: its session uploads in the background (or, with storage full, stays on this PC). */
	private fun startBackground(world: String, to: String?) {
		// With a dev cloud the world's own closing starts the real upload (WorldSync).
		if (RealWorlds.on) return
		val w = Sample.worlds.firstOrNull { it.name.equals(world, true) }
		if (Sample.storageFull && to == null) {
			w?.state = WorldState.PC_ONLY
			Minecraft.getInstance().execute { pendingFull = w ?: Sample.world(tr("tandem.sky_base")) }
			return
		}
		Sample.background = Triple(world, to, Timed(0f, 6f))
	}

	/** Storage full at the end of a session: shown over the title screen once the world has closed. */
	var pendingFull: World? = null

	/** Sync this world from the pause panel: friends in the game are ticked, then the first upload starts. */
	private fun syncThisWorld(s: Session, rebuild: () -> Unit) {
		val size = Sample.worlds.firstOrNull { it.name.equals(s.world, true) }?.size ?: if (s.real) "" else "820 MB"
		dialogs.sync(s.world, size, s.players.map { it.name }, false, emptyList(), Minecraft.getInstance().singleplayerServer?.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)?.normalize()) { picked ->
			s.mode = Mode.HOSTING
			val server = Minecraft.getInstance().singleplayerServer
			s.firstUpload = if (RealWorlds.on && server != null) {
				// The picker shows names; KeplerSync shares by account.
				val ids = picked.mapNotNull { n -> (s.players + Sample.friends).firstOrNull { it.name == n }?.userId }
				val t = WorldSync.get()!!.firstUploadOpen(server, s.world, ids)
				t.future.whenComplete { _, e ->
					if (e != null) Minecraft.getInstance().execute {
						s.firstUpload = null
						s.mode = Mode.JUST_PLAY
						rebuild()
						val cause = if (e is java.util.concurrent.CompletionException && e.cause != null) e.cause!! else e
						layers.toast(tr("tandem.couldnt_sync", s.world), cause.message ?: tr("tandem.try_again_from_sync_this_world"), "warn")
					}
				}
				Moving(t)
			} else Timed(0f, 14f)
			rebuild()
			layers.toast(tr("tandem.uploading_for_the_first_time", s.world), tr("tandem.keep_playing_friends_can_host_it_once"), "up")
		}
	}

	/** Builds the card (or the pill), puts it in the first free spot, and returns where it went. */
	private fun build(window: Window, others: List<AbstractWidget>, sw: Float, sh: Float, rebuild: () -> Unit): FloatArray? {
		val s = session ?: return null
		ticker(s, window, rebuild)
		inline = false
		if (!hidden) {
			val card = card(s, rebuild, false) childOf window
			place(card, others, sw, sh)?.let { inline = true; layers.toastRight = 320 + 24 + 16; return it }
			window.removeChild(card)
		}
		val pill = pill(s, rebuild) childOf window
		place(pill, others, sw, sh)?.let { layers.toastRight = 18; return it }
		window.removeChild(pill)
		return null
	}

	/**
	 * Top right, 24 px in. If anything is there, the next spot down below it, and so on. Never over another
	 * button, never off screen.
	 */
	private fun place(c: UIComponent, others: List<AbstractWidget>, sw: Float, sh: Float): FloatArray? {
		val w = c.getWidth(); val h = c.getHeight()
		val m = 24.tp
		val x = sw - m - w
		val gap = 8.tp
		fun hits(y: Float) = others.any { o ->
			o.visible && x < o.right + gap && x + w + gap > o.x && y < o.bottom + gap && y + h + gap > o.y
		}
		val tries = listOf(m) + others.filter { it.visible && it.x < x + w && it.right > x }.map { it.bottom + gap }.sorted()
		val y = tries.firstOrNull { it + h <= sh - m && !hits(it) } ?: return null
		c.constrain { this.x = x.pixels; this.y = y.pixels }
		return floatArrayOf(x, y, x + w, y + h)
	}

	/** Whether the card found room beside Minecraft's buttons last time it was built. */
	private var inline = false

	/**
	 * Shows the card or what changed in it. Where there's no room beside Minecraft's buttons (a small window, a busy
	 * pack), the pill stays in the corner and the card opens over the middle of the screen, like a dialog.
	 */
	private fun showCard(s: Session, rebuild: () -> Unit) {
		hidden = false
		rebuild()
		if (!inline && !layers.busy) {
			layers.afterDialog = { overlay = false }
			overlay = true
			layers.openDialog { card(s, rebuild, true) }
		} else if (inline && overlay) {
			// It fits again (the invite list closed): back to the corner.
			layers.closeDialog()
		}
	}

	/** The card is open over the screen. */
	private var overlay = false

	private fun card(s: Session, rebuild: () -> Unit, overlay: Boolean): UIComponent {
		val card = box(16, Tok.alpha(Tok.panel, 0.96), Tok.line).constrain { width = 320.at }
		val col = Column(12).constrain { x = 15.at; y = 15.at; width = 290.at } childOf card

		val title = Column(2).constrain { x = 28.at; y = CenterConstraint(); width = 100.percent - (28 + 40).at }
		val head = UIContainer().constrain { width = 100.percent; height = basicHeightConstraint { max(36.tp, title.getHeight()) } }
		Icon("logo", 18, Tok.violet).constrain { y = CenterConstraint() } childOf head
		title.add(Label(s.world, 16, Weight.BOLD).fitWithin(title))
		title.add(syncLine(s, (290 - 28 - 40).tp))
		title childOf head
		IconButton(if (overlay) "close" else "chevup", 32, Tok.panel) {
			s.inviting = false; layers.closeMenu()
			if (overlay) layers.closeDialog() else { hidden = true; rebuild() }
		}
			.also { devTargets["hide"] = it }.constrain { x = 0.fromEnd; y = CenterConstraint() } childOf head
		col.add(head)
		col.add(Rule(Tok.track))

		Ingame.pendingInvite()?.let { col.add(inviteRow(it)) }
		val rows = Column(2).constrain { width = 100.percent }
		for (p in playing(s)) rows.add(player(s, p, rebuild))
		col.add(rows)

		if (s.mode != Mode.GUEST && Sample.reachable) {
			if (s.inviting) col.add(invites(s, rebuild))
			val buttons = UIContainer().constrain { width = 100.percent; height = 36.at }
			Button(if (s.inviting) tr("tandem.done") else tr("tandem.invite_friends"), Kind.OUTLINE, Size.PANEL, 141, Tok.panel) {
				s.inviting = !s.inviting; layers.closeMenu(); showCard(s, rebuild)
			}.also { devTargets["invite"] = it } childOf buttons
			val second = Button(if (s.mode == Mode.HOSTING) tr("tandem.hand_off") else tr("tandem.sync_this_world"), Kind.OUTLINE, Size.PANEL, 141, Tok.panel) {
				if (s.mode == Mode.HOSTING && s.real) dialogs.leave(handOffTo(s.players)) { to -> layers.closeDialog(); if (to != null) handOff(s.world, to, layers) { quitButton?.onPress() } else quitButton?.onPress() }
				else if (s.mode == Mode.HOSTING) dialogs.leave(s.players.toList()) { to -> layers.closeDialog(); leavingTo(s, to) }
				else syncThisWorld(s, rebuild)
			}
			devTargets[if (s.mode == Mode.HOSTING) "handoff" else "second"] = second
			// Nobody to hand off to yet.
			if (s.mode == Mode.HOSTING && (if (s.real) handOffTo(s.players) else s.players).isEmpty()) second.enabled = false
			second.constrain { x = 0.fromEnd } childOf buttons
			col.add(buttons)
		}
		if (s.real) col.add(sendLogs())
		card.constrain { height = basicHeightConstraint { col.getHeight() + 31.tp } }
		return card
	}

	/** An invite that came while the game was paused: Join (or J) from here. */
	private fun inviteRow(i: KeplerSyncApi.PlayInvite): UIComponent {
		val b = box(12, Tok.over(Tok.panel, Tok.violet, 0.12), Tok.alpha(Tok.violet, 0.4)).constrain { width = 100.percent; height = 52.at }
		val t = Column(1).constrain { x = 12.at; y = CenterConstraint(); width = 100.percent - (12 + 90).at } childOf b
		t.add(Label(tr("tandem.invited_you", i.host.displayName), 13, Weight.SEMIBOLD).fitWithin(t))
		t.add(Label(i.worldName, 12, Weight.REGULAR, Tok.muted).fitWithin(t))
		Button(tr("tandem.join_2", TandemClient.JOIN.translatedKeyMessage.string), Kind.FILLED, Size.ROW, null, Tok.panel) { Ingame.joinInvite(Minecraft.getInstance()) }
			.also { devTargets["join-invite"] = it }.constrain { x = 10.fromEnd; y = CenterConstraint() } childOf b
		return b
	}

	/** A quiet link under everything: Send logs, for when something went wrong in this game. */
	private fun sendLogs(): UIComponent {
		val b = box(8, Tok.panel).constrain { width = 100.percent; height = 26.at }
		val l = Label(tr("tandem.something_wrong_send_logs"), 12.5, Weight.REGULAR, Tok.muted).constrain { x = CenterConstraint(); y = CenterConstraint() } childOf b
		val open = { dialogs.sendLogs() }
		b.onMouseEnter { l.tint = Tok.ink }
		b.onMouseLeave { l.tint = Tok.muted }
		b.onMouseClick { it.stopPropagation(); open() }
		Focus.add(b, b.corner, open)
		devTargets["send-logs"] = b
		return b
	}

	/** Hand off or close from the panel's button: the same as leaving with Save and Quit. */
	private fun leavingTo(s: Session, to: Person?) {
		startBackground(s.world, to?.name)
		quitButton?.onPress()
	}

	/** Minecraft's Save and Quit (or Disconnect) on the open pause screen. */
	private var quitButton: AbstractButton? = null
	val devQuit get() = quitButton

	/** Who's playing, the host first. */
	private fun playing(s: Session): List<Person?> = when {
		s.mode != Mode.GUEST -> listOf<Person?>(null) + s.players
		s.real -> s.players.filter { it.name == s.host } + listOf(null) + s.players.filter { it.name != s.host }
		else -> listOf(Sample.person(tr("tandem.sam")), null)
	}

	/** Everyone else in the game, from Minecraft's own player list, with their ping as bars. */
	private fun realPlayers(): List<Person> {
		val mc = Minecraft.getInstance()
		val hosting = Hosting.get()
		return mc.connection?.onlinePlayers.orEmpty().filter { it.profile.id != mc.user.profileId }.sortedBy { it.profile.name }.map { info ->
			val friend = Sample.friends.firstOrNull { it.profile?.id == info.profile.id }
			Person(friend?.name ?: info.profile.name, 0, true, "", bars = bars(info.latency)).also {
				it.userId = friend?.userId; it.profile = info.profile
				it.relayed = hosting != null && mc.singleplayerServer?.playerList?.getPlayer(info.profile.id)?.let(hosting::relayed) == true
			}
		}
	}

	private fun bars(latency: Int) = when { latency < 150 -> 3; latency < 300 -> 2; else -> 1 }

	/** A real invite: a Just play world asks how to open to friends first (like Open to LAN), then opens. */
	private fun invite(s: Session, f: Person, rebuild: () -> Unit) {
		val server = Minecraft.getInstance().singleplayerServer ?: return
		// Asked once: while the first invite is still opening the world, the next ones wait for it.
		if (Hosting.get() == null && !Hosting.opening(server) && s.mode == Mode.JUST_PLAY) {
			return dialogs.openToFriends(s.world, server.defaultGameType, server.worldData.difficulty) { settings -> sendInvite(s, f, rebuild, settings) }
		}
		sendInvite(s, f, rebuild, null)
	}

	private fun sendInvite(s: Session, f: Person, rebuild: () -> Unit, settings: Hosting.Settings?) {
		val mc = Minecraft.getInstance()
		val server = mc.singleplayerServer ?: return
		s.invited += f.name
		rebuild()
		// Sync this world still uploading: the invite waits until it's a synced world, rather than opening it as Just play.
		val first = WorldSync.get()?.firstUploading(server)?.future ?: CompletableFuture.completedFuture<Void?>(null)
		first.thenCompose { server.submit(Supplier { WorldSync.get()?.session?.worldId }) }
			.thenCompose { id -> Hosting.open(server, s.world, id, emptyList(), settings) }.thenCompose { it.invite(f.userId, f.profile?.id) }.whenComplete { _, e ->
			mc.execute {
				// From here on the invite is Hosting's to keep: a new game (leave and come back) starts with none.
				s.invited -= f.name
				if (e != null) {
					val cause = if (e is java.util.concurrent.CompletionException && e.cause != null) e.cause!! else e
					layers.toast(tr("tandem.couldnt_invite", f.name), cause.message ?: tr("tandem.try_again_2"), "warn")
				}
				// Open to friends now: the line under the world's name says so.
				rebuild()
			}
		}
	}

	/** Withdraws an invite they haven't used yet. */
	private fun uninvite(f: Person, rebuild: () -> Unit) {
		val mc = Minecraft.getInstance()
		val h = Hosting.get() ?: return
		h.uninvite(f.userId, f.profile?.id).whenComplete { _, e ->
			mc.execute {
				if (e != null) layers.toast(tr("tandem.couldnt_withdraw_the_invite"), (e.cause ?: e).message ?: tr("tandem.try_again_2"), "warn")
				rebuild()
			}
		}
		rebuild()
	}

	/** Hands the synced [world] to [to], then [leave]s: [to] hosts it once this game has saved it. */
	private fun handOff(world: String, to: Person, layers: Layers, leave: () -> Unit) {
		val mc = Minecraft.getInstance()
		val rules = mc.singleplayerServer?.let { WorldRules.of(it) }
		val user = to.profile?.id?.let { rules?.userId(it) }
		val account = KsAccount.get()
		if (rules == null || user == null || account == null) return layers.toast(tr("tandem.couldnt_hand_off_to", to.name), tr("tandem.they_arent_in", world), "warn")
		WorldSync.get()!!.async { account.api.handoff(rules.worldId, user) }.whenComplete { _, e ->
			mc.execute {
				if (e != null) layers.toast(tr("tandem.couldnt_hand_off_to", to.name), (e.cause ?: e).message ?: tr("tandem.try_again_2"), "warn")
				else { handingTo = to.name; handedWorld = world; leave() }
			}
		}
	}

	/** Who this world can be handed to: [players] in the game who are in the synced world. */
	private fun handOffTo(players: List<Person>): List<Person> {
		val rules = Minecraft.getInstance().singleplayerServer?.let { WorldRules.of(it) } ?: return emptyList()
		return players.filter { p -> p.profile?.id?.let(rules::isMember) == true }
	}

	private fun syncLine(s: Session, room: Float): UIComponent {
		val first = s.firstUpload
		if (first != null && first.value < 1f) {
			return row(6, Box(3.5f.tp, Tok.violet).constrain { width = 7.at; height = 7.at }, Live(12.5, Weight.REGULAR, Tok.muted) {
				if (waitingToUpload(first)) tr("tandem.offline_uploads_when_youre_back") else tr("tandem.uploading_for_the_first_time_2", (first.value * 100).toInt())
			})
		}
		val (dot, text) = if (!Sample.reachable && s.mode != Mode.GUEST) Tok.warn to tr("tandem.cant_reach_keplersync_invites_are_off")
		else if (Sample.offline && s.mode == Mode.HOSTING) Tok.warn to tr("tandem.offline_uploads_when_youre_back")
		else when (s.mode) {
			Mode.HOSTING -> Tok.ok to (WorldSync.get()?.session?.let { real ->
				tr("tandem.saved_next_in_min", RealWorlds.ago(real.savedAt), ((real.nextUploadAt() - System.currentTimeMillis()) / 60_000).coerceAtLeast(0) + 1)
			} ?: if (s.real) tr("tandem.synced") else tr("tandem.saved_2_min_ago_next_in_28"))
			Mode.GUEST -> Tok.violet to if (devLongLine) tr("tandem.is_hosting_through_keplersyncs_relay", "xX_EnderWalker_Xx")
				else if (!s.real) tr("tandem.sam_is_hosting_saved_2_min_ago")
				else if (Joining.current?.relayed == true) tr("tandem.is_hosting_through_keplersyncs_relay", s.host) else tr("tandem.is_hosting_2", s.host)
			Mode.JUST_PLAY -> if (Hosting.get() != null) Tok.ok to tr("tandem.just_play_open_to_friends_you_invite") else Tok.outline to tr("tandem.just_play_on_this_pc_only")
		}
		// A long line (a long name, the relay) wraps under itself rather than running under the button.
		val words = wrapped(text, 12.5, Weight.REGULAR, Tok.muted, room - 13.tp)
		val line = UIContainer().constrain { width = 100.percent; height = basicHeightConstraint { words.getHeight() } }
		Box(3.5f.tp, dot).constrain { y = (12.5 * LINE_EM / 2 - 3.5).at; width = 7.at; height = 7.at } childOf line
		words.constrain { x = 13.at } childOf line
		return line
	}

	/** The synced world in this game, from the worlds list: whose it is, its people and its operators. */
	private fun synced(s: Session): World? {
		val id = if (s.mode == Mode.GUEST) Joining.current?.info?.worldId
			else Minecraft.getInstance().singleplayerServer?.let { WorldRules.of(it)?.worldId }
		return id?.let { Sample.worlds.firstOrNull { w -> w.cloudId == it } }
	}

	/** Whether [p] (null for you) owns the synced world in this game. */
	private fun isOwner(s: Session, p: Person?): Boolean {
		devOwner?.let { return (p?.name ?: "You") == it }
		val mc = Minecraft.getInstance()
		val rules = mc.singleplayerServer?.let { WorldRules.of(it) }
		if (rules != null && rules.owner() != null) return rules.isOwner(if (p == null) mc.user.profileId else p.profile?.id)
		val w = synced(s) ?: return false
		if (p == null) return w.owner == "You"
		val ownerId = w.owner?.let { w.userIds[it] }
		return ownerId != null && p.userId == ownerId
	}

	private fun player(s: Session, p: Person?, rebuild: () -> Unit): UIComponent {
		val r = box(10, Tok.panel).constrain { width = 100.percent; height = 40.at }
		val face = Face({ p?.skin ?: yourSkin() }, 24, 6, null, Tok.panel).constrain { x = 6.at; y = CenterConstraint() } childOf r
		val isHost = if (s.mode == Mode.GUEST) p?.name == s.host else p == null
		val name = Label(p?.name ?: "You", 14)
		// The owner has the last word whoever hosts, so they're marked; the host only when it's someone else.
		val tag = if ((s.real || devOwner != null) && isOwner(s, p)) pill12(tr("tandem.owner")) else if (isHost) pill12(tr("tandem.host"))
			else if (p?.relayed == true) pill12(tr("tandem.relay")) else null
		(if (tag != null) row(8, name, tag) else row(8, name)).constrain { x = (6 + 24 + 10).at; y = CenterConstraint() } childOf r
		val canMenu = p != null
		val bars = Bars(p?.bars ?: s.bars, p == null).constrain { x = (if (canMenu) 4 + 28 + 10 else 4).fromEnd; y = CenterConstraint() } childOf r
		// A long name ends in an ellipsis before its tag and the bars.
		name.room = { bars.getLeft() - 10.tp - (tag?.let { it.getWidth() + 8.tp } ?: 0f) - name.getLeft() }
		if (canMenu) {
			lateinit var more: IconButton
			more = IconButton("more", 28, Tok.panel) { playerMenu(s, p!!, r, rebuild) }
			more.also { devTargets["more-${p!!.name}"] = it }.constrain { x = 4.fromEnd; y = CenterConstraint() } childOf r
		}
		r.onMouseEnter { r.fill = Tok.raise; face.under = Tok.raise }
		r.onMouseLeave { r.fill = Tok.panel; face.under = Tok.panel }
		return r
	}

	/** The player menu: Report… for anyone; Kick and, for a synced world, Remove for whoever owns it. */
	private fun playerMenu(s: Session, p: Person, r: UIComponent, rebuild: () -> Unit) {
		val items = mutableListOf(MenuItem(tr("tandem.report_2"), "warn") { dialogs.report(p) })
		// Nobody kicks the world's owner from it.
		if (s.mode != Mode.GUEST && !((s.real || devOwner != null) && isOwner(s, p))) {
			items += MenuItem(tr("tandem.kick_from_this_session"), "close", tr("tandem.can_join_again_if_invited", p.name)) {
				s.players.remove(p); s.invited.remove(p.name)
				if (s.real) Hosting.get()?.kick(p.profile?.name ?: p.name, p.userId)
				rebuild()
			}
		}
		val mc = Minecraft.getInstance()
		val rules = mc.singleplayerServer?.let { WorldRules.of(it) }
		val id = p.profile?.id
		if (rules != null && id != null && rules.isMember(id) && !rules.isOwner(id) && rules.isOperator(mc.user.profileId)) {
			val op = rules.isOperator(id)
			items += MenuItem(if (op) tr("tandem.remove_operator") else tr("tandem.make_operator"), "lock", if (op) tr("tandem.cant_use_commands_in", p.name, s.world) else tr("tandem.can_use_commands_in_whoever_hosts", p.name, s.world)) {
				rules.setOperator(id, !op).whenComplete { _, e ->
					mc.execute {
						if (e != null) layers.toast(tr("tandem.couldnt_change", p.name), (e.cause ?: e).message ?: tr("tandem.try_again_2"), "warn")
						else layers.toast(if (op) tr("tandem.isnt_an_operator_now", p.name) else tr("tandem.is_an_operator_now", p.name), tr("tandem.in_whoever_hosts_it", s.world))
					}
				}
			}
		}
		// The world's owner in someone else's game of it: operators and kicks go through KeplerSync to the host.
		val world = synced(s)
		val join = Joining.current
		val user = p.userId
		if (s.mode == Mode.GUEST && s.real && world?.cloudId != null && world.owner == "You" && join != null && user != null) {
			// The world's own name for them, found by account: two friends can share a display name.
			val label = world.userIds.entries.firstOrNull { it.value == user }?.key
			if (label != null) {
				val op = label in world.operators
				items += MenuItem(if (op) tr("tandem.remove_operator") else tr("tandem.make_operator"), "lock", if (op) tr("tandem.cant_use_commands_in", p.name, s.world) else tr("tandem.can_use_commands_in_whoever_hosts", p.name, s.world)) {
					asOwner(tr("tandem.couldnt_change", p.name), { it.setOperator(world.cloudId, user, !op) }) {
						if (op) world.operators -= label else world.operators += label
						layers.toast(if (op) tr("tandem.isnt_an_operator_now", p.name) else tr("tandem.is_an_operator_now", p.name), tr("tandem.in_whoever_hosts_it", s.world))
					}
				}
			}
			if (p.name != s.host) {
				items += MenuItem(tr("tandem.kick_from_this_session"), "close", tr("tandem.the_host_removes_them_for_you", p.name)) {
					asOwner(tr("tandem.couldnt_kick", p.name), { it.kick(join.info.sessionId, user) }) {
						layers.toast(tr("tandem.is_removed_from_this_game", p.name), tr("tandem.they_can_join_again_if_invited"))
					}
				}
			}
		}
		val server = mc.singleplayerServer
		val profile = p.profile
		// Just play: Minecraft's own operators, unless commands were turned on for everyone when the world opened.
		if (s.real && rules == null && server != null && profile != null && !server.playerList.isAllowCommandsForAllPlayers) {
			val op = server.playerList.isOp(profile)
			items += MenuItem(if (op) tr("tandem.remove_operator") else tr("tandem.make_operator"), "lock", if (op) tr("tandem.cant_use_commands_here", p.name) else tr("tandem.can_use_commands_in_this_game", p.name)) {
				server.execute { if (op) server.playerList.deop(profile) else server.playerList.op(profile) }
				layers.toast(if (op) tr("tandem.isnt_an_operator_now", p.name) else tr("tandem.is_an_operator_now", p.name), tr("tandem.in_while_you_host_it", s.world))
			}
		}
		if (s.mode == Mode.HOSTING && !s.real && !isOwner(s, p)) {
			items += MenuItem(tr("tandem.remove_from", s.world), "close", tr("tandem.cant_join_or_host_it_any_more", p.name)) {
				val at = s.players.indexOf(p)
				s.players.remove(p); s.invited.remove(p.name)
				rebuild()
				layers.toast(tr("tandem.is_removed_from", p.name, s.world), tr("tandem.their_own_copy_stays_on_their_pc"), undo = { s.players.add(at, p); rebuild() })
			}
		}
		layers.openMenu(r.getLeft() + 6.tp, r.getBottom() + 2.tp, r.getWidth() - 6.tp, items, flipAbove = r.getTop() - 2.tp, owner = "player:${p.name}")
	}

	/** [work] with KeplerSync on the sync thread; then [done], or a toast titled [failed] saying why not. */
	private fun asOwner(failed: String, work: (KeplerSyncApi) -> Unit, done: () -> Unit) {
		val mc = Minecraft.getInstance()
		val a = KsAccount.get() ?: return layers.toast(failed, tr("tandem.still_signing_in_to_keplersync_try_again"), "warn")
		WorldSync.get()!!.async { work(a.api) }.whenComplete { _, e ->
			mc.execute { if (e != null) layers.toast(failed, (e.cause ?: e).message ?: tr("tandem.try_again_2"), "warn") else done() }
		}
	}

	/** Online friends not in the game, each with Invite, inside the card. */
	private fun invites(s: Session, rebuild: () -> Unit): UIComponent {
		val b = box(12, Tok.rail, Tok.track).constrain { width = 100.percent }
		val list = Column(2).constrain { x = 7.at; y = 7.at; width = 100.percent - 14.at } childOf b
		val candidates = Sample.friends.filter { f -> f.online && s.players.none { it === f || (f.userId != null && it.userId == f.userId) } }
		for (f in candidates) {
			val r = UIContainer().constrain { width = 100.percent; height = 40.at }
			Face({ f.skin }, 24, 6, true, Tok.rail).constrain { x = 6.at; y = CenterConstraint() } childOf r
			val who = Column(0).constrain { x = (6 + 24 + 10).at; y = CenterConstraint(); width = 100.percent - (40 + 76).at } childOf r
			who.add(Label(f.name, 13.5).fitWithin(who))
			who.add(Label(f.presence, 12, Weight.REGULAR, Tok.muted).fitWithin(who))
			val hosted = f.userId != null && Hosting.get()?.invited?.contains(f.userId) == true
			if (f.name in s.invited || hosted) {
				val done = row(5, Icon("check", 14, Tok.okText), Label(tr("tandem.invited"), 12.5, Weight.REGULAR, Tok.okText))
				// Not joined yet: the invite can be taken back.
				if (hosted) row(6, done, IconButton("close", 26, Tok.rail) { uninvite(f, rebuild) }.also { devTargets["uninvite-${f.name}"] = it })
					.constrain { x = 2.fromEnd; y = CenterConstraint() } childOf r
				else done.constrain { x = 8.fromEnd; y = CenterConstraint() } childOf r
			} else {
				Button(tr("tandem.invite"), Kind.OUTLINE, Size.ROW, null, Tok.rail) {
					if (f.userId != null) invite(s, f, rebuild)
					else {
						s.invited += f.name
						// Only someone in the menu joins straight away in the sample.
						if (f.presence == tr("tandem.in_the_menu")) s.joining = f.name to System.currentTimeMillis() + 3000
						rebuild()
					}
				}.also { devTargets["invite-${f.name}"] = it }.constrain { x = 2.fromEnd; y = CenterConstraint() } childOf r
			}
			list.add(r)
		}
		if (candidates.isEmpty()) list.add(Label(tr("tandem.everyone_online_is_already_here"), 12.5, Weight.REGULAR, Tok.muted).constrain { x = 6.at })
		b.constrain { height = basicHeightConstraint { list.getHeight() + 14.tp } }
		return b
	}

	/** The hidden panel: the world, its state, who's playing, and a chevron to open it again. */
	private fun pill(s: Session, rebuild: () -> Unit): UIComponent {
		val p = box(14, Tok.alpha(Tok.panel, 0.96), Tok.line)
		val faces = UIContainer()
		val people = playing(s)
		for ((i, who) in people.withIndex()) {
			val ring = box(7, Tok.panel).constrain { x = (i * 18).at; width = 26.at; height = 26.at } childOf faces
			Face({ who?.skin ?: yourSkin() }, 22, 5, null, Tok.panel).constrain { x = CenterConstraint(); y = CenterConstraint() } childOf ring
		}
		faces.constrain { width = (people.size * 18 + 8).at; height = 26.at }
		val dot = when (s.mode) { Mode.HOSTING -> Tok.ok; Mode.GUEST -> Tok.violet; Mode.JUST_PLAY -> Tok.outline }
		val content = row(
			10, Icon("logo", 18, Tok.violet), Label(s.world, 14, Weight.BOLD).also { it.room = { 220.tp } },
			Box(4.tp, dot).constrain { width = 8.at; height = 8.at }, faces, Icon("chevdown", 16, Tok.muted),
		).constrain { x = 15.at; y = CenterConstraint() } childOf p
		p.constrain { width = basicWidthConstraint { content.getWidth() + 28.tp }; height = 44.at }
		val show = { showCard(s, rebuild) }
		p.onMouseEnter { p.border = Tok.outline }
		p.onMouseLeave { p.border = Tok.line }
		p.onMouseClick { it.stopPropagation(); show() }
		Focus.add(p, p.corner, show)
		devTargets["show"] = p
		return p
	}

	private fun ticker(s: Session, window: Window, rebuild: () -> Unit) {
		var shownInvite = Ingame.pendingInvite()?.sessionId
		// The world's people load after its first upload, and change when someone's added: Hand off… and the menus follow.
		var canHandOff = handOffTo(s.players).size
		Ticker {
			val inv = Ingame.pendingInvite()?.sessionId
			if (inv != shownInvite) { shownInvite = inv; rebuild(); return@Ticker }
			s.firstUpload?.let { if (it.value >= 1f) { s.firstUpload = null; rebuild(); return@Ticker } }
			if (s.real && s.mode == Mode.HOSTING && handOffTo(s.players).size != canHandOff) { rebuild(); return@Ticker }
			if (s.real) {
				val now = realPlayers()
				if (now.map { it.name } != s.players.map { it.name }) {
					// Someone came in: the invite list has done its job, as in the sample.
					if (now.size > s.players.size) s.inviting = false
					s.players.clear(); s.players += now; rebuild(); return@Ticker
				}
			}
			val j = s.joining
			if (j != null && System.currentTimeMillis() >= j.second) {
				s.joining = null
				s.players += Sample.person(j.first)
				s.inviting = false
				Minecraft.getInstance().gui.chat.addMessage(Component.translatable("multiplayer.player.joined", j.first).withStyle(ChatFormatting.YELLOW))
				rebuild()
			}
		} childOf window
	}
}
