package app.keplersync.tandem.fabric.ui

import gg.essential.elementa.UIComponent
import gg.essential.elementa.components.UIBlock
import gg.essential.elementa.components.UIContainer
import gg.essential.elementa.constraints.CenterConstraint
import gg.essential.elementa.constraints.SiblingConstraint
import gg.essential.elementa.dsl.basicHeightConstraint
import gg.essential.elementa.dsl.basicYConstraint
import gg.essential.elementa.effects.ScissorEffect
import gg.essential.elementa.dsl.basicWidthConstraint
import gg.essential.elementa.dsl.childOf
import gg.essential.elementa.dsl.constrain
import gg.essential.elementa.dsl.percent
import gg.essential.elementa.dsl.pixels
import gg.essential.elementa.dsl.minus
import gg.essential.universal.UGraphics
import gg.essential.universal.UMatrixStack
import com.mojang.blaze3d.platform.NativeImage
import net.minecraft.client.Minecraft
import net.minecraft.client.resources.language.I18n
import net.minecraft.client.gui.screens.FaviconTexture
import net.minecraft.resources.ResourceLocation
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.awt.Color
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A filled rounded rectangle on whole screen pixels: straight parts as plain rectangles, corners as quarter circles
 * drawn at their exact pixel radius. Elementa's own rounded rectangle softens corners over a whole GUI unit.
 */
fun rounded(ms: UMatrixStack, l: Float, t: Float, r: Float, b: Float, radius: Float, color: Color) {
	val g = Raster.gui
	val x0 = (l * g).roundToInt(); val y0 = (t * g).roundToInt(); val x1 = (r * g).roundToInt(); val y1 = (b * g).roundToInt()
	if (x1 <= x0 || y1 <= y0 || color.alpha == 0) return
	fun rect(a: Int, b: Int, c: Int, d: Int) {
		if (c > a && d > b) UIBlock.drawBlock(ms, color, a / g, b / g, c / g, d / g)
	}
	val n = min((radius * g).roundToInt(), min(x1 - x0, y1 - y0) / 2)
	if (n <= 0) return rect(x0, y0, x1, y1)
	rect(x0 + n, y0, x1 - n, y1)
	rect(x0, y0 + n, x0 + n, y1 - n)
	rect(x1 - n, y0 + n, x1, y1 - n)
	val c = Raster.corner(n, true).loc
	val s = (n / g).toFloat()
	val l2 = (x0 / g).toFloat(); val t2 = (y0 / g).toFloat(); val r2 = ((x1 - n) / g).toFloat(); val b2 = ((y1 - n) / g).toFloat()
	Tex.draw(ms, c, l2, t2, s, s, color)
	Tex.draw(ms, c, r2, t2, s, s, color, 1f, 0f, 0f, 1f)
	Tex.draw(ms, c, l2, b2, s, s, color, 0f, 1f, 1f, 0f)
	Tex.draw(ms, c, r2, b2, s, s, color, 1f, 1f, 0f, 0f)
}

/**
 * A rounded rectangle with an optional 1 px border. The border is drawn underneath the fill, so a box that
 * looks transparent takes the colour of the surface it sits on as its fill.
 */
open class Box(var corner: Float, var fill: Color, var border: Color? = null) : UIComponent() {
	override fun draw(matrixStack: UMatrixStack) {
		beforeDraw(matrixStack)
		val l = getLeft(); val t = getTop(); val r = getRight(); val b = getBottom()
		val line = border
		if (line != null) rounded(matrixStack, l, t, r, b, corner, line)
		val i = if (line != null) 1.tp else 0f
		rounded(matrixStack, l + i, t + i, r - i, b - i, max(0f, corner - i), fill)
		super.draw(matrixStack)
	}
}

fun box(radius: Int, fill: Color, border: Color? = null) = Box(radius.tp, fill, border)

/**
 * One line of Inter at a CSS pixel size. The box is the line's height: the browser's line-height: normal unless
 * [lineHeight] (in ems) says otherwise, with the extra split above and below like CSS does.
 */
