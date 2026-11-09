package app.keplersync.tandem.fabric.ui

import gg.essential.elementa.UIComponent
import gg.essential.elementa.components.UIBlock
import gg.essential.elementa.components.UIContainer
import gg.essential.elementa.components.Window
import gg.essential.elementa.constraints.CenterConstraint
import gg.essential.elementa.constraints.SiblingConstraint
import gg.essential.elementa.dsl.basicHeightConstraint
import gg.essential.elementa.dsl.basicWidthConstraint
import gg.essential.elementa.dsl.basicYConstraint
import gg.essential.elementa.dsl.childOf
import gg.essential.elementa.dsl.constrain
import gg.essential.elementa.dsl.minus
import gg.essential.elementa.dsl.percent
import gg.essential.elementa.dsl.pixels
import gg.essential.universal.UMatrixStack
import java.awt.Color
import kotlin.math.max

/** Stacks children top to bottom, [gap] apart; as tall as they are. */
class Column(private val gap: Int) : UIContainer() {
	/** Space above each child, so the height never depends on where the column itself sits. */
	private val above = mutableListOf<Int>()

	init {
		constrain { height = basicHeightConstraint { c -> c.children.sumOf { it.getHeight().toDouble() }.toFloat() + above.sum().tp } }
	}

	fun add(c: UIComponent, extraTop: Int = 0): UIComponent {
		val first = children.isEmpty()
		above += if (first) extraTop else gap + extraTop
		c.constrain { y = if (first) extraTop.at else SiblingConstraint((gap + extraTop).tp) }
		return c childOf this
	}
}

/**
 * Runs [tick] once a frame, just after the frame is drawn, so it may rebuild the screen (Elementa refuses changes
 * to the component tree while it's drawing it). Draws nothing.
 */
class Ticker(private val tick: () -> Unit) : UIComponent() {
	override fun draw(matrixStack: UMatrixStack) {
		beforeDraw(matrixStack)
		Window.enqueueRenderOperation { tick() }
		super.draw(matrixStack)
	}
}

/** A rounded bar filled to [value] (0 to 1). 8 px tall when big. */
class Progress(private val warn: Boolean, h: Int, private val value: () -> Float) : UIComponent() {
	init {
		constrain { height = h.at }
	}

	override fun draw(matrixStack: UMatrixStack) {
		beforeDraw(matrixStack)
		val l = getLeft(); val t = getTop(); val r = getRight(); val b = getBottom()
		val radius = (b - t) / 2
		rounded(matrixStack, l, t, r, b, radius, Tok.track)
		val v = value().coerceIn(0f, 1f)
		if (v > 0f) rounded(matrixStack, l, t, l + (r - l) * v, b, radius, if (warn) Tok.warn else Tok.violet)
		super.draw(matrixStack)
	}
}

/** A box with a 1 px dashed border, for tips and alternatives. */
class Dashed(radius: Int, private val color: java.awt.Color = Tok.outline) : UIComponent() {
	private val r = radius

	override fun draw(matrixStack: UMatrixStack) {
		beforeDraw(matrixStack)
		val g = Raster.gui
		val x0 = (getLeft() * g).toInt(); val y0 = (getTop() * g).toInt(); val x1 = (getRight() * g).toInt(); val y1 = (getBottom() * g).toInt()
		val n = (r.tp * g).toInt()
		val w = max(1, (1.tp * g).toInt())
		val dash = 3 * w
		fun px(v: Int) = (v / g).toFloat()
		var x = x0 + n
		while (x < x1 - n) { val e = minOf(x + dash, x1 - n); rounded(matrixStack, px(x), px(y0), px(e), px(y0 + w), 0f, color); rounded(matrixStack, px(x), px(y1 - w), px(e), px(y1), 0f, color); x += 2 * dash }
		var y = y0 + n
		while (y < y1 - n) { val e = minOf(y + dash, y1 - n); rounded(matrixStack, px(x0), px(y), px(x0 + w), px(e), 0f, color); rounded(matrixStack, px(x1 - w), px(y), px(x1), px(e), 0f, color); y += 2 * dash }
		val c = Raster.ring(n, w).loc
		val s = px(n)
		Tex.draw(matrixStack, c, px(x0), px(y0), s, s, color)
		Tex.draw(matrixStack, c, px(x1 - n), px(y0), s, s, color, 1f, 0f, 0f, 1f)
		Tex.draw(matrixStack, c, px(x0), px(y1 - n), s, s, color, 0f, 1f, 1f, 0f)
		Tex.draw(matrixStack, c, px(x1 - n), px(y1 - n), s, s, color, 1f, 1f, 0f, 0f)
		super.draw(matrixStack)
	}
}

