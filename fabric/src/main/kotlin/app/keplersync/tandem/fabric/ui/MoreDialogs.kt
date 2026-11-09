package app.keplersync.tandem.fabric.ui

import app.keplersync.tandem.fabric.account.Account as KsAccount
import app.keplersync.tandem.fabric.account.LogBook
import app.keplersync.tandem.fabric.play.Hosting
import app.keplersync.tandem.fabric.sync.WorldSync
import gg.essential.elementa.ElementaVersion
import gg.essential.elementa.UIComponent
import gg.essential.elementa.WindowScreen
import gg.essential.elementa.components.UIContainer
import gg.essential.elementa.constraints.CenterConstraint
import gg.essential.elementa.constraints.SiblingConstraint
import gg.essential.elementa.dsl.basicHeightConstraint
import gg.essential.elementa.dsl.basicWidthConstraint
import gg.essential.elementa.dsl.childOf
import gg.essential.elementa.dsl.constrain
import gg.essential.elementa.dsl.minus
import gg.essential.elementa.dsl.percent
import gg.essential.universal.UKeyboard
import gg.essential.universal.UMatrixStack
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.world.Difficulty
import net.minecraft.world.level.GameType
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.ConfirmLinkScreen
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// More dialogs, with the sample's data and words.

/** One option in a radio list: a title, an optional line under it, an optional violet note at the right. */
class Opt(val title: String, val line: String? = null, val rec: String? = null)

/** Choices as radios: picking one unpicks the rest. */
fun radios(width: Int, opts: List<Opt>, picked: Int?, pick: (Int) -> Unit): UIComponent {
	val col = Column(8).constrain { this.width = width.at }
	val all = mutableListOf<Choice>()
	for ((i, o) in opts.withIndex()) {
		val c = Choice(width) { all.forEachIndexed { j, other -> other.on = j == i }; pick(i) }
		c.text(o.title, o.line, null, null, o.rec)
		c.on = i == picked
		all += c
		col.add(c)
	}
	return col
}

/** A .choice without a radio that does something straight away, with a chevron: the storage-full options. */
fun actionRow(width: Int, title: String, line: String, press: () -> Unit): UIComponent {
	val b = Box(12.tp, Tok.raise, Tok.outline).constrain { this.width = width.at }
	val col = Column(3).constrain { x = 17.at; y = CenterConstraint(); this.width = (width - 70).at } childOf b
	col.add(Label(title, 14, Weight.SEMIBOLD))
	col.add(Label(line, 13, Weight.REGULAR, Tok.muted, 1.45f))
	Icon("chev", 16, Tok.muted).constrain { x = 17.fromEnd; y = CenterConstraint() } childOf b
	b.constrain { height = basicHeightConstraint { col.getHeight() + 30.tp } }
	b.onMouseEnter { b.border = Tok.sec }
	b.onMouseLeave { b.border = Tok.outline }
	b.onMouseClick { it.stopPropagation(); press() }
	Focus.add(b, b.corner, press)
	return b
}

/** A friend to tick in the picker: checkbox, face with presence, name and a line. */
private class Pick(p: Person, line: String, on: Boolean, private val changed: (Boolean) -> Unit) : Box(9.tp, Tok.rail) {
	var on = on
		private set
	private val face: Face

	init {
		constrain { width = 100.percent; height = 48.at }
		object : UIComponent() {
			override fun draw(matrixStack: gg.essential.universal.UMatrixStack) {
				beforeDraw(matrixStack)
				val l = getLeft(); val t = getTop(); val r = getRight(); val b = getBottom()
				rounded(matrixStack, l, t, r, b, 5.tp, if (this@Pick.on) Tok.violet else Tok.outline)
				if (!this@Pick.on) rounded(matrixStack, l + 2.tp, t + 2.tp, r - 2.tp, b - 2.tp, 3.tp, fill)
				else drawIcon(matrixStack, "check", l + 3.tp, t + 3.tp, 14.tp, Tok.violetInk)
				super.draw(matrixStack)
			}
		}.constrain { x = 10.at; y = CenterConstraint(); width = 20.at; height = 20.at } childOf this
		face = Face({ p.skin }, 28, 6, p.online, Tok.rail).constrain { x = (10 + 20 + 12).at; y = CenterConstraint() } childOf this
		val who = Column(1).constrain { x = (10 + 20 + 12 + 28 + 12).at; y = CenterConstraint(); width = 100.percent - 100.at } childOf this
		who.add(Label(p.name, 14, Weight.MEDIUM).fitWithin(this, 12))
		who.add(Label(line, 13, Weight.REGULAR, Tok.muted).fitWithin(this, 12))
		val flip = { this.on = !this.on; changed(this.on) }
		onMouseEnter { fill = Tok.raise; face.under = Tok.raise }
		onMouseLeave { fill = Tok.rail; face.under = Tok.rail }
		onMouseClick { it.stopPropagation(); flip() }
		Focus.add(this, corner, flip)
	}
}

/**
 * Sync this world: a friend picker (people who played there already ticked), the storage it uses, and that it stays a
 * normal world. With [edit], it's the world's "Who can host" from Shared with.
 */
fun Dialogs.sync(world: String, size: String, played: List<String>, edit: Boolean, current: List<String>, folder: java.nio.file.Path? = null, done: (List<String>) -> Unit) {
	// Adding to a synced world ([edit]) lists only friends not in it yet; removing is the ✕ on each person.
	val picked = (if (edit) emptyList() else played).toMutableSet()
	layers.openDialog {
		lateinit var go: Button
		fun s() = if (picked.size > 1) "s" else ""
		fun label() = if (edit) (if (picked.isEmpty()) tr("tandem.add") else tr("tandem.add_friend", picked.size, s())) else if (picked.isEmpty()) tr("tandem.sync_for_just_me") else tr("tandem.sync_with_friend", picked.size, s())
		val order = Sample.friends.filter { !edit || it.name !in current }.sortedWith(compareBy({ it.name !in played }, { !it.online }))
		val box = box(12, Tok.rail, Tok.line).constrain { width = 500.at; height = (min(order.size, 5) * 50 + 8).at }
		val list = scroller(UIContainer().constrain { x = 5.at; y = 5.at; width = 100.percent - 10.at; height = 100.percent - 10.at } childOf box)
		for (p in order) {
			Pick(p, if (p.name in played) tr("tandem.played_in_this_world") else p.presence, p.name in picked) { on ->
				if (on) picked += p.name else picked -= p.name
				go.text = label()
			}.constrain { y = SiblingConstraint(2.tp) } childOf list
		}
		go = Button(label(), Kind.FILLED, Size.NORMAL, null, Tok.panel) { layers.closeDialog(); done(picked.toList()) }
		dialogTargets["sync-go"] = go
		val parts = mutableListOf<UIComponent>(
			titled(
				if (edit) tr("tandem.add_friends_to", world) else tr("tandem.sync", world),
				if (edit) tr("tandem.friends_you_add_can_play_it_and") else tr("tandem.friends_you_pick_can_host_the_latest"), 500,
			),
			if (order.isEmpty()) wrapped(tr("tandem.all_your_friends_are_in_already", world), 13, Weight.REGULAR, Tok.muted, 500.tp, 1.5f) else box,
		)
		if (!edit) {
			val o = if (RealStorage.on) RealStorage.of { } else null
			val space = if (RealStorage.on) o?.let { tr("tandem.of_used", bytesText(it.usedBytes), bytesText(it.totalBytes)) } ?: ""
			else tr("tandem.of_gb_used", Storage.usedGb(), Storage.gb)
			// A world bigger than the space left is said here, not after the copy by KeplerSync refusing it. With your
			// own storage set up too: a new world starts on KeplerSync's, and moves to yours after.
			val need = if (o != null && folder != null) runCatching { app.keplersync.tandem.core.Ignores.syncedBytes(folder) }.getOrNull() else null
			val free = if (o != null) maxOf(0L, o.totalBytes - o.usedBytes) else 0L
			if (need != null && need > free) {
				parts += safe("warn", tr("tandem.s_is_and_your_storage_has_free", world, bytesText(need), bytesText(free)), 500, Tok.warn)
				go.enabled = false
			} else parts += safe("cloud", tr("tandem.uses_of_your_storage_distant_horizons_data", size.ifEmpty { "some" }, space), 500, Tok.violet)
			parts += safe("shield", tr("tandem.stays_a_normal_world_on_this_pc", world), 500)
		}
		parts += actions(500, Button(tr("tandem.cancel"), Kind.OUTLINE, Size.NORMAL, null, Tok.panel) { layers.closeDialog() }, go)
		dialog(500, *parts.toTypedArray())
	}
}


