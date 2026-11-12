package app.keplersync.tandem.fabric.dev

import app.keplersync.tandem.fabric.TandemClient
import app.keplersync.tandem.fabric.account.Account as KsAccount
import app.keplersync.tandem.fabric.ui.Account
import app.keplersync.tandem.fabric.sync.WorldSync
import app.keplersync.tandem.fabric.ui.Sample
import app.keplersync.tandem.fabric.ui.Person
import app.keplersync.tandem.fabric.ui.World
import app.keplersync.tandem.fabric.ui.WorldState
import app.keplersync.tandem.core.Cloud
import app.keplersync.tandem.core.HostNeeds
import app.keplersync.tandem.fabric.play.HostFit
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen
import net.minecraft.client.gui.screens.worldselection.WorldSelectionList
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import app.keplersync.tandem.fabric.ui.Ingame
import app.keplersync.tandem.fabric.ui.PausePanel
import app.keplersync.tandem.fabric.ui.PlayScreen
import app.keplersync.tandem.fabric.ui.dialogTargets
import app.keplersync.tandem.fabric.ui.Layers
import app.keplersync.tandem.fabric.ui.RealHistory
import app.keplersync.tandem.fabric.ui.RealLeave
import app.keplersync.tandem.fabric.ui.RealStorage
import app.keplersync.tandem.fabric.ui.RealWorlds
import app.keplersync.tandem.fabric.ui.RealOperators
import app.keplersync.tandem.fabric.ui.Dialogs
import app.keplersync.tandem.fabric.ui.DialogScreen
import gg.essential.elementa.UIComponent
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.screen.v1.Screens
import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.client.MouseHandler
import net.minecraft.client.Screenshot
import net.minecraft.client.gui.components.AbstractButton
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.PauseScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.client.gui.components.toasts.SystemToast
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.contents.TranslatableContents
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen
import net.minecraft.world.Difficulty
import net.minecraft.world.level.GameRules
import net.minecraft.world.level.GameType
import net.minecraft.world.level.LevelSettings
import net.minecraft.world.level.WorldDataConfiguration
import net.minecraft.world.level.levelgen.WorldOptions
import net.minecraft.world.level.levelgen.presets.WorldPresets
import org.lwjgl.glfw.GLFW

/**
 * Dev only (`gradlew :fabric:runShots`): screenshots every Tandem screen at the window sizes and GUI scales players
 * use, then quits. Files land in run/screenshots, named by the size and scale the game actually used. The
 * pause panel needs a world, so it makes one called Sky Base in run/saves/tandem-shots the first time.
 */
object Shots {
	/** Friends by their KeplerSync ids, as worlds are shared now; a dev cloud's players are their names. */
	private fun ids(vararg names: String) = names.map { n -> Sample.friends.firstOrNull { it.name == n }?.userId ?: n }

	/** A step returns false to be tried again next tick (waiting for something), true when it's done. */
	private val steps = ArrayDeque<(Minecraft) -> Boolean>()
	private var wait = 0
	private var started = false
	private var waited = 0

	private fun step(action: (Minecraft) -> Unit) {
		steps += { action(it); true }
	}

	private fun size(w: Int, h: Int, gui: Int) {
		step { GLFW.glfwSetWindowSize(it.window.window, w, h) }
		step { it.options.guiScale().set(gui); it.resizeDisplay() }
	}

	// Through the keyboard handler, like a real key press, so Fabric's screen keyboard events see it too.
	private fun press(mc: Minecraft, key: Int) {
		mc.keyboardHandler.keyPress(mc.window.window, key, 0, GLFW.GLFW_PRESS, 0)
		mc.keyboardHandler.keyPress(mc.window.window, key, 0, GLFW.GLFW_RELEASE, 0)
	}

	private fun sleep(ticks: Int) {
		var n = 0
		steps += { ++n > ticks }
	}

	/** Back at the title screen, or at the popup over it that an upload after leaving shows. */
	private fun atTitle(mc: Minecraft) = mc.screen is TitleScreen || mc.screen is DialogScreen

	private fun keys(vararg k: Int) = step { mc -> k.forEach { press(mc, it) } }

	// The mouse handler's own callbacks (private, so by reflection; dev runs use Mojang's names), so clicks take the
	// same path a real mouse does, Fabric's screen events included.
	private fun mouse(name: String, vararg types: Class<*>) =
		MouseHandler::class.java.getDeclaredMethod(name, *types).apply { isAccessible = true }

	private fun moveTo(mc: Minecraft, c: UIComponent) {
		val g = mc.window.guiScale
		mouse("onMove", Long::class.java, Double::class.java, Double::class.java)
			.invoke(mc.mouseHandler, mc.window.window, (c.getLeft() + c.getRight()) / 2.0 * g, (c.getTop() + c.getBottom()) / 2.0 * g)
	}

	private fun click(mc: Minecraft, c: UIComponent) {
		moveTo(mc, c)
		val press = mouse("onPress", Long::class.java, Int::class.java, Int::class.java, Int::class.java)
		press.invoke(mc.mouseHandler, mc.window.window, 0, GLFW.GLFW_PRESS, 0)
		press.invoke(mc.mouseHandler, mc.window.window, 0, GLFW.GLFW_RELEASE, 0)
	}

	private fun scroll(mc: Minecraft, c: UIComponent, amount: Double) {
		moveTo(mc, c)
		mouse("onScroll", Long::class.java, Double::class.java, Double::class.java).invoke(mc.mouseHandler, mc.window.window, 0.0, amount)
	}

	private fun clickWidget(mc: Minecraft, w: AbstractWidget) {
		val g = mc.window.guiScale
		mouse("onMove", Long::class.java, Double::class.java, Double::class.java).invoke(mc.mouseHandler, mc.window.window, (w.x + w.width / 2.0) * g, (w.y + w.height / 2.0) * g)
		val press = mouse("onPress", Long::class.java, Int::class.java, Int::class.java, Int::class.java)
		press.invoke(mc.mouseHandler, mc.window.window, 0, GLFW.GLFW_PRESS, 0)
		press.invoke(mc.mouseHandler, mc.window.window, 0, GLFW.GLFW_RELEASE, 0)
	}

	private fun scrollAt(mc: Minecraft, x: Double, y: Double, amount: Double) {
		val g = mc.window.guiScale
		mouse("onMove", Long::class.java, Double::class.java, Double::class.java).invoke(mc.mouseHandler, mc.window.window, x * g, y * g)
		mouse("onScroll", Long::class.java, Double::class.java, Double::class.java).invoke(mc.mouseHandler, mc.window.window, 0.0, amount)
	}

	private fun target(mc: Minecraft, name: String) = (mc.screen as PlayScreen).devTargets.getValue(name)

	/** True once [name] is fully inside the worlds list; until then it scrolls one notch towards it (earlier suites leave many worlds). */
	private fun inView(mc: Minecraft, name: String): Boolean {
		val list = target(mc, "worlds"); val c = target(mc, name)
		if (c.getTop() >= list.getTop() && c.getBottom() <= list.getBottom()) return true
		scroll(mc, list, if (c.getTop() < list.getTop()) 1.0 else -1.0)
		return false
	}

	// The screen builds once more on its first frame, which would close a menu opened straight away.
	private fun play(what: String? = null) {
		step { it.setScreen(PlayScreen()) }
		if (what != null) step { (it.screen as PlayScreen).dev(what) }
	}