/** A link styled as text: violet-hi, 600, underlined. */
fun link(text: String, color: Color = Tok.violetHi, press: () -> Unit): Label {
	val l = Label(text, 13, Weight.SEMIBOLD, color)
	l.underline = true
	l.onMouseClick { it.stopPropagation(); press() }
	Focus.add(l, 4.tp, press)
	return l
}

/**
 * A full-screen layer that only takes the mouse where one of its children is. Elementa gives a click to the
 * topmost component under the pointer, so an empty layer on top would otherwise swallow every click and scroll.
 */
class Layer : UIContainer() {
	override fun isPointInside(x: Float, y: Float) = children.any { it.isPointInside(x, y) }
}

/** One item of a popover menu: an optional icon, a title and an optional second line. */
class MenuItem(val title: String, val icon: String? = null, val small: String? = null, val press: () -> Unit)

/**
 * What sits above a Tandem screen: a popover menu, one dialog, toasts, and the focus ring. Screens rebuild on
 * resize; [attach] puts the layers back and reopens the dialog that was open.
 */
class Layers {
	private val menus = Layer()
	private val dialogs = Layer()
	private val toastBox = Layer()
	private var dialog: (() -> UIComponent)? = null
	private var menuOpen = false
	private var menuOwner: Any? = null
	private var toasts = listOf<Toast>()
	private var ring: UIComponent? = null
	private var window: Window? = null
	private val floated = mutableListOf<UIComponent>()

	/** How far toasts sit from the right edge, in Tandem px: 18, or 360 beside the pause card. */
	var toastRight = 18

	private class Toast(val title: String, val detail: String?, val icon: String, val undo: (() -> Unit)?, val until: Long, val action: String)

	val busy get() = menuOpen || dialog != null

	fun attach(window: Window) {
		// Screens rebuild into the same Window, which keeps its floating list: drop ours before building again.
		floated.forEach { window.removeFloatingComponent(it); this.window?.removeFloatingComponent(it) }
		floated.clear()
		this.window = window
		for (c in listOf(menus, dialogs, toastBox)) {
			c.clearChildren()
			c.constrain { width = 100.percent; height = 100.percent } childOf window
		}
		menuOpen = false
		menuOwner = null
		dialog?.let { showDialog(it) }
		drawToasts()
		Ticker { if (toasts.any { it.until < System.currentTimeMillis() }) { toasts = toasts.filter { it.until >= System.currentTimeMillis() }; drawToasts() } } childOf window
		ring = Focus.Ring() childOf window
	}

	/**
	 * An open menu or dialog floats: Elementa then gives hover to nothing underneath it, so rows below a menu don't
	 * light up. Floating things draw last, so the focus ring is floated again after it to stay on top.
	 */
	private fun float(c: UIComponent) {
		val w = window ?: return
		c.setFloating(true)
		floated += c
		ring?.let {
			if (it.isFloating) w.removeFloatingComponent(it) else it.setFloating(true)
			w.addFloatingComponent(it)
			floated.remove(it); floated += it
		}
	}

	private fun clear(layer: Layer) {
		layer.children.forEach { c -> window?.removeFloatingComponent(c); floated.remove(c) }
		layer.clearChildren()
	}

	/**
	 * A popover menu at [x], [y] (GUI units), [width] wide. If it would run off the bottom of
	 * the screen it opens upwards instead, ending at [flipAbove].
	 */
	fun openMenu(x: Float, y: Float, width: Float, items: List<MenuItem>, note: String? = null, flipAbove: Float? = null, owner: Any? = null) {
		// The button that opened a menu closes it again.
		if (menuOpen && owner != null && owner == menuOwner) { closeMenu(); return }
		closeMenu()
		menuOwner = owner
		Focus.push()
		val menu = box(12, Tok.raise2, Tok.outline).constrain { this.x = x.pixels; this.y = y.pixels; this.width = width.pixels } childOf menus
		val col = Column(0).constrain { this.x = 7.at; this.y = 7.at; this.width = 100.percent - 14.at } childOf menu
		if (note != null) {
			val n = UIContainer().constrain { this.width = 100.percent; height = (13 * LINE_EM + 20).at }
			Label(note, 13, Weight.REGULAR, Tok.muted).constrain { this.x = 12.at; this.y = CenterConstraint() } childOf n
			col.add(n)
		}
		for (it in items) col.add(menuRow(it))
		menu.constrain { height = basicHeightConstraint { col.getHeight() + 14.tp } }
		if (flipAbove != null && y + menu.getHeight() > menus.getBottom() - 8.tp) menu.constrain { this.y = (flipAbove - menu.getHeight()).pixels }
		menu.onMouseClick { e -> e.stopPropagation() }
		float(menu)
		menuOpen = true
		Focus.entered()
	}