/** Get more space: yearly or monthly, then Free, Plus and Group; the current plan and too-small plans are off. */
fun Dialogs.plans() {
	var yearly = true
	var pick = tr("tandem.group")
	var waiting = false
	fun open() {
		layers.openDialog {
			if (waiting) {
				return@openDialog dialog(
					500,
					who(mic("cloud", Tok.violet), tr("tandem.finish_in_your_browser"), tr("tandem.keplersync_app_opened_with_you_already_signed"), 500),
					actions(500, Button(tr("tandem.close"), Kind.OUTLINE, Size.NORMAL, null, Tok.panel) { layers.closeDialog() }),
				)
			}
			val used = Storage.usedGb()
			val head = UIContainer().constrain { width = 760.at }
			val t = titled(tr("tandem.get_more_space"), tr("tandem.every_plan_syncs_and_hosts_the_same", used), 520) childOf head
			val seg = box(12, Tok.rail, Tok.line) childOf head
			val segRow = row(4,
				segButton(tr("tandem.yearly"), tr("tandem.save_33"), yearly) { yearly = true; open() },
				segButton(tr("tandem.monthly"), null, !yearly) { yearly = false; open() },
			).constrain { x = 5.at; y = 5.at } childOf seg
			seg.constrain { x = 0.fromEnd; width = basicWidthConstraint { segRow.getWidth() + 10.tp }; height = 44.at }
			head.constrain { height = basicHeightConstraint { max(t.getHeight(), 44.tp) } }

			val tiers = UIContainer().constrain { width = 760.at }
			val each = (760 - 20) / 3f
			for ((i, tier) in Storage.tiers.withIndex()) {
				val (name, gb, month, year, note) = tier
				val current = name == Storage.plan
				val small = gb.toDouble() < used
				val off = current || small
				val sel = pick == name && !off
				val b = Box(14.tp, if (sel) Tok.over(Tok.panel, Tok.violet, 0.1) else Tok.raise, if (sel) Tok.violet else if (off) Tok.line else Tok.outline)
					.constrain { x = (i * (each + 10)).at; width = each.at } childOf tiers
				val col = Column(4).constrain { x = 17.at; y = 17.at; width = 100.percent - 34.at } childOf b
				val dim = if (off) Tok.muted else Tok.ink
				col.add(Label(if (current) tr("tandem.your_plan", name) else name, 14, Weight.SEMIBOLD, Tok.sec))
				col.add(Label("$gb GB", 26, Weight.BOLD, dim))
				if (month == "0") col.add(Label(tr("tandem.free"), 18, Weight.BOLD, dim))
				else {
					col.add(rich(listOf(Run(if (yearly) "$$year" else "$$month", Weight.BOLD, dim), Run(if (yearly) tr("tandem.a_year") else tr("tandem.a_month"), Weight.REGULAR, dim)), 16, 200.tp))
					col.add(Label(if (yearly) "$%.2f a month".format(year.toDouble() / 12) else "$%.2f a year".format(month.toDouble() * 12), 12.5, Weight.REGULAR, Tok.muted))
				}
				col.add(wrapped(note, 12.5, Weight.REGULAR, Tok.muted, (each - 34).tp, 1.3f))
				if (small && !current) col.add(Label(tr("tandem.too_small_for_your_worlds"), 12.5, Weight.REGULAR, Tok.muted))
				b.constrain { height = basicHeightConstraint { col.getHeight() + 34.tp } }
				if (!off) {
					val choose = { pick = name; open() }
					b.onMouseClick { it.stopPropagation(); choose() }
					Focus.add(b, b.corner, choose)
				}
			}
			tiers.constrain { height = basicHeightConstraint { c -> c.children.maxOf { it.getHeight() } } }
			val foot = UIContainer().constrain { width = 760.at }
			val fine = wrapped(tr("tandem.sold_by_keplersync_payments_by_paddle_receipts", Storage.DISCLAIMER), 12.5, Weight.REGULAR, Tok.muted, 440.tp, 1.5f) childOf foot
			actions(300,
				Button(tr("tandem.not_now"), Kind.OUTLINE, Size.NORMAL, null, Tok.panel) { layers.closeDialog() },
				Button(tr("tandem.continue_to_payment"), Kind.FILLED, Size.NORMAL, null, Tok.panel) {
					waiting = true
					// shortcut: sample payment; the real one opens keplersync.app signed in and waits for Paddle's webhook
					val chosen = Storage.tiers.first { it[0] == pick }
					open()
					Thread {
						Thread.sleep(2500)
						Minecraft.getInstance().execute {
							Storage.plan = chosen[0]; Storage.gb = chosen[1].toInt()
							layers.closeDialog(); changed()
							layers.toast(tr("tandem.youre_on", chosen[0]), tr("tandem.gb_of_storage_nothing_else_to_do", chosen[1]), "cloud")
						}
					}.start()
				},
			).constrain { x = 0.fromEnd; y = 0.fromEnd } childOf foot
			foot.constrain { height = basicHeightConstraint { max(fine.getHeight(), 40.tp) } }
			dialog(
				760, head, tiers,
				safe("shield", tr("tandem.cancel_any_time_your_worlds_are_never"), 760),
				foot,
			)
		}
	}
	open()
}

/** One half of a two-way toggle. */
private fun segButton(text: String, note: String?, on: Boolean, press: () -> Unit): UIComponent {
	val b = box(9, if (on) Tok.raise2 else Tok.rail)
	val r = (if (note != null) row(6, Label(text, 13, Weight.SEMIBOLD, if (on) Tok.ink else Tok.muted), Label(note, 13, Weight.MEDIUM, Tok.okText))
	else row(6, Label(text, 13, Weight.SEMIBOLD, if (on) Tok.ink else Tok.muted))).constrain { x = 14.at; y = CenterConstraint() } childOf b
	b.constrain { width = basicWidthConstraint { r.getWidth() + 28.tp }; height = 34.at }
	b.onMouseClick { it.stopPropagation(); press() }
	Focus.add(b, b.corner, press)
	return b
}

/** Your own Cloudflare R2: four steps beside four fields, a test, then Save and move worlds ([move], or all yours). */
fun Dialogs.ownStorage(move: World? = null, saved: () -> Unit = {}) {
	if (RealStorage.on) return ownStorageReal(move, saved)
	var state = "" // "", "testing", "ok", "bad"
	val values = mutableMapOf(tr("tandem.account_id") to "3f9c2a81d0", tr("tandem.bucket_name") to "tandem-worlds", tr("tandem.access_key_id") to "a41e09c7", tr("tandem.secret_access_key") to "")
	fun open() {
		layers.openDialog {
			val cols = UIContainer().constrain { width = 900.at }
			val left = steps(
				438,
				listOf(Run(tr("tandem.in_cloudflare_create_a_bucket_for_example")), Run("tandem-worlds", Weight.SEMIBOLD), Run(".")),
				listOf(Run(tr("tandem.go_to_r2_manage_api_tokens_create"))),
				listOf(Run(tr("tandem.permission")), Run(tr("tandem.object_read_write"), Weight.SEMIBOLD), Run(tr("tandem.apply_to")), Run(tr("tandem.specific_buckets_only"), Weight.SEMIBOLD), Run(tr("tandem.and_pick_your_bucket"))),
				listOf(Run(tr("tandem.paste_the_four_values_here_your_friends"))),
			) childOf cols
			val right = Column(12).constrain { x = 462.at; width = 438.at } childOf cols
			for ((k, v) in values) right.add(labeled(k, Field("", null, 40, v, masked = k.startsWith(tr("tandem.secret"))) { values[k] = it }))
			cols.constrain { height = basicHeightConstraint { max(left.getHeight(), right.getHeight()) } }
			val parts = mutableListOf<UIComponent>(
				titled(tr("tandem.use_your_own_cloudflare_r2_storage"), tr("tandem.for_the_worlds_you_sync_you_pay"), 900),
				cols,
			)
			if (state == "ok" || state == "bad") {
				val c = box(12, Tok.raise, Tok.line).constrain { width = 900.at }
				val col = Column(8).constrain { x = 15.at; y = 13.at; width = 868.at } childOf c
				if (state == "ok") {
					col.add(safe("check", tr("tandem.can_save_read_and_delete_files_in"), 868, Tok.ok))
					col.add(safe("warn", tr("tandem.this_token_can_also_reach_your_other"), 868, Tok.warn))
				} else col.add(safe("warn", tr("tandem.fill_in_the_secret_access_key_then"), 868, Tok.warn))
				c.constrain { height = basicHeightConstraint { col.getHeight() + 26.tp } }
				parts += c
			}
			val foot = UIContainer().constrain { width = 900.at; height = 40.at }
			row(8, Icon("lock", 16, Tok.muted), Label(tr("tandem.stored_encrypted_only_used_to_make_short"), 13, Weight.REGULAR, Tok.muted))
				.constrain { y = CenterConstraint() } childOf foot
			val main = if (state == "ok") Button(tr("tandem.save_and_move_worlds"), Kind.FILLED, Size.NORMAL, null, Tok.panel) {
				Storage.ownR2 = true
				layers.closeDialog(); changed(); saved()
				layers.toast(tr("tandem.moving_worlds_to_tandem_worlds"), tr("tandem.friends_can_keep_playing_while_it_moves"), "cloud")
			} else Button(if (state == "testing") tr("tandem.testing") else tr("tandem.test_the_keys"), Kind.FILLED, Size.NORMAL, null, Tok.panel) {
				state = "testing"; open()
				// shortcut: sample test; the real one tries a write, a read and a delete in the bucket
				Thread { Thread.sleep(900); Minecraft.getInstance().execute { state = if (values[tr("tandem.secret_access_key")].isNullOrEmpty()) "bad" else "ok"; open() } }.start()
			}.also { it.enabled = state != "testing" }
			actions(330, Button(tr("tandem.cancel"), Kind.OUTLINE, Size.NORMAL, null, Tok.panel) { layers.closeDialog() }, main)
				.constrain { x = 0.fromEnd } childOf foot
			parts += foot
			dialog(900, *parts.toTypedArray())
		}
	}
	open()
}