	fun start() {
		// run/options.txt is shared with runClient, so the GUI scale and the Open Tandem key go back at the end.
		var gui = 0
		var key = TandemClient.OPEN.saveString()
		step { gui = it.options.guiScale().get(); key = TandemClient.OPEN.saveString() }
		// Only the Synced worlds tab: -Ptandem.shotsOnly=synced.
		if (System.getProperty("tandem.shotsOnly") == "synced") {
			synced()
			step { it.options.guiScale().set(gui); it.options.save(); it.stop() }
			ClientTickEvents.END_CLIENT_TICK.register(::tick)
			return
		}

		// The long Synced worlds list, left open to click through by hand: -Ptandem.shotsOnly=many.
		if (System.getProperty("tandem.shotsOnly") == "many") {
			play("synced-many")
			ClientTickEvents.END_CLIENT_TICK.register(::tick)
			return
		}

		// The Open Tandem key on the title screen opens Tandem, and pressing it again closes it.
		step { TandemClient.OPEN.setKey(InputConstants.getKey("key.keyboard.k")); KeyMapping.resetMapping() }
		step { press(it, GLFW.GLFW_KEY_K) }
		step { TandemClient.LOG.info("Open Tandem key on the title screen: {}", if (it.screen is PlayScreen) "opened Tandem" else "FAILED") }
		step { press(it, GLFW.GLFW_KEY_K) }
		step { TandemClient.LOG.info("Open Tandem key in Tandem: {}", if (it.screen is TitleScreen) "closed Tandem" else "FAILED") }
		step { TandemClient.OPEN.setKey(InputConstants.getKey(key)); KeyMapping.resetMapping() }

		for ((w, h) in listOf(1366 to 768, 1920 to 1080, 2560 to 1440)) for (g in 2..4) {
			size(w, h, g)
			step { it.setScreen(TitleScreen()) }
			step { shot(it, "title") }
			play()
			step { shot(it, "play") }
		}
		for ((w, h, g) in listOf(Triple(1366, 768, 2), Triple(1920, 1080, 3), Triple(2560, 1440, 4))) {
			size(w, h, g)
			for (d in listOf("get", "wait", "mods", "mods-version", "friend-Sam", "friend-Riley")) {
				play(d)
				step { shot(it, d) }
			}
			// Pick Sam's session from the keyboard, so the ring and the named button both show.
			play("versions")
			keys(GLFW.GLFW_KEY_TAB, GLFW.GLFW_KEY_SPACE)
			step { shot(it, "versions") }
			// Tab past the three count tabs to the search field, then type.
			play("mods-big")
			keys(GLFW.GLFW_KEY_TAB, GLFW.GLFW_KEY_TAB, GLFW.GLFW_KEY_TAB, GLFW.GLFW_KEY_TAB, GLFW.GLFW_KEY_SPACE)
			step { mc -> "better".forEach { mc.screen!!.charTyped(it, 0) } }
			step { shot(it, "mods-big") }
		}
		// Real mouse input: a click on a world's button, a click outside the dialog, a friend, and the wheel.
		size(1920, 1080, 3)
		play()
		step { click(it, target(it, "button-Old Survival")) }
		step { shot(it, "mouse-versions") }
		keys(GLFW.GLFW_KEY_ESCAPE)
		step { click(it, target(it, "friend-Sam")) }
		step { shot(it, "mouse-friend") }
		// The pointer over the menu, where Noor's row is underneath: only the menu item lights up.
		step { moveTo(it, target(it, "friend-Noor")) }
		sleep(5)
		step { shot(it, "mouse-menu-over") }
		keys(GLFW.GLFW_KEY_ESCAPE)
		step { scroll(it, target(it, "worlds"), -3.0) }
		step { shot(it, "mouse-scroll") }

		for ((w, h, g) in listOf(Triple(1366, 768, 2), Triple(1920, 1080, 3), Triple(2560, 1440, 4))) {
			size(w, h, g)
			for (d in listOf("storage", "settings", "details-Sky Base/over", "details-Sky Base/hist", "details-Sky Base/mem", "details-Old Survival/set")) {
				play(d)
				step { shot(it, d.replace("/", "-").replace(" ", "")) }
			}
			// Stopped worlds with Delete now; a mods list with real ids and icons; a one-line and a long toast.
			play("storage-stopped")
			step { shot(it, "storage-stopped") }
			step { app.keplersync.tandem.fabric.ui.Pages.devStorage = null }
			play("mods-real")
			sleep(60)
			step { shot(it, "mods-real") }
			play("toast-short")
			step { shot(it, "toast-short") }
			play("toast-long")
			step { shot(it, "toast-long") }
		}
		size(1920, 1080, 3)
		play("settings")
		step { scrollAt(it, it.window.guiScaledWidth / 2.0, it.window.guiScaledHeight / 2.0, -20.0) }
		sleep(20)
		step { shot(it, "settings-bottom") }
		// Rebuilt (as when a friend comes online): it stays where it was.
		step { (it.screen as PlayScreen).dev("rebuild") }
		sleep(10)
		step { shot(it, "settings-bottom-rebuilt") }
		for (d in listOf("sync", "plans", "r2", "report", "code", "add", "full", "stop", "remove", "slow", "disk", "waitquit", "handed", "freeing", "asking", "askedyou", "unreachable")) {
			play(d)
			step { shot(it, "d3-$d") }
		}

		step { it.setScreen(KeyBindsScreen(TitleScreen(), it.options)) }
		step { it.screen!!.mouseScrolled(it.window.guiScaledWidth / 2.0, it.window.guiScaledHeight / 2.0, 0.0, -1000.0) }
		step { shot(it, "keybinds") }

		step { openWorld(it) }
		steps += { mc -> mc.player != null && mc.screen == null || ++waited > 1200 }
		// One of Minecraft's toasts first: Tandem's goes below it, not under it.
		step { SystemToast.add(it.toasts, SystemToast.SystemToastId.PERIODIC_NOTIFICATION, Component.literal("A Minecraft toast"), Component.literal("Tandem's goes below it")) }
		step { Ingame.dev("invite") }
		step { shot(it, "hud-invite") }
		keys(GLFW.GLFW_KEY_J)
		step { shot(it, "hud-invite-joined") }
		keys(GLFW.GLFW_KEY_ESCAPE)
		step { it.setScreen(null) }
		step { Ingame.dev("offline") }
		step { shot(it, "hud-offline") }
		step { Ingame.dev("offline") }
		step { Ingame.dev("stopped") }
		step { shot(it, "d3-stopped") }
		keys(GLFW.GLFW_KEY_ESCAPE)
		step { it.setScreen(null) }
		step { it.pauseGame(false) }
		step { shot(it, "pause-host") }
		// Real mouse input on the panel: open the invite list and invite Jules, who joins after 3 seconds.
		step { click(it, panel("invite")) }
		step { shot(it, "pause-inviting") }
		step { click(it, panel("invite-Jules")) }
		sleep(80)
		step { PausePanel.devJoin("Noor"); reinit(it) }
		step { shot(it, "pause-players") }
		// The … opens Jules's menu; clicking it again closes it.
		step { click(it, panel("more-Jules")) }
		step { shot(it, "pause-player-menu") }
		step { click(it, panel("more-Jules")) }
		step { shot(it, "pause-menu-closed") }
		// Jules owns it and you host: Jules is marked, and their menu has no Kick.
		step { PausePanel.devOwner = "Jules"; reinit(it) }
		step { click(it, panel("more-Jules")) }
		step { shot(it, "pause-host-owner-menu") }
		step { click(it, panel("more-Jules")) }
		step { PausePanel.devOwner = null; reinit(it) }
		// Hide to the pill, then open it again.
		step { click(it, panel("hide")) }
		step { shot(it, "pause-hidden") }
		step { click(it, panel("show")) }
		step { PausePanel.devMode = PausePanel.Mode.GUEST; reinit(it) }
		step { shot(it, "pause-guest") }
		// Through the relay with a long name: the line wraps instead of running under the button.
		step { PausePanel.devLongLine = true; reinit(it) }
		step { shot(it, "pause-guest-relay") }
		// You own it and Sam hosts: you're the one marked.
		step { PausePanel.devOwner = "You"; reinit(it) }
		step { shot(it, "pause-guest-owner") }
		step { PausePanel.devLongLine = false; PausePanel.devOwner = null }
		step { PausePanel.devMode = PausePanel.Mode.JUST_PLAY; reinit(it) }
		step { shot(it, "pause-just-play") }
		step { PausePanel.devMode = null; reinit(it) }
		for ((w, h, g) in listOf(Triple(1366, 768, 2), Triple(1366, 768, 3), Triple(2560, 1440, 4))) {
			size(w, h, g)
			step { reinit(it) }
			step { shot(it, "pause-host") }
		}
		// A small window (1256 x 936 at GUI 3): no room beside Minecraft's buttons for the invite list,
		// so the card opens over the screen; Done puts it back.
		size(1256, 936, 3)
		step { reinit(it) }
		step { shot(it, "pause-small") }
		step { click(it, panel("invite")) }
		step { shot(it, "pause-small-inviting") }
		step { click(it, panel("invite")) }
		step { shot(it, "pause-small-done") }
		// A busy pack: other mods' buttons in the top-right corner and beside Singleplayer. Tandem moves, nothing overlaps.
		size(1920, 1080, 3)
		step { busy = true; reinit(it) }
		step { shot(it, "busy-pause") }
		step { busy = false; PausePanel.devJoin("Jules"); reinit(it) }
		step { click(it, panel("handoff")) }
		step { shot(it, "d3-leave") }
		keys(GLFW.GLFW_KEY_ESCAPE)
		// Five, then ten: the list scrolls past five. Sam's world lagged when he hosted it, so he goes last.
		step { Sample.person("Sam").hostProblem = HostNeeds.Problem.LAGGED; listOf("Sam", "Alex", "Riley").forEach(PausePanel::devJoin); reinit(it) }
		step { click(it, panel("handoff")) }
		step { shot(it, "d3-leave-5") }
		keys(GLFW.GLFW_KEY_ESCAPE)
		step {
			listOf("Kai", "Mo").forEach(PausePanel::devJoin)
			listOf("xX_EnderWalker_Xx" to 3, "MossyBoots" to 1, "Ivy" to 2).forEachIndexed { i, (n, b) -> PausePanel.devJoin(Person(n, 1 + 2 * i, true, "", bars = b)) }
			reinit(it)
		}
		step { click(it, panel("handoff")) }
		step { shot(it, "d3-leave-10") }
		keys(GLFW.GLFW_KEY_ESCAPE)
		step { Sample.person("Sam").hostProblem = null }
		step { PausePanel.devMode = PausePanel.Mode.JUST_PLAY; reinit(it) }
		step { PausePanel.devJoin("Jules"); reinit(it) }
		step { clickWidget(it, PausePanel.devQuit!!) }
		step { shot(it, "d3-leave-just") }
		keys(GLFW.GLFW_KEY_ESCAPE)
		step { click(it, panel("second")) }
		step { shot(it, "d3-sync-pause") }
		// The wheel over the friend list, through Minecraft's own mouse handler: the list scrolls on the pause screen too.
		step { scrollAt(it, it.window.guiScaledWidth / 2.0, it.window.guiScaledHeight / 2.0 - 30, -3.0) }
		sleep(10)
		step { shot(it, "d3-sync-pause-scrolled") }
		keys(GLFW.GLFW_KEY_ESCAPE)
		step { PausePanel.devMode = null; reinit(it) }
		step { clickWidget(it, PausePanel.devQuit!!) }
		// Long enough to shoot the popup, the menu, the Tandem screen and quitting while it uploads, done before busy-title.
		step { Sample.background = Sample.background?.let { (w, to, _) -> Triple(w, to, app.keplersync.tandem.fabric.ui.Timed(0.3f, 12f)) } }
		// Leaving says what's uploading, over the title screen; Escape hides it.
		steps += { it.screen is DialogScreen }
		sleep(10)
		step { shot(it, "d3-leaving") }
		keys(GLFW.GLFW_KEY_ESCAPE)
		steps += { it.screen is TitleScreen }
		sleep(5)
		step { shot(it, "title-uploading") }
		step { it.setScreen(PlayScreen()) }
		sleep(10)
		step { shot(it, "play-uploading") }
		keys(GLFW.GLFW_KEY_ESCAPE)
		steps += { it.screen is TitleScreen }
		step { mc -> clickWidget(mc, Screens.getButtons(mc.screen!!).first { (it.message.contents as? TranslatableContents)?.key == "menu.quit" }) }
		step { shot(it, "d3-quitting") }
		keys(GLFW.GLFW_KEY_ESCAPE)
		steps += { it.screen is TitleScreen }
		// Closing the window (its X, Alt+F4) asks the same, and Minecraft stays open.
		step { GLFW.glfwSetWindowShouldClose(it.window.window, true) }
		sleep(5)
		step { shot(it, "d3-quitting-window") }
		keys(GLFW.GLFW_KEY_ESCAPE)
		sleep(140)
		step { busy = true; reinit(it) }
		step { shot(it, "busy-title") }
		step { busy = false }

		// The sizes players really use: Minecraft's own 854 x 480 window (GUI 1 and Auto's 2), 720p, 900p, and Auto at
		// 1080p (4) and 1440p (6).
		for ((w, h, g) in listOf(Triple(854, 480, 2), Triple(854, 480, 1), Triple(1280, 720, 2), Triple(1280, 720, 3), Triple(1600, 900, 3),
				Triple(1920, 1080, 4), Triple(2560, 1440, 6))) {
			size(w, h, g)
			step { it.setScreen(TitleScreen()) }
			step { shot(it, "sizes-title") }
			for (d in listOf<String?>(null, "mods", "settings", "storage", "sync", "versions", "details-Sky Base/hist")) {
				play(d)
				step { shot(it, "sizes-" + (d ?: "play").replace("/", "-").replace(" ", "")) }
			}
		}
		synced()
		// Names from anywhere: other scripts, emoji, a styled Discord name, and ones far too long for their row.
		size(1920, 1080, 3)
		step { stressNames() }
		play()
		step { shot(it, "names-play") }
		step { scrollAt(it, it.window.guiScaledWidth * 0.82, it.window.guiScaledHeight * 0.6, -30.0) }
		sleep(20)
		step { shot(it, "names-play-friends-scrolled") }
		play()
		step { click(it, target(it, "friend-$LONG_FRIEND")) }
		step { shot(it, "names-friend-menu") }
		keys(GLFW.GLFW_KEY_ESCAPE)
		for (d in listOf("details-$INTL_WORLD/over", "details-$INTL_WORLD/mem", "details-$LONG_WORLD/over", "sync", "mods")) {
			play(d)
			step { shot(it, "names-" + d.substringAfter('/').let { s -> if (d.startsWith("details-$LONG_WORLD")) "long-$s" else s }) }
		}
		play("toast-names")
		step { shot(it, "names-toast") }
		size(854, 480, 2)
		play()
		step { shot(it, "names-play-small") }
		size(1920, 1080, 3)
		step { waited = 0; openWorld(it, "tandem-shots-intl", INTL_WORLD) }
		steps += { mc -> mc.player != null && mc.screen == null || ++waited > 1200 }
		step { it.pauseGame(false) }
		step { listOf(LONG_FRIEND, "李小龙", "محمد").forEach(PausePanel::devJoin); reinit(it) }
		step { shot(it, "names-pause") }
		step { click(it, panel("handoff")) }
		step { shot(it, "names-handoff") }
		keys(GLFW.GLFW_KEY_ESCAPE)
		step { click(it, panel("invite")) }
		step { shot(it, "names-inviting") }
		step { click(it, panel("invite")) }
		size(854, 480, 2)
		step { reinit(it) }
		step { shot(it, "names-pause-small") }
		size(1920, 1080, 3)
		step { it.level?.disconnect(); it.disconnect(TitleScreen()) }
		steps += { it.screen is TitleScreen }
		step { it.options.guiScale().set(gui); it.options.save(); it.stop() }
		ClientTickEvents.END_CLIENT_TICK.register(::tick)
		ScreenEvents.AFTER_INIT.register { _, screen, width, _ -> if (busy && (screen is TitleScreen || screen is PauseScreen)) crowd(screen, width) }
	}

	/**
	 * Dev only (`gradlew :fabric:runCheckA`, then `runCheckB`, then `runCheckA` again): passes one world between two
	 * games through the dev cloud, with checks in the log ("Sync check"). Alex makes Shared Base, syncs it, plays
	 * through a checkpoint and quits; Sam gets it, finds Alex's blocks, adds one and quits; Alex opens it from
	 * Minecraft's own Singleplayer list, which goes through Tandem, and finds Sam's block.
	 */
	/**
	 * Dev only (`runFriendB`, `runFriendA`, then both together): two games against
	 * a local KeplerSync. First Sam signs in and leaves his Minecraft name in the dev cloud folder, then Alex sends him a
	 * request. Then Sam accepts it with the real button and opens his world; Alex starts, and Sam gets the "came
	 * online" toast; Alex opens Shared Base and Sam's list says so. Steps meet through marker files, and each logs
	 * "Friend check, <name>: OK …".
	 */
	/**
	 * Dev only (`runLeaseA` and `runLeaseB` together, with a script that stops and starts the server at the marks):
	 * the lease cases through KeplerSync. Wait: Alex is saving, Sam presses Play when ready and the world opens by
	 * itself once Alex is done. A crash: Sam's game dies while hosting, and Alex can play once the lease runs out.
	 * Offline: the server stops while Alex plays, play goes on, and the session uploads once it's back.
	 * Unreachable: with the server stopped, synced worlds show but can't be opened.
	 */
	fun leaseCheck() {
		val a = KsAccount.get() ?: error("The lease check needs -Dtandem.server")
		val d = WorldSync.get() ?: error("The lease check needs worlds to sync")
		val marks = java.nio.file.Paths.get(System.getProperty("tandem.checkdir") ?: error("The lease check needs -Dtandem.checkdir"))
		java.nio.file.Files.createDirectories(marks)
		fun mark(name: String) = java.nio.file.Files.writeString(marks.resolve(name), "")
		fun marked(name: String) = java.nio.file.Files.exists(marks.resolve(name))
		fun shared() = Sample.worlds.firstOrNull { it.name == "Shared Base" && it.cloudId != null }
		steps += { a.signedIn() }
		size(1920, 1080, 3)
		play()
		steps += { shared() != null }
		step { mc ->
			val me = a.me.displayName
			fun check(what: String, ok: Boolean) = TandemClient.LOG.info("Lease check, {}: {} {}", me, if (ok) "OK" else "FAILED", what)
			val id = shared()!!.cloudId!!
			fun waitFor(state: WorldState, ticks: Int) {
				var n = 0
				steps += { shared()?.state == state || ++n > ticks }
			}
			if (me == "Sam") {
				// Alex is saving; Play when ready waits, and opens the world by itself.
				steps += { marked("alex-uploading") }
				waitFor(WorldState.SAVING, 400)
				step { check("sees Alex saving", shared()?.state == WorldState.SAVING) }
				steps += { inView(it, "button-Shared Base") }
				step { click(it, target(it, "button-Shared Base")) }
				sleep(30)
				step { shot(it, "lease-b-waiting") }
				step { mark("sam-waiting") }
				var n = 0
				steps += { it.player != null && it.screen == null || ++n > 2400 }
				step { check("the world opened by itself once Alex was done", it.player != null) }
				step { mark("sam-hosting") }
				// the game dies while hosting, without saving or letting go of the lease.
				sleep(100)
				step { TandemClient.LOG.info("Lease check, Sam: crashing on purpose"); Runtime.getRuntime().halt(1) }
			} else {
				step { d.async { d.cloud.takeLease(id, me, Cloud.UPLOADING); d.cloud.heartbeat(id, me, Cloud.UPLOADING, 0.3f) }.join() }
				step { mark("alex-uploading") }
				steps += { marked("sam-waiting") }
				step { d.async { d.cloud.heartbeat(id, me, Cloud.UPLOADING, 0.7f) }.join() }
				sleep(100)
				step { d.async { d.cloud.releaseLease(id, me) }.join() }
				steps += { marked("sam-hosting") }
				sleep(40)
				step { it.setScreen(null) }
				play()
				// What the list and KeplerSync say every 2 s while waiting, so a miss shows why.
				var polls = 0
				steps += {
					if (polls++ % 40 == 0) {
						val l = runCatching { d.cloud.lease(id) }.getOrNull()
						TandemClient.LOG.info("Lease check, Alex: list says {} ({}); KeplerSync's lease: {}", shared()?.state, shared()?.who,
							l?.let { x -> "${x.holderName} ${x.state}, ${(x.expires - System.currentTimeMillis()) / 1000} s left" } ?: "none")
					}
					shared()?.state == WorldState.HOSTED || polls > 600
				}
				step { check("sees Sam hosting", shared()?.state == WorldState.HOSTED) }
				step { shot(it, "lease-a-sam-hosting") }
				// Sam's game is gone now; his lease runs out within 90 seconds.
				waitFor(WorldState.READY, 3600)
				step { check("Shared Base is free again after Sam's game stopped", shared()?.state == WorldState.READY) }
				steps += { inView(it, "button-Shared Base") }
				step { click(it, target(it, "button-Shared Base")) }
				var n = 0
				steps += { it.player != null && it.screen == null || ++n > 2400 }
				step { check("took over Shared Base", it.player != null) }
				// the server stops while Alex plays.
				step { mark("alex-in-world") }
				steps += { d.offline || marked("server-back") }
				step { check("kept playing, saving on this PC, while KeplerSync was down", d.offline) }
				step { it.pauseGame(false) }
				sleep(10)
				step { shot(it, "lease-a-offline") }
				step { it.setScreen(null) }
				step { mark("alex-offline") }
				steps += { !d.offline }
				step { check("back online by itself", true) }
				step { it.level?.disconnect(); it.disconnect(TitleScreen()) }
				steps += { atTitle(it) }
				steps += { d.background?.done() == true }
				step { check("the session uploaded once KeplerSync was back", d.background?.future?.isCompletedExceptionally == false) }
				// KeplerSync can't be reached at all.
				step { mark("alex-left") }
				steps += { marked("server-down-again") }
				play()
				steps += { !Sample.reachable }
				sleep(20)
				step { check("shows KeplerSync can't be reached", !Sample.reachable) }
				step { check("Shared Base can't be opened", (target(it, "button-Shared Base") as? app.keplersync.tandem.fabric.ui.Button)?.enabled == false) }
				step { shot(it, "lease-a-unreachable") }
				step { mark("alex-done") }
			}
			step { it.stop() }
		}
		ClientTickEvents.END_CLIENT_TICK.register(::tick)
	}

