package app.keplersync.tandem.fabric.ui

import app.keplersync.tandem.core.TextEdit
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
 * A text field: 40 tall at 14 px, or a search box 34 tall at 13 px. Click or Tab to it, then type. It edits like any
 * text box: the arrows, Home and End move the caret (by word with Ctrl, selecting with Shift), a click or drag places
 * it, and Ctrl+A, C, X, V, Z, Shift+Z or Y, and Ctrl+Backspace or Delete do what they do everywhere (Cmd on a Mac).
 * [masked] shows dots (a secret key) and never copies; [lines] above 1 wraps and grows (a textarea).
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
	private val edit = TextEdit(initial)
	val value: String get() = edit.text()
	private var typing = false
	private var dragging = false
	private val size = if (height >= 40) 14 else 13

	init {
		constrain {
			this.width = width?.at ?: 100.percent
			this.height = (if (lines > 1) 20 + lines * 21 else height).at
		}
		onMouseClick {
			it.stopPropagation()
			grabWindowFocus()
			edit.move(indexAt(it.relativeX, it.relativeY), UKeyboard.isShiftKeyDown())
			dragging = true
		}
		onMouseDrag { x, y, _ -> if (dragging) edit.move(indexAt(x, y), true) }
		onMouseRelease { dragging = false }
		onFocus { typing = true }
		onFocusLost { typing = false }
		onKeyType { typedChar, keyCode ->
			val before = edit.text()
			val ctrl = UKeyboard.isCtrlKeyDown()
			val shift = UKeyboard.isShiftKeyDown()
			val clipboard = Minecraft.getInstance().keyboardHandler
			when {
				keyCode == UKeyboard.KEY_BACKSPACE -> edit.backspace(ctrl)
				keyCode == UKeyboard.KEY_DELETE -> edit.delete(ctrl)
				keyCode == UKeyboard.KEY_LEFT -> edit.left(ctrl, shift)
				keyCode == UKeyboard.KEY_RIGHT -> edit.right(ctrl, shift)
				keyCode == UKeyboard.KEY_HOME -> edit.move(0, shift)
				keyCode == UKeyboard.KEY_END -> edit.move(edit.text().length, shift)
				ctrl && keyCode == UKeyboard.KEY_A -> edit.selectAll()
				ctrl && keyCode == UKeyboard.KEY_C -> if (!masked && edit.selected()) clipboard.clipboard = edit.selection()
				ctrl && keyCode == UKeyboard.KEY_X -> if (!masked && edit.selected()) clipboard.clipboard = edit.cut()
				// The R2 keys are long, and the steps beside them say to paste them.
				ctrl && keyCode == UKeyboard.KEY_V -> edit.insert(clipboard.clipboard.filter { it >= ' ' && it != '' }, false)
				ctrl && keyCode == UKeyboard.KEY_Z -> if (shift) edit.redo() else edit.undo()
				ctrl && keyCode == UKeyboard.KEY_Y -> edit.redo()
				typedChar >= ' ' && typedChar != '' -> edit.insert(typedChar.toString(), true)
				else -> return@onKeyType
			}
			if (edit.text() != before) changed(edit.text())
		}
		Focus.add(this, corner) { grabWindowFocus() }
	}

	private val shown get() = if (masked) "•".repeat(value.length) else value
	private val lineHeight get() = if (lines > 1) 21.tp else size.tp * LINE_EM
	private val firstTop get() = if (lines > 1) 11.tp else (getHeight() - size.tp * LINE_EM) / 2

	/** [text] as drawn: each line's first letter and its words. One line, or wrapped by words like a textarea. */
	private fun rows(text: String): List<Pair<Int, String>> {
		if (lines <= 1) return listOf(0 to text)
		val maxW = getWidth() - 26.tp
		val out = ArrayList<Pair<Int, String>>()
		var start = 0
		var at = 0
		var line: String? = null
		for (word in text.split(' ')) {
			val next = if (line == null) word else "$line $word"
			if (line != null && textWidth(next, size, Weight.REGULAR) > maxW) { out += start to line; start = at; line = word } else line = next
			at += word.length + 1
		}
		out += start to (line ?: "")
		return out
	}

	private fun widthTo(row: Pair<Int, String>, index: Int) = textWidth(row.second.substring(0, (index - row.first).coerceIn(0, row.second.length)), size, Weight.REGULAR)

	/** The letter a click at ([x], [y]) inside the field lands before: the nearest gap between two letters. */
	private fun indexAt(x: Float, y: Float): Int {
		if (value.isEmpty()) return 0
		val rows = rows(shown)
		val row = rows[((y - firstTop) / lineHeight).toInt().coerceIn(0, rows.lastIndex)]
		val dx = x - 13.tp
		var lo = 0
		var hi = row.second.length
		while (lo < hi) {
			val mid = (lo + hi + 1) / 2
			if (widthTo(row, row.first + mid) <= dx) lo = mid else hi = mid - 1
		}
		val right = lo < row.second.length && dx - widthTo(row, row.first + lo) > widthTo(row, row.first + lo + 1) - dx
		return row.first + lo + if (right) 1 else 0
	}

	override fun draw(matrixStack: UMatrixStack) {
		super.draw(matrixStack)
		val x = getLeft() + 13.tp
		val empty = value.isEmpty()
		val rows = rows(if (empty) placeholder else shown)
		for ((n, row) in rows.withIndex()) {
			val top = getTop() + firstTop + n * lineHeight
			val a = maxOf(edit.from(), row.first)
			val b = minOf(edit.to(), row.first + row.second.length)
			if (typing && !empty && a < b) rounded(matrixStack, x + widthTo(row, a), top + 1.tp, x + widthTo(row, b), top + (size * LINE_EM - 1).tp, 2.tp, Tok.over(Tok.rail, Tok.violet, 0.45))
			drawText(matrixStack, row.second, size, Weight.REGULAR, if (empty) Tok.muted else Tok.ink, x, top)
		}
		if (typing && System.currentTimeMillis() / 530 % 2 == 0L) {
			val n = if (empty) 0 else rows.indexOfLast { it.first <= edit.caret() }
			val top = getTop() + firstTop + n * lineHeight
			val cx = x + (if (empty) 0f else widthTo(rows[n], edit.caret()) + 1.tp)
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