/**
 * The real setup: KeplerSync tests the keys with a write, a read and a delete in the bucket, and saves them only when
 * that works. The secret is sent once, sealed on the server, and never shown again; once saved it's gone from here.
 */
private fun Dialogs.ownStorageReal(move: World?, saved: () -> Unit) {
	var state = "" // "", "testing", "ok", "bad"
	var problem = ""
	var result: app.keplersync.tandem.core.KeplerSyncApi.OwnStorage? = null
	val own = RealStorage.of { }?.own
	val values = linkedMapOf(tr("tandem.account_id") to (own?.accountId ?: ""), tr("tandem.bucket_name") to (own?.bucket ?: ""), tr("tandem.access_key_id") to (own?.accessKeyId ?: ""), tr("tandem.secret_access_key") to "")
	fun open() {
		layers.openDialog {
			val cols = UIContainer().constrain { width = 900.at }
			val left = steps(
				438,
				listOf(Run(tr("tandem.in_cloudflare_create_a_bucket_for_example")), Run("tandem-worlds", Weight.SEMIBOLD), Run(".")),
				listOf(Run(tr("tandem.go_to_r2_manage_api_tokens_create"))),
				listOf(Run(tr("tandem.permission")), Run(tr("tandem.object_read_write"), Weight.SEMIBOLD), Run(tr("tandem.apply_to")), Run(tr("tandem.specific_buckets_only"), Weight.SEMIBOLD), Run(tr("tandem.and_pick_your_bucket"))),
				listOf(Run(tr("tandem.paste_the_four_values_here_your_friends"))),
			) childOf cols
			val right = Column(12).constrain { x = 462.at; width = 438.at } childOf cols
			for ((k, v) in values) right.add(labeled(k, Field("", null, 40, v, masked = k.startsWith(tr("tandem.secret"))) { values[k] = it }))
			cols.constrain { height = basicHeightConstraint { max(left.getHeight(), right.getHeight()) } }
			val parts = mutableListOf<UIComponent>(
				titled(tr("tandem.use_your_own_cloudflare_r2_storage"), tr("tandem.for_the_worlds_you_sync_you_pay_2"), 900),
				cols,
			)
			if (state == "ok" || state == "bad") {
				val c = box(12, Tok.raise, Tok.line).constrain { width = 900.at }
				val col = Column(8).constrain { x = 15.at; y = 13.at; width = 868.at } childOf c
				val r = result
				if (state == "ok" && r != null) {
					col.add(safe("check", tr("tandem.can_save_read_and_delete_files_in_2", r.bucket), 868, Tok.ok))
					if (r.scoped == false) col.add(safe("warn", tr("tandem.this_token_can_also_reach_your_other_2", r.bucket), 868, Tok.warn))
				} else col.add(safe("warn", tr("tandem.nothing_was_saved", problem), 868, Tok.warn))
				c.constrain { height = basicHeightConstraint { col.getHeight() + 26.tp } }
				parts += c
			}
			val foot = UIContainer().constrain { width = 900.at; height = 40.at }
			row(8, Icon("lock", 16, Tok.muted), Label(tr("tandem.stored_encrypted_only_used_to_make_short"), 13, Weight.REGULAR, Tok.muted))
				.constrain { y = CenterConstraint() } childOf foot
			val buttons = if (state == "ok") actions(400,
				Button(tr("tandem.not_now"), Kind.OUTLINE, Size.NORMAL, null, Tok.panel) { layers.closeDialog(); changed(); saved() },
				Button(if (move != null) tr("tandem.move_here", move.name) else tr("tandem.move_my_worlds_here"), Kind.FILLED, Size.NORMAL, null, Tok.panel) {
					layers.closeDialog()
					RealStorage.move(if (move != null) listOf(move) else Sample.worlds.toList(), "own", layers) { changed(); saved() }
				},
			) else actions(330,
				Button(tr("tandem.cancel"), Kind.OUTLINE, Size.NORMAL, null, Tok.panel) { layers.closeDialog() },
				Button(if (state == "testing") tr("tandem.testing") else tr("tandem.test_and_save"), Kind.FILLED, Size.NORMAL, null, Tok.panel) {
					state = "testing"; open()
					RealStorage.save(values.getValue(tr("tandem.account_id")), values.getValue(tr("tandem.bucket_name")), values.getValue(tr("tandem.access_key_id")), values.getValue(tr("tandem.secret_access_key"))) { o, why ->
						if (o != null) { state = "ok"; result = o; values[tr("tandem.secret_access_key")] = "" } else { state = "bad"; problem = why!!.trimEnd('.') }
						open()
					}
				}.also { it.enabled = state != "testing" },
			)
			buttons.constrain { x = 0.fromEnd } childOf foot
			parts += foot
			dialog(900, *parts.toTypedArray())
		}
	}
	open()
}

/** Remove your own storage's keys: KeplerSync forgets them at once; worlds still kept there need them to be played. */
fun Dialogs.removeKeys(bucket: String, worlds: Int, removed: () -> Unit) {
	val them = if (worlds == 1) "it" else "them"
	layers.openDialog {
		dialog(
			500,
			titled(tr("tandem.remove_your_storage_keys"),
				if (worlds > 0) tr("tandem.kept_in_nobody_can_play_from_there", if (worlds == 1) tr("tandem.a_world_is") else tr("tandem.worlds_are", worlds), bucket, them, them)
				else tr("tandem.keplersync_forgets_them_at_once_your_bucket"), 500),
			actions(500,
				Button(tr("tandem.cancel"), Kind.OUTLINE, Size.NORMAL, null, Tok.panel) { layers.closeDialog() },
				Button(tr("tandem.remove_keys"), Kind.DANGER, Size.NORMAL, null, Tok.panel) { layers.closeDialog(); RealStorage.remove(layers, removed) },
			),
		)
	}
}

/** Delete a stopped world's cloud copy now, rather than when its 30 days are up. */
fun Dialogs.deleteNow(cloudId: String, name: String, deleted: () -> Unit) {
	layers.openDialog {
		dialog(
			500,
			titled(tr("tandem.delete_now_title", name), tr("tandem.its_cloud_copy_and_history_are_deleted"), 500),
			actions(500,
				Button(tr("tandem.cancel"), Kind.OUTLINE, Size.NORMAL, null, Tok.panel) { layers.closeDialog() },
				Button(tr("tandem.delete_now"), Kind.DANGER, Size.NORMAL, null, Tok.panel) { layers.closeDialog(); RealStorage.deleteNow(layers, cloudId, name, deleted) },
			),
		)
	}
}