class Label(
	text: String, size: Number, var weight: Weight = Weight.REGULAR, var tint: Color = Tok.ink, private val lineHeight: Float = LINE_EM,
) : UIComponent() {
	val size = size.toFloat()
	var text = text
	var underline = false
	/**
	 * Names come from players and can be any length: wider than this many GUI units, the text ends in an ellipsis. Never
	 * from the label's own width or a position that depends on it (centred), which would go round in a circle.
	 */
	var room: (() -> Float)? = null
	private var cut: Triple<String, Float, String>? = null

	private val em get() = size.tp

	/** The words as drawn: all of [text], or as much as fits in [room]. */
	private val shown: String
		get() {
			val room = room?.invoke() ?: return text
			if (textWidth(text, size, weight) <= room) return text
			cut?.let { (t, r, s) -> if (t == text && r == room) return s }
			return fitText(text, size, weight, room).also { cut = Triple(text, room, it) }
		}

	init {
		constrain {
			width = basicWidthConstraint { textWidth(shown, this@Label.size, weight) }
			height = basicHeightConstraint { em * lineHeight }
		}
	}

	override fun draw(matrixStack: UMatrixStack) {
		beforeDraw(matrixStack)
		drawText(matrixStack, shown, size, weight, tint, getLeft(), getTop() + (lineHeight - LINE_EM) / 2 * em)
		if (underline) {
			val y = getTop() + (lineHeight - LINE_EM) / 2 * em + ASCENT_EM * em + 3.tp
			rounded(matrixStack, getLeft(), y, getRight(), y + 1.tp, 0f, tint)
		}
		super.draw(matrixStack)
	}
}

/** This label, cut short with an ellipsis where it would pass [c]'s right edge. For labels placed from the left only. */
fun Label.fitWithin(c: UIComponent, less: Number = 0) = apply { room = { c.getRight() - less.tp - getLeft() } }

/** This label, cut short with an ellipsis [gap] before [c] starts (a button beside it). For labels placed from the left only. */
fun Label.fitBefore(c: UIComponent, gap: Number = 0) = apply { room = { c.getLeft() - gap.tp - getLeft() } }

/** Words wrapped onto as many lines as fit in [maxWidth] GUI units, [lineHeight] ems apart. */
fun wrapped(text: String, size: Number, weight: Weight, color: Color, maxWidth: Float, lineHeight: Float = LINE_EM): UIComponent =
	rich(listOf(Run(text, weight, color)), size, maxWidth, lineHeight)

/** A stretch of text in one style, for [rich]. */
class Run(val text: String, val weight: Weight = Weight.REGULAR, val color: Color = Tok.ink)

/** A paragraph mixing styles (bold words inside a sentence), wrapped like [wrapped]. */
fun rich(runs: List<Run>, size: Number, maxWidth: Float, lineHeight: Float = LINE_EM): UIComponent {
	// Each line is a list of runs; words of one style on one line stay together, so kerning is kept.
	val lines = mutableListOf(mutableListOf<Run>())
	var used = 0f
	for ((n, run) in runs.withIndex()) for ((i, word) in run.text.trim().split(' ').withIndex()) {
		val line = lines.last()
		val space = if (i > 0 || run.text.startsWith(' ') || n > 0 && runs[n - 1].text.endsWith(' ')) " " else ""
		val piece = if (line.isEmpty()) word else space + word
		val w = textWidth(piece, size, run.weight)
		if (line.isNotEmpty() && used + w > maxWidth) {
			lines += mutableListOf(Run(word, run.weight, run.color))
			used = textWidth(word, size, run.weight)
		} else {
			val last = line.lastOrNull()
			if (last != null && last.weight == run.weight && last.color == run.color) line[line.lastIndex] = Run(last.text + piece, run.weight, run.color)
			else line += Run(piece, run.weight, run.color)
			used += w
		}
	}
	val c = UIContainer()
	for (l in lines) {
		val r = UIContainer().constrain { y = SiblingConstraint(); height = (size.toFloat() * lineHeight).tp.pixels } childOf c
		var x = 0f
		for (run in l) {
			Label(run.text, size, run.weight, run.color, lineHeight).constrain { this.x = x.pixels } childOf r
			x += textWidth(run.text, size, run.weight)
		}
		r.constrain { width = x.pixels }
	}
	c.constrain {
		width = basicWidthConstraint { it.children.maxOf { ch -> ch.getWidth() } }
		height = basicHeightConstraint { it.children.sumOf { ch -> ch.getHeight().toDouble() }.toFloat() }
	}
	return c
}