	fun friendCheck() {
		val a = KsAccount.get() ?: error("The friend check needs -Dtandem.server")
		val marks = java.nio.file.Paths.get(System.getProperty("tandem.checkdir") ?: error("The friend check needs -Dtandem.checkdir"))
		java.nio.file.Files.createDirectories(marks)
		fun mark(name: String) = java.nio.file.Files.writeString(marks.resolve(name), "")
		fun marked(name: String) = java.nio.file.Files.exists(marks.resolve(name))
		steps += { a.signedIn() }
		step { mc ->
			val me = a.me.displayName
			fun check(what: String, ok: Boolean) = TandemClient.LOG.info("Friend check, {}: {} {}", me, if (ok) "OK" else "FAILED", what)
			check("signed in to KeplerSync", true)
			val friends = a.friends.any { it.user.displayName != me }
			val asked = a.requests.any { it.direction == "incoming" }
			size(1920, 1080, 3)
			when {
				me == "Sam" && !friends && !asked -> step {
					java.nio.file.Files.writeString(marks.resolve("Sam.name"), a.me.username)
					check("left his Minecraft name", true)
				}
				me == "Alex" && !friends -> step {
					val name = java.nio.file.Files.readString(marks.resolve("Sam.name"))
					a.run { api -> api.addFriend(name) }.join()
					check("sent Sam a friend request", true)
				}
				// Already friends: what changes behind an open dialog shows once it closes.
				me == "Sam" && friends -> {
					play()
					sleep(20)
					step { (it.screen as PlayScreen).dev("add") }
					step { mark("sam-in-world") }
					steps += { marked("alex-playing") }
					sleep(100)
					step { check("still showing the dialog", (it.screen as PlayScreen).devBusy()) }
					keys(GLFW.GLFW_KEY_ESCAPE)
					sleep(10)
					step { check("sees Alex playing Shared Base once the dialog closed", Sample.friends.any { f -> f.name == "Alex" && f.presence == "Playing Shared Base" }) }
					step { shot(it, "friends-b-after-dialog") }
					step { mark("sam-done") }
				}
				me == "Sam" -> {
					step { Account.friendsOnline = true }
					play()
					steps += { Sample.requests.any { it.name == "Alex" } }
					step { shot(it, "friends-b-request") }
					step { click(it, target(it, "accept-Alex")) }
					steps += { Sample.friends.any { it.name == "Alex" } }
					step { check("accepted Alex's request with the button", true) }
					step { shot(it, "friends-b-accepted") }
					step { it.setScreen(null); openWorld(it, "Shared Base", "Shared Base") }
					steps += { it.player != null && it.screen == null }
					step { mark("sam-in-world") }
					// Alex starts now: his coming online is the toast.
					steps += { Ingame.devOnline() == "Alex" || marked("alex-playing") }
					step { check("got the toast when Alex came online", Ingame.devOnline() == "Alex") }
					step { shot(it, "friends-b-toast") }
					steps += { marked("alex-playing") }
					sleep(60)
					play()
					sleep(20)
					step { check("sees Alex playing Shared Base", Sample.friends.any { f -> f.name == "Alex" && f.presence == "Playing Shared Base" }) }
					step { shot(it, "friends-b-playing") }
					// The server restarts now; both reconnect by themselves.
					steps += { marked("restarted") }
					sleep(400)
					step { mark("sam-done") }
				}
				else -> {
					step { openWorld(it, "tandem-sync", "Shared Base") }
					steps += { it.player != null && it.screen == null }
					step { mark("alex-playing") }
					steps += { marked("sam-done") }
				}
			}
			step { it.stop() }
		}
		ClientTickEvents.END_CLIENT_TICK.register(::tick)
	}

	/**
	 * Just play, both games at once (playcheck.sh). Direct round: Alex opens Play Base to friends with its settings and
	 * invites Sam, withdraws the invite (Sam's toast goes), invites again, and Sam joins with J from his own world.
	 * Relay round (-Dtandem.forceRelay): Sam waits at the title screen and joins with J there; Alex kicks him, KeplerSync
	 * refuses his next join and Alex's game turns away his direct connection; then Alex invites him again and he comes
	 * back through the relay, Alex's game answering with a fresh ticket (-Dtandem.ticketReuseMs=0).
	 */
	fun playCheck() {
		val a = KsAccount.get() ?: error("The play check needs -Dtandem.server")
		val marks = java.nio.file.Paths.get(System.getProperty("tandem.checkdir") ?: error("The play check needs -Dtandem.checkdir"))
		java.nio.file.Files.createDirectories(marks)
		fun mark(name: String) = java.nio.file.Files.writeString(marks.resolve(name), "")
		fun marked(name: String) = java.nio.file.Files.exists(marks.resolve(name))
		val relay = java.lang.Boolean.getBoolean("tandem.forceRelay") || System.getProperty("tandem.playround") == "relay"
		// Punched through over UDP, with direct TCP and the relay both ruled out (-Dtandem.forcePunch on Sam).
		val punch = java.lang.Boolean.getBoolean("tandem.forcePunch") || System.getProperty("tandem.playround") == "punch"
		steps += { a.signedIn() && a.friends.isNotEmpty() }
		size(1920, 1080, 3)
		// Two windows at once: whichever isn't focused would pause itself.
		step { it.options.pauseOnLostFocus = false }
		step { mc ->
			val me = a.me.displayName
			fun check(what: String, ok: Boolean) = TandemClient.LOG.info("Play check, {}: {} {}", me, if (ok) "OK" else "FAILED", what)
			fun others() = mc.connection?.onlinePlayers?.count { it.profile.id != mc.user.profileId } ?: 0
			fun waitToast() {
				var n = 0
				steps += { Ingame.devInvite() != null || ++n > 1200 }
			}
			fun joined() {
				var n = 0
				steps += { it.singleplayerServer == null && it.player != null && others() > 0 || ++n > 1200 }
			}
			if (me == "Sam") {
				// Invites from an earlier round's game are stale.
				step { a.invited.clear() }
				if (!relay) {
					step { openWorld(it, "tandem-play-b", "Sam's Base") }
					steps += { it.player != null && it.screen == null }
					step { mark("sam-ready") }
					waitToast()
					step { check("got Alex's invite toast in his own world", Ingame.devInvite() == "Play Base"); mark("sam-saw-invite") }
					steps += { marked("alex-withdrew") }
					var n = 0
					steps += { Ingame.devInvite() == null || ++n > 400 }
					step { check("the withdrawn invite's toast went away", Ingame.devInvite() == null); mark("sam-saw-withdraw") }
					waitToast()
					step { shot(it, "play-b-invite") }
					keys(GLFW.GLFW_KEY_J)
				} else {
					step { mark("sam-ready") }
					waitToast()
					step { check("got Alex's invite toast at the title screen", it.screen is TitleScreen && Ingame.devInvite() == "Play Base") }
					step { shot(it, "play-b-invite-title") }
					keys(GLFW.GLFW_KEY_J)
					sleep(2)
					step { shot(it, "play-b-joining") }
				}
				joined()
				step {
					val t = app.keplersync.tandem.fabric.play.Joining.current
					check("joined Alex's world with J", it.singleplayerServer == null && it.player != null)
					val way = if (relay) "relay" else if (punch) "punched" else "lan"
					check("came in by way of $way", t != null && t.via == way)
				}
				sleep(40)
				step { it.pauseGame(false) }
				sleep(20)
				step { shot(it, "play-b-guest") }
				step { it.setScreen(null); mark("sam-joined") }
				if (!relay) {
					steps += { marked("alex-saw-sam") }
					step { it.level?.disconnect(); it.disconnect(TitleScreen()) }
				} else {
					var n = 0
					steps += { it.screen is net.minecraft.client.gui.screens.DisconnectedScreen || ++n > 1200 }
					step { check("was kicked", it.screen is net.minecraft.client.gui.screens.DisconnectedScreen) }
					step {
						val t = app.keplersync.tandem.fabric.play.Joining.current!!
						val refused = try { a.api.join(t.info.sessionId); false } catch (e: java.io.IOException) { e is app.keplersync.tandem.core.KeplerSyncApi.Refused }
						check("KeplerSync refuses a join without the invite", refused)
						// Straight to Alex's port, past KeplerSync: Alex's game turns the uninvited away.
						val direct = t.info.direct.first { d -> d.startsWith("127.0.0.1:") }
						val data = net.minecraft.client.multiplayer.ServerData("Alex", direct, net.minecraft.client.multiplayer.ServerData.Type.OTHER)
						net.minecraft.client.gui.screens.ConnectScreen.startConnecting(TitleScreen(), it, net.minecraft.client.multiplayer.resolver.ServerAddress.parseString(direct), data, false, null)
					}
					n = 0
					steps += { it.screen is net.minecraft.client.gui.screens.DisconnectedScreen || ++n > 1200 }
					sleep(10)
					step {
						val text = it.screen?.let { s -> Screens.getButtons(s).joinToString { b -> b.message.string } + " " + s.narrationMessage.string } ?: ""
						check("Alex's game turned away the uninvited join", "invite" in text)
						shot(it, "play-b-turned-away")
					}
					// Invited again: back in through the relay, minutes after Alex's game first connected to it.
					step { it.setScreen(TitleScreen()); mark("sam-turned-away") }
					waitToast()
					keys(GLFW.GLFW_KEY_J)
					joined()
					step { check("came back through the relay after a new invite", it.singleplayerServer == null && others() > 0) }
					step { it.level?.disconnect(); it.disconnect(TitleScreen()) }
				}
				step { mark("sam-done") }
			} else {
				fun inviteSam() {
					step { it.pauseGame(false) }
					sleep(10)
					step { click(it, PausePanel.devTargets.getValue("invite")) }
					sleep(10)
					step { click(it, PausePanel.devTargets.getValue("invite-Sam")) }
				}
				step { openWorld(it, "tandem-play-a", "Play Base") }
				steps += { it.player != null && it.screen == null }
				steps += { marked("sam-ready") }
				sleep(20)
				inviteSam()
				// The first invite asks how to open the world, as Open to LAN does.
				sleep(10)
				step { shot(it, "play-a-open-to-friends") }
				step { click(it, dialogTargets.getValue("open-to-friends")) }
				var n = 0
				steps += { app.keplersync.tandem.fabric.play.Hosting.get()?.invited?.isNotEmpty() == true || ++n > 600 }
				step { check("opened the world and invited Sam", app.keplersync.tandem.fabric.play.Hosting.get() != null) }
				step { shot(it, "play-a-invited") }
				if (!relay) {
					steps += { marked("sam-saw-invite") }
					step { click(it, PausePanel.devTargets.getValue("uninvite-Sam")) }
					n = 0
					steps += { app.keplersync.tandem.fabric.play.Hosting.get()?.invited?.isEmpty() == true || ++n > 600 }
					step { check("withdrew the invite", app.keplersync.tandem.fabric.play.Hosting.get()?.invited?.isEmpty() == true); mark("alex-withdrew") }
					steps += { marked("sam-saw-withdraw") }
					step { click(it, PausePanel.devTargets.getValue("invite-Sam")) }
				}
				step { it.setScreen(null) }
				n = 0
				steps += { others() > 0 || ++n > 2400 }
				step { check("Sam is in the game", others() > 0) }
				if (punch) step {
					val sam = it.singleplayerServer!!.playerList.getPlayerByName("Sam")
					check("Sam came in punched through", sam != null && app.keplersync.tandem.fabric.play.Hosting.get()!!.punched(sam))
				}
				steps += { marked("sam-joined") }
				step { it.pauseGame(false) }
				sleep(20)
				step { shot(it, "play-a-hosting") }
				step { it.setScreen(null); mark("alex-saw-sam") }
				if (!relay) {
					// Send logs: the link on the pause card opens the dialog; what it sends reaches KeplerSync.
					step { it.pauseGame(false) }
					sleep(10)
					step { click(it, PausePanel.devTargets.getValue("send-logs")) }
					sleep(10)
					step { shot(it, "play-a-send-logs") }
					keys(GLFW.GLFW_KEY_ESCAPE)
					step {
						val log = app.keplersync.tandem.fabric.account.LogBook.text()
						a.api.sendLogs("check", "1.21.1", "Play check", log)
						check("sent logs with Tandem's lines in them", "opened Play Base to friends" in log)
					}
					step { it.setScreen(null) }
				}
				if (relay) {
					step { check("the relay is connected", app.keplersync.tandem.fabric.play.Hosting.get()!!.relayConnected()) }
					step {
						val sam = a.friends.first { f -> f.user.displayName == "Sam" }.user.id
						app.keplersync.tandem.fabric.play.Hosting.get()!!.kick("Sam", sam)
					}
					steps += { marked("sam-turned-away") }
					inviteSam()
					step { it.setScreen(null) }
					n = 0
					steps += { others() > 0 || ++n > 2400 }
					step { check("Sam is back after a new invite", others() > 0) }
				}
				steps += { marked("sam-done") }
			}
			step { it.stop() }
		}
		ClientTickEvents.END_CLIENT_TICK.register(::tick)
	}