/** Leave a world shared with you: keep your copy as a normal world, or delete it. */
fun Dialogs.leaveWorld(w: World, left: () -> Unit) {
	var pick = 0
	val owner = w.owner ?: tr("tandem.the_owner")
	fun confirm() = layers.openDialog {
		dialog(
			500,
			titled(tr("tandem.delete_your_copy_of", w.name), tr("tandem.its_gone_from_this_pc_for_good", owner), 500),
			actions(500,
				Button(tr("tandem.cancel"), Kind.OUTLINE, Size.NORMAL, null, Tok.panel) { layers.closeDialog() },
				Button(tr("tandem.delete_and_leave"), Kind.DANGER, Size.NORMAL, null, Tok.panel) { layers.closeDialog(); RealLeave.leave(layers, w, true, left) },
			),
		)
	}
	layers.openDialog {
		val go = Button(tr("tandem.leave"), Kind.FILLED, Size.NORMAL, null, Tok.panel) {
			layers.closeDialog()
			if (pick == 1) confirm() else RealLeave.leave(layers, w, false, left)
		}
		val parts = mutableListOf<UIComponent>(titled(tr("tandem.leave_2", w.name), tr("tandem.you_cant_host_it_or_get_new", owner), 500))
		if (w.levelId != null) parts += radios(500, listOf(
			Opt(tr("tandem.keep_my_copy_as_a_normal_world"), tr("tandem.it_stays_on_this_pc_as_it")),
			Opt(tr("tandem.delete_my_copy"), tr("tandem.frees_on_this_pc", w.size)),
		), 0) { pick = it }
		parts += actions(500, Button(tr("tandem.cancel"), Kind.OUTLINE, Size.NORMAL, null, Tok.panel) { layers.closeDialog() }, go)
		dialog(500, *parts.toTypedArray())
	}
}

/** Report someone: a reason, what happened (optional), and Also block. KeplerSync reviews every report. */
fun Dialogs.report(p: Person, blocked: () -> Unit = {}) {
	var alsoBlock = false
	var reason = ""
	val reasons = listOf(tr("tandem.harassment_or_bullying"), tr("tandem.inappropriate_name_or_picture"), tr("tandem.cheating_or_griefing"), tr("tandem.something_else"))
	layers.openDialog {
		lateinit var send: Button
		val what = Field("", null, lines = 3)
		send = Button(tr("tandem.pick_a_reason_first"), Kind.FILLED, Size.NORMAL, null, Tok.panel) {
			layers.closeDialog()
			if (p.userId != null) return@Button RealFriends.report(layers, changed, p, reason, what.value, alsoBlock)
			if (alsoBlock) {
				Sample.friends.remove(p); Sample.blocked += p; blocked(); changed()
			}
			layers.toast(tr("tandem.report_sent"), if (alsoBlock) tr("tandem.thanks_is_blocked_too", p.name) else tr("tandem.thanks_keplersync_looks_at_every_report"))
		}
		send.enabled = false
		dialog(
			500,
			who(Face({ p.skin }, 48, 10, null, Tok.panel), tr("tandem.report", p.name), tr("tandem.keplersync_reviews_every_report_isnt_told_who", p.name), 500),
			radios(500, reasons.map { Opt(it) }, null) { reason = reasons[it]; send.enabled = true; send.text = tr("tandem.send_report") },
			labeled(tr("tandem.what_happened_optional"), what),
			checkbox(tr("tandem.also_block", p.name), false) { alsoBlock = it },
			actions(500, Button(tr("tandem.cancel"), Kind.OUTLINE, Size.NORMAL, null, Tok.panel) { layers.closeDialog() }, send),
		)
	}
}

/** A new friend code: the old one stops working, friends stay. */
fun Dialogs.newCode() {
	layers.openDialog {
		dialog(
			500,
			titled(tr("tandem.get_a_new_friend_code"), tr("tandem.stops_working_so_anyone_you_gave_it", Account.code), 500),
			actions(500,
				Button(tr("tandem.keep_this_code"), Kind.OUTLINE, Size.NORMAL, null, Tok.panel) { layers.closeDialog() },
				Button(tr("tandem.get_a_new_code"), Kind.FILLED, Size.NORMAL, null, Tok.panel) {
					if (RealFriends.signedIn) { layers.closeDialog(); return@Button RealFriends.newCode(layers, changed) }
					Account.code = "KEPL-" + (1000 + (Math.random() * 9000).toInt())
					layers.closeDialog(); changed()
					layers.toast(tr("tandem.your_new_code_is", Account.code), tr("tandem.the_old_one_no_longer_works"))
				},
			),
		)
	}
}

/** Add a friend: your code to copy, theirs to type, and who it is before you send. */
fun Dialogs.addFriend() {
	layers.openDialog {
		lateinit var send: Button
		val hint = UIContainer().constrain { width = 500.at; height = 22.at }
		fun preview(ok: Boolean) {
			hint.clearChildren()
			if (ok) {
				val pat = Sample.requests.firstOrNull() ?: Person(tr("tandem.pat"), 14, true, tr("tandem.in_the_menu"))
				row(8, Face({ pat.skin }, 22, 5, null, Tok.panel), Label(tr("tandem.pat"), 13, Weight.REGULAR, Tok.ink), Label(tr("tandem.will_get_your_request"), 13, Weight.REGULAR, Tok.muted))
					.constrain { y = CenterConstraint() } childOf hint
			} else Label(tr("tandem.friend_codes_look_like", if (RealFriends.signedIn) "7KQ29XMB" else "KEPL-1234"), 13, Weight.REGULAR, Tok.muted).constrain { y = CenterConstraint() } childOf hint
		}
		preview(false)
		var typed = ""
		send = Button(tr("tandem.send_request"), Kind.FILLED, Size.NORMAL, null, Tok.panel) {
			layers.closeDialog()
			if (RealFriends.signedIn) return@Button RealFriends.addFriend(layers, changed, typed)
			layers.toast(tr("tandem.friend_request_sent_to_pat"), tr("tandem.pat_shows_up_here_once_they_accept"))
		}
		send.enabled = false
		val card = box(14, Tok.raise, Tok.line).constrain { width = 500.at; height = 74.at }
		val mine = Column(4).constrain { x = 17.at; y = CenterConstraint(); width = 300.at } childOf card
		mine.add(Label(tr("tandem.your_friend_code"), 13, Weight.REGULAR, Tok.muted))
		mine.add(Label(Account.code, 18, Weight.SEMIBOLD))
		Button(tr("tandem.copy"), Kind.OUTLINE, Size.SMALL, null, Tok.raise) { copyCode() }.constrain { x = 17.fromEnd; y = CenterConstraint() } childOf card
		dialog(
			500,
			titled(tr("tandem.add_a_friend"), tr("tandem.swap_friend_codes_with_them"), 500),
			card,
			labeled(tr("tandem.your_friends_code"), Field(if (RealFriends.signedIn) "7KQ29XMB" else "KEPL-1234", null, 40) {
				typed = it
				val ok = if (RealFriends.signedIn) Regex("^[a-zA-Z0-9]{8}$").matches(it.trim().replace("-", ""))
				else Regex("^[a-zA-Z]{4}-?\\d{4}$").matches(it.trim())
				send.enabled = ok; preview(ok && !RealFriends.signedIn)
			}),
			hint,
			actions(500, Button(tr("tandem.cancel"), Kind.OUTLINE, Size.NORMAL, null, Tok.panel) { layers.closeDialog() }, send),
		)
	}
}

/** Send logs: Tandem's own lines and this game's warnings and errors go to KeplerSync, with what happened if the player says. */
fun Dialogs.sendLogs() {
	layers.openDialog {
		val what = Field("", null, lines = 3)
		val send = Button(tr("tandem.send_logs"), Kind.FILLED, Size.NORMAL, null, Tok.panel) {
			layers.closeDialog()
			val account = KsAccount.get() ?: return@Button
			val log = LogBook.text()
			val note = what.value
			val version = FabricLoader.getInstance().getModContainer("tandem").map { it.metadata.version.friendlyString }.orElse("dev")
			account.run { it.sendLogs(version, WorldSync.mcVersion(), note, log) }.whenComplete { _, e ->
				Minecraft.getInstance().execute {
					if (e == null) layers.toast(tr("tandem.logs_sent"), tr("tandem.thanks_theyre_deleted_after_30_days"))
					else layers.toast(tr("tandem.couldnt_send_the_logs"), (e.cause ?: e).message ?: tr("tandem.try_again_in_a_moment"), "warn")
				}
			}
		}
		dialog(
			500,
			titled(tr("tandem.send_logs_to_keplersync"), tr("tandem.tandems_own_log_lines_and_this_games"), 500),
			labeled(tr("tandem.what_happened_optional"), what),
			actions(500, Button(tr("tandem.cancel"), Kind.OUTLINE, Size.NORMAL, null, Tok.panel) { layers.closeDialog() }, send),
		)
	}
}

fun Dialogs.copyCode() {
	Minecraft.getInstance().keyboardHandler.clipboard = Account.code
	layers.toast(tr("tandem.copied", Account.code), tr("tandem.send_it_to_your_friend_however_you"))
}

/**
 * Leaving a synced world with friends in it: a compact list of who could host
 * next, best connection first and picked, that scrolls past five; Close for everyone is a link beside the buttons.
 * [leave] gets the pick, or null to close for everyone, and closes the dialog or opens the next one in its place.
 */
