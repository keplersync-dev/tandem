package app.keplersync.tandem.fabric.ui

import app.keplersync.tandem.fabric.Guard
import app.keplersync.tandem.fabric.TandemClient
import app.keplersync.tandem.fabric.account.Account as KsAccount
import app.keplersync.tandem.fabric.mixin.DatapackLoadFailureScreenAccess
import app.keplersync.tandem.fabric.mixin.ToastComponentAccess
import app.keplersync.tandem.fabric.sync.WorldSync
import gg.essential.elementa.ElementaVersion
import gg.essential.elementa.UIComponent
import gg.essential.elementa.components.Window
import gg.essential.elementa.constraints.CenterConstraint
import gg.essential.elementa.dsl.basicHeightConstraint
import gg.essential.elementa.dsl.basicWidthConstraint
import gg.essential.elementa.dsl.basicYConstraint
import gg.essential.elementa.dsl.childOf
import gg.essential.elementa.dsl.constrain
import gg.essential.universal.UMatrixStack
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager
import net.fabricmc.loader.api.FabricLoader
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents
import net.fabricmc.fabric.api.client.screen.v1.Screens
import app.keplersync.tandem.core.KeplerSyncApi
import net.minecraft.ChatFormatting
import app.keplersync.tandem.fabric.play.Joining
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.DatapackLoadFailureScreen
import net.minecraft.client.gui.screens.DisconnectedScreen
import net.minecraft.client.gui.screens.RecoverWorldDataScreen
import net.minecraft.client.gui.components.AbstractButton
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.sounds.SoundSource
import net.minecraft.network.chat.contents.TranslatableContents
import org.lwjgl.glfw.GLFW
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.max

/**
 * What Tandem shows outside its own screens, and over them: invites, short notices (a world synced, one stopped
 * syncing, a zip downloaded) and, while playing, the offline banner; after leaving, the background upload on the
 * title tile; and the dialogs that belong to those moments. Sample events come from /tandem, which switches the
 * sample's situations on and off.
 */
object Ingame {
	private var spaceChecked: WorldSync.Transfer? = null

	/** Sam's invite to Create Nights, showing until joined, dismissed or 30 seconds pass. */
	private var invite: Long? = null
	/** A real invite from a friend, in place of Sam's. */
	private var realInvite: KeplerSyncApi.PlayInvite? = null
	private var online: Pair<String, Long>? = null
	/** Notices waiting, and the one showing (title, detail, icon) since it was first drawn (0 until then). */
	private val notices = ConcurrentLinkedQueue<Triple<String, String, String>>()
	private var notice: Pair<Triple<String, String, String>, Long>? = null
	/** What the live refresh last saw of KeplerSync's changes, and the stops already told about. */
	private var seenWorlds = -1
	private val toldStop = mutableSetOf<String>()
	private var pending: Screen? = null
	private var hud: Window? = null
	private var hudKey = ""
	/** The invite toast in [hud], which takes clicks on the title screen. */
	private var hudToast: UIComponent? = null

	/** A synced world's host handed it over while this player was in their game: acted on once that game closes. */
	private var handoff: Triple<String, String, Pair<String, String>?>? = null

	/** Shows a short notice top right, wherever the player is (Tandem's own screens included). Any thread. */
	@JvmStatic
	fun notice(title: String, detail: String, icon: String = "check") {
		notices.add(Triple(title, detail, icon))
	}

	private fun drawHud(g: GuiGraphics, banner: Boolean = true) {
		val offline = banner && Sample.offline
		val key = "${invite != null}|${realInvite?.sessionId}|$offline|${online?.first}|${notice?.first}|${Minecraft.getInstance().window.guiScaledWidth}"
		if (invite == null && !offline && online == null && notice == null) return
		// Its time counts from when it's seen, not from under the pause screen.
		notice?.let { if (it.second == 0L) notice = it.first to System.currentTimeMillis() }
		if (hud == null || key != hudKey) { hud = buildHud(offline); hudKey = key }
		g.flush()
		hud!!.draw(UMatrixStack(g.pose()))
	}