private fun textPic(text: String, size: Number, weight: Weight) = Raster.text(text, weight, (size.toDouble() * Scale.s).toFloat())

/** A label whose words come from [text] every frame: progress that moves. */
class Live(size: Number, weight: Weight, color: Color, private val text: () -> String) : UIComponent() {
	private val label = Label(text(), size, weight, color) childOf this

	init {
		constrain { width = basicWidthConstraint { label.getWidth() }; height = basicHeightConstraint { label.getHeight() } }
	}

	override fun draw(matrixStack: UMatrixStack) {
		beforeDraw(matrixStack)
		label.text = text()
		super.draw(matrixStack)
	}
}

fun textWidth(text: String, size: Number, weight: Weight) = (textPic(text, size, weight).advance / Raster.gui).toFloat()

/** [text], cut short with an ellipsis if it's wider than [max] GUI units. */
fun fitText(text: String, size: Number, weight: Weight, max: Float): String {
	if (textWidth(text, size, weight) <= max) return text
	fun cut(n: Int) = text.substring(0, if (n > 0 && Character.isHighSurrogate(text[n - 1])) n - 1 else n).trimEnd() + "…"
	var lo = 0
	var hi = text.length
	while (lo < hi) {
		val mid = (lo + hi + 1) / 2
		if (textWidth(cut(mid), size, weight) <= max) lo = mid else hi = mid - 1
	}
	return cut(lo)
}

/** Draws one line with its line box's top at [top]; the baseline lands on a whole screen pixel. */
fun drawText(ms: UMatrixStack, text: String, size: Number, weight: Weight, color: Color, x: Float, top: Float) {
	val p = textPic(text, size, weight)
	val g = Raster.gui
	val px = (x * g).roundToInt() - p.ox
	val py = ((top + ASCENT_EM * size.tp) * g).roundToInt() - p.oy
	Tex.draw(ms, p.loc, (px / g).toFloat(), (py / g).toFloat(), (p.w / g).toFloat(), (p.h / g).toFloat(), color)
}

/** An icon from textures/icon, [size] GUI units square, tinted. */
fun drawIcon(ms: UMatrixStack, name: String, x: Float, y: Float, size: Float, color: Color) {
	val n = (size * Raster.gui).roundToInt()
	if (n <= 0) return
	val s = (n / Raster.gui).toFloat()
	Tex.draw(ms, Raster.icon(name, n).loc, x, y, s, s, color)
}

object Tex {
	/** A texture quad with its edges on whole screen pixels. */
	fun draw(
		ms: UMatrixStack, loc: ResourceLocation, x0: Float, y0: Float, w0: Float, h0: Float, color: Color,
		u0: Float = 0f, v0: Float = 0f, u1: Float = 1f, v1: Float = 1f,
	) {
		val x = Raster.snap(x0); val y = Raster.snap(y0)
		val w = Raster.snap(x0 + w0) - x; val h = Raster.snap(y0 + h0) - y
		UGraphics.enableBlend()
		UGraphics.tryBlendFuncSeparate(770, 771, 1, 0)
		UGraphics.bindTexture(0, loc)
		val r = UGraphics.getFromTessellator()
		r.beginWithDefaultShader(UGraphics.DrawMode.QUADS, UGraphics.CommonVertexFormats.POSITION_TEXTURE_COLOR)
		r.pos(ms, x.toDouble(), y.toDouble(), 0.0).tex(u0.toDouble(), v0.toDouble()).color(color).endVertex()
		r.pos(ms, x.toDouble(), (y + h).toDouble(), 0.0).tex(u0.toDouble(), v1.toDouble()).color(color).endVertex()
		r.pos(ms, (x + w).toDouble(), (y + h).toDouble(), 0.0).tex(u1.toDouble(), v1.toDouble()).color(color).endVertex()
		r.pos(ms, (x + w).toDouble(), y.toDouble(), 0.0).tex(u1.toDouble(), v0.toDouble()).color(color).endVertex()
		r.drawDirect()
	}