fun Dialogs.leave(friends: List<Person>, leave: (Person?) -> Unit) {
	val sorted = friends.sortedByDescending { it.bars }
	var pick = sorted.first()
	layers.openDialog {
		val w = 460
		// 44 px rows; past five, half of the sixth shows, so it's clear the list scrolls.
		val box = box(12, Tok.rail, Tok.line).constrain { width = w.at; height = (min(sorted.size * 44f, 242f) + 2).at }
		val list = scroller(UIContainer().constrain { x = 1.at; y = 1.at; width = 100.percent - 2.at; height = 100.percent - 2.at } childOf box)
		val rows = mutableListOf<HostPick>()
		for ((i, p) in sorted.withIndex()) {
			rows += HostPick(p, best = i == 0 && sorted.size > 1, first = i == 0) { pick = p; rows.forEach { r -> r.on = r.person === p } }
				.also { it.on = i == 0 }.constrain { y = SiblingConstraint() } childOf list
		}
		val foot = UIContainer().constrain { width = w.at; height = 40.at }
		actions(w, Button(tr("tandem.keep_playing"), Kind.OUTLINE, Size.NORMAL, null, Tok.panel) { layers.closeDialog() },
			Button(tr("tandem.hand_off_and_leave"), Kind.FILLED, Size.NORMAL, null, Tok.panel) { leave(pick) }) childOf foot
		// After the buttons' row, so it's on top of it and gets its clicks.
		link(tr("tandem.close_for_everyone")) { leave(null) }.constrain { y = CenterConstraint() } childOf foot
		dialog(
			w,
			titled(tr("tandem.still_playing", if (sorted.size == 1) sorted[0].name + " is" else tr("tandem.friends_are", sorted.size)), tr("tandem.pick_who_hosts_next_everyone_joins_them"), w),
			box,
			foot,
		)
	}
}

/** Someone in the hand-off list: a radio, their face and name, Best connection on the first, and their bars. */
private class HostPick(val person: Person, best: Boolean, private val first: Boolean, private val choose: () -> Unit) : UIComponent() {
	var on = false
		set(v) { field = v; face.under = surface() }
	private var hover = false
	private val face: Face

	init {
		constrain { width = 100.percent; height = 44.at }
		face = Face({ person.skin }, 24, 6, null, Tok.rail).constrain { x = (12 + 18 + 10).at; y = CenterConstraint() } childOf this
		val name = Label(person.name, 14, Weight.MEDIUM)
		val tag = if (best) pill12(tr("tandem.best_connection"), Tok.rail) else null
		(if (tag != null) row(8, name, tag) else row(8, name))
			.constrain { x = (12 + 18 + 10 + 24 + 10).at; y = CenterConstraint() } childOf this
		val bars = Bars(person.bars, false).constrain { x = 14.fromEnd; y = CenterConstraint() } childOf this
		name.room = { bars.getLeft() - 12.tp - (tag?.let { it.getWidth() + 8.tp } ?: 0f) - name.getLeft() }
		onMouseEnter { hover = true; face.under = surface() }
		onMouseLeave { hover = false; face.under = surface() }
		onMouseClick { it.stopPropagation(); choose() }
		Focus.add(this, 4.tp, choose)
	}

	private fun surface() = if (on) Tok.over(Tok.rail, Tok.violet, 0.1) else if (hover) Tok.raise else Tok.rail

	override fun draw(matrixStack: gg.essential.universal.UMatrixStack) {
		beforeDraw(matrixStack)
		val l = getLeft(); val t = getTop(); val r = getRight(); val b = getBottom()
		val bg = surface()
		rounded(matrixStack, l, t, r, b, 0f, bg)
		if (!first) rounded(matrixStack, l, t, r, t + 1.tp, 0f, Tok.line)
		val x = l + 12.tp; val y = (t + b) / 2 - 9.tp
		rounded(matrixStack, x, y, x + 18.tp, y + 18.tp, 9.tp, if (on) Tok.violet else Tok.outline)
		rounded(matrixStack, x + 2.tp, y + 2.tp, x + 16.tp, y + 16.tp, 7.tp, bg)
		if (on) rounded(matrixStack, x + 5.tp, y + 5.tp, x + 13.tp, y + 13.tp, 4.tp, Tok.violet)
		super.draw(matrixStack)
	}
}

/**
 * Leaving a Just play world with friends in it: it closes for them; Sync this world (when offered) means it won't next
 * time. [leave] closes the dialog or opens the next one in its place.
 */
fun Dialogs.leaveJust(friends: List<Person>, world: String, leave: () -> Unit, sync: (() -> Unit)?) {
	val names = friends.joinToString(", ") { it.name }.let { s -> s.substringBeforeLast(", ").let { if (friends.size > 1) "$it and ${friends.last().name}" else s } }
	layers.openDialog {
		val line = UIContainer().constrain { width = 500.at }
		Icon("cloud", 18, Tok.violet).constrain { y = 1.at } childOf line
		val t = Column(4).constrain { x = 28.at; width = 472.at } childOf line
		t.add(wrapped(tr("tandem.sync_this_world_and_friends_can_keep"), 13, Weight.REGULAR, Tok.sec, 472.tp, 1.5f))
		t.add(link(tr("tandem.sync", world)) { layers.closeDialog(); sync?.invoke() })
		line.constrain { height = basicHeightConstraint { t.getHeight() } }
		dialog(
			500,
			titled(tr("tandem.still_playing_2", names, if (friends.size > 1) "are" else "is"), tr("tandem.runs_on_your_pc_so_it_closes", world, if (friends.size > 1) "them" else friends[0].name), 500),
			*listOfNotNull(line.takeIf { sync != null }).toTypedArray(),
			actions(500,
				Button(tr("tandem.keep_playing"), Kind.OUTLINE, Size.NORMAL, null, Tok.panel) { layers.closeDialog() },
				Button(tr("tandem.close_for_everyone"), Kind.FILLED, Size.NORMAL, null, Tok.panel) { leave() },
			),
		)
	}
}

/** Quitting Minecraft while your session is still uploading: Quit when done, or Quit anyway (it finishes next time). */
fun Dialogs.quitting(quit: () -> Unit) {
	val (world, to, run) = Sample.background ?: return quit()
	layers.openDialog {
		val pct = Label(tr("tandem.uploaded", (run.value * 100).roundToInt()), 13, Weight.REGULAR, Tok.muted)
		lateinit var whenDone: Button
		whenDone = Button(if (Sample.quitWhenDone) tr("tandem.quitting_when_done") else tr("tandem.quit_when_done"), Kind.FILLED, Size.NORMAL, null, Tok.panel) {
			Sample.quitWhenDone = true; whenDone.text = tr("tandem.quitting_when_done"); whenDone.enabled = false
		}
		whenDone.enabled = !Sample.quitWhenDone
		val box = dialog(
			500,
			titled(
				if (to != null) tr("tandem.handing_off_to", world, to) else tr("tandem.saving_to_the_cloud", world),
				if (to != null) tr("tandem.takes_over_as_soon_as_this_is", to) else tr("tandem.your_friends_get_this_session_as_soon"), 500,
			),
			progress(false, 500, { run.value }, pct, Label(tr("tandem.about_5_s_left"), 13, Weight.REGULAR, Tok.muted)),
			wrapped(tr("tandem.if_you_quit_now_your_session_is", world), 13, Weight.REGULAR, Tok.muted, 500.tp, 1.5f),
			actions(500, Button(tr("tandem.quit_anyway"), Kind.OUTLINE, Size.NORMAL, null, Tok.panel) { quit() }, whenDone),
		)
		Ticker {
			pct.text = tr("tandem.uploaded", (run.value * 100).roundToInt())
			if (Sample.background == null) { layers.closeDialog(); if (Sample.quitWhenDone) quit() }
		} childOf box
		box
	}
}

/** Storage full at the end of a session: saved on this PC only for now; trim history, own storage, or stop syncing. */
fun Dialogs.full(w: World) {
	if (w.cloudId != null && RealStorage.on) return fullReal(w)
	layers.openDialog {
		dialog(
			500,
			who(mic("cloud", Tok.warn), tr("tandem.storage_is_full", if (w.mine) tr("tandem.your") else tr("tandem.s_possessive", w.owner)), tr("tandem.this_session_of_is_saved_on_your", w.name), 500),
			Column(8).also { c ->
				c.constrain { width = 500.at }
				c.add(actionRow(500, tr("tandem.trim_history"), tr("tandem.frees_0_6_gb_your_latest_save")) {
					layers.closeDialog(); Sample.storageFull = false
					w.state = WorldState.READY; w.who = "you"; w.ago = tr("tandem.just_now"); changed()
					layers.toast(tr("tandem.trimmed_0_6_gb_of_old_history"), tr("tandem.saving_now", w.name))
				})
				c.add(actionRow(500, tr("tandem.use_your_own_storage"), tr("tandem.your_own_cloudflare_storage_with_no_keplersync")) { ownStorage() })
				c.add(actionRow(500, tr("tandem.stop_syncing"), tr("tandem.it_becomes_a_normal_world_nothing_is")) { stopSyncing(w) })
			},
			actions(500, Button(tr("tandem.decide_later"), Kind.OUTLINE, Size.NORMAL, null, Tok.panel) { layers.closeDialog() }),
		)
	}
}

