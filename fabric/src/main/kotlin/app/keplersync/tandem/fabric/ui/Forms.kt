package app.keplersync.tandem.fabric.ui

import gg.essential.elementa.UIComponent
import gg.essential.elementa.components.UIContainer
import gg.essential.elementa.constraints.CenterConstraint
import gg.essential.elementa.dsl.basicHeightConstraint
import gg.essential.elementa.dsl.basicWidthConstraint
import gg.essential.elementa.dsl.childOf
import gg.essential.elementa.dsl.constrain
import gg.essential.elementa.dsl.percent
import gg.essential.universal.UKeyboard
import gg.essential.universal.UMatrixStack
import net.minecraft.client.Minecraft
import kotlin.math.max

/**
 * A text field: 40 tall at 14 px, or a search box 34 tall at 13 px. Click or Tab to it, then
 * type. [masked] shows dots (a secret key); [lines] above 1 wraps and grows (a textarea).
 */
class Field(
	private val placeholder: String,
	width: Int?,
	height: Int = 34,
	initial: String = "",
	private val masked: Boolean = false,
	private val lines: Int = 1,
	private val changed: (String) -> Unit = {},
) : Box(9.tp, Tok.rail, Tok.outline) {
	var value = initial
		private set
	private var typing = false
	private val size = if (height >= 40) 14 else 13

	init {
		constrain {
			this.width = width?.at ?: 100.percent
			this.height = (if (lines > 1) 20 + lines * 21 else height).at
		}
		onMouseClick { it.stopPropagation(); grabWindowFocus() }
		onFocus { typing = true }
		onFocusLost { typing = false }
		onKeyType { typedChar, keyCode ->
			when {
				keyCode == UKeyboard.KEY_BACKSPACE -> value = value.dropLast(1)
				// Ctrl+V (Cmd+V on a Mac): the R2 keys are long, and the steps beside them say to paste them.
				keyCode == UKeyboard.KEY_V && UKeyboard.isCtrlKeyDown() -> value += Minecraft.getInstance().keyboardHandler.clipboard.filter { it >= ' ' && it != '\u007f' }
				typedChar >= ' ' && typedChar != '\u007f' -> value += typedChar
				else -> return@onKeyType
			}
			changed(value)
		}
		Focus.add(this, corner) { grabWindowFocus() }
	}

	private val shown get() = if (masked) "•".repeat(value.length) else value

	override fun draw(matrixStack: UMatrixStack) {
		super.draw(matrixStack)
		val x = getLeft() + 13.tp
		val maxW = getWidth() - 26.tp
		if (lines > 1) {
			// Wrapped by words, 21 px a line, like a textarea.
			val text = if (value.isEmpty()) placeholder else shown
			var line = ""
			var top = getTop() + 11.tp
			fun flush() { drawText(matrixStack, line, size, Weight.REGULAR, if (value.isEmpty()) Tok.muted else Tok.ink, x, top); top += 21.tp; line = "" }
			for (word in text.split(' ')) {
				val next = if (line.isEmpty()) word else "$line $word"
				if (line.isNotEmpty() && textWidth(next, size, Weight.REGULAR) > maxW) flush()
				line = if (line.isEmpty()) word else if (textWidth(next, size, Weight.REGULAR) > maxW) word else next
			}
			val caretTop = top
			val caretX = x + textWidth(line, size, Weight.REGULAR)
			flush()
			if (typing && System.currentTimeMillis() / 530 % 2 == 0L) {
				val cx = if (value.isEmpty()) x else caretX + 1.tp
				rounded(matrixStack, cx, caretTop + 2.tp, cx + 1.tp, caretTop + (size * LINE_EM - 1).tp, 0f, Tok.ink)
			}
			return
		}
		val top = getTop() + (getHeight() - size.tp * LINE_EM) / 2
		if (value.isEmpty()) drawText(matrixStack, placeholder, size, Weight.REGULAR, Tok.muted, x, top)
		else drawText(matrixStack, shown, size, Weight.REGULAR, Tok.ink, x, top)
		if (typing && System.currentTimeMillis() / 530 % 2 == 0L) {
			val cx = x + (if (value.isEmpty()) 0f else textWidth(shown, size, Weight.REGULAR)) + 1.tp
			rounded(matrixStack, cx, top + 1.tp, cx + 1.tp, top + (size * LINE_EM - 1).tp, 0f, Tok.ink)
		}
	}
}

/** A 13 px muted label over its field, 6 apart. */
fun labeled(label: String, field: UIComponent, width: Int? = null): UIComponent {
	val c = Column(6).constrain { this.width = width?.at ?: 100.percent }
	c.add(Label(label, 13, Weight.REGULAR, Tok.muted))
	c.add(field)
	return c
}

/** A 42 × 24 switch, violet when on. */
class Toggle(on: Boolean, private val changed: (Boolean) -> Unit) : UIComponent() {
	var on = on
		private set