	/** Rounds a texture's corners by painting the surface colour over them. */
	fun corners(ms: UMatrixStack, l0: Float, t0: Float, r0: Float, b0: Float, radius: Float, under: Color) {
		val n = (radius * Raster.gui).roundToInt()
		if (n <= 0) return
		val c = Raster.corner(n, false).loc
		val s = (n / Raster.gui).toFloat()
		val l = Raster.snap(l0); val t = Raster.snap(t0); val r = Raster.snap(r0); val b = Raster.snap(b0)
		draw(ms, c, l, t, s, s, under)
		draw(ms, c, r - s, t, s, s, under, 1f, 0f, 0f, 1f)
		draw(ms, c, l, b - s, s, s, under, 0f, 1f, 1f, 0f)
		draw(ms, c, r - s, b - s, s, s, under, 1f, 1f, 0f, 0f)
	}
}

/** Worlds' icon.png files as textures, each loaded again only when the file changes. */
object WorldIcons {
	private val loaded = mutableMapOf<Path, Pair<Long, FaviconTexture?>>()

	fun get(icon: Path): ResourceLocation? {
		val modified = try {
			Files.getLastModifiedTime(icon).toMillis()
		} catch (e: IOException) {
			return null
		}
		loaded[icon]?.let { (at, tex) -> if (at == modified) return tex?.textureLocation() else tex?.close() }
		val tex = try {
			val image = Files.newInputStream(icon).use { NativeImage.read(it) }
			FaviconTexture.forWorld(Minecraft.getInstance().textureManager, "tandem/" + icon.parent.fileName).also { it.upload(image) }
		} catch (e: Exception) {
			// Not a 64×64 PNG: the bands stand in.
			null
		}
		loaded[icon] = modified to tex
		return tex?.textureLocation()
	}
}

/** One of Tandem's stroke icons, tinted. */
class Icon(private val name: String, size: Int, var tint: Color) : UIComponent() {
	init {
		constrain { width = size.at; height = size.at }
	}

	override fun draw(matrixStack: UMatrixStack) {
		beforeDraw(matrixStack)
		drawIcon(matrixStack, name, getLeft(), getTop(), getWidth(), tint)
		super.draw(matrixStack)
	}
}

/**
 * A player's skin face with its hat layer. Offline faces are dimmed; online ones get a green dot. [skin] is read each
 * frame: Minecraft shows the default skin until a player's own has downloaded.
 */
class Face(private val skin: () -> ResourceLocation, size: Int, private val round: Int, private val online: Boolean?, var under: Color) : UIComponent() {
	init {
		constrain { width = size.at; height = size.at }
	}

	override fun draw(matrixStack: UMatrixStack) {
		beforeDraw(matrixStack)
		val l = getLeft(); val t = getTop(); val s = getWidth()
		// shortcut: dimmed by brightness only, not desaturated; add a greyscale shader if it reads as too colourful
		val tint = if (online == false) Color(178, 178, 178) else Color.WHITE
		val skin = skin()
		Tex.draw(matrixStack, skin, l, t, s, s, tint, 8 / 64f, 8 / 64f, 16 / 64f, 16 / 64f)
		Tex.draw(matrixStack, skin, l, t, s, s, tint, 40 / 64f, 8 / 64f, 48 / 64f, 16 / 64f)
		Tex.corners(matrixStack, l, t, l + s, t + s, round.tp, under)
		if (online == true) {
			val c = 10.tp / 2
			val cx = l + s + 3.tp - c
			val cy = t + s + 3.tp - c
			rounded(matrixStack, cx - c, cy - c, cx + c, cy + c, c, under)
			rounded(matrixStack, cx - c + 2.tp, cy - c + 2.tp, cx + c - 2.tp, cy + c - 2.tp, c - 2.tp, Tok.ok)
		}
		super.draw(matrixStack)
	}
}

/** A world picture: the world's own icon.png when it has one, otherwise horizontal bands. */
class Thumb(private val bands: List<Pair<Float, Color>>, w: Int, h: Int, private val round: Int, private val dim: Boolean, var under: Color, icon: Path? = null) : UIComponent() {
	private val texture = icon?.let(WorldIcons::get)

	init {
		constrain { width = w.at; height = h.at }
	}