	private fun menuRow(item: MenuItem): UIComponent {
		val row = box(8, Tok.raise2).constrain { width = 100.percent; height = (if (item.small != null) 49 else 40).at }
		var x = 12
		if (item.icon != null) {
			Icon(item.icon, 16, Tok.muted).constrain { this.x = 12.at; y = CenterConstraint() } childOf row
			x = 12 + 16 + 10
		}
		val text = Column(0).constrain { this.x = x.at; y = CenterConstraint(); width = basicWidthConstraint { c -> c.children.maxOf { it.getWidth() } } } childOf row
		// Friends' and worlds' names can be any length: each line ends in an ellipsis inside the menu.
		text.add(Label(item.title, 14, Weight.MEDIUM).fitWithin(row, 12))
		if (item.small != null) text.add(Label(item.small, 12.5, Weight.REGULAR, Tok.muted).fitWithin(row, 12))
		row.onMouseEnter { row.fill = Tok.line }
		row.onMouseLeave { row.fill = Tok.raise2 }
		val press = { closeMenu(); item.press() }
		row.onMouseClick { it.stopPropagation(); press() }
		Focus.add(row, row.corner, press)
		return row
	}

	fun closeMenu() {
		if (!menuOpen) return
		clear(menus)
		menuOpen = false
		menuOwner = null
		Focus.pop()
	}

	/** Opens a dialog built by [build]; it's rebuilt the same way after a resize. */
	fun openDialog(build: () -> UIComponent) {
		closeMenu()
		if (dialog != null) { clear(dialogs); Focus.pop() }
		dialog = build
		showDialog(build)
	}

	private fun showDialog(build: () -> UIComponent) {
		clear(dialogs)
		Focus.push()
		val wrap = UIContainer().constrain { width = 100.percent; height = 100.percent } childOf dialogs
		val scrim = UIBlock(Tok.dimmer).constrain { width = 100.percent; height = 100.percent } childOf wrap
		scrim.onMouseClick { closeDialog() }
		val box = build()
		box.onMouseClick { it.stopPropagation() }
		box.constrain { x = CenterConstraint(); y = CenterConstraint() } childOf wrap
		float(wrap)
		Focus.entered()
	}

	fun closeDialog() {
		if (dialog == null) return
		dialog = null
		clear(dialogs)
		Focus.pop()
		afterDialog?.invoke()
	}

	/** Runs whenever a dialog closes: a screen that exists only for one dialog closes with it. */
	var afterDialog: (() -> Unit)? = null

	/** Esc closes the menu, then the dialog. Returns false when there was nothing to close. */
	fun escape(): Boolean = when {
		menuOpen -> { closeMenu(); true }
		dialog != null -> { closeDialog(); true }
		else -> false
	}

	/** A toast: a tick (or [icon]), a line, a second line, Undo (or [action]) when it can be undone. 5 seconds, or [ms]. */
	fun toast(title: String, detail: String? = null, icon: String = "check", undo: (() -> Unit)? = null, action: String = tr("tandem.undo"), ms: Long = 5000) {
		toasts = listOf(Toast(title, detail, icon, undo, System.currentTimeMillis() + ms, action))
		drawToasts()
	}

	private fun drawToasts() {
		toastBox.clearChildren()
		var y = 18
		for (t in toasts) {
			val card = box(14, Tok.alpha(Tok.panel, 0.96), Tok.line).constrain {
				x = toastRight.fromEnd; this.y = y.at; width = 350.at
			} childOf toastBox
			val tile = box(8, Tok.raise2).constrain { x = 17.at; this.y = 15.at; width = 36.at; height = 36.at } childOf card
			Icon(t.icon, 18, Tok.violetHi).constrain { x = CenterConstraint(); this.y = CenterConstraint() } childOf tile
			// Beside the tile, centred on it when it's a line or two, top-aligned when longer.
			val text = Column(0).constrain { x = (17 + 36 + 12).at; width = (350 - 2 - 32 - 36 - 12).at }
			text.constrain { this.y = basicYConstraint { card.getTop() + 15.tp + max(0f, (36.tp - text.getHeight()) / 2) } } childOf card
			text.add(wrapped(t.title, 14, Weight.SEMIBOLD, Tok.ink, (350 - 2 - 32 - 36 - 12).tp, 1.4f))
			if (t.detail != null) text.add(wrapped(t.detail, 13, Weight.REGULAR, Tok.muted, (350 - 2 - 32 - 36 - 12).tp, 1.45f), 2)
			t.undo?.let { undo ->
				text.add(Button(t.action, Kind.OUTLINE, Size.TOAST, null, Tok.alpha(Tok.panel, 0.96)) {
					undo(); toasts = toasts - t; drawToasts()
				}, 10)
			}
			card.constrain { height = basicHeightConstraint { max(36.tp, text.getHeight()) + 30.tp } }
			card.onMouseClick { it.stopPropagation() }
		}
	}
}
