package app.keplersync.tandem.fabric.ui

import com.mojang.blaze3d.platform.NativeImage
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.resources.ResourceLocation
import java.awt.Font
import java.awt.Image
import java.awt.RenderingHints
import java.awt.font.FontRenderContext
import java.awt.font.TextAttribute
import java.awt.font.TextLayout
import java.awt.image.BufferedImage
import java.text.AttributedString
import javax.imageio.ImageIO
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Text, icons and corner masks drawn at the exact number of screen pixels they cover, then copied to the screen
 * pixel for pixel. Scaling a pre-made picture, or Elementa's MSDF fonts (which snap each letter to its own grid),
 * leaves text soft and letters at uneven heights.
 */
object Raster {
	/**
	 * A white picture with its alpha; [ox], [oy] is where the drawing origin sits in it (a line's pen start and
	 * baseline). Measuring needs only the picture; it becomes a texture the first time it's drawn.
	 */
	class Pic(private var img: BufferedImage?, val w: Int, val h: Int, val ox: Int, val oy: Int, val advance: Float) {
		var used = System.currentTimeMillis()
			private set
		private var uploaded: ResourceLocation? = null

		val loc: ResourceLocation
			get() {
				used = System.currentTimeMillis()
				return uploaded ?: upload(img!!).also { uploaded = it; img = null }
			}

		fun release() {
			uploaded?.let { Minecraft.getInstance().textureManager.release(it) }
		}
	}

	private var next = 0
	private val cache = HashMap<String, Pic>()

	// Drops only pictures nobody has drawn for a while, so nothing on screen loses its texture.
	private fun cached(key: String, make: () -> Pic): Pic {
		cache[key]?.let { return it }
		if (cache.size > 3000) {
			val old = System.currentTimeMillis() - 30_000
			cache.values.removeIf { p -> (p.used < old).also { if (it) p.release() } }
		}
		return make().also { cache[key] = it }
	}

	/** Screen pixels per GUI unit. */
	val gui: Double get() = Minecraft.getInstance().window.guiScale

	/** [v] GUI units moved onto the nearest screen pixel. */
	fun snap(v: Float): Float = ((v * gui).roundToInt() / gui).toFloat()

	/**
	 * One line of text at [px] screen pixels per em, with kerning, as the browser lays it out. Letters Inter hasn't got
	 * (Chinese, Japanese, Korean, Arabic, Hebrew, emoji in names) come from the system's own fonts, as a browser's would.
	 */
	fun text(s: String, weight: Weight, px: Float): Pic = cached("t|$weight|${(px * 4).roundToInt()}|$s") {
		val font = weight.awt.deriveFont(mapOf(TextAttribute.SIZE to px, TextAttribute.KERNING to TextAttribute.KERNING_ON))
		val str = s.ifEmpty { " " }
		val frc = FontRenderContext(null, true, true)
		val layout = if (font.canDisplayUpTo(str) == -1) TextLayout(str, font, frc) else {
			// Java's Dialog font is the system's own: it falls back through every font installed for what it lacks.
			val other = Font(Font.DIALOG, if (weight == Weight.SEMIBOLD || weight == Weight.BOLD) Font.BOLD else Font.PLAIN, 1).deriveFont(px)
			// Left to right whatever the first letter: "محمد is saving" keeps the name first, as the sentence has it.
			val runs = AttributedString(str).apply {
				addAttribute(TextAttribute.FONT, font)
				addAttribute(TextAttribute.RUN_DIRECTION, TextAttribute.RUN_DIRECTION_LTR)
			}
			var i = 0
			while (i < str.length) {
				val cp = str.codePointAt(i)
				val n = Character.charCount(cp)
				if (!font.canDisplay(cp)) runs.addAttribute(TextAttribute.FONT, other, i, i + n)
				i += n
			}
			TextLayout(runs.iterator, frc)
		}
		val ascent = ceil(layout.ascent).toInt() + 1
		val img = BufferedImage(ceil(layout.advance).toInt() + 2, ascent + ceil(layout.descent).toInt() + 1, BufferedImage.TYPE_INT_ARGB)
		val g = img.createGraphics()
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
		g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
		g.color = java.awt.Color.WHITE
		layout.draw(g, 1f, ascent.toFloat())
		g.dispose()
		Pic(img, img.width, img.height, 1, ascent, if (s.isEmpty()) 0f else layout.advance)
	}

	/** One of the 128 px icons in textures/icon, scaled down to [px] with area averaging. */
	fun icon(name: String, px: Int): Pic = cached("i|$name|$px") {
		val src = Raster::class.java.getResourceAsStream("/assets/tandem/textures/icon/$name.png").use { ImageIO.read(it) }
		val img = BufferedImage(px, px, BufferedImage.TYPE_INT_ARGB)
		val g = img.createGraphics()
		g.drawImage(src.getScaledInstance(px, px, Image.SCALE_AREA_AVERAGING), 0, 0, null)
		g.dispose()
		Pic(img, px, px, 0, 0, px.toFloat())
	}

	/**
	 * A top-left quarter circle [r] pixels square, 4×4 samples a pixel: opaque inside it ([inside]) to draw rounded
	 * corners, or outside it to paint over a picture's corners.
	 */
	fun corner(r: Int, inside: Boolean): Pic = cached("c|$r|$inside") {
		mask(r) { d -> (d <= r) == inside }
	}

	/** A top-left quarter of a ring [w] pixels thick whose outer edge has radius [r]. */
	fun ring(r: Int, w: Int): Pic = cached("r|$r|$w") { mask(r) { d -> d <= r && d > r - w } }

	private fun mask(r: Int, covers: (Double) -> Boolean): Pic {
		val img = BufferedImage(r, r, BufferedImage.TYPE_INT_ARGB)
		for (y in 0 until r) for (x in 0 until r) {
			var n = 0
			for (sy in 0 until 4) for (sx in 0 until 4) {
				if (covers(hypot(r - (x + (sx + 0.5) / 4), r - (y + (sy + 0.5) / 4)))) n++
			}
			img.setRGB(x, y, (n * 255 / 16 shl 24) or 0xFFFFFF)
		}
		return Pic(img, r, r, 0, 0, r.toFloat())
	}

	private fun upload(img: BufferedImage): ResourceLocation {
		val n = NativeImage(img.width, img.height, true)
		for (y in 0 until img.height) for (x in 0 until img.width) {
			n.setPixelRGBA(x, y, (img.getRGB(x, y) ushr 24 shl 24) or 0xFFFFFF)
		}
		val loc = ResourceLocation.fromNamespaceAndPath("tandem", "raster/${next++}")
		Minecraft.getInstance().textureManager.register(loc, DynamicTexture(n))
		return loc
	}

	fun loadFont(file: String): Font =
		Raster::class.java.getResourceAsStream("/assets/tandem/inter/$file").use { Font.createFont(Font.TRUETYPE_FONT, it) }
}