	override fun draw(matrixStack: UMatrixStack) {
		beforeDraw(matrixStack)
		val l = getLeft(); val t = getTop(); val r = getRight(); val b = getBottom()
		if (texture != null) {
			// The square icon, cropped to the picture's shape.
			val aspect = (r - l) / (b - t)
			val du = if (aspect < 1) (1 - aspect) / 2 else 0f
			val dv = if (aspect > 1) (1 - 1 / aspect) / 2 else 0f
			Tex.draw(matrixStack, texture, l, t, r - l, b - t, if (dim) Color(125, 125, 125) else Color.WHITE, du, dv, 1 - du, 1 - dv)
			Tex.corners(matrixStack, l, t, r, b, round.tp, under)
			return super.draw(matrixStack)
		}
		var top = t
		for ((end, c) in bands) {
			val bottom = t + (b - t) * end
			rounded(matrixStack, l, top, r, bottom, 0f, if (dim) dimmed(c) else c)
			top = bottom
		}
		Tex.corners(matrixStack, l, t, r, b, round.tp, under)
		super.draw(matrixStack)
	}

	// Greyscale 0.6, brightness 0.7.
	private fun dimmed(c: Color): Color {
		val grey = 0.2126 * c.red + 0.7152 * c.green + 0.0722 * c.blue
		fun ch(v: Int) = ((v + (grey - v) * 0.6) * 0.7).toInt().coerceIn(0, 255)
		return Color(ch(c.red), ch(c.green), ch(c.blue))
	}
}

enum class Kind { FILLED, OUTLINE, WARN, DANGER }

/** Button sizes: normal, small, the toast's buttons, and the pause panel's. */
enum class Size(val h: Int, val pad: Int, val radius: Int, val text: Float) {
	NORMAL(40, 18, 10, 14f), SMALL(34, 13, 9, 13f), TOAST(32, 14, 9, 13f),
	/** The pause panel's buttons and its invite list's. */
	PANEL(36, 12, 10, 13f), ROW(30, 12, 8, 12.5f),
}

/**
 * A button: filled violet for the screen's one main action, outlined otherwise. [on] is the surface the
 * button sits on. A disabled button is drawn at 45% opacity and ignores clicks, like the browser's.
 */
class Button(text: String, private val kind: Kind, size: Size, width: Int?, private val on: Color, private val press: () -> Unit) :
	Box(size.radius.tp, on, null) {
	private val label = Label(text, size.text, Weight.SEMIBOLD) childOf this
	private var hot = false

	var enabled = true
		set(v) { field = v; paint() }

	var text: String
		get() = label.text
		set(v) { label.text = v }

	init {
		constrain {
			this.width = width?.at ?: basicWidthConstraint { label.getWidth() + (2 * size.pad + 2).tp }
			height = size.h.at
		}
		label.constrain { x = CenterConstraint(); y = CenterConstraint() }
		// A fixed width with a name in it ("Join Zoë O'Connor-McAllister"): the name ends in an ellipsis inside the button.
		// Its own words (Play when ready) still fit as before: only past 6 px from each edge.
		if (width != null) label.room = { (width - 12).tp }
		paint()
		onMouseEnter { hot = true; paint() }
		onMouseLeave { hot = false; paint() }
		onMouseClick { if (it.mouseButton == 0) { it.stopPropagation(); if (enabled) press() } }
		Focus.add(this, corner) { if (enabled) press() }
	}

	private fun paint() {
		val h = hot && enabled
		var f: Color; var b: Color?; var t: Color
		when (kind) {
			Kind.FILLED -> { f = if (h) Tok.violetHi else Tok.violet; b = null; t = Tok.violetInk }
			Kind.OUTLINE -> { f = if (h) Tok.raise2 else on; b = Tok.outline; t = Tok.ink }
			Kind.WARN -> { f = if (h) Tok.raise2 else on; b = Tok.warn; t = Tok.warnText }
			Kind.DANGER -> { f = if (h) Tok.over(on, Tok.danger, 0.12) else on; b = Tok.danger; t = Tok.dangerText }
		}
		if (!enabled) { f = Tok.over(on, f, 0.45); b = b?.let { Tok.over(on, it, 0.45) }; t = Tok.over(on, t, 0.45) }
		fill = f; border = b; label.tint = t
	}
}

/** A square button with an 18 px icon, 40 (or 34) wide. */
class IconButton(name: String, size: Int, private val on: Color, private val press: () -> Unit) : Box(10.tp, on) {
	private val icon = Icon(name, 18, Tok.muted) childOf this