	/**
	 * A synced world handed from Alex to Sam (handoffcheck.sh). Alex syncs Hand Base (a survival world), plays in
	 * creative with a sword; Sam joins from Play without an invite and gets an emerald and creative; Alex, its owner,
	 * makes Sam an operator; Alex hands the world to Sam and leaves; Sam takes over by himself; Alex joins Sam. Whoever
	 * hosts, each keeps their own inventory and game mode, and operators stay the world's.
	 */
	fun handoffCheck() {
		val a = KsAccount.get() ?: error("The hand-off check needs -Dtandem.server")
		val marks = java.nio.file.Paths.get(System.getProperty("tandem.checkdir") ?: error("The hand-off check needs -Dtandem.checkdir"))
		java.nio.file.Files.createDirectories(marks)
		fun mark(name: String) = java.nio.file.Files.writeString(marks.resolve(name), "")
		fun marked(name: String) = java.nio.file.Files.exists(marks.resolve(name))
		steps += { a.signedIn() && a.friends.isNotEmpty() }
		size(1920, 1080, 3)
		step { it.options.pauseOnLostFocus = false }
		step { mc ->
			val me = a.me.displayName
			val d = WorldSync.get()!!
			fun check(what: String, ok: Boolean) = TandemClient.LOG.info("Hand-off check, {}: {} {}", me, if (ok) "OK" else "FAILED", what)
			fun others() = mc.connection?.onlinePlayers?.count { it.profile.id != mc.user.profileId } ?: 0
			fun server() = mc.singleplayerServer!!
			fun player(name: String) = server().playerList.getPlayerByName(name)
			fun has(item: net.minecraft.world.item.Item) = mc.player!!.inventory.items.any { s -> s.`is`(item) }
			fun creative() = mc.gameMode?.playerMode == GameType.CREATIVE
			fun hosted() = Sample.worlds.any { w -> w.name == "Hand Base" && w.state == WorldState.HOSTED }
			fun waitFor(ticks: Int, ok: (Minecraft) -> Boolean) {
				var n = 0
				steps += { ok(it) || ++n > ticks }
			}
			if (me == "Alex") {
				var sam: java.util.UUID? = null
				var samStart = false
				step { openWorld(it, "tandem-hand-a", "Hand Base", GameType.SURVIVAL) }
				steps += { it.player != null && it.screen == null }
				step { server().executeBlocking { player("Alex")!!.let { p -> p.setGameMode(GameType.CREATIVE); p.inventory.add(net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND_SWORD)) } } }
				var first: WorldSync.Transfer? = null
				step { first = d.firstUploadOpen(server(), "Hand Base", ids("Sam")) }
				waitFor(2400) { d.session != null && app.keplersync.tandem.fabric.play.Hosting.get()?.worldId != null || first?.future?.isCompletedExceptionally == true }
				step { check("synced Hand Base and opened it to its members", d.session != null && app.keplersync.tandem.fabric.play.Hosting.get()?.worldId != null); mark("alex-synced") }
				waitFor(2400) { others() > 0 }
				step { check("Sam joined from Play, without an invite", others() > 0) }
				sleep(40)
				step {
					server().executeBlocking {
						val p = player("Sam")!!
						sam = p.uuid
						samStart = p.gameMode.gameModeForPlayer == GameType.SURVIVAL && p.inventory.isEmpty && server().getProfilePermissions(p.gameProfile) == 0
						p.setGameMode(GameType.CREATIVE)
						p.inventory.add(net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.EMERALD))
					}
				}
				step { check("Sam came in with his own empty inventory, the world's game mode and no commands", samStart) }
				waitFor(200) { sam?.let(HostFit::heardMsPerTick) != null }
				step { check("Sam's Tandem said how well his PC could host: he never has, and nothing says he'd struggle", sam?.let(HostFit::heardMsPerTick) == 0f && sam?.let(HostFit::problem) == null) }
				step { app.keplersync.tandem.fabric.play.WorldRules.of(server())!!.setOperator(sam, true) }
				var op = false
				waitFor(600) { server().executeBlocking { op = server().getProfilePermissions(player("Sam")!!.gameProfile) == 3 }; op }
				step { check("made Sam an operator", op); mark("alex-made-sam-op") }
				steps += { marked("sam-is-op") }
				// Hosting long enough that Tandem keeps how Hand Base ran (half a minute of samples, from the 10th second).
				sleep(600)
				// Leaving with Sam in the game asks who takes over.
				step { it.pauseGame(false) }
				sleep(10)
				step { clickWidget(it, PausePanel.devQuit!!) }
				sleep(10)
				step { shot(it, "hand-a-leave") }
				keys(GLFW.GLFW_KEY_ESCAPE)
				step {
					val rules = app.keplersync.tandem.fabric.play.WorldRules.of(server())!!
					a.api.handoff(rules.worldId, rules.userId(sam))
					it.level?.disconnect()
					it.disconnect(TitleScreen())
				}
				steps += { atTitle(it) }
				waitFor(2400) { d.background?.done() == true }
				step { check("Alex's session uploaded", d.background?.future?.isCompletedExceptionally == false) }
				waitFor(4800) { marked("sam-hosting") }
				play()
				waitFor(600) { hosted() }
				step { shot(it, "hand-a-list") }
				steps += { inView(it, "button-Hand Base") }
				step { click(it, target(it, "button-Hand Base")) }
				waitFor(1200) { it.singleplayerServer == null && it.player != null && others() > 0 }
				sleep(40)
				step { check("joined Sam, who hosts Hand Base now", it.singleplayerServer == null && others() > 0) }
				step { check("kept his own sword and creative mode at Sam's", has(net.minecraft.world.item.Items.DIAMOND_SWORD) && !has(net.minecraft.world.item.Items.EMERALD) && creative()) }
				step { check("is still an operator at Sam's: he owns the world", it.player!!.hasPermissions(3)) }
				step { it.pauseGame(false) }
				sleep(20)
				step { shot(it, "hand-a-guest") }
				step { it.setScreen(null); mark("alex-done") }
				step { it.level?.disconnect(); it.disconnect(TitleScreen()) }
			} else {
				play()
				waitFor(4800) { marked("alex-synced") }
				waitFor(600) { hosted() }
				step { shot(it, "hand-b-list") }
				steps += { inView(it, "button-Hand Base") }
				step { click(it, target(it, "button-Hand Base")) }
				waitFor(1200) { it.singleplayerServer == null && it.player != null && others() > 0 }
				step { check("joined Alex's synced world from Play", it.singleplayerServer == null && others() > 0) }
				waitFor(1200) { marked("alex-made-sam-op") && it.player!!.hasPermissions(3) }
				step { check("became an operator", it.player!!.hasPermissions(3)); mark("sam-is-op") }
				// Alex hands Hand Base over and leaves: Sam's game asks him first, and he says Host it.
				waitFor(2400) { (it.screen as? PlayScreen)?.devBusy() == true }
				sleep(10)
				step { shot(it, "hand-b-asked") }
				step { app.keplersync.tandem.fabric.ui.devHostIt!!() }
				sleep(10)
				step { shot(it, "hand-b-takeover") }
				waitFor(4800) { it.singleplayerServer != null && it.player != null && it.screen == null }
				sleep(40)
				step { check("took over Hand Base and hosts it", it.singleplayerServer != null) }
				step { check("kept his own emerald and creative mode", has(net.minecraft.world.item.Items.EMERALD) && !has(net.minecraft.world.item.Items.DIAMOND_SWORD) && creative()) }
				step { check("has every command as the operator hosting", server().getProfilePermissions(it.player!!.gameProfile) == 4) }
				waitFor(600) { app.keplersync.tandem.fabric.play.Hosting.get()?.worldId != null }
				step { check("opened it to its members", app.keplersync.tandem.fabric.play.Hosting.get()?.worldId != null); mark("sam-hosting") }
				fun alexRan() = mc.connection?.getPlayerInfo("Alex")?.profile?.id?.let(HostFit::heardMsPerTick)
				waitFor(4800) { alexRan() != null }
				step { check("Alex's Tandem said how Hand Base ran when he hosted it", (alexRan() ?: 0f) > 0f) }
				waitFor(4800) { marked("alex-done") }
				step { it.level?.disconnect(); it.disconnect(TitleScreen()) }
				steps += { atTitle(it) }
				// Straight into Tandem while it uploads: the world he took over in the game is linked to its folder here
				// (its own picture), and shows his upload, not "Ready" from Alex's last session.
				play()
				fun hand() = Sample.worlds.firstOrNull { w -> w.name == "Hand Base" && w.cloudId != null }
				waitFor(600) { hand()?.levelId != null }
				step { check("Hand Base is linked to its folder here, with its own picture", hand()?.levelId != null && hand()?.icon != null) }
				// Long enough for every read started while the game was closing to come back.
				sleep(60)
				step { check("Tandem shows his session uploading, or saved by him", hand()?.let { w -> w.state == WorldState.FIRST || w.who == "you" } == true) }
				step { shot(it, "hand-b-after-hosting") }
				waitFor(2400) { d.background?.done() == true }
				step { check("Sam's session uploaded", d.background?.future?.isCompletedExceptionally == false) }
				waitFor(600) { hand()?.let { w -> w.state == WorldState.READY && w.who == "you" } == true }
				step { check("then says he played it last", hand()?.let { w -> w.state == WorldState.READY && w.who == "you" } == true) }
				step { shot(it, "hand-b-uploaded") }
			}
			step { it.stop() }
		}
		ClientTickEvents.END_CLIENT_TICK.register(::tick)
	}

	/**
	 * Hosting for each other (rescuecheck): Alex syncs Rescue Base and leaves; Sam's download is slow, so he asks Alex
	 * to host, Alex says Host it and Sam joins him; Alex hands the world to Sam and leaves, and Sam says Not now, which
	 * leaves it free; Sam hosts it and quits while his upload is held midway (his game stops), and Alex, waiting to
	 * play, is told Sam quit before it finished. Alex owns Rescue Base, so he stops waiting and plays from the last upload.
	 */
	fun rescueCheck() {
		val a = KsAccount.get() ?: error("The rescue check needs -Dtandem.server")
		val marks = java.nio.file.Paths.get(System.getProperty("tandem.checkdir") ?: error("The rescue check needs -Dtandem.checkdir"))
		java.nio.file.Files.createDirectories(marks)
		fun mark(name: String) = java.nio.file.Files.writeString(marks.resolve(name), "")
		fun marked(name: String) = java.nio.file.Files.exists(marks.resolve(name))
		steps += { a.signedIn() && a.friends.isNotEmpty() }
		size(1920, 1080, 3)
		step { it.options.pauseOnLostFocus = false }
		step { mc ->
			val me = a.me.displayName
			val d = WorldSync.get()!!
			fun check(what: String, ok: Boolean) = TandemClient.LOG.info("Rescue check, {}: {} {}", me, if (ok) "OK" else "FAILED", what)
			fun others() = mc.connection?.onlinePlayers?.count { it.profile.id != mc.user.profileId } ?: 0
			fun server() = mc.singleplayerServer!!
			fun base() = Sample.worlds.firstOrNull { w -> w.name == "Rescue Base" && w.cloudId != null }
			fun hosting() = mc.singleplayerServer != null && app.keplersync.tandem.fabric.play.Hosting.get()?.worldId != null
			fun waitFor(ticks: Int, ok: (Minecraft) -> Boolean) {
				var n = 0
				steps += { ok(it) || ++n > ticks }
			}
			fun leave() {
				step { it.level?.disconnect(); it.disconnect(TitleScreen()) }
				steps += { atTitle(it) }
			}
			if (me == "Alex") {
				step { openWorld(it, "tandem-rescue-a", "Rescue Base", GameType.CREATIVE) }
				steps += { it.player != null && it.screen == null }
				var first: WorldSync.Transfer? = null
				step { first = d.firstUploadOpen(server(), "Rescue Base", ids("Sam")) }
				waitFor(2400) { hosting() || first?.future?.isCompletedExceptionally == true }
				step { check("synced Rescue Base", hosting()) }
				leave()
				waitFor(2400) { d.background?.done() == true }
				step { mark("alex-left") }
				// Sam's download is slow: he asks Alex to host, and Alex says Host it.
				waitFor(4800) { app.keplersync.tandem.fabric.ui.devAskedHost != null }
				step { check("Sam's request to host reached Alex", app.keplersync.tandem.fabric.ui.devAskedHost != null) }
				sleep(10)
				step { shot(it, "rescue-a-asked") }
				step { app.keplersync.tandem.fabric.ui.devAskedHost!!(); app.keplersync.tandem.fabric.ui.devAskedHost = null }
				waitFor(2400) { hosting() }
				step { check("hosted Rescue Base for Sam", hosting()) }
				waitFor(2400) { others() > 0 }
				step { check("Sam joined him", others() > 0) }
				// He hands it to Sam and leaves; Sam says Not now.
				steps += { marked("sam-in") }
				step {
					val rules = app.keplersync.tandem.fabric.play.WorldRules.of(server())!!
					a.api.handoff(rules.worldId, rules.userId(server().playerList.getPlayerByName("Sam")!!.uuid))
				}
				leave()
				waitFor(2400) { d.background?.done() == true }
				step { mark("alex-handed") }
				steps += { marked("sam-said-no") }
				// Sam hosts it and quits midway through his upload: Alex, waiting to play, is told why, and as the
				// owner plays from the last upload instead.
				steps += { marked("sam-uploading") }
				play()
				waitFor(600) { base()?.state == WorldState.SAVING }
				steps += { inView(it, "button-Rescue Base") }
				step { click(it, target(it, "button-Rescue Base")) }
				waitFor(4800) { app.keplersync.tandem.fabric.ui.devQuitShown }
				step { check("told Sam quit before his upload finished", app.keplersync.tandem.fabric.ui.devQuitShown) }
				step { check("the owner can play from the last upload", app.keplersync.tandem.fabric.ui.devCarryOn != null) }
				sleep(10)
				step { shot(it, "rescue-a-quit") }
				step { app.keplersync.tandem.fabric.ui.devCarryOn!!() }
				sleep(10)
				step { shot(it, "rescue-a-carry-on") }
				step { app.keplersync.tandem.fabric.ui.devCarryOn!!(); app.keplersync.tandem.fabric.ui.devCarryOn = null }
				waitFor(2400) { hosting() }
				step { check("hosting from the last upload", hosting()) }
			} else {
				steps += { marked("alex-left") }
				// A held download counts as slow: Ask Alex to host.
				step { System.setProperty("tandem.downloadPauseMs", "120000") }
				play()
				waitFor(600) { base() != null }
				steps += { inView(it, "button-Rescue Base") }
				step { click(it, target(it, "button-Rescue Base")) }
				waitFor(1200) { app.keplersync.tandem.fabric.ui.devAsk != null }
				step { check("a slow download offers to ask Alex to host", app.keplersync.tandem.fabric.ui.devAsk != null) }
				sleep(10)
				step { shot(it, "rescue-b-slow") }
				step { System.clearProperty("tandem.downloadPauseMs"); app.keplersync.tandem.fabric.ui.devAsk!!(); app.keplersync.tandem.fabric.ui.devAsk = null }
				sleep(10)
				step { shot(it, "rescue-b-waiting") }
				waitFor(4800) { it.singleplayerServer == null && it.player != null && others() > 0 }
				step { check("joined Alex once he hosted", it.singleplayerServer == null && others() > 0); mark("sam-in") }
				// Alex hands Rescue Base over and leaves: Sam is asked, and says Not now.
				waitFor(4800) { app.keplersync.tandem.fabric.ui.devNotNow != null }
				step { check("a hand-off asks first", app.keplersync.tandem.fabric.ui.devNotNow != null) }
				sleep(10)
				step { shot(it, "rescue-b-handed") }
				step { app.keplersync.tandem.fabric.ui.devNotNow!!(); app.keplersync.tandem.fabric.ui.devNotNow = null }
				steps += { marked("alex-handed") }
				var free = false
				waitFor(1200) {
					val id = base()?.cloudId
					if (id != null) free = runCatching { d.cloud.lease(id) }.getOrNull() == null
					free
				}
				step { check("saying no leaves the world free", free); mark("sam-said-no") }
				// Free: he hosts it, then quits while his upload is held (as if the game closed).
				play()
				waitFor(600) { base() != null }
				steps += { inView(it, "button-Rescue Base") }
				step { click(it, target(it, "button-Rescue Base")) }
				waitFor(2400) { hosting() }
				step { check("hosted Rescue Base once it was free", hosting()) }
				sleep(100)
				step { System.setProperty("tandem.uploadPauseMs", "600000") }
				leave()
				sleep(200)
				step { mark("sam-uploading") }
				sleep(40)
				step { TandemClient.LOG.info("Rescue check, Sam: stopping the game midway through the upload"); Runtime.getRuntime().halt(0) }
			}
			step { it.stop() }
		}
		ClientTickEvents.END_CLIENT_TICK.register(::tick)
	}

	/**
	 * A host's game stopping (crashcheck): Sam syncs and hosts Crash Base, Alex joins from Play, Sam's game stops
	 * without a word. Alex is asked to take over hosting, and does once KeplerSync lets the world go.
	 */
	fun crashCheck() {
		val a = KsAccount.get() ?: error("The crash check needs -Dtandem.server")
		val marks = java.nio.file.Paths.get(System.getProperty("tandem.checkdir") ?: error("The crash check needs -Dtandem.checkdir"))
		java.nio.file.Files.createDirectories(marks)
		fun mark(name: String) = java.nio.file.Files.writeString(marks.resolve(name), "")
		fun marked(name: String) = java.nio.file.Files.exists(marks.resolve(name))
		steps += { a.signedIn() && a.friends.isNotEmpty() }
		size(1920, 1080, 3)
		step { it.options.pauseOnLostFocus = false }
		step { mc ->
			val me = a.me.displayName
			val d = WorldSync.get()!!
			fun check(what: String, ok: Boolean) = TandemClient.LOG.info("Crash check, {}: {} {}", me, if (ok) "OK" else "FAILED", what)
			fun others() = mc.connection?.onlinePlayers?.count { it.profile.id != mc.user.profileId } ?: 0
			fun server() = mc.singleplayerServer!!
			fun hosting() = mc.singleplayerServer != null && app.keplersync.tandem.fabric.play.Hosting.get()?.worldId != null
			fun waitFor(ticks: Int, ok: (Minecraft) -> Boolean) {
				var n = 0
				steps += { ok(it) || ++n > ticks }
			}
			if (me == "Sam") {
				step { openWorld(it, "tandem-crash-b", "Crash Base", GameType.CREATIVE) }
				steps += { it.player != null && it.screen == null }
				var first: WorldSync.Transfer? = null
				step { first = d.firstUploadOpen(server(), "Crash Base", ids("Alex")) }
				waitFor(2400) { hosting() || first?.future?.isCompletedExceptionally == true }
				step { check("synced and hosts Crash Base", hosting()); mark("sam-hosting") }
				waitFor(4800) { others() > 0 }
				step { check("Alex joined", others() > 0) }
				sleep(200)
				step { TandemClient.LOG.info("Crash check, Sam: stopping the game"); Runtime.getRuntime().halt(0) }
			} else {
				steps += { marked("sam-hosting") }
				play()
				waitFor(1200) { Sample.worlds.any { w -> w.name == "Crash Base" && w.state == WorldState.HOSTED } }
				steps += { inView(it, "button-Crash Base") }
				step { click(it, target(it, "button-Crash Base")) }
				waitFor(2400) { it.singleplayerServer == null && it.player != null && others() > 0 }
				step { check("joined Sam's game", it.singleplayerServer == null && others() > 0) }
				// Sam's game stops: the connection drops, and once KeplerSync says his game stopped answering, Alex is asked.
				waitFor(6000) { app.keplersync.tandem.fabric.ui.devTakeOver != null }
				step { check("asked to take over once Sam's game stopped", app.keplersync.tandem.fabric.ui.devTakeOver != null) }
				sleep(10)
				step { shot(it, "crash-a-stopped") }
				step { app.keplersync.tandem.fabric.ui.devTakeOver!!(); app.keplersync.tandem.fabric.ui.devTakeOver = null }
				sleep(10)
				step { shot(it, "crash-a-taking-over") }
				waitFor(6000) { hosting() }
				step { check("took over Crash Base and hosts it", hosting()) }
				step { it.level?.disconnect(); it.disconnect(TitleScreen()) }
				steps += { atTitle(it) }
				waitFor(2400) { d.background?.done() == true }
			}
			step { it.stop() }
		}
		ClientTickEvents.END_CLIENT_TICK.register(::tick)
	}

	/**
	 * History, both games at once (historycheck.sh). Alex syncs History Base with gold at spawn, wrecks it (takes the
	 * gold) in his next session, and restores the first upload from History, then saves it as a zip. Sam
	 * finds the gold back, adds emerald; Alex's offline change races Sam's session, so the world splits and Sam,
	 * who isn't an operator, picks his own version. Sam then uploads a damaged level.dat; Alex opens the world, it won't
	 * load, and Open the previous version gets him in.
	 */
	fun historyCheck() {
		val a = KsAccount.get() ?: error("The history check needs -Dtandem.server")
		val marks = java.nio.file.Paths.get(System.getProperty("tandem.checkdir") ?: error("The history check needs -Dtandem.checkdir"))
		java.nio.file.Files.createDirectories(marks)
		fun mark(name: String) = java.nio.file.Files.writeString(marks.resolve(name), "")
		fun marked(name: String) = java.nio.file.Files.exists(marks.resolve(name))
		steps += { a.signedIn() && a.friends.isNotEmpty() }
		size(1920, 1080, 3)
		step { it.options.pauseOnLostFocus = false }
		step { mc ->
			val me = a.me.displayName
			val d = WorldSync.get()!!
			fun check(what: String, ok: Boolean) = TandemClient.LOG.info("History check, {}: {} {}", me, if (ok) "OK" else "FAILED", what)
			fun put(up: Int, block: Block?) = mc.singleplayerServer!!.let { s ->
				s.executeBlocking { s.overworld().setBlockAndUpdate(s.overworld().sharedSpawnPos.above(up), (block ?: Blocks.AIR).defaultBlockState()) }
			}
			fun has(up: Int, block: Block): Boolean {
				var found = false
				mc.singleplayerServer!!.let { s -> s.executeBlocking { found = s.overworld().getBlockState(s.overworld().sharedSpawnPos.above(up)).`is`(block) } }
				return found
			}
			fun world() = Sample.worlds.firstOrNull { it.name == "History Base" && it.cloudId != null }
			fun waitFor(ticks: Int, ok: (Minecraft) -> Boolean) {
				var n = 0
				steps += { ok(it) || ++n > ticks }
			}
			fun leave() {
				step { it.level?.disconnect(); it.disconnect(TitleScreen()) }
				steps += { atTitle(it) }
				waitFor(2400) { d.background?.done() == true }
			}
			fun playIt() {
				play()
				waitFor(600) { world() != null }
				steps += { inView(it, "button-History Base") }
				step { click(it, target(it, "button-History Base")) }
				waitFor(2400) { it.singleplayerServer != null && it.player != null && it.screen == null }
			}
			fun folder() = WorldSync.folder(world()!!.levelId!!)
			if (me == "Alex") {
				step { openWorld(it, "tandem-hist-a", "History Base") }
				steps += { it.player != null && it.screen == null }
				step { put(20, Blocks.GOLD_BLOCK) }
				var first: WorldSync.Transfer? = null
				step { first = d.firstUploadOpen(it.singleplayerServer!!, "History Base", ids("Sam")) }
				waitFor(2400) { d.session != null || first?.future?.isCompletedExceptionally == true }
				leave()
				step { check("synced History Base with gold at spawn", d.background?.future?.isCompletedExceptionally == false) }
				// A creeper takes the gold, and the session saves.
				playIt()
				step { put(20, null) }
				leave()
				step { mark("alex-wrecked") }
				play()
				waitFor(600) { world() != null }
				step { (it.screen as PlayScreen).dev("details-History Base/hist") }
				sleep(60)
				step { shot(it, "hist-a-history") }
				var restored = false
				step {
					val w = world()!!
					val history = d.cloud.history(w.cloudId!!)
					val firstUpload = history.last { s -> s.kind == "import" }
					restored = d.cloud.restore(w.cloudId!!, firstUpload.id, d.player()) != w.head
				}
				step { check("restored the first upload from History", restored); mark("alex-restored") }
				// Download as zip, into the check's own folder.
				step { RealWorlds.refresh { }; }
				sleep(40)
				step { (it.screen as? PlayScreen)?.let { s -> s.dev("details-History Base/over") } }
				var zipped: java.nio.file.Path? = null
				step {
					val dir = java.nio.file.Path.of(System.getProperty("tandem.downloads"))
					java.nio.file.Files.createDirectories(dir)
					java.nio.file.Files.list(dir).use { l -> l.forEach { f -> java.nio.file.Files.delete(f) } }
					RealHistory.zip(world()!!, null, Layers())
				}
				waitFor(1200) {
					val dir = java.nio.file.Path.of(System.getProperty("tandem.downloads"))
					zipped = java.nio.file.Files.list(dir).use { l -> l.filter { f -> f.toString().endsWith(".zip") }.findFirst().orElse(null) }
					zipped != null
				}
				step {
					val names = mutableListOf<String>()
					zipped?.let { z -> java.util.zip.ZipFile(z.toFile()).use { f -> f.entries().asSequence().forEach { e -> names += e.name } } }
					check("saved History Base as a zip with its world in it", "History Base/level.dat" in names)
				}
				// Sam plays from the restored version; meanwhile a change on this PC, made while not connected, races it.
				waitFor(4800) { marked("sam-played") }
				var raced = false
				step {
					val dir = folder()
					java.nio.file.Files.createDirectories(dir.resolve("data"))
					java.nio.file.Files.writeString(dir.resolve("data/alex-note.dat"), "Alex was here, offline")
					val sync = d.sync()
					sync.checkpoint(dir, app.keplersync.tandem.core.Snapshot.SESSION)
					sync.upload(dir, app.keplersync.tandem.core.Progress())
					raced = d.cloud.world(world()!!.cloudId!!).branches.isNotEmpty()
				}
				step { check("an offline change raced Sam's session, and both were kept", raced); mark("alex-raced") }
				// Sam uploads a damaged level.dat; this game can't load it.
				waitFor(4800) { marked("sam-broke") }
				play()
				waitFor(600) { world()?.state == WorldState.READY }
				steps += { inView(it, "button-History Base") }
				step { click(it, target(it, "button-History Base")) }
				waitFor(2400) { dialogTargets["previous-version"]?.let { b -> (it.screen as? PlayScreen) != null } == true }
				sleep(20)
				step { check("Tandem said the world won't load", (it.screen as? PlayScreen)?.devBusy() == true) }
				step { shot(it, "hist-a-wont-load") }
				step { click(it, dialogTargets.getValue("previous-version")) }
				waitFor(2400) { it.singleplayerServer != null && it.player != null && it.screen == null }
				sleep(20)
				step { check("opened the previous version: Sam's, with his emerald", it.singleplayerServer != null && has(21, Blocks.EMERALD_BLOCK)) }
				leave()
				step { mark("alex-done") }
			} else {
				waitFor(4800) { marked("alex-restored") }
				playIt()
				step { check("found the gold back after Alex restored", has(20, Blocks.GOLD_BLOCK)) }
				step { put(21, Blocks.EMERALD_BLOCK) }
				leave()
				step { mark("sam-played") }
				waitFor(4800) { marked("alex-raced") }
				play()
				waitFor(1200) { world()?.state == WorldState.VERSIONS }
				step { check("History Base shows it split into versions", world()?.state == WorldState.VERSIONS) }
				steps += { inView(it, "button-History Base") }
				step { click(it, target(it, "button-History Base")) }
				waitFor(600) { dialogTargets["version-You"] != null && (it.screen as? PlayScreen)?.devBusy() == true }
				sleep(20)
				step { shot(it, "hist-b-versions") }
				step { click(it, dialogTargets.getValue("version-You")) }
				step { click(it, dialogTargets.getValue("versions-confirm")) }
				waitFor(1200) { d.cloud.world(world()!!.cloudId!!).branches.isEmpty() }
				step { check("picked his own version, though he isn't an operator", d.cloud.world(world()!!.cloudId!!).branches.isEmpty()) }
				// A game that closed mid-save: level.dat damaged, uploaded as the latest.
				step {
					val w = world()!!
					val dir = folder()
					val sync = d.sync()
					sync.getLatest(dir, w.cloudId!!, app.keplersync.tandem.core.Progress())
					for (f in listOf("level.dat", "level.dat_old")) java.nio.file.Files.write(dir.resolve(f), ByteArray(64) { 7 })
					sync.checkpoint(dir, app.keplersync.tandem.core.Snapshot.SESSION)
					sync.upload(dir, app.keplersync.tandem.core.Progress())
				}
				step { mark("sam-broke") }
				waitFor(9600) { marked("alex-done") }
			}
			step { it.stop() }
		}
		ClientTickEvents.END_CLIENT_TICK.register(::tick)
	}

	/**
	 * Own storage and leaving, both games at once (owncheck.sh). Alex syncs Own Base (gold at spawn) and Leave Base, both
	 * shared with Sam. While Sam hosts Own Base, Alex saves keys for a second local bucket and moves the world there;
	 * Sam's emerald goes up to it. Alex moves it back and finds the emerald, then stops syncing: his copy is a normal
	 * world, Sam is told his is too, and Resume undoes it on KeplerSync. Sam gets Leave Base, then leaves it, deleting
	 * his copy.
	 */
	fun ownCheck() {
		val a = KsAccount.get() ?: error("The own storage check needs -Dtandem.server")
		val marks = java.nio.file.Paths.get(System.getProperty("tandem.checkdir") ?: error("The own storage check needs -Dtandem.checkdir"))
		java.nio.file.Files.createDirectories(marks)
		fun mark(name: String) = java.nio.file.Files.writeString(marks.resolve(name), "")
		fun marked(name: String) = java.nio.file.Files.exists(marks.resolve(name))
		steps += { a.signedIn() && a.friends.isNotEmpty() }
		size(1920, 1080, 3)
		step { it.options.pauseOnLostFocus = false }
		step { mc ->
			val me = a.me.displayName
			val d = WorldSync.get()!!
			fun check(what: String, ok: Boolean) = TandemClient.LOG.info("Own storage check, {}: {} {}", me, if (ok) "OK" else "FAILED", what)
			fun put(up: Int, block: Block?) = mc.singleplayerServer!!.let { s ->
				s.executeBlocking { s.overworld().setBlockAndUpdate(s.overworld().sharedSpawnPos.above(up), (block ?: Blocks.AIR).defaultBlockState()) }
			}
			fun has(up: Int, block: Block): Boolean {
				var found = false
				mc.singleplayerServer!!.let { s -> s.executeBlocking { found = s.overworld().getBlockState(s.overworld().sharedSpawnPos.above(up)).`is`(block) } }
				return found
			}
			fun world(name: String = "Own Base") = Sample.worlds.firstOrNull { it.name == name && it.cloudId != null }
			fun cloud(name: String = "Own Base") = d.cloud.world(world(name)!!.cloudId!!)
			fun waitFor(ticks: Int, ok: (Minecraft) -> Boolean) {
				var n = 0
				steps += { ok(it) || ++n > ticks }
			}
			fun leave() {
				step { it.level?.disconnect(); it.disconnect(TitleScreen()) }
				steps += { atTitle(it) }
				waitFor(2400) { d.background?.done() == true }
			}
			fun playIt(name: String = "Own Base") {
				play()
				waitFor(600) { world(name) != null }
				steps += { inView(it, "button-$name") }
				step { click(it, target(it, "button-$name")) }
				waitFor(2400) { it.singleplayerServer != null && it.player != null && it.screen == null }
			}
			fun sync(level: String, name: String) {
				step { openWorld(it, level, name) }
				steps += { it.player != null && it.screen == null }
				step { put(20, Blocks.GOLD_BLOCK) }
				var first: WorldSync.Transfer? = null
				step { first = d.firstUploadOpen(it.singleplayerServer!!, name, ids("Sam")) }
				waitFor(2400) { d.session != null || first?.future?.isCompletedExceptionally == true }
				leave()
				step { check("synced $name", d.background?.future?.isCompletedExceptionally == false) }
			}
			if (me == "Alex") {
				sync("tandem-own-a", "Own Base")
				sync("tandem-own-l", "Leave Base")
				step { mark("alex-synced") }
				waitFor(4800) { marked("sam-hosting") }
				var saved: String? = "not tried"
				step {
					saved = null
					RealStorage.save("0123456789abcdef0123456789abcdef", "tandem-check-own", "atlas-dev", "atlas-dev-secret") { o, why -> saved = if (o != null) "ok" else why }
				}
				waitFor(600) { saved != null }
				step { check("saved keys for the second bucket ($saved)", saved == "ok") }
				play("storage")
				sleep(60)
				step { shot(it, "own-a-storage") }
				var moved = false
				step { a.api.moveStorage(world()!!.cloudId!!, "own") }
				waitFor(2400) { cloud().let { w -> w.storage == "own" && w.movingTo == null }.also { ok -> moved = ok } }
				step { check("moved Own Base to his own storage while Sam hosts it", moved); mark("alex-moved") }
				waitFor(4800) { marked("sam-uploaded") }
				play()
				waitFor(600) { world() != null }
				step { (it.screen as PlayScreen).dev("details-Own Base/set") }
				sleep(60)
				step { shot(it, "own-a-settings") }
				step { a.api.moveStorage(world()!!.cloudId!!, "keplersync") }
				waitFor(2400) { cloud().let { w -> w.storage == "keplersync" && w.movingTo == null }.also { ok -> moved = ok } }
				step { check("moved it back to KeplerSync storage", moved) }
				playIt()
				step { check("found Sam's emerald, saved while it was in his own storage", has(21, Blocks.EMERALD_BLOCK)) }
				leave()
				play()
				waitFor(600) { world()?.state == WorldState.READY }
				step { (it.screen as PlayScreen).dev("details-Own Base/set") }
				sleep(20)
				var level: String? = null
				var id: String? = null
				step { level = world()?.levelId; id = world()?.cloudId }
				step { RealLeave.stop((it.screen as PlayScreen).devDialogs(), world()!!, false) { } }
				waitFor(1200) { Sample.worlds.any { w -> w.name == "Own Base" && w.cloudId == null } }
				sleep(20)
				step { shot(it, "own-a-stopped") }
				step {
					check("stopped syncing: his copy is a normal world", !app.keplersync.tandem.core.LocalState.load(WorldSync.folder(level!!)).synced() && java.nio.file.Files.exists(WorldSync.folder(level!!).resolve("level.dat")))
					check("KeplerSync keeps it read-only", d.cloud.world(id!!).stopped)
					mark("alex-stopped")
				}
				waitFor(4800) { marked("sam-saw-stop") }
				step { a.api.resumeSyncing(id!!); check("Resume undid it on KeplerSync", !d.cloud.world(id!!).stopped) }
				waitFor(9600) { marked("sam-left") }
				var gone = false
				step { gone = d.cloud.worlds(me).firstOrNull { w -> w.name == "Leave Base" }?.sharedWith?.contains(ids("Sam")[0]) == false }
				step { check("Sam isn't in Leave Base any more", gone); mark("alex-done") }
			} else {
				waitFor(4800) { marked("alex-synced") }
				playIt()
				step { check("hosts Own Base", d.session != null); mark("sam-hosting") }
				waitFor(4800) { marked("alex-moved") }
				step { put(21, Blocks.EMERALD_BLOCK) }
				leave()
				step {
					check("his session went up to Alex's own storage", d.background?.future?.isCompletedExceptionally == false && d.cloud.world(world()!!.cloudId!!).storage == "own")
					mark("sam-uploaded")
				}
				waitFor(9600) { marked("alex-stopped") }
				var level: String? = null
				play()
				waitFor(1200) { Sample.worlds.any { w -> w.name == "Own Base" && w.cloudId == null } }
				sleep(10)
				step { shot(it, "own-b-stopped") }
				step {
					level = Sample.worlds.firstOrNull { w -> w.name == "Own Base" }?.levelId
					check("Alex stopping it made his copy a normal world", level != null && !app.keplersync.tandem.core.LocalState.load(WorldSync.folder(level!!)).synced() && java.nio.file.Files.exists(WorldSync.folder(level!!).resolve("level.dat")))
					check("was told, with Get the latest copy", RealLeave.stopped.isEmpty())
					mark("sam-saw-stop")
				}
				playIt("Leave Base")
				leave()
				play()
				waitFor(600) { world("Leave Base")?.levelId != null }
				step { (it.screen as PlayScreen).dev("details-Leave Base/set") }
				sleep(20)
				step { shot(it, "own-b-leave") }
				step { level = world("Leave Base")!!.levelId; RealLeave.leave(Layers(), world("Leave Base")!!, true) { } }
				waitFor(1200) { !java.nio.file.Files.exists(WorldSync.folder(level!!)) }
				step {
					check("left Leave Base and deleted his copy", !java.nio.file.Files.exists(WorldSync.folder(level!!)) && d.cloud.worlds(me).none { w -> w.name == "Leave Base" })
					mark("sam-left")
				}
				waitFor(4800) { marked("alex-done") }
			}
			step { it.stop() }
		}
		ClientTickEvents.END_CLIENT_TICK.register(::tick)
	}

	fun syncCheck() {
		WorldSync.get() ?: error("The sync check needs a dev cloud")
		val id = "tandem-sync"
		size(1920, 1080, 3)
		play()
		steps += { Sample.worlds.any { it.name == "Shared Base" } || ++waited > 100 }
		// Which run this is depends on the world on disk, so the rest is queued once the game is up.
		step { mc -> waited = 0; roles(mc.levelSource.levelExists(id)) }
		ClientTickEvents.END_CLIENT_TICK.register(::tick)
	}

	private fun roles(haveIt: Boolean) {
		val d = WorldSync.get()!!
		// The game's own name: through KeplerSync, d.player() is an account id.
		val me = Minecraft.getInstance().user.name
		val id = "tandem-sync"
		fun check(what: String, ok: Boolean) = TandemClient.LOG.info("Sync check, {}: {} {}", me, if (ok) "OK" else "FAILED", what)
		fun mark(mc: Minecraft, up: Int, block: Block) = mc.singleplayerServer!!.let { s ->
			s.executeBlocking { s.overworld().setBlockAndUpdate(s.overworld().sharedSpawnPos.above(up), block.defaultBlockState()) }
		}
		fun has(mc: Minecraft, up: Int, block: Block): Boolean {
			var found = false
			mc.singleplayerServer!!.let { s -> s.executeBlocking { found = s.overworld().getBlockState(s.overworld().sharedSpawnPos.above(up)).`is`(block) } }
			return found
		}
		fun shared() = Sample.worlds.firstOrNull { it.name == "Shared Base" }
		fun leave(tag: String) {
			step { it.level?.disconnect(); it.disconnect(TitleScreen()) }
			steps += { atTitle(it) }
			sleep(10)
			step { shot(it, "sync-$tag-title") }
			steps += { d.background?.done() == true }
			step { check("the session uploaded", d.background?.future?.isCompletedExceptionally == false) }
		}
		if (me == "Alex" && !haveIt) {
			step { shot(it, "sync-a1-list") }
			step { openWorld(it, id, "Shared Base") }
			steps += { mc -> mc.player != null && mc.screen == null }
			step { mark(it, 20, Blocks.GOLD_BLOCK) }
			step { it.pauseGame(false) }
			step { shot(it, "sync-a1-just-play") }
			var first: WorldSync.Transfer? = null
			step { mc -> first = d.firstUploadOpen(mc.singleplayerServer!!, "Shared Base", ids("Sam")) }
			steps += { d.session != null || first?.future?.isCompletedExceptionally == true || ++waited > 1200 }
			step { check("the first upload finished and the session started", d.session != null) }
			step { waited = 0; if (d.session == null) it.stop() }
			step { it.setScreen(null) }
			step { mark(it, 21, Blocks.DIAMOND_BLOCK) }
			// A checkpoint every 20 s in check runs: wait for one and read its time in the log.
			sleep(500)
			step { it.pauseGame(false) }
			step { shot(it, "sync-a1-hosting") }
			step { it.setScreen(null) }
			leave("a1")
		} else if (me == "Sam") {
			step { shot(it, "sync-b-list") }
			step { check("Shared Base is listed and ready", shared()?.state == WorldState.READY) }
			steps += { inView(it, "button-Shared Base") }
			step { click(it, target(it, "button-Shared Base")) }
			steps += { mc -> mc.player != null && mc.screen == null }
			step { check("found Alex's gold and diamond", has(it, 20, Blocks.GOLD_BLOCK) && has(it, 21, Blocks.DIAMOND_BLOCK)) }
			step { mark(it, 22, Blocks.EMERALD_BLOCK) }
			leave("b")
		} else {
			step { shot(it, "sync-a2-list") }
			step { check("Shared Base shows Sam's session", shared()?.who == "Sam") }
			step { it.setScreen(SelectWorldScreen(TitleScreen())) }
			sleep(40)
			step { shot(it, "sync-a2-singleplayer") }
			step { mc ->
				val list = (mc.screen as SelectWorldScreen).children().filterIsInstance<WorldSelectionList>().first()
				list.children().filterIsInstance<WorldSelectionList.WorldListEntry>().first { it.levelName == "Shared Base" }.joinWorld()
			}
			step { check("opening from the Singleplayer list went through Tandem", it.screen is PlayScreen) }
			steps += { mc -> mc.player != null && mc.screen == null }
			step { check("found Sam's emerald", has(it, 22, Blocks.EMERALD_BLOCK)) }
			leave("a2")
		}
		step { it.stop() }
	}

	private fun panel(name: String) = PausePanel.devTargets.getValue(name)

	/** Pretend another mod put buttons where packs tend to: three icons top right, and one beside Singleplayer. */
	private var busy = false

	private fun crowd(screen: Screen, width: Int) {
		val add = Screens.getButtons(screen)
		for (i in 1..3) add += Button.builder(Component.literal("")) {}.bounds(width - 4 - 24 * i, 4, 20, 20).build()
		if (screen is TitleScreen) {
			val single = add.firstOrNull { (it.message.contents as? TranslatableContents)?.key == "menu.singleplayer" }
			if (single != null) add += Button.builder(Component.literal("")) {}.bounds(single.x + single.width + 4, single.y, 20, 20).build()
			val multi = add.firstOrNull { (it.message.contents as? TranslatableContents)?.key == "menu.multiplayer" }
			if (multi != null) { multi.width = 98; add += Button.builder(Component.literal("Other mod")) {}.bounds(multi.x + 102, multi.y, 98, 20).build() }
		}
	}

	private fun reinit(mc: Minecraft) {
		mc.screen?.resize(mc, mc.window.guiScaledWidth, mc.window.guiScaledHeight)
	}

	/**
	 * The friends test's third round, fixed (roundcheck.sh). Alex syncs Round Base with Sam ticked: Sam gets an invite
	 * and joins with J, and isn't toasted again. Alex's card offers Sam for Hand off… straight away, joining a friend
	 * asks first, and the Shared with tab can make Sam an operator. Alex opens Round Base from Minecraft's own list.
	 * Sam hosts it next; Alex stops syncing it for after Sam's session, Sam is told, writes a file and leaves, and
	 * Alex's copy gets that last session by itself. Last, Plain Base opens to friends once however fast the invites
	 * come, and Later puts Sam's invite away for good.
	 */
	fun roundCheck() {
		val a = KsAccount.get() ?: error("The round check needs -Dtandem.server")
		val marks = java.nio.file.Paths.get(System.getProperty("tandem.checkdir") ?: error("The round check needs -Dtandem.checkdir"))
		java.nio.file.Files.createDirectories(marks)
		fun mark(name: String) = java.nio.file.Files.writeString(marks.resolve(name), "")
		fun marked(name: String) = java.nio.file.Files.exists(marks.resolve(name))
		steps += { a.signedIn() && a.friends.isNotEmpty() }
		size(1920, 1080, 3)
		step { it.options.pauseOnLostFocus = false }
		step { mc ->
			val me = a.me.displayName
			val d = WorldSync.get()!!
			fun check(what: String, ok: Boolean) = TandemClient.LOG.info("Round check, {}: {} {}", me, if (ok) "OK" else "FAILED", what)
			fun others() = mc.connection?.onlinePlayers?.count { it.profile.id != mc.user.profileId } ?: 0
			fun server() = mc.singleplayerServer!!
			fun world() = Sample.worlds.firstOrNull { it.name == "Round Base" && it.cloudId != null }
			fun waitFor(ticks: Int, ok: (Minecraft) -> Boolean) {
				var n = 0
				steps += { ok(it) || ++n > ticks }
			}
			fun leave() {
				step { it.level?.disconnect(); it.disconnect(TitleScreen()) }
				steps += { atTitle(it) }
				waitFor(2400) { d.background?.done() == true }
			}
			if (me == "Alex") {
				step { openWorld(it, "tandem-round-a", "Round Base", GameType.SURVIVAL) }
				steps += { it.player != null && it.screen == null }
				step { d.firstUploadOpen(server(), "Round Base", ids("Sam")) }
				waitFor(2400) { d.session != null && app.keplersync.tandem.fabric.play.Hosting.get()?.worldId != null }
				step { check("synced Round Base and opened it to its members", app.keplersync.tandem.fabric.play.Hosting.get()?.worldId != null) }
				waitFor(200) { Ingame.devNotice()?.contains("is synced") == true }
				step { check("said Round Base is synced", Ingame.devNotice() == "Round Base is synced"); mark("alex-synced") }
				waitFor(2400) { others() > 0 }
				step { check("Sam joined with the invite", others() > 0) }
				waitFor(600) { "Sam" in PausePanel.devCanHandOff() }
				step { check("Hand off… offers Sam without opening the pause screen again", "Sam" in PausePanel.devCanHandOff()) }
				var asked: Dialogs? = null
				var left = false
				step { it.setScreen(DialogScreen { dl -> asked = dl; PausePanel.beforeLeaving(dl) { left = true } }) }
				sleep(20)
				step { shot(it, "round-a-join-asks") }
				step { check("joining a friend's game asks first, with Sam still here", asked?.layers?.busy == true && !left) }
				keys(GLFW.GLFW_KEY_ESCAPE)
				step { it.setScreen(null) }
				step { RealWorlds.refresh { } }
				waitFor(200) { world()?.let { w -> RealOperators.canChange(w, "Sam") } == true }
				step { check("the Shared with tab can make Sam an operator", world()?.let { w -> RealOperators.canChange(w, "Sam") } == true); mark("alex-checked") }
				waitFor(2400) { marked("sam-left") }
				leave()
				// Minecraft's own Singleplayer list goes through Tandem for a synced world.
				var level: String? = null
				step { level = "tandem-round-a"; it.setScreen(net.minecraft.client.gui.screens.worldselection.SelectWorldScreen(TitleScreen())) }
				sleep(20)
				step { RealWorlds.openSynced(level!!) }
				waitFor(2400) { it.singleplayerServer != null && it.player != null && it.screen == null }
				step { check("opened Round Base from Minecraft's own list", it.singleplayerServer != null) }
				leave()
				step { check("that session uploaded", d.background?.future?.isCompletedExceptionally == false); mark("alex-free") }
				waitFor(4800) { marked("sam-hosting") }
				step { RealWorlds.refresh { } }
				waitFor(600) { world()?.state == WorldState.HOSTED }
				step { a.api.stopSyncing(world()!!.cloudId!!, true); mark("alex-stopping") }
				// At the title screen, Tandem closed: the stop comes through by itself, with Sam's last session.
				val copy = { WorldSync.folder(level!!).resolve("tandem-round.txt") }
				waitFor(4800) { java.nio.file.Files.exists(copy()) && !app.keplersync.tandem.core.LocalState.load(WorldSync.folder(level!!)).synced() }
				step { check("got Sam's last session by itself once syncing stopped", java.nio.file.Files.exists(copy())) }
				step { check("Round Base is a normal world here now", !app.keplersync.tandem.core.LocalState.load(WorldSync.folder(level!!)).synced()) }
				waitFor(200) { Ingame.devNotice()?.contains("latest copy") == true }
				step { shot(it, "round-a-stopped") }
				// Plain Base opens to friends once, however fast the invites come.
				step { openWorld(it, "tandem-round-plain", "Plain Base") }
				steps += { it.player != null && it.screen == null }
				var opens: List<java.util.concurrent.CompletableFuture<app.keplersync.tandem.fabric.play.Hosting>> = emptyList()
				step {
					val settings = app.keplersync.tandem.fabric.play.Hosting.Settings(GameType.CREATIVE, false, net.minecraft.world.Difficulty.PEACEFUL)
					opens = List(2) { app.keplersync.tandem.fabric.play.Hosting.open(server(), "Plain Base", null, emptyList(), settings) }
				}
				waitFor(600) { opens.all { f -> f.isDone } }
				step { check("two quick invites opened Plain Base once", opens.all { f -> !f.isCompletedExceptionally } && opens[0].join() === opens[1].join()) }
				step {
					val sam = a.friends.first { f -> f.user.displayName == "Sam" }.user
					opens[0].join().invite(sam.id, app.keplersync.tandem.fabric.play.Hosting.minecraftId(sam.minecraftUuid))
				}
				waitFor(2400) { marked("sam-later") }
				leave()
			} else {
				waitFor(4800) { marked("alex-synced") }
				waitFor(1200) { Ingame.devInvite() == "Round Base" }
				step { check("got an invite to Round Base when Alex synced it with him ticked", Ingame.devInvite() == "Round Base") }
				step { Ingame.joinInvite(it) }
				waitFor(1200) { it.singleplayerServer == null && it.player != null && others() > 0 }
				step { check("joined Alex with J", it.singleplayerServer == null && others() > 0) }
				step { a.reloadInvitesSoon() }
				sleep(60)
				step { check("isn't toasted again for the invite he used", Ingame.devInvite() == null) }
				waitFor(2400) { marked("alex-checked") }
				step { it.level?.disconnect(); it.disconnect(TitleScreen()); mark("sam-left") }
				steps += { atTitle(it) }
				waitFor(4800) { marked("alex-free") }
				play()
				waitFor(600) { world()?.state == WorldState.READY }
				steps += { inView(it, "button-Round Base") }
				step { click(it, target(it, "button-Round Base")) }
				waitFor(2400) { it.singleplayerServer != null && it.player != null && it.screen == null }
				step { check("hosts Round Base", it.singleplayerServer != null); mark("sam-hosting") }
				waitFor(2400) { Ingame.devNotice()?.contains("will stop syncing") == true }
				step { shot(it, "round-b-stopping") }
				step { check("was told Alex will stop syncing it after this session", Ingame.devNotice()?.contains("will stop syncing") == true) }
				step { java.nio.file.Files.writeString(server().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("tandem-round.txt"), "Sam was here") }
				leave()
				step { check("his last session uploaded", d.background?.future?.isCompletedExceptionally == false) }
				waitFor(2400) { Ingame.devInvite() == "Plain Base" }
				step { check("got Alex's invite to Plain Base", Ingame.devInvite() == "Plain Base") }
				step { Ingame.devLater() }
				step { a.reloadInvitesSoon() }
				sleep(60)
				step { check("Later put the invite away, and it stays away", Ingame.devInvite() == null); mark("sam-later") }
			}
			step { it.stop() }
		}
		ClientTickEvents.END_CLIENT_TICK.register(::tick)
	}

	/**
	 * Friends' other buttons and a full storage, Riley and Alex at once (dev/checks.sh walk). Riley, a new account,
	 * leaves his code; Alex asks, Riley declines; Alex asks again, Riley blocks him, and Alex's next request never
	 * reaches him; Riley unblocks and reports him. Then the runner shrinks Alex's storage, and Sync this world says
	 * it's full instead of uploading.
	 */
	fun walkCheck() {
		val a = KsAccount.get() ?: error("The walk check needs -Dtandem.server")
		val marks = java.nio.file.Paths.get(System.getProperty("tandem.checkdir") ?: error("The walk check needs -Dtandem.checkdir"))
		java.nio.file.Files.createDirectories(marks)
		fun mark(name: String) = java.nio.file.Files.writeString(marks.resolve(name), "")
		fun marked(name: String) = java.nio.file.Files.exists(marks.resolve(name))
		steps += { a.signedIn() }
		size(1920, 1080, 3)
		step { mc ->
			val me = a.me.displayName
			fun check(what: String, ok: Boolean) = TandemClient.LOG.info("Walk check, {}: {} {}", me, if (ok) "OK" else "FAILED", what)
			fun <T> ask(work: (app.keplersync.tandem.core.KeplerSyncApi) -> T): Result<T> = runCatching { work(a.api) }
				.onFailure { TandemClient.LOG.info("Walk check, {}: KeplerSync refused: {}", me, it.message) }
			fun from(name: String, direction: String) = ask { it.requests() }.getOrNull().orEmpty().firstOrNull { r -> r.direction == direction && r.user.displayName == name }
			if (me == "Riley") {
				var alex: String? = null
				step { java.nio.file.Files.writeString(marks.resolve("Riley.name"), a.me.username); mark("riley-ready") }
				steps += { marked("alex-asked") }
				step {
					alex = from("Alex", "incoming")?.user?.id
					check("got Alex's request", alex != null)
					check("declined it", ask { it.decline(alex!!) }.isSuccess && from("Alex", "incoming") == null)
					mark("riley-declined")
				}
				steps += { marked("alex-asked-again") }
				step {
					check("got Alex's second request", from("Alex", "incoming") != null)
					check("blocked Alex", ask { it.block(alex!!) }.isSuccess && ask { it.blocked() }.getOrNull().orEmpty().any { u -> u.id == alex })
					mark("riley-blocked")
				}
				steps += { marked("alex-tried") }
				step {
					check("a blocked player's request never arrives", from("Alex", "incoming") == null)
					check("unblocked Alex", ask { it.unblock(alex!!) }.isSuccess && ask { it.blocked() }.getOrNull().orEmpty().none { u -> u.id == alex })
					check("reported Alex", ask { it.report(alex!!, "abuse", "Walk check: a test report.") }.isSuccess)
					mark("riley-done")
				}
			} else {
				val d = WorldSync.get()!!
				var code = ""
				steps += { marked("riley-ready") }
				step {
					code = java.nio.file.Files.readString(marks.resolve("Riley.name"))
					check("sent Riley a request", ask { it.addFriend(code) }.isSuccess)
					mark("alex-asked")
				}
				steps += { marked("riley-declined") }
				step {
					check("a declined request is gone from his side too", from("Riley", "outgoing") == null)
					check("asked again", ask { it.addFriend(code) }.isSuccess)
					mark("alex-asked-again")
				}
				steps += { marked("riley-blocked") }
				step {
					val r = ask { it.addFriend(code) }
					TandemClient.LOG.info("Walk check, Alex: asking someone who blocked him {}", if (r.isSuccess) "looked sent" else "was refused")
					mark("alex-tried")
				}
				steps += { marked("riley-done") && marked("space-small") }
				step { openWorld(it, "tandem-walk-full", "Full Base") }
				steps += { it.player != null && it.screen == null }
				var t: WorldSync.Transfer? = null
				step { t = d.firstUploadOpen(it.singleplayerServer!!, "Full Base", emptyList()) }
				var n = 0
				steps += { t!!.future.isDone || ++n > 2400 }
				step {
					val why = runCatching { t!!.future.join() }.exceptionOrNull()?.let { e -> (e.cause ?: e).message }.orEmpty()
					TandemClient.LOG.info("Walk check, Alex: Sync this world with no space left: {}", why.ifEmpty { "it uploaded" })
					check("Sync this world says the storage is full", Regex("(?i)space|storage|full").containsMatchIn(why))
				}
				sleep(40)
				step { shot(it, "walk-full") }
				step { it.level?.disconnect(); it.disconnect(TitleScreen()) }
				steps += { atTitle(it) }
				// The same world from the Play list: the Sync dialog says it won't fit and Sync is off, before any copy.
				step { it.setScreen(PlayScreen()) }
				var wait = 0
				steps += { RealStorage.of { } != null || ++wait > 600 }
				step { dialogTargets.remove("sync-go"); (it.screen as PlayScreen).dev("sync-level:tandem-walk-full") }
				sleep(20)
				step {
					val go = dialogTargets["sync-go"] as? app.keplersync.tandem.fabric.ui.Button
					check("the Sync dialog says a world that won't fit can't sync, and Sync is off", go != null && !go.enabled)
					shot(it, "walk-wont-fit")
				}
				// Full Base's first upload was refused: it waits to upload, and is still Alex's to play here meanwhile.
				keys(GLFW.GLFW_KEY_ESCAPE)
				var n2 = 0
				steps += { Sample.worlds.any { w -> w.name == "Full Base" && w.cloudId != null } || ++n2 > 600 }
				step {
					check("a world whose first upload was refused says it's waiting to upload",
						Sample.worlds.any { w -> w.name == "Full Base" && w.state == WorldState.FIRST && w.upload == null })
					shot(it, "walk-refused-first")
					click(it, target(it, "button-Full Base"))
				}
				var n3 = 0
				steps += { it.player != null && it.screen == null || ++n3 > 1200 }
				step { check("and it still opens here to play", it.player != null) }
				step { it.level?.disconnect(); it.disconnect(TitleScreen()) }
				steps += { atTitle(it) }
				step { it.setScreen(TitleScreen()) }
				step { mark("alex-done") }
			}
			step { it.stop() }
		}
		ClientTickEvents.END_CLIENT_TICK.register(::tick)
	}

	/**
	 * A big world through KeplerSync, Alex and Sam at once (dev/checks.sh big, with saves/tandem-big already there):
	 * Alex syncs it the pause card's way (a flush save, then copied with autosave off while he plays), plays through a
	 * checkpoint, leaves, and it uploads; Sam then plays it, which downloads it all. Tandem's own lines give the
	 * timings; this adds how long each part took end to end and the longest the world's server stood still.
	 */
	fun bigCheck() {
		val a = KsAccount.get() ?: error("The big check needs -Dtandem.server")
		val marks = java.nio.file.Paths.get(System.getProperty("tandem.checkdir") ?: error("The big check needs -Dtandem.checkdir"))
		java.nio.file.Files.createDirectories(marks)
		fun mark(name: String) = java.nio.file.Files.writeString(marks.resolve(name), "")
		fun marked(name: String) = java.nio.file.Files.exists(marks.resolve(name))
		// The longest gap between two server ticks while it runs: a freeze the player would feel.
		var lastTick = 0L
		var longest = 0L
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register {
			val now = System.nanoTime()
			if (lastTick != 0L) longest = maxOf(longest, now - lastTick)
			lastTick = now
		}
		steps += { a.signedIn() && a.friends.isNotEmpty() }
		size(1920, 1080, 3)
		step { it.options.pauseOnLostFocus = false }
		step { mc ->
			val me = a.me.displayName
			val d = WorldSync.get()!!
			fun check(what: String, ok: Boolean) = TandemClient.LOG.info("Big check, {}: {} {}", me, if (ok) "OK" else "FAILED", what)
			fun secs(since: Long) = "%.1f s".format((System.nanoTime() - since) / 1e9)
			if (me == "Alex") {
				var t0 = 0L
				var t: WorldSync.Transfer? = null
				step { t0 = System.nanoTime(); openWorld(it, "tandem-big", "Big Base") }
				steps += { it.player != null && it.screen == null }
				step { TandemClient.LOG.info("Big check, Alex: Big Base open in {}", secs(t0)); lastTick = 0L; longest = 0L }
				sleep(100)
				step { t0 = System.nanoTime(); lastTick = 0L; longest = 0L; t = d.firstUploadOpen(it.singleplayerServer!!, "Big Base", ids("Sam")); mark("alex-uploading") }
				var n = 0
				steps += { t!!.future.isDone || t!!.progress.fraction() > 0.15f || ++n > 6000 }
				step { it.pauseGame(false) }
				sleep(10)
				// The world stands still while paused for a screenshot; that pause isn't a freeze, so it isn't counted.
				step { shot(it, "big-a-uploading"); lastTick = 0L; it.setScreen(null) }
				// Storage goes away part way (the runner stops it): the upload waits, and carries on once it's back.
				step { mark("storage-stop") }
				steps += { marked("storage-stopped") }
				n = 0
				steps += { t!!.waiting || t!!.future.isDone || ++n > 2400 }
				step { check("the first upload waits when storage can't be reached, rather than failing", t!!.waiting && !t!!.future.isDone) }
				step { it.pauseGame(false) }
				sleep(10)
				step { shot(it, "big-a-waiting-card"); it.setScreen(PlayScreen()) }
				n = 0
				steps += { Sample.worlds.any { w -> w.name == "Big Base" && w.cloudId != null } || ++n > 600 }
				sleep(20)
				step {
					check("the Play list says it's waiting to upload", Sample.worlds.any { w -> w.name == "Big Base" && w.state == WorldState.FIRST && app.keplersync.tandem.fabric.ui.waitingToUpload(w.upload) })
					shot(it, "big-a-waiting-list")
					lastTick = 0L
					it.setScreen(null)
					mark("storage-start")
				}
				steps += { marked("storage-started") }
				n = 0
				steps += { t!!.future.isDone || ++n > 72_000 }
				step {
					check("synced Big Base in ${secs(t0)}, storage stopped part way", t!!.future.isDone && !t!!.future.isCompletedExceptionally)
					check("the world never stood still over 2 s meanwhile (longest ${longest / 1_000_000} ms)", longest < 2_000_000_000L)
					lastTick = 0L; longest = 0L
				}
				// Long enough for a checkpoint with -Dtandem.checkpointSeconds=20.
				sleep(900)
				step { check("checkpoints kept the world under 2 s too (longest ${longest / 1_000_000} ms)", longest < 2_000_000_000L) }
				step { t0 = System.nanoTime(); it.level?.disconnect(); it.disconnect(TitleScreen()) }
				steps += { atTitle(it) }
				n = 0
				steps += { d.background?.let { b -> b.world == "tandem-big" && b.done() } == true || ++n > 72_000 }
				step { check("the last session uploaded in ${secs(t0)}", d.background?.world == "tandem-big" && d.background?.future?.isCompletedExceptionally == false); mark("alex-done") }
			} else {
				// Alex's first upload, as a friend sees it: how far it's got, and Play when ready.
				steps += { marked("alex-uploading") }
				play()
				var n = 0
				steps += { Sample.worlds.any { w -> w.name == "Big Base" && w.state == WorldState.SAVING } || ++n > 3000 }
				step {
					check("Alex's first upload shows as Alex saving, with how far it's got", Sample.worlds.any { w -> w.name == "Big Base" && w.state == WorldState.SAVING })
					shot(it, "big-b-saving")
				}
				steps += { inView(it, "button-Big Base") }
				step { click(it, target(it, "button-Big Base")) }
				sleep(20)
				step { shot(it, "big-b-play-when-ready") }
				keys(GLFW.GLFW_KEY_ESCAPE)
				step { it.setScreen(TitleScreen()) }
				steps += { marked("alex-done") }
				play()
				n = 0
				steps += { Sample.worlds.any { w -> w.name == "Big Base" && w.cloudId != null && w.state == WorldState.READY } || ++n > 6000 }
				var t0 = 0L
				steps += { inView(it, "button-Big Base") }
				step { t0 = System.nanoTime(); click(it, target(it, "button-Big Base")) }
				sleep(60)
				step { shot(it, "big-b-getting") }
				n = 0
				steps += { it.player != null && it.screen == null || ++n > 72_000 }
				step { check("got all of Big Base and was in it in ${secs(t0)}", it.player != null) }
				sleep(100)
				step { it.level?.disconnect(); it.disconnect(TitleScreen()) }
				steps += { atTitle(it) }
				n = 0
				steps += { d.background?.done() == true || ++n > 72_000 }
				step { mark("sam-done") }
			}
			step { it.stop() }
		}
		ClientTickEvents.END_CLIENT_TICK.register(::tick)
	}

	/**
	 * Dev only (`gradlew :fabric:runShootA -Ptandem.shoot="..."`, dev/checks.sh mistakes): one game against the local
	 * KeplerSync, a few steps, and real screenshots of what it shows. Steps, separated by `;`, with `+` for a space:
	 * size:W:H:GUI, title, play, play:<dev>, click:<target>, esc, tab, type:<text>, wait:<ticks>, signed (until signed in, at most a
	 * minute), listed:<world> (until the world list has it as a synced world), shot:<name>. The game quits at the end.
	 */
	fun shoot() {
		for (s in System.getProperty("tandem.shoot").orEmpty().split(';').map { it.trim().replace('+', ' ') }.filter { it.isNotEmpty() }) {
			val arg = s.substringAfter(':', "")
			when (s.substringBefore(':')) {
				"size" -> arg.split(':').map(String::toInt).let { (w, h, g) -> size(w, h, g) }
				"title" -> step { it.setScreen(TitleScreen()) }
				"play" -> play(arg.ifEmpty { null })
				"click" -> step { click(it, target(it, arg)) }
				"esc" -> keys(GLFW.GLFW_KEY_ESCAPE)
				"tab" -> keys(GLFW.GLFW_KEY_TAB)
				"type" -> step { mc -> arg.forEach { ch -> mc.screen?.charTyped(ch, 0) } }
				"wait" -> sleep(arg.toInt())
				"signed" -> { var n = 0; steps += { KsAccount.get()?.signedIn() == true || ++n > 1200 } }
				"listed" -> { var n = 0; steps += { Sample.worlds.any { w -> w.name == arg && w.cloudId != null } || ++n > 1200 } }
				"shot" -> step { shot(it, arg) }
				else -> TandemClient.LOG.warn("Shoot: no step called {}", s)
			}
		}
		step { it.stop() }
		ClientTickEvents.END_CLIENT_TICK.register(::tick)
	}

	private const val INTL_WORLD = "Мой мир 世界 🌍✨"
	private const val LONG_WORLD = "A world with a name so long that it can't possibly fit on one line of the list"
	private const val LONG_FRIEND = "Zoë O'Connor-McAllister-Smythe the Third"

	/** The sample's worlds and friends, plus names in other scripts, emoji, a styled Discord name and very long ones. */
	private fun stressNames() {
		val bands = Sample.worlds.first().bands
		fun world(name: String, state: WorldState, who: String = "", ago: String = "", pct: Int = 0) =
			World(name, state, bands, who = who, ago = ago, pct = pct).also { w ->
				w.size = "1.4 GB"; w.owner = "You"; w.shared += listOf("You", "李小龙", LONG_FRIEND, "🔥Gamer🔥", "محمد")
			}
		Sample.worlds.add(0, world(INTL_WORLD, WorldState.READY, who = "李小龙", ago = "5 min ago"))
		Sample.worlds.add(1, world(LONG_WORLD, WorldState.HOSTED, who = LONG_FRIEND))
		Sample.worlds.add(2, world("محمد العربية", WorldState.SAVING, who = "محمد", pct = 37))
		Sample.worlds.add(3, world("ひらがな の せかい", WorldState.READY, who = "🔥Gamer🔥", ago = "2 h ago"))
		Sample.friends.addAll(listOf(
			Person("李小龙", 3, true, "Playing $INTL_WORLD", inWorld = true, bars = 3),
			Person("🔥Gamer🔥", 5, true, "In the menu"),
			Person(LONG_FRIEND, 7, true, "Hosting $LONG_WORLD", hosting = true),
			Person("محمد", 11, true, "In Atlas").also { it.atlasOnly = true },
			Person("José Ñúñez", 13, false, "Offline · 5 days ago"),
			Person("ひらがな", 1, false, "Offline · yesterday"),
		))
		repeat(30) { Sample.friends.add(Person("Friend %02d".format(it + 1), it % 16, false, "Offline · ${it + 2} days ago")) }
		Sample.friends.sortBy { it.rank }
		Sample.requests.add(Person("𝓙𝓪𝔂 ✨", 15, true, "Wants to be friends"))
	}

	private fun openWorld(mc: Minecraft, id: String = "tandem-shots", name: String = "Sky Base", mode: GameType = GameType.CREATIVE) {
		val flows = mc.createWorldOpenFlows()
		if (mc.levelSource.levelExists(id)) flows.openWorld(id) { mc.setScreen(TitleScreen()) }
		else flows.createFreshLevel(
			id,
			LevelSettings(name, mode, false, Difficulty.PEACEFUL, true, GameRules(), WorldDataConfiguration.DEFAULT),
			WorldOptions(0L, false, false),
			WorldPresets::createNormalWorldDimensions,
			TitleScreen(),
		)
	}

	private fun tick(mc: Minecraft) {
		if (!started) {
			if (mc.screen !is TitleScreen) return
			started = true
			wait = 40
		}
		if (wait-- > 0) return
		val step = steps.firstOrNull() ?: return
		if (step(mc)) {
			steps.removeFirst()
			wait = 10
		}
	}

	/** The Synced worlds tab, folded, searched, and How to play for each kind of world, at three window sizes. */
	private fun synced() {
		for ((w, h, g) in listOf(Triple(1366, 768, 2), Triple(1920, 1080, 3), Triple(2560, 1440, 4))) {
			size(w, h, g)
			for (d in listOf("synced", "synced-folded", "how-differs", "how-differs-list", "how-version", "how-update", "how-get", "how-own", "how-own-list")) {
				play(d)
				sleep(40)
				step { shot(it, d) }
			}
			for ((q, name) in listOf("sky" to "synced-search", "big" to "synced-unknown")) {
				play("synced")
				step { click(it, target(it, "synced-search")) }
				step { mc -> q.forEach { ch -> mc.screen!!.charTyped(ch, 0) } }
				step { shot(it, name) }
			}
			// Forty more worlds: the long list, then folded with its own Collapse all, then open again with Expand all.
			play("synced-many")
			sleep(40)
			step { shot(it, "synced-many") }
			step { click(it, target(it, "synced-toggle")) }
			sleep(20)
			step { shot(it, "synced-many-folded") }
			step { click(it, target(it, "synced-toggle")) }
			sleep(20)
			step { shot(it, "synced-many-open") }
			play("synced-many-off")
		}
	}

	private fun shot(mc: Minecraft, what: String) {
		val w = mc.window
		val name = "tandem-$what-${w.width}x${w.height}-gui${w.guiScale.toInt()}.png"
		Screenshot.grab(mc.gameDirectory, name, mc.mainRenderTarget) { TandemClient.LOG.info("Shot {}", name) }
	}
}