/** Storage full for real: the owner trims, moves it to their own storage or stops; anyone else tries again later. */
private fun Dialogs.fullReal(w: World) {
	layers.openDialog {
		val rows = Column(8).constrain { width = 500.at }
		if (w.mine) {
			rows.add(actionRow(500, tr("tandem.trim_history"), tr("tandem.every_world_you_sync_keeps_only_its")) { layers.closeDialog(); RealLeave.trim(layers, w) { changed() } })
			rows.add(actionRow(500, tr("tandem.use_your_own_storage"), tr("tandem.your_own_cloudflare_storage_with_no_keplersync_2")) { ownStorage(w) { changed() } })
			rows.add(actionRow(500, tr("tandem.stop_syncing"), tr("tandem.it_becomes_a_normal_world_nothing_is")) { stopSyncing(w) { changed() } })
		} else rows.add(actionRow(500, tr("tandem.try_again"), tr("tandem.once_has_made_room_this_session_goes", w.owner)) { layers.closeDialog(); RealLeave.upload(layers, w) { changed() } })
		dialog(
			500,
			who(mic("cloud", Tok.warn), tr("tandem.storage_is_full", if (w.mine) tr("tandem.your") else tr("tandem.s_possessive", w.owner)), tr("tandem.this_session_of_is_saved_on_your", w.name), 500),
			rows,
			actions(500, Button(tr("tandem.decide_later"), Kind.OUTLINE, Size.NORMAL, null, Tok.panel) { layers.closeDialog() }),
		)
	}
}

/** The host's game stopped: host from the last save (the others reconnect to you), or go back to the title screen. */
fun Dialogs.stopped(host: Person, world: String, others: List<String>, title: () -> Unit, takeOver: () -> Unit) {
	val rest = if (others.isEmpty()) "" else if (others.size == 1) tr("tandem.and_reconnects_to_you", others[0]) else tr("tandem.and_reconnect_to_you", Pages.names(others))
	layers.openDialog {
		dialog(
			500,
			who(Face({ host.skin }, 48, 10, null, Tok.panel), tr("tandem.s_game_stopped", host.name), tr("tandem.was_running_on_s_pc_you_can", world, host.name, rest), 500),
			safe("shield", tr("tandem.last_saved_to_the_cloud_22_min", host.name, host.name), 500),
			actions(500,
				Button(tr("tandem.back_to_title_screen"), Kind.OUTLINE, Size.NORMAL, null, Tok.panel) { layers.closeDialog(); title() },
				Button(tr("tandem.take_over_hosting"), Kind.FILLED, Size.NORMAL, null, Tok.panel) { takeOver() },
			),
		)
	}
}

/** Delete a synced world from this PC only: friends keep it, and Play gets the latest copy again. */
fun Dialogs.removeFromPc(w: World, removed: () -> Unit) {
	layers.openDialog {
		dialog(
			500,
			titled(tr("tandem.delete_from_this_pc", w.name), tr("tandem.its_a_synced_world_so_only_this"), 500),
			safe("shield", tr("tandem.your_sessions_are_already_saved_to_the"), 500),
			actions(500,
				Button(tr("tandem.cancel"), Kind.OUTLINE, Size.NORMAL, null, Tok.panel) { layers.closeDialog() },
				Button(tr("tandem.delete_from_this_pc_2"), Kind.FILLED, Size.NORMAL, null, Tok.panel) {
					layers.closeDialog()
					if (w.levelId != null && RealStorage.on) return@Button RealLeave.removeFromPc(layers, w, removed)
					removed()
					layers.toast(tr("tandem.is_off_this_pc", w.name), tr("tandem.your_friends_still_have_it_play_gets"))
				},
			),
		)
	}
}

/** Stop syncing: keep it as a normal world, a zip for a server, or keep syncing with your own storage. */
fun Dialogs.stopSyncing(w: World, stopped: () -> Unit = {}) {
	if (w.cloudId != null && RealStorage.on) return stopSyncingReal(w, stopped)
	var pick = 0
	layers.openDialog {
		lateinit var go: Button
		val labels = listOf(tr("tandem.stop_syncing"), tr("tandem.download_zip_and_stop"), tr("tandem.set_up_my_own_storage"))
		go = Button(labels[0], Kind.FILLED, Size.NORMAL, null, Tok.panel) {
			if (pick == 2) { ownStorage(); return@Button }
			layers.closeDialog()
			val was = Triple(w.state, w.owner, w.shared.toList())
			w.state = WorldState.LOCAL; w.owner = null; w.ago = tr("tandem.just_now")
			stopped(); changed()
			layers.toast(tr("tandem.is_a_normal_world_now", w.name), tr("tandem.the_cloud_copy_is_kept_for_30", if (pick == 1) tr("tandem.the_zip_is_in_your_downloads_folder") else ""), undo = {
				w.state = was.first; w.owner = was.second; w.shared.clear(); w.shared += was.third; changed()
			})
		}
		dialog(
			500,
			titled(tr("tandem.stop_syncing_2", w.name), tr("tandem.the_world_stays_yours_choose_where_it"), 500),
			radios(500, listOf(
				Opt(tr("tandem.keep_it_as_a_normal_world"), tr("tandem.the_latest_version_becomes_a_regular_singleplayer")),
				Opt(tr("tandem.move_it_to_a_server"), tr("tandem.download_a_zip_ready_for_a_dedicated")),
				Opt(tr("tandem.keep_syncing_with_your_own_storage"), tr("tandem.move_it_to_your_own_cloudflare_r2")),
			), 0) { pick = it; go.text = labels[it] },
			safe("shield", tr("tandem.the_cloud_copy_and_its_history_are"), 500),
			actions(500, Button(tr("tandem.cancel"), Kind.OUTLINE, Size.NORMAL, null, Tok.panel) { layers.closeDialog() }, go),
		)
	}
}

/** The real Stop syncing: the world is read-only on KeplerSync for 30 days; with someone hosting, it waits for them. */
private fun Dialogs.stopSyncingReal(w: World, stopped: () -> Unit) {
	var pick = 0
	val host = w.who.takeIf { w.state == WorldState.HOSTED || w.state == WorldState.SAVING }
	layers.openDialog {
		lateinit var go: Button
		fun label() = when {
			pick == 2 -> tr("tandem.use_my_own_storage")
			host != null -> tr("tandem.stop_after_s_session", host)
			pick == 1 -> tr("tandem.download_zip_and_stop")
			else -> tr("tandem.stop_syncing")
		}
		go = Button(label(), Kind.FILLED, Size.NORMAL, null, Tok.panel) {
			layers.closeDialog()
			if (pick != 2) return@Button RealLeave.stop(this, w, pick == 1, stopped)
			if (RealStorage.of { }?.own != null) RealStorage.move(listOf(w), "own", layers, stopped) else ownStorage(w, stopped)
		}
		val opts = mutableListOf(
			Opt(tr("tandem.keep_it_as_a_normal_world"), tr("tandem.the_latest_version_becomes_a_regular_singleplayer")),
			Opt(tr("tandem.move_it_to_a_server"), if (host != null) tr("tandem.once_s_session_ends_download_as_zip", host) else tr("tandem.download_a_zip_ready_for_a_dedicated")),
		)
		if (w.storage != "own") opts += Opt(tr("tandem.keep_syncing_with_your_own_storage"), tr("tandem.move_it_to_your_own_cloudflare_r2"))
		dialog(
			500,
			titled(tr("tandem.stop_syncing_2", w.name), if (host != null) tr("tandem.is_hosting_it_right_now_so_it", host) else tr("tandem.the_world_stays_yours_choose_where_it"), 500),
			radios(500, opts, 0) { pick = it; go.text = label() },
			safe("shield", tr("tandem.the_cloud_copy_and_its_history_are"), 500),
			actions(500, Button(tr("tandem.cancel"), Kind.OUTLINE, Size.NORMAL, null, Tok.panel) { layers.closeDialog() }, go),
		)
	}
}

/** Not enough disk space to get the latest copy. Nothing was changed. */
fun Dialogs.noSpace(w: World) {
	layers.openDialog {
		dialog(
			500,
			who(mic("disk", Tok.warn), tr("tandem.not_enough_space_to_get", w.name), tr("tandem.you_need_1_2_gb_free_on"), 500),
			actions(500,
				Button(tr("tandem.close"), Kind.OUTLINE, Size.NORMAL, null, Tok.panel) { layers.closeDialog() },
				Button(tr("tandem.try_again"), Kind.FILLED, Size.NORMAL, null, Tok.panel) { if (Sample.diskFull) noSpace(w) else getLatest(w) },
			),
		)
	}
}