	init {
		constrain { width = 42.at; height = 24.at }
		val flip = { this.on = !this.on; changed(this.on) }
		onMouseClick { it.stopPropagation(); flip() }
		Focus.add(this, 12.tp, flip)
	}

	override fun draw(matrixStack: UMatrixStack) {
		beforeDraw(matrixStack)
		val l = getLeft(); val t = getTop(); val r = getRight(); val b = getBottom()
		rounded(matrixStack, l, t, r, b, 12.tp, if (on) Tok.violet else Tok.outline)
		if (!on) rounded(matrixStack, l + 1.tp, t + 1.tp, r - 1.tp, b - 1.tp, 11.tp, Tok.track)
		val kx = l + (if (on) 22 else 4).tp
		rounded(matrixStack, kx, t + 4.tp, kx + 16.tp, t + 20.tp, 8.tp, if (on) Tok.violetInk else Tok.sec)
		super.draw(matrixStack)
	}
}

/** A 16 px checkbox and a 13 px label. */
fun checkbox(label: String, on: Boolean, changed: (Boolean) -> Unit): UIComponent {
	var state = on
	val c = UIContainer()
	val box = object : UIComponent() {
		override fun draw(matrixStack: UMatrixStack) {
			beforeDraw(matrixStack)
			val l = getLeft(); val t = getTop(); val r = getRight(); val b = getBottom()
			rounded(matrixStack, l, t, r, b, 4.tp, if (state) Tok.violet else Tok.outline)
			if (!state) rounded(matrixStack, l + 2.tp, t + 2.tp, r - 2.tp, b - 2.tp, 2.tp, Tok.panel)
			else drawIcon(matrixStack, "check", l + 1.tp, t + 1.tp, 14.tp, Tok.violetInk)
			super.draw(matrixStack)
		}
	}.constrain { width = 16.at; height = 16.at; y = CenterConstraint() } childOf c
	Label(label, 13, Weight.REGULAR, Tok.sec).constrain { x = 24.at; y = CenterConstraint() } childOf c
	c.constrain { width = basicWidthConstraint { 24.tp + textWidth(label, 13, Weight.REGULAR) }; height = 20.at }
	val flip = { state = !state; changed(state) }
	c.onMouseClick { it.stopPropagation(); flip() }
	Focus.add(box, 4.tp, flip)
	return c
}

/** A key as a chip: 12 px, outlined. */
fun keyChip(text: String): UIComponent {
	val b = box(5, Tok.panel, Tok.outline)
	val l = Label(text, 12, Weight.SEMIBOLD) childOf b
	l.constrain { x = 6.at; y = CenterConstraint() }
	b.constrain { width = basicWidthConstraint { l.getWidth() + 12.tp }; height = 18.at }
	return b
}

/** Numbered circles and a 14 px line each, at line-height 1.5. */
fun steps(width: Int, vararg lines: List<Run>): UIComponent {
	val c = Column(12).constrain { this.width = width.at }
	for ((i, runs) in lines.withIndex()) {
		val r = UIContainer().constrain { this.width = 100.percent }
		val n = box(12, Tok.raise2).constrain { this.width = 24.at; height = 24.at } childOf r
		Label((i + 1).toString(), 13, Weight.BOLD, Tok.violetHi).constrain { x = CenterConstraint(); y = CenterConstraint() } childOf n
		val t = rich(runs, 14, (width - 36).tp, 1.5f).constrain { x = 36.at } childOf r
		r.constrain { height = basicHeightConstraint { max(24.tp, t.getHeight()) } }
		c.add(r)
	}
	return c
}

/** A settings row: a bold label and its note on the left, controls on the right, a rule underneath. */
fun setRow(title: String, note: List<Run>, width: Int, vararg controls: UIComponent, last: Boolean = false, top: Boolean = false): UIComponent {
	val r = UIContainer().constrain { this.width = width.at }
	val room = width - controls.size * 140
	val lbl = Column(3).constrain { y = if (top) 14.at else CenterConstraint() } childOf r
	lbl.add(Label(title, 14, Weight.SEMIBOLD))
	lbl.add(rich(note, 13, room.tp, 1.45f))
	lbl.constrain { this.width = room.at }
	if (controls.isNotEmpty()) row(10, *controls).constrain { x = 0.fromEnd; y = CenterConstraint() } childOf r
	r.constrain { height = basicHeightConstraint { lbl.getHeight() + 28.tp } }
	if (!last) Rule(Tok.line).constrain { y = 0.fromEnd } childOf r
	return r
}

/** A 1 px line across its parent. */
class Rule(private val color: java.awt.Color) : UIComponent() {
	init {
		constrain { width = 100.percent; height = 1.at }
	}

	override fun draw(matrixStack: UMatrixStack) {
		beforeDraw(matrixStack)
		rounded(matrixStack, getLeft(), getTop(), getRight(), getBottom(), 0f, color)
		super.draw(matrixStack)
	}
}
