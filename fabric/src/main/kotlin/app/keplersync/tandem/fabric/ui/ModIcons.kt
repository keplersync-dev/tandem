package app.keplersync.tandem.fabric.ui

import app.keplersync.tandem.fabric.TandemClient
import com.google.gson.JsonArray
import com.google.gson.JsonParser
import com.mojang.blaze3d.platform.NativeImage
import com.twelvemonkeys.imageio.plugins.webp.WebPImageReaderSpi
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.resources.ResourceLocation
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO

/**
 * Mods' icons and names for the mods-don't-match list: an installed mod's own, otherwise its Modrinth project's,
 * looked up by mod id in the background. Null until it's in, and for a mod Modrinth doesn't know by its id.
 */
object ModIcons {
	private val loaded = ConcurrentHashMap<String, ResourceLocation>()
	private val titles = ConcurrentHashMap<String, String>()
	private val asked = ConcurrentHashMap.newKeySet<String>()
	private val SLUG = Regex("[a-z0-9_.-]{3,64}")

	fun get(id: String): ResourceLocation? = loaded[id]

	/** The mod's name: this game's copy's, else its Modrinth project's, else its id. */
	fun name(id: String): String = FabricLoader.getInstance().getModContainer(id).map { it.metadata.name }.orElse(null)?.takeIf { it.isNotBlank() }
		?: titles[id] ?: id

	/** Looks each of [ids] up once. */
	fun want(ids: Collection<String>) {
		val fresh = ids.filter { asked.add(it) }
		if (fresh.isEmpty()) return
		Thread({
			val remote = fresh.filter { id -> installed(id)?.let { put(id, it) } == null }
			remote.filter { SLUG.matches(it) }.chunked(100).forEach { fetch(it) }
		}, "Tandem mod icons").apply { isDaemon = true }.start()
	}