/** A friend is hosting a synced world, and their game isn't open to friends (no KeplerSync, or it can't be reached). */
fun Dialogs.joinLater(w: World) {
	val skin = Sample.friends.firstOrNull { it.name == w.who }?.skin ?: yourSkin()
	layers.openDialog {
		dialog(
			500,
			who(Face({ skin }, 48, 10, null, Tok.panel), tr("tandem.is_hosting", w.who, w.name), tr("tandem.only_one_person_can_host_a_synced", w.who, w.name, w.who), 500),
			actions(500, Button(tr("tandem.ok"), Kind.FILLED, Size.NORMAL, null, Tok.panel) { layers.closeDialog() }),
		)
	}
}

/** Not enough space, with the real numbers from a dev cloud run. */
fun Dialogs.noSpace(w: World, needed: Long, free: Long, drive: String, retry: () -> Unit) {
	layers.openDialog {
		dialog(
			500,
			who(mic("disk", Tok.warn), tr("tandem.not_enough_space_to_get", w.name), tr("tandem.you_need_free_on_and_theres_nothing", bytesText(needed), drive, bytesText(free)), 500),
			actions(500,
				Button(tr("tandem.close"), Kind.OUTLINE, Size.NORMAL, null, Tok.panel) { layers.closeDialog() },
				Button(tr("tandem.try_again"), Kind.FILLED, Size.NORMAL, null, Tok.panel) { layers.closeDialog(); retry() },
			),
		)
	}
}

/** [w] is open in another game on this account, or that game is still saving it: playing here would split it. */
fun Dialogs.otherGame(w: World, saving: Boolean) {
	layers.openDialog {
		dialog(
			500,
			who(mic("pc", Tok.warn), tr(if (saving) "tandem.your_other_game_is_still_saving" else "tandem.is_open_in_your_other_game", w.name),
				tr(if (saving) "tandem.it_opens_here_once_its_saved" else "tandem.close_it_there_first", w.name), 500),
			actions(500, Button(tr("tandem.close"), Kind.FILLED, Size.NORMAL, null, Tok.panel) { layers.closeDialog() }),
		)
	}
}

/** Getting the latest copy for real: bytes, speed and time left from the transfer, closed by the caller when it's done. */
fun Dialogs.gettingLatest(w: World, t: WorldSync.Transfer, cancel: () -> Unit) {
	layers.openDialog {
		val got = Label(tr("tandem.checking_what_changed"), 13, Weight.REGULAR, Tok.muted)
		val rate = Label("", 13, Weight.REGULAR, Tok.muted)
		val box = dialog(
			500,
			titled(tr("tandem.getting_the_latest", w.name), tr("tandem.last_saved_by_only_the_parts_that", w.who, w.ago), 500),
			progress(false, 500, { t.progress.fraction() }, got, rate),
			safe("shield", tr("tandem.your_copy_on_this_pc_isnt_touched"), 500),
			actions(500, Button(tr("tandem.cancel"), Kind.OUTLINE, Size.NORMAL, null, Tok.panel) { layers.closeDialog(); cancel() }),
		)
		Ticker {
			val p = t.progress
			if (p.total() > 0) {
				got.text = tr("tandem.x_of_y", bytesText(p.done()), bytesText(p.total()))
				val left = p.secondsLeft()
				rate.text = tr("tandem.mb_per_s", "%.1f".format(p.bytesPerSecond() / (1 shl 20))) +
					if (left < 0) "" else tr("tandem.about_left", if (left < 60) tr("tandem.n_s", left) else tr("tandem.n_min", left / 60))
			}
		} childOf box
		box
	}
}

/** Play when ready, for real: the friend's upload progress comes from their lease; [done] closes it. */
fun Dialogs.waitingFor(w: World, value: () -> Float, done: () -> Boolean) {
	val skin = Sample.friends.firstOrNull { it.name == w.who }?.skin ?: yourSkin()
	layers.openDialog {
		val pct = Label(tr("tandem.uploaded", w.pct), 13, Weight.REGULAR, Tok.muted)
		val box = dialog(
			500,
			who(Face({ skin }, 48, 10, null, Tok.panel), tr("tandem.waiting_for_to_finish_saving", w.who), tr("tandem.s_last_session_of_is_uploading_the", w.who, w.name), 500),
			progress(true, 500, value, pct, Label("", 13, Weight.REGULAR, Tok.muted)),
			actions(500, Button(tr("tandem.stop_waiting"), Kind.OUTLINE, Size.NORMAL, null, Tok.panel) { layers.closeDialog() }),
		)
		Ticker { if (!done()) pct.text = tr("tandem.uploaded", (value() * 100).roundToInt()) } childOf box
		box
	}
}

/** The copy on this PC changed since Tandem last synced it, usually from Minecraft's own world list. */
fun Dialogs.changedOutside(w: World, files: Int, cancel: () -> Unit, discard: () -> Unit, upload: () -> Unit) {
	layers.openDialog {
		dialog(
			500,
			who(
				mic("warn", Tok.warn), tr("tandem.changed_outside_tandem", w.name),
				if (files == 1) tr("tandem.file_on_this_pc_changed_since_tandem") else tr("tandem.files_on_this_pc_changed_since_tandem", files), 500,
			),
			safe("shield", tr("tandem.if_someone_else_played_since_both_versions"), 500),
			actions(500,
				Button(tr("tandem.cancel"), Kind.OUTLINE, Size.NORMAL, null, Tok.panel) { layers.closeDialog(); cancel() },
				Button(tr("tandem.discard_and_get_the_latest"), Kind.OUTLINE, Size.NORMAL, null, Tok.panel) { layers.closeDialog(); discard() },
				Button(tr("tandem.upload_these_changes"), Kind.FILLED, Size.NORMAL, null, Tok.panel) { layers.closeDialog(); upload() },
			),
		)
	}
}

/** Waiting, when the friend quit before their upload finished: it finishes when they're back, 24 hours at most. */
fun Dialogs.waitQuit(w: World) {
	val friend = Sample.person(w.who)
	layers.openDialog {
		dialog(
			500,
			who(Face({ friend.skin }, 48, 10, null, Tok.panel), tr("tandem.is_still_saving", w.who, w.name), tr("tandem.quit_minecraft_before_the_upload_finished_it", w.who, w.who), 500),
			safe("clock", tr("tandem.if_it_hasnt_arrived_by_9_40", w.who, w.who), 500, Tok.muted),
			actions(500, Button(tr("tandem.close"), Kind.FILLED, Size.NORMAL, null, Tok.panel) { layers.closeDialog() }),
		)
	}
}

/** Opens a link the way Minecraft does, asking first. */
fun openLink(url: String) {
	val mc = Minecraft.getInstance()
	ConfirmLinkScreen.confirmLinkNow(mc.screen, url)
}

/**
 * Tandem's own screens: an error in one closes it with a notice, logged (so it goes with the logs), instead of
 * crashing the game. Subclasses' own overrides go through [safely] too.
 */
abstract class TandemScreen : WindowScreen(ElementaVersion.V8, drawDefaultBackground = true, restoreCurrentGuiOnClose = true) {
	private var failed = false

	protected fun safely(body: () -> Unit) {
		if (failed) return
		try {
			body()
		} catch (e: VirtualMachineError) {
			throw e
		} catch (t: Throwable) {
			failed = true
			app.keplersync.tandem.fabric.TandemClient.LOG.error("Tandem closed its screen after an error", t)
			Ingame.notice(tr("tandem.tandem_closed_after_a_problem"), tr("tandem.your_worlds_are_fine_send_logs_from"), "warn")
			// Once Minecraft is done with this frame or click, not in the middle of it.
			Minecraft.getInstance().tell { restorePreviousScreen() }
		}
	}

	override fun onDrawScreen(matrixStack: UMatrixStack, mouseX: Int, mouseY: Int, partialTicks: Float) =
		safely { super.onDrawScreen(matrixStack, mouseX, mouseY, partialTicks) }

	override fun onTick() = safely { super.onTick() }

	override fun onMouseReleased(mouseX: Double, mouseY: Double, state: Int) = safely { super.onMouseReleased(mouseX, mouseY, state) }

	override fun onMouseDragged(x: Double, y: Double, clickedButton: Int, timeSinceLastClick: Long) =
		safely { super.onMouseDragged(x, y, clickedButton, timeSinceLastClick) }