	fun install() {
		HudRenderCallback.EVENT.register { g, _ ->
			// Under a menu it would only show blurred behind it; the pause card says the same things there.
			Guard.run("HUD") { if (Minecraft.getInstance().screen == null) drawHud(g) }
		}
		ClientTickEvents.END_CLIENT_TICK.register { mc -> Guard.run("invites and notices") { tick(mc) } }
		// A synced world Minecraft couldn't load (its own recovery screens): Tandem offers the version before.
		ScreenEvents.AFTER_INIT.register { mc, screen, _, _ ->
			// Over Tandem's own screens too; they have their own offline banner.
			if (screen is PlayScreen || screen is DialogScreen) Guard.run("HUD") { hudOn(screen, banner = false) }
			Guard.run("help for worlds that won't load") {
				val opening = RealWorlds.opening
				if (opening != null && (screen is RecoverWorldDataScreen || screen is DatapackLoadFailureScreen)) {
					RealWorlds.opening = null
					mc.execute {
						// Minecraft's own Cancel first: it closes the world, so Tandem can replace its files.
						if (screen is RecoverWorldDataScreen) screen.onClose() else ((screen as DatapackLoadFailureScreenAccess).`tandem$cancel`()).run()
						mc.setScreen(PlayScreen().also { it.afterOpen { s -> s.wontLoad(opening.first, opening.second) } })
					}
				}
			}
		}
		if (FabricLoader.getInstance().isDevelopmentEnvironment) ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
			var cmd = ClientCommandManager.literal("tandem")
				.executes { c -> c.source.sendFeedback(Component.literal(tr("tandem.tandem_sample_events", events.keys.joinToString(", ")))); 1 }
			for ((name, run) in events) {
				cmd = cmd.then(ClientCommandManager.literal(name).executes { c -> c.source.sendFeedback(Component.literal(tr("tandem.tandem_sample") + run())); 1 })
			}
			dispatcher.register(cmd)
		}
	}

	/** The sample's events and situations, by name: /tandem <name> in game. */
	private val events: Map<String, () -> String> = linkedMapOf(
		"invite" to { invite = System.currentTimeMillis(); tr("tandem.sam_invited_you_press_to_join", TandemClient.JOIN.translatedKeyMessage.string) },
		"offline" to { Sample.offline = !Sample.offline; if (Sample.offline) tr("tandem.your_internet_dropped") else tr("tandem.your_internet_is_back") },
		"stopped" to { pending = stoppedScreen(); tr("tandem.sams_game_crashed") },
		"full" to { Sample.storageFull = !Sample.storageFull; if (Sample.storageFull) tr("tandem.storage_is_full_leave_a_synced_world") else tr("tandem.storage_has_room_again") },
		"unreachable" to { Sample.reachable = !Sample.reachable; if (Sample.reachable) tr("tandem.keplersync_is_reachable_again") else tr("tandem.keplersync_is_unreachable") },
		"slow" to { Sample.slow = !Sample.slow; if (Sample.slow) tr("tandem.slow_internet_downloads_take_a_while") else tr("tandem.normal_internet") },
		"disk" to { Sample.diskFull = !Sample.diskFull; if (Sample.diskFull) tr("tandem.not_enough_free_disk_space") else tr("tandem.enough_free_disk_space") },
		"alexquit" to { Sample.alexQuit = !Sample.alexQuit; if (Sample.alexQuit) tr("tandem.alex_quit_before_the_upload_finished") else tr("tandem.alex_is_still_saving") },
		"online" to {
			if (Account.friendsOnline) { online = tr("tandem.riley") to System.currentTimeMillis(); tr("tandem.riley_came_online") }
			else tr("tandem.riley_came_online_nothing_shows_settings_tell")
		},
	)

	/** Dev checks: who the online toast is showing, if anyone. */
	fun devOnline() = online?.first

	/** Dev checks: the world of the real invite showing, if any. */
	fun devInvite() = realInvite?.worldName

	/** Dev checks: the notice showing, if any, and Later on the invite toast. */
	fun devNotice() = notice?.first?.first

	fun devLater() {
		invite = null; realInvite = null
	}

	/** The real invite waiting for Join, for the pause card. */
	fun pendingInvite(): KeplerSyncApi.PlayInvite? = if (invite != null) realInvite else null

	/** Dev screenshots: the same events as /tandem. */
	fun dev(name: String) = events.getValue(name)()

	private fun tick(mc: Minecraft) {
		val now = System.currentTimeMillis()
		if (RealWorlds.on && Sample.worlds.any { it.levelId == null && it.cloudId == null }) Sample.worlds.clear()
		if (RealFriends.on && (Sample.friends.any { it.userId == null } || Sample.requests.any { it.userId == null })) { Sample.friends.clear(); Sample.requests.clear() }
		if (invite != null && now - invite!! > 30_000) { invite = null; realInvite = null }
		// Loaded fine.
		if (mc.singleplayerServer != null) { RealWorlds.opening = null; RealWorlds.opened() }
		val account = KsAccount.get()
		account?.invited?.poll()?.let { realInvite = it; invite = now; inviteSound(mc) }
		// Withdrawn, the game ended, or already joined from the menus: the toast goes.
		realInvite?.let { r ->
			val inIt = mc.level != null && Joining.current?.info?.sessionId == r.sessionId
			if (inIt || account?.invites?.none { it.sessionId == r.sessionId } == true) { realInvite = null; invite = null }
		}
		account?.handoffs?.poll()?.let { h ->
			val host = Joining.current?.info?.host
			if (mc.level != null && mc.singleplayerServer == null && host?.id == h.fromId) {
				// Who takes over, on screen and by id: the id is what their lease is checked against.
				val to = if (h.toId == account.me?.id) null
				else (account.friends.firstOrNull { it.user.id == h.toId }?.user?.displayName ?: tr("tandem.your_friend")) to h.toId
				handoff = Triple(h.worldId, host.displayName, to)
			}
		}
		// The host's game closed for the hand-off: take over, or follow whoever did.
		handoff?.let { (world, from, to) ->
			if (mc.level == null && (mc.screen == null || mc.screen is TitleScreen || mc.screen is DisconnectedScreen)) {
				handoff = null
				mc.setScreen(PlayScreen().also { it.afterOpen { s -> s.handoff(world, from, to) } })
			}
		}
		if (online != null && now - online!!.second > 5_000) online = null
		if (notice.let { it != null && it.second != 0L && now - it.second > 8_000 }) notice = null
		RealLeave.stopped.poll()?.let { s -> told(s).let { (t, d) -> notice(t, d, "pc") } }
		if (notice == null) notices.poll()?.let { notice = it to 0L }
		if (account != null) live(mc, account)
		// A real friend came online: the toast, if Settings asks for it.
		KsAccount.get()?.cameOnline?.poll()?.let { name -> if (Account.friendsOnline) online = name to now }
		// Used up even with no invite showing, so presses don't pile up and join the next one by themselves.
		if (mc.screen == null && TandemClient.JOIN.consumeClick() && invite != null) joinInvite(mc)
		pending?.let { if (mc.screen == null || mc.screen is TitleScreen) { mc.setScreen(it); pending = null } }
		WorldSync.get()?.let { if (it.server) Sample.offline = it.offline }
		// A real upload after leaving a world shows on the title tile like the sample's.
		WorldSync.get()?.background?.let { t ->
			if (!t.done()) { if (Sample.background?.first != t.world) Sample.background = Triple(t.world, null, Moving(t)) }
			else if (Sample.background?.first == t.world) Sample.background = null
			// KeplerSync refused it for space: storage full, over the title screen.
			if (t.done() && t !== spaceChecked) {
				spaceChecked = t
				RealLeave.refusedForSpace(t)?.let { PausePanel.pendingFull = it }
				RealLeave.refusedAtCapacity(t)?.let { (name, why) -> notice(tr("tandem.is_saved_on_this_pc_for_now", name), why, "pc") }
			}
		}
		val bg = Sample.background
		if (bg != null && bg.third.value >= 1f) {
			Sample.background = null
			Sample.worlds.firstOrNull { it.name.equals(bg.first, true) }?.let { w ->
				if (bg.second != null) { w.state = WorldState.HOSTED; w.who = bg.second!! } else { w.state = WorldState.READY; w.who = "you"; w.ago = tr("tandem.just_now") }
			}
		}
		PausePanel.pendingFull?.let { w -> if (mc.screen is TitleScreen) { PausePanel.pendingFull = null; mc.setScreen(DialogScreen { it.full(w) }) } }
	}

	/** Atlas's notification sound (assets/tandem/sounds/invite.ogg), when Settings › Sound for invites is on. */
	private fun inviteSound(mc: Minecraft) {
		if (!Account.inviteSound) return
		mc.soundManager.play(SimpleSoundInstance(ResourceLocation.fromNamespaceAndPath("tandem", "invite"), SoundSource.MASTER, 1f, 1f,
			SoundInstance.createUnseededRandom(), false, 0, SoundInstance.Attenuation.NONE, 0.0, 0.0, 0.0, true))
	}

	/**
	 * Join (J) on the invite showing. Also from the Game Menu: the game pauses itself when its window loses focus, as
	 * when someone switches to a chat app to ask for the invite.
	 */
	@JvmStatic
	fun joinInvite(mc: Minecraft): Boolean {
		if (invite == null) return false
		invite = null
		val real = realInvite
		realInvite = null
		if (real != null) mc.setScreen(DialogScreen { d -> RealJoin.join(real.sessionId, real.host.displayName, real.worldName, d) })
		// The sample never has Sam's mods, so joining shows what's different.
		else mc.setScreen(PlayScreen().also { it.afterOpen { s -> s.dev("mods") } })
		return true
	}

	private fun stoppedScreen() = DialogScreen { d ->
		val sam = Sample.person(tr("tandem.sam"))
		d.stopped(sam, tr("tandem.create_nights"), listOf(tr("tandem.noor")), title = {
			val mc = Minecraft.getInstance()
			mc.level?.disconnect(); mc.disconnect(TitleScreen())
		}) { d.getLatest(Sample.world(tr("tandem.create_nights"))) }
	}

	/**
	 * The title screen, after Tandem's tile is placed: quitting Minecraft while a session is still uploading asks first
	 * (Quit when done, or Quit anyway).
	 */
	@JvmStatic
	fun onTitle(screen: Screen) {
		val mc = Minecraft.getInstance()
		val quit = Screens.getButtons(screen).firstOrNull { (it.message.contents as? TranslatableContents)?.key == "menu.quit" } as? AbstractButton ?: return
		fun ask(): Boolean {
			if (Sample.background == null) return false
			mc.setScreen(DialogScreen { it.quitting { mc.stop() } })
			return true
		}
		// An invite that arrives at the title screen shows here too; J joins.
		hudOn(screen, banner = true)
		ScreenMouseEvents.allowMouseClick(screen).register { _, x, y, button ->
			Guard.get("quit check", true) { !(button == 0 && quit.isMouseOver(x, y) && ask()) }
		}
		ScreenKeyboardEvents.allowKeyPress(screen).register { s, key, _, _ ->
			Guard.get("quit check", true) { !((key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_SPACE) && s.focused === quit && ask()) }
		}
	}

	/** The HUD over [screen], with clicks on the invite toast's buttons. */
	private fun hudOn(screen: Screen, banner: Boolean) {
		ScreenEvents.afterRender(screen).register { _, g, _, _, _ -> Guard.run("HUD") { drawHud(g, banner) } }
		ScreenMouseEvents.allowMouseClick(screen).register { _, x, y, button ->
			Guard.get("HUD", true) {
				val t = hudToast
				if (invite == null || t == null || !t.isPointInside(x.toFloat(), y.toFloat())) return@get true
				hud?.mouseClick(x, y, button)
				false
			}
		}
	}

	/** A stopped world, in words: whose stop, and what the copy here is now. */
	private fun told(s: RealLeave.Stopped): Pair<String, String> {
		val copy = s.copyTime?.let { tr("tandem.from_at", RealHistory.day(it).lowercase(), RealHistory.time(it)) } ?: ""
		val title = if (s.owner == "You") tr("tandem.you_stopped_syncing", s.world) else tr("tandem.stopped_syncing", s.owner, s.world)
		return title to when {
			s.fetching -> tr("tandem.getting_the_latest_copy_here_first_then")
			s.unsent -> tr("tandem.your_copy_keeps_what_you_played_that", copy)
			s.behind -> tr("tandem.your_copy_is_a_normal_world_now", copy)
			else -> tr("tandem.your_copy_is_a_normal_world_now_2", copy)
		}
	}

	/**
	 * KeplerSync's changes while Tandem's screen is closed: the worlds list is read again (so stops and the title tile
	 * are current), friends too, and a world being hosted here that's set to stop afterwards says so once.
	 */
	private fun live(mc: Minecraft, account: KsAccount) {
		if (mc.screen is PlayScreen) return
		RealFriends.sync()
		val d = WorldSync.get() ?: return
		if (account.worldsVersion == seenWorlds || RealWorlds.opening != null || RealWorlds.pendingLevel != null) return
		seenWorlds = account.worldsVersion
		if (mc.singleplayerServer == null || RealWorlds.read) RealWorlds.refresh { }
		val s = d.session ?: return
		d.async { d.cloud.world(s.worldId) }.thenAccept { w ->
			mc.execute {
				if (!w.stopWhenFree) toldStop -= w.id
				else if (w.createdBy != d.player() && toldStop.add(w.id)) {
					notice(tr("tandem.will_stop_syncing_after_this_session", w.nameOf(w.createdBy), w.name), tr("tandem.keep_playing_this_session_still_saves_then"), "clock")
				}
			}
		}
	}

	private fun buildHud(offline: Boolean): Window {
		Scale.update()
		// In game there's no pointer (J joins); on the title screen the toast takes clicks. Either way it stays out of Tab.
		Focus.push()
		val w = Window(ElementaVersion.V8)
		val real = realInvite
		if (real != null) RealFriends.sync()
		val later = { Button(tr("tandem.later"), Kind.OUTLINE, Size.TOAST, null, Tok.alpha(Tok.panel, 0.96)) { invite = null; realInvite = null } }
		hudToast = null
		if (real != null) hudToast = toast(
			tr("tandem.invited_you_to", real.host.displayName, real.worldName), tr("tandem.is_hosting_minecraft", real.host.displayName, real.minecraftVersion),
			Sample.friends.firstOrNull { it.userId == real.host.id },
			row(8, joinButton(), later()),
		) childOf w
		else if (invite != null) hudToast = toast(
			tr("tandem.sam_invited_you_to_create_nights"), tr("tandem.sam_is_hosting_with_noor"), Sample.person(tr("tandem.sam")),
			row(8, joinButton(), later()),
		) childOf w
		else notice?.let { (n, _) -> toast(n.first, n.second, null, null, n.third) childOf w }
			?: online?.let { (name, _) -> toast(tr("tandem.is_online", name), tr("tandem.in_the_menu"), Sample.friends.firstOrNull { it.name == name }, null) childOf w }
		if (offline) banner() childOf w
		Focus.pop()
		return w
	}

	/** Where Minecraft's toasts end, in GUI units: each uses a 32-unit slot from the top. */
	private fun vanillaToastsBottom(): Float =
		((Minecraft.getInstance().toasts as ToastComponentAccess).`tandem$occupiedSlots`().length() * 32).toFloat()

	/** The toast, top right. */
	private fun toast(title: String, detail: String, who: Person?, buttons: UIComponent?, icon: String? = null): UIComponent {
		val fill = Tok.alpha(Tok.panel, 0.96)
		// Below Minecraft's own toasts (advancements, recipes, tutorial hints), which draw over the HUD in this corner.
		val card = box(14, fill, Tok.line).constrain {
			x = 18.fromEnd; width = 350.at
			y = basicYConstraint { vanillaToastsBottom() + 18.tp }
		}
		if (who != null) Face({ who.skin }, 36, 8, null, Tok.panel).constrain { x = 17.at; y = 15.at } childOf card
		else if (icon != null) Icon(icon, 20, Tok.violet).constrain { x = (17 + 8).at; y = (15 + 8).at } childOf card
		val inner = 350 - 32 - 36 - 12 - 2
		val col = Column(0).constrain { x = (17 + 36 + 12).at; width = inner.at }
		col.constrain { y = basicYConstraint { card.getTop() + 15.tp + max(0f, (36.tp - col.getHeight()) / 2) } } childOf card
		col.add(wrapped(title, 14, Weight.SEMIBOLD, Tok.ink, inner.tp, 1.4f))
		col.add(wrapped(detail, 13, Weight.REGULAR, Tok.muted, inner.tp, 1.45f), 2)
		if (buttons != null) col.add(buttons, 10)
		card.constrain { height = basicHeightConstraint { max(36.tp, col.getHeight()) + 30.tp } }
		return card
	}

	/** Join, with the bound key on it: a key chip inside the violet button. */
	private fun joinButton(): UIComponent {
		val b = box(9, Tok.violet)
		val key = box(5, Tok.violet, Tok.alpha(Tok.violetInk, 0.35))
		val k = Label(TandemClient.JOIN.translatedKeyMessage.string, 12, Weight.SEMIBOLD, Tok.violetInk) childOf key
		k.constrain { x = 6.at; y = CenterConstraint() }
		key.constrain { width = basicWidthConstraint { k.getWidth() + 12.tp }; height = 18.at }
		val r = row(8, Label(tr("tandem.join_3"), 13, Weight.SEMIBOLD, Tok.violetInk), key).constrain { x = 14.at; y = CenterConstraint() } childOf b
		b.constrain { width = basicWidthConstraint { r.getWidth() + 28.tp }; height = 32.at }
		b.onMouseClick { it.stopPropagation(); joinInvite(Minecraft.getInstance()) }
		return b
	}

	/** The offline banner, top centre: amber edge, what still works. */
	private fun banner(): UIComponent {
		val b = box(12, Tok.alpha(Tok.panel, 0.94), Tok.alpha(Tok.warn, 0.45))
		val r = row(10, Icon("wifioff", 16, Tok.warn), Label(tr("tandem.offline_your_progress_is_saved_on_this"), 13))
			.constrain { x = 17.at; y = CenterConstraint() } childOf b
		b.constrain { x = CenterConstraint(); y = 18.at; width = basicWidthConstraint { r.getWidth() + 34.tp }; height = 40.at }
		return b
	}
}