	init {
		constrain { width = size.at; height = size.at }
		icon.constrain { x = CenterConstraint(); y = CenterConstraint() }
		onMouseEnter { fill = Tok.raise2; icon.tint = Tok.ink }
		onMouseLeave { fill = on; icon.tint = Tok.muted }
		onMouseClick { if (it.mouseButton == 0) { it.stopPropagation(); press() } }
		Focus.add(this, corner, press)
	}
}

/** The amber count on the title button and tabs. */
fun badge(n: Int, size: Int = 22): UIComponent {
	val b = box(size / 2, Tok.warn)
	val l = Label(n.toString(), if (size < 22) 12 else 13, Weight.BOLD, Tok.warnInk) childOf b
	// The minimum width doesn't include the 6 px padding either side.
	b.constrain { width = basicWidthConstraint { max(size.tp, l.getWidth()) + 12.tp }; height = size.at }
	l.constrain { x = CenterConstraint(); y = CenterConstraint() }
	return b
}

/** Children laid out left to right, [gap] apart, centred vertically; as tall as its tallest child. */
fun row(gap: Int, vararg parts: UIComponent): UIContainer {
	val c = UIContainer()
	for (p in parts) {
		p.constrain { x = SiblingConstraint(gap.tp); y = CenterConstraint() }
		p childOf c
	}
	c.constrain {
		width = basicWidthConstraint { it.children.sumOf { ch -> ch.getWidth().toDouble() }.toFloat() + gap.tp * (it.children.size - 1).coerceAtLeast(0) }
		height = basicHeightConstraint { it.children.maxOfOrNull { ch -> ch.getHeight() } ?: 0f }
	}
	return c
}

/**
 * Smooth scrolling, like the browser's: about 100 Tandem px a wheel notch, easing out over roughly 150 ms, and a
 * notch during the movement adds to it. Elementa's own scroll area jumps 40 GUI units a notch with an ease-in
 * curve, which read as choppy in game. The thin scrollbar (6 px, panel colours, no arrows) sits in a 10 px gutter
 * like the browser's and can be dragged.
 */
class Scroller : UIContainer() {
	/** Children go in here. */
	val content = UIContainer()
	private var offset = 0f
	private var target = 0f
	private var last = 0L
	private var dragging = false
	private var hotGrip = false

	init {
		constrain { width = 100.percent; height = 100.percent }
		content.constrain {
			y = basicYConstraint { this@Scroller.getTop() - Raster.snap(offset) }
			// The scrollbar's 10 px gutter only when there's something to scroll, so rows reach the edge otherwise.
			width = basicWidthConstraint { this@Scroller.getWidth() - if (scrolls) 10.tp else 0f }
			height = basicHeightConstraint { c -> (c.children.maxOfOrNull { it.getBottom() } ?: c.getTop()) - c.getTop() }
		} childOf this
		enableEffect(ScissorEffect())
		onMouseScroll { it.stopPropagation(); scrollBy(-it.delta.toFloat() * scrollStep.tp) }
		onMouseClick { if (it.relativeX >= getWidth() - 10.tp) { it.stopPropagation(); dragging = true; dragTo(it.absoluteY) } }
		onMouseDrag { _, mouseY, _ -> if (dragging) dragTo(getTop() + mouseY) }
		onMouseRelease { dragging = false }
		onMouseEnter { hotGrip = true }
		onMouseLeave { hotGrip = false }
	}

	private val range get() = max(0f, content.getHeight() - getHeight())
	private val scrolls get() = content.getHeight() > getHeight() + 0.5f

	/** Where it's scrolled to, to put a rebuilt page back where it was. */
	var position: Float
		get() = target
		set(v) { target = v; offset = v }

	fun scrollBy(d: Float) {
		target = (target + d).coerceIn(0f, range)
	}

	/** Brings [top]..[bottom] (GUI units) into view with [pad] to spare. */
	fun reveal(top: Float, bottom: Float, pad: Float) {
		when {
			top - pad < getTop() -> scrollBy(top - pad - getTop())
			bottom + pad > getBottom() -> scrollBy(bottom + pad - getBottom())
		}
	}