	override fun onMouseScrolled(mouseX: Double, mouseY: Double, deltaHorizontal: Double, deltaVertical: Double) =
		safely { super.onMouseScrolled(mouseX, mouseY, deltaHorizontal, deltaVertical) }

	override fun onKeyReleased(keyCode: Int, typedChar: Char, modifiers: UKeyboard.Modifiers?) =
		safely { super.onKeyReleased(keyCode, typedChar, modifiers) }
}

/**
 * A screen that's only a dialog over whatever is behind it (the game or the title screen), for the few Tandem
 * dialogs that appear outside its own screens: the host's game stopping, storage full, quitting mid-upload.
 */
class DialogScreen(open: (Dialogs) -> Unit) : TandemScreen() {
	private val layers = Layers()
	private val dialogs = Dialogs(layers) {}
	private var shown = false
	private val first = open

	override fun initScreen(width: Int, height: Int) = safely {
		super.initScreen(width, height)
		Scale.update()
		Focus.reset()
		window.clearChildren()
		layers.attach(window)
		layers.afterDialog = { restorePreviousScreen() }
		if (!shown) { shown = true; first(dialogs) }
	}

	override fun onKeyPressed(keyCode: Int, typedChar: Char, modifiers: UKeyboard.Modifiers?) = safely {
		// As on Tandem's own screen: Tab leaves a field, and Space or Enter in one types rather than pressing a button.
		val typing = window.focusedComponent is Field
		when {
			keyCode == UKeyboard.KEY_TAB -> { window.unfocus(); Focus.move(modifiers?.isShift == true) }
			keyCode == UKeyboard.KEY_ESCAPE -> if (!layers.escape()) super.onKeyPressed(keyCode, typedChar, modifiers)
			!typing && (keyCode == UKeyboard.KEY_ENTER || keyCode == UKeyboard.KEY_SPACE) -> if (!Focus.press()) super.onKeyPressed(keyCode, typedChar, modifiers)
			else -> super.onKeyPressed(keyCode, typedChar, modifiers)
		}
	}

	override fun onMouseClicked(mouseX: Double, mouseY: Double, mouseButton: Int) = safely {
		Focus.hide()
		super.onMouseClicked(mouseX, mouseY, mouseButton)
	}
}

/** Dev checks: buttons inside dialogs the checks press, by name. */
val dialogTargets = mutableMapOf<String, UIComponent>()

/** Picking one of a few short options: the picked one filled, the rest outlined (game mode, difficulty). */
fun segmented(options: List<String>, picked: Int, pick: (Int) -> Unit): UIComponent {
	val c = UIContainer().constrain { width = 100.percent; height = 34.at }
	var now = picked
	fun build() {
		c.clearChildren()
		val r = row(8, *options.mapIndexed { i, o ->
			Button(o, if (i == now) Kind.FILLED else Kind.OUTLINE, Size.SMALL, null, Tok.panel) { now = i; pick(i); build() }
		}.toTypedArray())
		r childOf c
	}
	build()
	return c
}

/**
 * Opening a Just play world to friends, as Open to LAN does: their game mode, the difficulty, and whether they get
 * commands. Asked once, before the first invite.
 */
fun Dialogs.openToFriends(world: String, mode: GameType, difficulty: Difficulty, open: (Hosting.Settings) -> Unit) {
	val modes = listOf(GameType.SURVIVAL, GameType.CREATIVE, GameType.ADVENTURE)
	val levels = listOf(Difficulty.PEACEFUL, Difficulty.EASY, Difficulty.NORMAL, Difficulty.HARD)
	var pickedMode = modes.indexOf(mode).coerceAtLeast(0)
	var pickedLevel = levels.indexOf(difficulty).coerceAtLeast(0)
	var cheats = false
	layers.openDialog {
		dialog(
			500,
			titled(tr("tandem.open_to_friends", world), tr("tandem.friends_you_invite_join_this_world_while"), 500),
			labeled(tr("tandem.friends_play_in"), segmented(listOf(tr("tandem.survival"), tr("tandem.creative"), tr("tandem.adventure")), pickedMode) { pickedMode = it }),
			labeled(tr("tandem.difficulty"), segmented(listOf(tr("tandem.peaceful"), tr("tandem.easy"), tr("tandem.normal"), tr("tandem.hard")), pickedLevel) { pickedLevel = it }),
			checkbox(tr("tandem.let_friends_use_commands_cheats"), false) { cheats = it },
			actions(
				500,
				Button(tr("tandem.cancel"), Kind.OUTLINE, Size.NORMAL, null, Tok.panel) { layers.closeDialog() },
				Button(tr("tandem.open_and_invite"), Kind.FILLED, Size.NORMAL, null, Tok.panel) {
					layers.closeDialog()
					open(Hosting.Settings(modes[pickedMode], cheats, levels[pickedLevel]))
				}.also { dialogTargets["open-to-friends"] = it },
			),
		)
	}
}

/** Joining a friend's game: what's happening right now, until Minecraft's own connect screen takes over. */
fun Dialogs.joining(host: String, world: String, status: () -> String, cancel: () -> Unit) {
	val skin = Sample.friends.firstOrNull { it.name == host }?.skin ?: yourSkin()
	layers.openDialog {
		dialog(
			500,
			who(Face({ skin }, 48, 10, null, Tok.panel), tr("tandem.joining", host), world, 500),
			Live(13, Weight.REGULAR, Tok.sec, status),
			actions(500, Button(tr("tandem.cancel"), Kind.OUTLINE, Size.NORMAL, null, Tok.panel) { cancel(); layers.closeDialog() }),
		)
	}
}

/** [from] handed [world] to this player: their upload first, then the latest copy, then it opens here. */
fun Dialogs.takingOver(from: String, world: String, status: () -> String, value: () -> Float, cancel: () -> Unit) {
	val skin = Sample.friends.firstOrNull { it.name == from }?.skin ?: yourSkin()
	layers.openDialog {
		val line = Label(status(), 13, Weight.REGULAR, Tok.muted)
		val box = dialog(
			500,
			who(Face({ skin }, 48, 10, null, Tok.panel), tr("tandem.handed_to_you", from, world), tr("tandem.youll_host_it_it_opens_here_once", from), 500),
			progress(false, 500, value, line, Label("", 13, Weight.REGULAR, Tok.muted)),
			actions(500, Button(tr("tandem.dont_host"), Kind.OUTLINE, Size.NORMAL, null, Tok.panel) { cancel(); layers.closeDialog() }),
		)
		Ticker { line.text = status() } childOf box
		box
	}
}

/** [from] handed [world] to [to]: this player joins [to] as soon as they're hosting. */
fun Dialogs.following(from: String, to: String, world: String, status: () -> String, cancel: () -> Unit) {
	val skin = Sample.friends.firstOrNull { it.name == to }?.skin ?: yourSkin()
	layers.openDialog {
		dialog(
			500,
			who(Face({ skin }, 48, 10, null, Tok.panel), tr("tandem.is_taking_over", to, world), tr("tandem.handed_the_world_to_youll_join_by", from, to, to), 500),
			Live(13, Weight.REGULAR, Tok.sec, status),
			actions(500, Button(tr("tandem.stop_waiting"), Kind.OUTLINE, Size.NORMAL, null, Tok.panel) { cancel(); layers.closeDialog() }),
		)
	}
}

/** Joining didn't work: why, in place of the joining dialog. */
fun Dialogs.couldntJoin(host: String, why: String) {
	layers.openDialog {
		dialog(
			500,
			who(mic("warn", Tok.warn), tr("tandem.couldnt_join", host), why, 500),
			actions(500, Button(tr("tandem.ok"), Kind.FILLED, Size.NORMAL, null, Tok.panel) { layers.closeDialog() }),
		)
	}
}

/** A synced world that won't load: the version before it, [before], restored on top so it can be undone. */
fun Dialogs.wontLoad(w: World, before: String?, cancel: () -> Unit, open: () -> Unit) {
	layers.openDialog {
		val sub = tr("tandem.it_may_have_been_damaged_when_a") + if (before != null)
			tr("tandem.open_the_previous_version_tandem_makes_it", before)
		else tr("tandem.theres_no_earlier_version_in_its_history")
		val buttons = mutableListOf<UIComponent>(Button(if (before != null) tr("tandem.not_now") else "OK", Kind.OUTLINE, Size.NORMAL, null, Tok.panel) { layers.closeDialog(); cancel() })
		if (before != null) buttons += Button(tr("tandem.open_the_previous_version"), Kind.FILLED, Size.NORMAL, null, Tok.panel) { layers.closeDialog(); open() }
			.also { dialogTargets["previous-version"] = it }
		dialog(500, who(mic("warn", Tok.warn), tr("tandem.wont_load", w.name), sub, 500), actions(500, *buttons.toTypedArray()))
	}
}
