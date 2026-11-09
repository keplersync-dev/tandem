package app.keplersync.tandem.fabric.ui

import gg.essential.elementa.UIComponent
import gg.essential.universal.UMatrixStack
import net.minecraft.client.gui.components.AbstractWidget
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Keyboard focus for the open Tandem screen: Tab and Shift+Tab move in the order things were added (reading
 * order), Enter or Space presses. The ring shows only after a key is used, like the browser's :focus-visible.
 * A dialog or menu opens its own scope, so Tab stays inside it, and closing it puts focus back where it was.
 */
object Focus {
	private class Item(val c: UIComponent?, val widget: AbstractWidget?, val radius: Float, val press: () -> Unit) {
		val bounds: FloatArray?
			get() = c?.let { floatArrayOf(it.getLeft(), it.getTop(), it.getRight(), it.getBottom()) }
				?: widget?.takeIf { it.visible }?.let { floatArrayOf(it.x.toFloat(), it.y.toFloat(), it.right.toFloat(), it.bottom.toFloat()) }
	}

	private class Scope {
		val items = mutableListOf<Item>()
		var at = -1
	}

	private val scopes = ArrayDeque(listOf(Scope()))
	private val top get() = scopes.last()
	private var visible = false

	fun reset() {
		scopes.clear()
		scopes += Scope()
		visible = false
	}

	fun add(c: UIComponent, radius: Float, press: () -> Unit) {
		top.items += Item(c, null, radius, press)
	}

	/** A vanilla button on a screen Tandem shares, so one Tab order covers both. */
	fun add(widget: AbstractWidget, press: () -> Unit) {
		top.items += Item(null, widget, 0f, press)
	}

	/** Items added from now on belong to a new dialog or menu. */
	fun push() {
		scopes += Scope()
	}

	/** Called once the dialog or menu is built: from the keyboard, focus starts on its first item. */
	fun entered() {
		if (visible && top.items.isNotEmpty()) top.at = 0
	}

	fun pop() {
		if (scopes.size > 1) scopes.removeLast()
	}

	fun move(back: Boolean) {
		val s = top
		if (s.items.isEmpty()) return
		s.at = if (s.at < 0) (if (back) s.items.lastIndex else 0) else (s.at + if (back) -1 else 1).mod(s.items.size)
		visible = true
		s.items[s.at].c?.let(::reveal)
	}

	fun press(): Boolean {
		val item = top.items.getOrNull(top.at)?.takeIf { visible } ?: return false
		item.press()
		return true
	}

	fun hide() {
		visible = false
	}

	private fun reveal(c: UIComponent) {
		var p: UIComponent? = c.parent
		while (p != null && p !is Scroller) p = if (p.parent === p) null else p.parent
		(p as? Scroller)?.reveal(c.getTop(), c.getBottom(), 12.tp)
	}

	/** Draws the ring on the focused item. Added last so it sits on top; it has no size, so it never takes a click. */
	class Ring : UIComponent() {
		override fun draw(matrixStack: UMatrixStack) {
			beforeDraw(matrixStack)
			val item = top.items.getOrNull(top.at)
			val b = item?.bounds
			if (visible && b != null) focusRing(matrixStack, b[0], b[1], b[2], b[3], item.radius)
			super.draw(matrixStack)
		}
	}
}

/** 2 px of violet-hi, 2 px outside the item, following its corners like the browser's outline. */
fun focusRing(ms: UMatrixStack, left: Float, top: Float, right: Float, bottom: Float, radius: Float) {
	val g = Raster.gui
	val w = (2.tp * g).roundToInt()
	val out = (4.tp * g).roundToInt()
	val x0 = (left * g).roundToInt() - out; val y0 = (top * g).roundToInt() - out
	val x1 = (right * g).roundToInt() + out; val y1 = (bottom * g).roundToInt() + out
	val n = min((radius * g).roundToInt() + out, min(x1 - x0, y1 - y0) / 2).coerceAtLeast(w)
	fun bar(a: Int, b: Int, c: Int, d: Int) = rounded(ms, (a / g).toFloat(), (b / g).toFloat(), (c / g).toFloat(), (d / g).toFloat(), 0f, Tok.violetHi)
	bar(x0 + n, y0, x1 - n, y0 + w)
	bar(x0 + n, y1 - w, x1 - n, y1)
	bar(x0, y0 + n, x0 + w, y1 - n)
	bar(x1 - w, y0 + n, x1, y1 - n)
	val c = Raster.ring(n, w).loc
	val s = (n / g).toFloat()
	val l = (x0 / g).toFloat(); val t = (y0 / g).toFloat(); val r = ((x1 - n) / g).toFloat(); val b = ((y1 - n) / g).toFloat()
	Tex.draw(ms, c, l, t, s, s, Tok.violetHi)
	Tex.draw(ms, c, r, t, s, s, Tok.violetHi, 1f, 0f, 0f, 1f)
	Tex.draw(ms, c, l, b, s, s, Tok.violetHi, 0f, 1f, 1f, 0f)
	Tex.draw(ms, c, r, b, s, s, Tok.violetHi, 1f, 1f, 0f, 0f)
}