	/**
	 * A modpack's picture: CurseForge's from the image address its profile keeps, Modrinth's looked up by project id.
	 * Fetched once, only from the two sites' own image hosts. Null until it's in.
	 */
	fun pack(mp: Modpack): ResourceLocation? {
		val key = "pack-" + mp.key.replace(':', '-')
		loaded[key]?.let { return it }
		if (asked.add(key)) Thread({
			try {
				val url = when (mp.source) {
					"curseforge" -> mp.icon?.takeIf { it.startsWith("https://media.forgecdn.net/") }
					"modrinth" -> if (PROJECT.matches(mp.project)) JsonParser.parseString(String(read("https://api.modrinth.com/v2/project/${mp.project}"), Charsets.UTF_8))
						.asJsonObject.get("icon_url")?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.startsWith("https://cdn.modrinth.com/") } else null
					else -> null
				}
				if (url != null) decode(read(url))?.let { put(key, it) }
			} catch (e: Exception) {
				TandemClient.LOG.info("Tandem couldn't fetch a modpack's picture: {}", e.toString())
			}
		}, "Tandem pack pictures").apply { isDaemon = true }.start()
		return null
	}

	/**
	 * A game folder's own picture on this PC, read once: a Modrinth App profile's icon.png, or the picture CurseForge keeps
	 * for a profile that isn't a modpack it installed (one Atlas made, or one made by hand).
	 */
	fun file(dir: String): ResourceLocation? {
		val k = "file-" + Integer.toHexString(dir.hashCode())
		loaded[k]?.let { return it }
		if (asked.add(k)) Thread({
			try {
				val d = java.nio.file.Paths.get(dir)
				val cf = d.resolve("minecraftinstance.json")
				val path = d.resolve("icon.png").takeIf { Files.isRegularFile(it) }
					?: cf.takeIf { Files.isRegularFile(it) }?.let { JsonParser.parseString(Files.readString(it)).asJsonObject.get("profileImagePath") }
						?.takeIf { it.isJsonPrimitive }?.let { java.nio.file.Paths.get(it.asString) }
				if (path != null && Files.isRegularFile(path) && Files.size(path) < (2 shl 20)) decode(Files.readAllBytes(path))?.let { put(k, it) }
			} catch (e: Exception) {
				// No picture: the letter stands in.
			}
		}, "Tandem pack pictures").apply { isDaemon = true }.start()
		return null
	}

	private val PROJECT = Regex("[A-Za-z0-9]{1,64}")

	private fun installed(id: String): BufferedImage? = try {
		val m = FabricLoader.getInstance().getModContainer(id).orElse(null)
		m?.metadata?.getIconPath(64)?.orElse(null)?.let { m.findPath(it).orElse(null) }?.let { Files.newInputStream(it).use(ImageIO::read) }
	} catch (e: Exception) {
		null
	}

	private fun fetch(ids: List<String>) {
		try {
			val query = URLEncoder.encode(JsonArray().also { a -> ids.forEach(a::add) }.toString(), "UTF-8")
			val projects = JsonParser.parseString(String(read("https://api.modrinth.com/v2/projects?ids=$query"), Charsets.UTF_8)).asJsonArray
			for (p in projects) {
				val o = p.asJsonObject
				val slug = o.get("slug")?.asString ?: continue
				if (slug in ids) o.get("title")?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotBlank() }?.let { titles[slug] = it.take(64) }
				val url = o.get("icon_url")?.takeIf { !it.isJsonNull }?.asString ?: continue
				if (slug in ids) decode(read(url))?.let { put(slug, it) }
			}
		} catch (e: Exception) {
			TandemClient.LOG.info("Tandem couldn't look up mod icons: {}", e.toString())
		}
	}

	private fun read(url: String): ByteArray {
		val c = URI(url).toURL().openConnection() as HttpURLConnection
		c.connectTimeout = 10_000
		c.readTimeout = 20_000
		c.setRequestProperty("User-Agent", "KeplerSync-Tandem (keplersync.app)")
		// An icon is a few KB; anything far bigger is cut off, and then simply doesn't decode.
		return c.inputStream.use { it.readNBytes(2 shl 20) }
	}

	/**
	 * PNG, JPEG or GIF through Java's own readers; WebP, which most Modrinth icons are, through TwelveMonkeys'. Read at
	 * about 128 px whatever its size, since icons are drawn far smaller, so a huge picture never fills the memory.
	 */
	private fun decode(bytes: ByteArray): BufferedImage? {
		ImageIO.createImageInputStream(ByteArrayInputStream(bytes)).use { input ->
			val readers = ImageIO.getImageReaders(input).asSequence().toList() + WebPImageReaderSpi().createReaderInstance()
			for (reader in readers) {
				try {
					input.seek(0)
					reader.input = input
					val step = maxOf(1, maxOf(reader.getWidth(0), reader.getHeight(0)) / 128)
					return reader.read(0, reader.defaultReadParam.also { it.setSourceSubsampling(step, step, 0, 0) })
				} catch (e: Exception) {
					// Not this reader's format; the next may know it.
				} finally {
					reader.dispose()
				}
			}
		}
		return null
	}

	private fun put(id: String, img: BufferedImage) {
		val n = NativeImage(img.width, img.height, true)
		for (y in 0 until img.height) for (x in 0 until img.width) {
			val argb = img.getRGB(x, y)
			// NativeImage keeps ABGR.
			n.setPixelRGBA(x, y, (argb and 0xFF00FF00.toInt()) or (argb shr 16 and 0xFF) or (argb and 0xFF shl 16))
		}
		val mc = Minecraft.getInstance()
		mc.execute {
			val loc = ResourceLocation.fromNamespaceAndPath("tandem", "mod/" + id.replace(Regex("[^a-z0-9_.-]"), "_"))
			mc.textureManager.register(loc, DynamicTexture(n))
			loaded[id] = loc
		}
	}
}
