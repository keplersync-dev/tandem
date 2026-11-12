package app.keplersync.tandem.fabric.ui

import gg.essential.universal.UMatrixStack
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.AbstractButton
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.contents.TranslatableContents
import java.awt.Color
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The title screen's Tandem button: a violet half button with the mark and "Tandem" beside
 * Multiplayer, or, when another mod already uses that row, a violet tile the size of Minecraft's icon
 * buttons in the first free spot. Both have Minecraft's black outline and bevel, the amber count of what
 * needs you; the tile also has a tooltip. A vanilla widget, so keyboard navigation and narration reach it.
 */
class TitleButton(x: Int, y: Int, w: Int, private val wide: Boolean, private val open: Runnable) :
	AbstractButton(x, y, w, SIZE, Component.literal(label(Sample.needsYou))) {
	private val count get() = Sample.needsYou

	init {
		// FancyMenu's layout editor finds a widget by its id: a fixed one lets a pack move or hide this button for good.
		runCatching { javaClass.getMethod("setWidgetIdentifierFancyMenu", String::class.java).invoke(this, "tandem") }
	}

	override fun onPress() = open.run()

	override fun updateWidgetNarration(out: NarrationElementOutput) = defaultButtonNarrationText(out)

	override fun renderWidget(g: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
		val mc = Minecraft.getInstance()
		val keyboard = isFocused && mc.lastInputType.isKeyboard
		val lit = isHovered || keyboard
		// Minecraft's button shape in whole GUI units: a black outline (white when pointed at), a light top-left
		// edge and a dark bottom-right one, here in Tandem violet.
		val x0 = x; val y0 = y; val x1 = x + width; val y1 = y + height
		g.fill(x0, y0, x1, y1, if (lit) 0xFFFFFFFF.toInt() else 0xFF000000.toInt())
		g.fillGradient(x0 + 1, y0 + 1, x1 - 1, y1 - 1, if (lit) 0xFFB49BFF.toInt() else 0xFF9B7BFF.toInt(), if (lit) 0xFF8B5CF6.toInt() else 0xFF7C3AED.toInt())
		g.fill(x0 + 1, y0 + 1, x1 - 1, y0 + 2, 0xFFC4B5FD.toInt())
		g.fill(x0 + 1, y0 + 1, x0 + 2, y1 - 1, 0xFFC4B5FD.toInt())
		g.fill(x0 + 1, y1 - 2, x1 - 1, y1 - 1, 0xFF5B21B6.toInt())
		g.fill(x1 - 2, y0 + 1, x1 - 1, y1 - 1, 0xFF5B21B6.toInt())
		// A session uploading after you left fills it on a darker violet: the button left to right, the tile bottom to top.
		val run = Sample.background?.third
		if (run != null) {
			val v = run.value.coerceIn(0f, 1f)
			g.fill(x0 + 1, y0 + 1, x1 - 1, y1 - 1, 0xFF3D2A86.toInt())
			if (wide) g.fill(x0 + 1, y0 + 1, x0 + 1 + ((x1 - x0 - 2) * v).roundToInt(), y1 - 1, if (lit) 0xFF9B7BFF.toInt() else 0xFF8A67F0.toInt())
			else g.fill(x0 + 1, y1 - 1 - ((y1 - y0 - 2) * v).roundToInt(), x1 - 1, y1 - 1, if (lit) 0xFF9B7BFF.toInt() else 0xFF8A67F0.toInt())
		}
		Scale.update()
		g.flush()
		val ms = UMatrixStack(g.pose())
		val l = x0.toFloat(); val t = y0.toFloat(); val r = x1.toFloat(); val b = y1.toFloat()
		// The mark's solid leader is heavier than its hollow follower, so centred by the eye it sits a little left.
		val mark = 11f
		if (wide) {
			val text = if (run != null) uploadedShort(run) else tr("tandem.tandem")
			val tw = mc.font.width(text)
			val left = (l + r - (mark + 4 + tw)) / 2
			drawIcon(ms, if (run != null) "up" else "logo", left, (t + b - mark) / 2, mark, Color.WHITE)
			g.drawString(mc.font, text, (left + mark + 4).roundToInt(), ((t + b) / 2 - 4).roundToInt(), 0xFFFFFF, true)
		} else drawIcon(ms, "logo", (l + r - mark) / 2 - 0.6f, (t + b - mark) / 2, mark, Color.WHITE)
		// Drawn as the design's 40 px tile stretched to Minecraft's 20-unit button, so it keeps its weight at any GUI scale.
		val k = height * mc.window.guiScale / (40 * Scale.s)
		fun q(n: Number) = (n.toDouble() * k).tp
		if (count > 0) {
			val n = count.toString()
			val w = max(q(20), textWidth(n, 12 * k, Weight.BOLD) + q(10))
			val bx = r + q(7) - w; val by = t - q(7)
			rounded(ms, bx - q(2), by - q(2), bx + w + q(2), by + q(22), q(12), Tok.violetInk)
			rounded(ms, bx, by, bx + w, by + q(20), q(10), Tok.warn)
			drawText(ms, n, 12 * k, Weight.BOLD, Tok.warnInk, bx + (w - textWidth(n, 12 * k, Weight.BOLD)) / 2, by + q(10) - q(12) * LINE_EM / 2)
		}
		// The tile has no room for words: how far and how long go in a small label beside it while it uploads.
		if (run != null && !wide && !lit) {
			val label = uploadedShort(run)
			val h = 24.tp; val w = textWidth(label, 12.5, Weight.MEDIUM) + 18.tp
			val lx = if (r + 6.tp + w > mc.window.guiScaledWidth - 4) l - 6.tp - w else r + 6.tp
			val ly = (t + b - h) / 2
			rounded(ms, lx, ly, lx + w, ly + h, h / 2, Tok.line)
			rounded(ms, lx + 1.tp, ly + 1.tp, lx + w - 1.tp, ly + h - 1.tp, h / 2 - 1.tp, Tok.alpha(Tok.panel, 0.94))
			drawText(ms, label, 12.5, Weight.MEDIUM, Tok.ink, lx + 9.tp, (t + b) / 2 - (12.5 * LINE_EM).tp / 2)
		}
		// The full button says Tandem on its face; only the bare tile needs its name beside it.
		if (lit && !wide) tooltip(ms, mc, r + 12.tp, t + 4.tp)
	}

	/** "63% · 20 s": short enough for the button's face. */
	private fun uploadedShort(run: Prog) = "${(run.value * 100).roundToInt()}%" + (leftText(run.secondsLeft)?.let { " · $it" } ?: "")

	/** Tandem's own tooltip: the name, then what needs you and who's playing. Flips left if it would leave the screen. */
	private fun tooltip(ms: UMatrixStack, mc: Minecraft, x0: Float, top: Float) {
		val title = tr("tandem.tandem")
		val bg = Sample.background
		val line = if (bg != null) "${if (bg.second != null) tr("tandem.handing_off_to_2", bg.second) else tr("tandem.saving", bg.first)} · ${uploadedText(bg.third)}"
		else listOfNotNull(if (count > 0) tr("tandem.need_you", count) else null, playing()).joinToString(" · ").ifEmpty { tr("tandem.play_with_friends") }
		val w = max(textWidth(title, 13, Weight.SEMIBOLD), textWidth(line, 12.5, Weight.REGULAR)) + 22.tp
		val h = (13 * LINE_EM + 2 + 12.5 * LINE_EM + 16).tp
		val x = if (x0 + w > mc.window.guiScaledWidth - 4) x.toFloat() - 12.tp - w else x0
		rounded(ms, x, top, x + w, top + h, 8.tp, Tok.line)
		rounded(ms, x + 1.tp, top + 1.tp, x + w - 1.tp, top + h - 1.tp, 7.tp, Tok.alpha(Tok.panel, 0.96))
		drawText(ms, title, 13, Weight.SEMIBOLD, Tok.ink, x + 11.tp, top + 8.tp)
		drawText(ms, line, 12.5, Weight.REGULAR, Tok.muted, x + 11.tp, top + (8 + 13 * LINE_EM + 2).tp)
	}

	/** Who's playing: a friend's game you're invited to, or the sample's Sam. */
	private fun playing(): String? {
		if (!RealFriends.on) return tr("tandem.sam_is_hosting")
		return app.keplersync.tandem.fabric.account.Account.get()?.invites?.firstOrNull()?.let { tr("tandem.is_hosting", it.host.displayName, it.worldName) }
	}

	companion object {
		/** Minecraft's buttons are 20 GUI units tall, and its icon buttons (language, accessibility) as wide. */
		const val SIZE = 20
		private const val GAP = 4

		private fun label(n: Int) = if (n > 0) tr("tandem.tandem_need_you", n) else tr("tandem.tandem")

		/**
		 * Puts the button on the title screen ([widgets] are its buttons, in GUI units). Beside Multiplayer when that
		 * row is Minecraft's own: Multiplayer gives up half its width, like Options and Quit Game share a row. Otherwise
		 * the tile, in the first free spot: beside Singleplayer on the right, then on its left, beside Multiplayer, then
		 * the top-right and top-left corners, so a pack that rearranges the menu just moves Tandem along.
		 */
		@JvmStatic
		fun add(widgets: MutableList<AbstractWidget>, screenWidth: Int, screenHeight: Int, open: Runnable) {
			fun find(key: String) = widgets.firstOrNull { (it.message.contents as? TranslatableContents)?.key == key }
			fun free(x: Int, y: Int, w: Int, h: Int, except: AbstractWidget? = null) = x >= 0 && y >= 0 && x + w <= screenWidth && y + h <= screenHeight &&
				widgets.none { o -> o !== except && o.visible && x < o.x + o.width + 2 && x + w + 2 > o.x && y < o.y + o.height + 2 && y + h + 2 > o.y }
			val single = find("menu.singleplayer")
			val multi = find("menu.multiplayer")
			if (multi != null && multi.visible && multi.width >= 150 && multi.height == SIZE) {
				val half = (multi.width - GAP) / 2
				if (free(multi.x + half + GAP, multi.y, multi.width - half - GAP, SIZE, except = multi)) {
					val w = multi.width - half - GAP
					multi.width = half
					widgets.add(TitleButton(multi.x + half + GAP, multi.y, w, true, open))
					return
				}
			}
			val spots = buildList {
				single?.let { add(intArrayOf(it.x + it.width + GAP, it.y)); add(intArrayOf(it.x - GAP - SIZE, it.y)) }
				multi?.let { add(intArrayOf(it.x + it.width + GAP, it.y)) }
				add(intArrayOf(screenWidth - SIZE - GAP, GAP))
				add(intArrayOf(GAP, GAP))
			}
			spots.firstOrNull { (x, y) -> free(x, y, SIZE, SIZE) }?.let { (x, y) -> widgets.add(TitleButton(x, y, SIZE, false, open)) }
		}
	}
}