	private fun dragTo(y: Float) {
		val (_, h) = grip() ?: return
		target = ((y - getTop() - h / 2) / (getHeight() - h) * range).coerceIn(0f, range)
		offset = target
	}

	/** The grip's top (from this area's top) and height, when there's something to scroll. */
	private fun grip(): Pair<Float, Float>? {
		val total = content.getHeight()
		if (total <= getHeight() + 0.5f) return null
		val track = getHeight() - 8.tp
		val h = max(24.tp, track * getHeight() / total)
		return 4.tp + (track - h) * (offset / range) to h
	}

	override fun draw(matrixStack: UMatrixStack) {
		val now = System.nanoTime()
		val dt = if (last == 0L) 0f else ((now - last) / 1e9f).coerceAtMost(0.1f)
		last = now
		target = target.coerceIn(0f, range)
		offset += (target - offset) * (1 - kotlin.math.exp(-dt / 0.045f))
		if (kotlin.math.abs(target - offset) < 0.01f) offset = target
		super.draw(matrixStack)
		val g = grip() ?: return
		val x = getRight() - 8.tp
		rounded(matrixStack, x, getTop() + g.first, x + 6.tp, getTop() + g.first + g.second, 3.tp, if (hotGrip || dragging) Tok.outline else Tok.line)
	}

	companion object {
		const val STEP = 100
	}
}

/** A scrolling area filling [parent]; children go in the returned container. */
fun scroller(parent: UIComponent): UIContainer = (Scroller() childOf parent).content

/** A small violet tag (Host, Relay, Best connection) on a row whose surface is [under]. */
fun pill12(text: String, under: Color = Tok.panel): UIComponent {
	val t = box(10, Tok.over(under, Tok.violet, 0.14))
	val l = Label(text, 12, Weight.REGULAR, Tok.violetHi) childOf t
	l.constrain { x = 8.at; y = CenterConstraint() }
	t.constrain { width = basicWidthConstraint { l.getWidth() + 16.tp }; height = 19.at }
	return t
}

/** Connection strength as three bars (5, 9 and 14 px). Your own weak connection shows in amber. */
class Bars(private val n: Int, private val you: Boolean) : UIComponent() {
	init {
		constrain { width = 13.at; height = 14.at }
	}

	override fun draw(matrixStack: UMatrixStack) {
		beforeDraw(matrixStack)
		val on = if (you && n < 3) Tok.warn else Tok.ok
		for ((i, h) in listOf(5, 9, 14).withIndex()) {
			val x = getLeft() + (i * 5).tp
			rounded(matrixStack, x, getBottom() - h.tp, x + 3.tp, getBottom(), 1.tp, if (i < n) on else Tok.line)
		}
		super.draw(matrixStack)
	}
}

/** Tandem's English, read from its own jar: words asked for before Minecraft has loaded its languages. */
private val english: Map<String, String> by lazy {
	val type = object : com.google.gson.reflect.TypeToken<Map<String, String>>() {}.type
	Ingame::class.java.getResourceAsStream("/assets/tandem/lang/en_us.json")?.reader(Charsets.UTF_8)?.use { com.google.gson.Gson().fromJson<Map<String, String>>(it, type) }.orEmpty()
}

/** Words on screen, from Tandem's language file (assets/tandem/lang), with [args] in place of its %s. */
fun tr(key: String, vararg args: Any?): String {
	if (I18n.exists(key)) return I18n.get(key, *args)
	val en = english[key] ?: return key.also { app.keplersync.tandem.fabric.TandemClient.LOG.warn("Tandem has no words for {}", key) }
	return try { String.format(en, *args) } catch (e: java.util.IllegalFormatException) { en }
}

/** "your" for this player ("You" in the lists), "Sam's" for anyone else: words from the language file, not English in code. */
fun whose(who: String?): String = if (who == "You") tr("tandem.your_lower") else tr("tandem.s_possessive", who ?: tr("tandem.someone"))

/**
 * How far one unit of scrolling moves a list, in Tandem px: 100 for a mouse wheel's notch. A Mac's trackpad (and its
 * smooth-scrolling mice) sends many small amounts a second instead, so there it's a fifth of that.
 */
val scrollStep = if (net.minecraft.Util.getPlatform() == net.minecraft.Util.OS.OSX) 20 else Scroller.STEP
