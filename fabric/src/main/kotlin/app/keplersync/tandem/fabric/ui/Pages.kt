package app.keplersync.tandem.fabric.ui

import app.keplersync.tandem.fabric.TandemClient
import app.keplersync.tandem.fabric.account.Account as KsAccount
import gg.essential.elementa.UIComponent
import gg.essential.elementa.components.UIContainer
import gg.essential.elementa.constraints.CenterConstraint
import gg.essential.elementa.constraints.SiblingConstraint
import gg.essential.elementa.dsl.basicHeightConstraint
import gg.essential.elementa.dsl.basicWidthConstraint
import gg.essential.elementa.dsl.childOf
import gg.essential.elementa.dsl.constrain
import gg.essential.elementa.dsl.minus
import gg.essential.elementa.dsl.percent
import gg.essential.universal.UMatrixStack
import app.keplersync.tandem.fabric.sync.WorldSync
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.SharedConstants
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen
import java.awt.Color
import kotlin.math.max

/**
 * Tandem's Storage and Settings tabs and a world's details. Each fills a scrolling column [W] Tandem px wide.
 */
class Pages(private val dialogs: Dialogs, private val rebuild: () -> Unit, private val toWorlds: () -> Unit) {
	private val layers get() = dialogs.layers

	/** A 24 px title and its muted line. */
	private fun head(title: String, sub: String): UIComponent {
		val c = Column(0).constrain { width = W.at }
		c.add(Label(title, 24, Weight.BOLD))
		c.add(wrapped(sub, 14, Weight.REGULAR, Tok.muted, W.tp, 1.5f), 4)
		return c
	}

	/** A .card: raised, bordered, 18 px in (or [padY] above and below, for a list of .setrows). */
	private fun card(gap: Int = 14, padY: Int = 18, padX: Int = 18, vararg parts: UIComponent): UIComponent {
		val b = box(14, Tok.raise, Tok.line).constrain { width = W.at }
		val col = Column(gap).constrain { x = (padX + 1).at; y = (padY + 1).at; width = (W - 2 * padX - 2).at } childOf b
		for (p in parts) col.add(p)
		b.constrain { height = basicHeightConstraint { col.getHeight() + (2 * padY + 2).tp } }
		return b
	}

	/** A 17 px section title. */
	private fun section(title: String, vararg parts: UIComponent): UIComponent {
		val c = Column(12).constrain { width = W.at }
		c.add(Label(title, 17, Weight.BOLD))
		for (p in parts) c.add(p)
		return c
	}

	/** A display name typed in Settings and not saved yet. */
	private var draftName: String? = null

	/** A dashed .alt box: a bold line, a muted line, and a button at the right. */
	private fun alt(title: String, line: String, button: UIComponent): UIComponent {
		val b = Dashed(12, Tok.outline).constrain { width = W.at }
		val col = Column(2).constrain { x = 17.at; y = CenterConstraint(); width = (W - 230).at } childOf b
		col.add(Label(title, 14, Weight.SEMIBOLD))
		col.add(wrapped(line, 13, Weight.REGULAR, Tok.muted, (W - 230).tp, 1.45f))
		button.constrain { x = 17.fromEnd; y = CenterConstraint() } childOf b
		b.constrain { height = basicHeightConstraint { max(col.getHeight(), button.getHeight()) + 30.tp } }
		return b
	}

	private fun foot(text: String) = wrapped(text, 13, Weight.REGULAR, Tok.muted, W.tp, 1.5f)

	private fun fine(text: String) = wrapped(text, 12.5, Weight.REGULAR, Tok.muted, W.tp, 1.5f)

	/** A round pill: the plan's name, Linked, Synced it, Current. */
	private fun pill(text: String, color: Color, bg: Color): UIComponent {
		val t = box(11, bg)
		val l = Label(text, 13, Weight.REGULAR, color) childOf t
		l.constrain { x = 10.at; y = CenterConstraint() }
		t.constrain { width = basicWidthConstraint { l.getWidth() + 20.tp }; height = 22.at }
		return t
	}

	fun storage(into: UIComponent) {
		if (RealStorage.on || devStorage != null) return realStorage(into)
		if (!FabricLoader.getInstance().isDevelopmentEnvironment) {
			val col = Column(24).constrain { x = 24.at; y = 22.at; width = W.at } childOf into
			col.add(head(tr("tandem.storage"), tr("tandem.space_for_the_worlds_you_sync_and")))
			col.add(foot(tr("tandem.storage_shows_up_when_keplersync_is_reachable")))
			return
		}
		val col = Column(24).constrain { x = 24.at; y = 22.at; width = W.at } childOf into
		col.add(head(tr("tandem.storage"), if (Storage.ownR2) tr("tandem.where_the_worlds_you_sync_are_kept") else tr("tandem.space_in_the_cloud_for_the_worlds")))
		if (Storage.ownR2) {
			val row = UIContainer().constrain { width = (W - 38).at; height = 48.at }
			mic("cloud", Tok.violet) childOf row
			val t = Column(3).constrain { x = 64.at; y = CenterConstraint(); width = (W - 400).at } childOf row
			t.add(Label(tr("tandem.your_own_cloudflare_r2"), 17, Weight.SEMIBOLD))
			t.add(Label(tr("tandem.tandem_worlds_no_keplersync_limit_you_pay"), 13, Weight.REGULAR, Tok.muted))
			Button(tr("tandem.switch_back_to_keplersync_storage"), Kind.OUTLINE, Size.SMALL, null, Tok.raise) { Storage.ownR2 = false; rebuild() }
				.constrain { x = 0.fromEnd; y = CenterConstraint() } childOf row
			col.add(card(parts = arrayOf(row)))
			col.add(foot(tr("tandem.switching_back_moves_your_worlds_over_in")))
		} else {
			val used = Storage.usedGb()
			val top = UIContainer().constrain { width = (W - 38).at; height = 40.at }
			row(8, Label("$used GB", 28, Weight.BOLD), Label(tr("tandem.of_gb", Storage.gb), 14, Weight.REGULAR, Tok.muted), pill(Storage.plan, Tok.violetHi, Tok.over(Tok.raise, Tok.violet, 0.14)))
				.constrain { y = CenterConstraint() } childOf top
			row(10,
				Button(tr("tandem.manage_plan"), Kind.OUTLINE, Size.NORMAL, null, Tok.raise) { openLink("https://keplersync.app") },
				Button(tr("tandem.get_more_space"), Kind.FILLED, Size.NORMAL, null, Tok.raise) { dialogs.plans() },
			).constrain { x = 0.fromEnd; y = CenterConstraint() } childOf top
			val worlds = Storage.worldsGb()
			val parts = listOfNotNull(
				worlds to Tok.violet, Storage.HISTORY_GB to Color(0x6b5aa8),
				if (Sample.usesAtlas) Storage.ATLAS_GB to Color(0x8ad9eb) else null,
			)
			val bar = Bar(parts.map { (gb, c) -> (gb / Storage.gb).toFloat() to c }).constrain { width = 100.percent; height = 10.at }
			val list = Column(0).constrain { width = 100.percent }
			for (w in Sample.worlds.filter { it.mine && it.state != WorldState.LOCAL }) {
				list.add(useRow(Thumb(w.bands, 40, 26, 5, false, Tok.raise, w.icon), w.name, null, w.size))
			}
			list.add(useRow(swatch(Color(0x6b5aa8)), tr("tandem.history"), tr("tandem.older_saves_of_these_worlds_for_restoring"), "%.1f GB".format(Storage.HISTORY_GB)))
			if (Sample.usesAtlas) list.add(useRow(swatch(Color(0x8ad9eb)), tr("tandem.atlas_files"), tr("tandem.configs_and_mods_you_shared_in_atlas"), "${(Storage.ATLAS_GB * 1000).toInt()} MB"))
			col.add(card(12, parts = arrayOf(top, bar, list)))
			col.add(alt(tr("tandem.already_use_cloudflare"), tr("tandem.you_can_keep_your_worlds_in_your"),
				Button(tr("tandem.set_up_r2"), Kind.OUTLINE, Size.SMALL, null, Tok.panel) { dialogs.ownStorage { rebuild() } }))
			col.add(foot(tr("tandem.if_you_run_out_of_space_tandem")))
			col.add(fine(tr("tandem.keplersync_storage_is_sold_by_keplersync_support", Storage.DISCLAIMER)))
		}
		UIContainer().constrain { y = SiblingConstraint(); height = 22.at } childOf into
	}

	/** The Storage tab from KeplerSync: space used, each world you own with its size, and your own R2 if it's set up. */
	private fun realStorage(into: UIComponent) {
		val col = Column(24).constrain { x = 24.at; y = 22.at; width = W.at } childOf into
		col.add(head(tr("tandem.storage"), tr("tandem.space_for_the_worlds_you_sync_and")))
		val o = devStorage ?: RealStorage.of { rebuild() }
		if (o == null) col.add(Label(tr("tandem.loading"), 14, Weight.REGULAR, Tok.muted))
		else {
			val top = UIContainer().constrain { width = (W - 38).at; height = 40.at }
			row(8, Label(bytesText(o.usedBytes), 28, Weight.BOLD), Label(tr("tandem.of_on_keplersync", bytesText(o.totalBytes)), 14, Weight.REGULAR, Tok.muted))
				.constrain { y = CenterConstraint() } childOf top
			val bar = Bar(listOf((o.usedBytes.toFloat() / o.totalBytes.coerceAtLeast(1)).coerceIn(0f, 1f) to Tok.violet)).constrain { width = 100.percent; height = 10.at }
			val list = Column(0).constrain { width = 100.percent }
			for (s in o.worlds) {
				val here = Sample.worlds.firstOrNull { it.cloudId == s.id }
				val pic = Thumb(here?.bands ?: RealWorlds.bands(s.id), 40, 26, 5, s.deletedAt != null, Tok.raise, here?.icon)
				val gone = s.deletedAt?.let { java.time.Instant.parse(it).toEpochMilli() }
				if (gone != null) {
					val days = ((gone - System.currentTimeMillis() + 86_399_999) / 86_400_000).coerceAtLeast(1)
					val delete = Button(tr("tandem.delete_now"), Kind.OUTLINE, Size.SMALL, null, Tok.raise) { dialogs.deleteNow(s.id, s.name) { rebuild() } }
					list.add(useRow(pic, s.name, if (days == 1L) tr("tandem.stopped_deleted_tomorrow") else tr("tandem.stopped_deleted_in_days", days), bytesText(s.bytes), delete))
				} else {
					list.add(useRow(pic, s.name, if (s.storage == "own") tr("tandem.in_your_own_r2_so_it_isnt") else tr("tandem.history_included"), bytesText(s.bytes)))
				}
			}
			if (o.worlds.isEmpty()) list.add(Label(tr("tandem.no_synced_worlds_of_yours_yet"), 13, Weight.REGULAR, Tok.muted))
			col.add(card(12, parts = arrayOf(top, bar, list)))
			val own = o.own
			if (own == null) col.add(alt(tr("tandem.already_use_cloudflare"), tr("tandem.you_can_keep_your_worlds_in_your"),
				Button(tr("tandem.set_up_r2"), Kind.OUTLINE, Size.SMALL, null, Tok.panel) { dialogs.ownStorage { rebuild() } }))
			else {
				val n = o.worlds.count { it.storage == "own" }
				val r = UIContainer().constrain { width = (W - 38).at; height = 48.at }
				mic("cloud", if (own.problem != null) Tok.warn else Tok.violet) childOf r
				val t = Column(3).constrain { x = 64.at; y = CenterConstraint(); width = (W - 400).at } childOf r
				t.add(Label(tr("tandem.your_own_cloudflare_r2"), 17, Weight.SEMIBOLD))
				t.add(Label(tr("tandem.key_secret_saved", own.bucket, own.accessKeyId, if (n == 1) tr("tandem.1_world") else tr("tandem.n_worlds", n)), 13, Weight.REGULAR, Tok.muted))
				row(8,
					Button(tr("tandem.test_again"), Kind.OUTLINE, Size.SMALL, null, Tok.raise) { RealStorage.test(layers) { rebuild() } },
					Button(tr("tandem.remove_keys"), Kind.OUTLINE, Size.SMALL, null, Tok.raise) { dialogs.removeKeys(own.bucket, n) { rebuild() } },
				).constrain { x = 0.fromEnd; y = CenterConstraint() } childOf r
				val parts = mutableListOf<UIComponent>(r)
				if (own.problem != null) parts += safe("warn", tr("tandem.it_needs_fixing_sessions_stay_on_players", own.problem.trimEnd('.')), W - 38, Tok.warn)
				col.add(card(parts = parts.toTypedArray()))
				col.add(foot(tr("tandem.move_a_world_in_or_out_from")))
			}
			col.add(foot(tr("tandem.if_you_run_out_of_space_tandem_2")))
		}
		col.add(fine(tr("tandem.keplersync_storage_is_sold_by_keplersync_support", Storage.DISCLAIMER)))
		UIContainer().constrain { y = SiblingConstraint(); height = 22.at } childOf into
	}

	/** A real synced world's Settings: where it's stored (and moving it), storage that needs fixing, stopping or leaving. */
	private fun realSettings(col: Column, w: World, hosted: Boolean) {
		val width = W - 38
		fun muted(t: String) = listOf(Run(t, Weight.REGULAR, Tok.muted))
		val whose = if (w.mine) tr("tandem.your") else tr("tandem.s_possessive", w.owner)
		val where = when {
			w.movingTo == "own" -> tr("tandem.moving_to_own_cloudflare_r2_friends_can", if (w.mine) tr("tandem.your_lower") else whose, w.movePct)
			w.movingTo != null -> tr("tandem.moving_back_to_keplersync_storage_friends_can", w.movePct)
			w.storage == "own" -> tr("tandem.own_cloudflare_r2", whose)
			else -> tr("tandem.keplersync_storage", whose)
		}
		val move = when {
			!w.mine || w.movingTo != null -> null
			w.storage == "own" -> Button(tr("tandem.move_back"), Kind.OUTLINE, Size.SMALL, null, Tok.raise) { RealStorage.move(listOf(w), "keplersync", layers) { rebuild() } }
			else -> Button(tr("tandem.move_to_your_r2"), Kind.OUTLINE, Size.SMALL, null, Tok.raise) {
				if (RealStorage.of { }?.own != null) RealStorage.move(listOf(w), "own", layers) { rebuild() } else dialogs.ownStorage(w) { rebuild() }
			}
		}
		val rows = mutableListOf(setRow(tr("tandem.stored_in"), muted(where), width, *listOfNotNull(move).toTypedArray()))
		w.storageProblem?.let { p ->
			rows += if (w.mine) setRow(tr("tandem.your_storage_needs_fixing"), muted(tr("tandem.sessions_stay_on_players_pcs_until_it", p.trimEnd('.'))), width,
				Button(tr("tandem.test_again"), Kind.OUTLINE, Size.SMALL, null, Tok.raise) { RealStorage.test(layers) { rebuild() } })
			else setRow(tr("tandem.s_storage_needs_fixing", w.owner), muted(tr("tandem.sessions_stay_on_your_pc_until_fixes", w.owner)), width)
		}
		if (w.mine) rows += if (w.stopWhenFree) setRow(tr("tandem.stops_after_s_session", w.who), muted(tr("tandem.everyones_copy_becomes_a_normal_world_then")), width,
			Button(tr("tandem.keep_syncing"), Kind.OUTLINE, Size.SMALL, null, Tok.raise) { RealLeave.keepSyncing(layers, w) { rebuild() } }, last = true)
		else setRow(tr("tandem.stop_syncing"), muted(if (hosted) tr("tandem.is_hosting_right_now_so_it_stops", w.who) else tr("tandem.it_becomes_a_normal_world_nothing_is_2")), width,
			Button(tr("tandem.stop_syncing"), Kind.OUTLINE, Size.SMALL, null, Tok.raise) { dialogs.stopSyncing(w) { toWorlds() } }, last = true)
		else {
			if (w.levelId != null) rows += setRow(tr("tandem.remove_from_this_pc"), muted(tr("tandem.frees_you_stay_in_it_and_play", w.size)), width,
				Button(tr("tandem.remove"), Kind.OUTLINE, Size.SMALL, null, Tok.raise) { dialogs.removeFromPc(w) { toWorlds() } })
			rows += setRow(tr("tandem.leave_3", w.name), muted(tr("tandem.only_can_stop_syncing_it_leaving_keeps", w.owner)), width,
				Button(tr("tandem.leave_world"), Kind.OUTLINE, Size.SMALL, null, Tok.raise) { dialogs.leaveWorld(w) { toWorlds() } }, last = true)
		}
		col.add(card(0, 4, parts = rows.toTypedArray()), 4)
	}

	private fun swatch(c: Color) = box(5, c).constrain { width = 40.at; height = 26.at }

	/** A .urow: picture, name (and a small line), size at the right, a rule above. */
	private fun useRow(pic: UIComponent, name: String, small: String?, size: String, button: UIComponent? = null): UIComponent {
		val r = UIContainer().constrain { width = 100.percent; height = (if (small != null) 54 else 46).at }
		Rule(Tok.line) childOf r
		pic.constrain { y = CenterConstraint() } childOf r
		val t = Column(1).constrain { x = 52.at; y = CenterConstraint(); width = 600.at } childOf r
		t.add(Label(name, 14).fitWithin(t))
		if (small != null) t.add(Label(small, 12.5, Weight.REGULAR, Tok.muted).fitWithin(t))
		val sizeLabel = Label(size, 14, Weight.REGULAR, Tok.sec)
		(if (button != null) row(16, button, sizeLabel) else sizeLabel).constrain { x = 0.fromEnd; y = CenterConstraint() } childOf r
		return r
	}

	/** Coloured parts of the plan's space, on the track colour. */
	private class Bar(private val parts: List<Pair<Float, Color>>) : UIComponent() {
		override fun draw(matrixStack: UMatrixStack) {
			beforeDraw(matrixStack)
			val l = getLeft(); val t = getTop(); val r = getRight(); val b = getBottom()
			rounded(matrixStack, l, t, r, b, 5.tp, Tok.track)
			var x = l
			for ((f, c) in parts) {
				val w = (r - l) * f
				rounded(matrixStack, x, t, x + w, b, if (x == l) 5.tp else 0f, c)
				x += w
			}
			super.draw(matrixStack)
		}
	}

	fun settings(into: UIComponent) {
		val col = Column(24).constrain { x = 24.at; y = 22.at; width = W.at } childOf into
		col.add(head(tr("tandem.settings"), tr("tandem.your_keplersync_account_works_the_same_here")))

		// As in Atlas: the display name to change, the Minecraft name beside it to read, Save at the end.
		val me = UIContainer().constrain { width = (W - 38).at; height = 92.at }
		Face({ yourSkin() }, 48, 10, null, Tok.raise).constrain { y = 22.at } childOf me
		// Signed in, the name is KeplerSync's: Save sends it, as in Atlas. What's typed outlives a rebuild (a friend coming online).
		val real = RealFriends.signedIn
		if (draftName?.trim() == Account.name) draftName = null
		fun savable(n: String) = n.trim().let { it.isNotEmpty() && it.length <= 32 && it != Account.name }
		var save: Button? = null
		val colW = (W - 38 - 64 - 16 - if (real) 100 else 0) / 2
		val name = labeled(tr("tandem.display_name"), Field(Account.minecraftName, null, 40, draftName ?: Account.name) {
			if (real) { draftName = it; save?.enabled = savable(it) }
			else Account.name = it.ifBlank { Account.minecraftName }
		}) as Column
		name.add(wrapped(tr("tandem.what_friends_see_change_it_any_time"), 13, Weight.REGULAR, Tok.muted, colW.tp, 1.45f))
		name.constrain { x = 64.at; width = colW.at } childOf me
		// Read, not typed: no field, a quieter box.
		val handleBox = box(9, Tok.raise, Tok.line).constrain { width = 100.percent; height = 40.at }
		Label("@" + Account.handle, 14, Weight.REGULAR, Tok.sec).constrain { x = 13.at; y = CenterConstraint() } childOf handleBox
		val handle = labeled(tr("tandem.minecraft_name"), handleBox) as Column
		handle.add(wrapped(tr("tandem.friends_add_you_with_this_rename"), 13, Weight.REGULAR, Tok.muted, colW.tp, 1.45f))
		handle.constrain { x = (64 + colW + 16).at; width = colW.at } childOf me
		if (real) save = Button(tr("tandem.save"), Kind.OUTLINE, Size.NORMAL, null, Tok.raise) { draftName?.let { RealFriends.rename(layers, rebuild, it.trim()) } }
			.also { it.enabled = draftName?.let(::savable) == true; it.constrain { x = 0.fromEnd; y = 22.at } childOf me }
		val w = W - 38
		col.add(section(tr("tandem.profile"), card(16, parts = arrayOf(me))))

		val link = setRow(tr("tandem.your_friend_link"), listOf(Run(tr("tandem.anyone_with_it_can_send_you_a"), Weight.REGULAR, Tok.muted)), w,
			Button(tr("tandem.copy"), Kind.OUTLINE, Size.SMALL, null, Tok.raise) { dialogs.copyLink() },
			Button(tr("tandem.get_a_new_link"), Kind.OUTLINE, Size.SMALL, null, Tok.raise) { dialogs.newCode() })
		val blocked = Column(8).constrain { width = w.at }
		blocked.add(Label(tr("tandem.blocked"), 14, Weight.SEMIBOLD), 14)
		val people = if (real) RealFriends.blocked { rebuild() } else Sample.blocked
		val about = when {
			people != null && people.isNotEmpty() -> tr("tandem.they_cant_see_you_invite_you_or")
			people != null -> tr("tandem.nobody_block_someone_from_their_menu_in")
			else -> RealFriends.blockedProblem?.let { tr("tandem.couldnt_load_who_you_blocked", it) } ?: tr("tandem.loading")
		}
		blocked.add(wrapped(about, 13, Weight.REGULAR, Tok.muted, w.tp, 1.45f), -5)
		for (p in people.orEmpty().toList()) {
			val r = UIContainer().constrain { width = 100.percent; height = 44.at }
			val name = Label(p.name, 15, Weight.SEMIBOLD).also { it.room = { (w - 160).tp } }
			val at = p.handle?.let { h -> Label("@$h", 13, Weight.REGULAR, Tok.muted).also { it.room = { (w - 160).tp - name.getWidth() - 6.tp } } }
			row(12, Face({ p.skin }, 28, 6, null, Tok.raise), if (at != null) row(6, name, at) else name).constrain { y = CenterConstraint() } childOf r
			Button(tr("tandem.unblock"), Kind.OUTLINE, Size.SMALL, null, Tok.raise) {
				if (p.userId != null) return@Button RealFriends.unblock(layers, rebuild, p)
				Sample.blocked.remove(p); rebuild()
				layers.toast(tr("tandem.unblocked", p.name), tr("tandem.they_arent_added_back_as_a_friend"))
			}.constrain { x = 0.fromEnd; y = CenterConstraint() } childOf r
			blocked.add(r)
		}
		blocked.add(UIContainer().constrain { height = 6.at })
		col.add(section(tr("tandem.friends_and_privacy"), card(0, 4, parts = arrayOf(link, blocked))))

		val keys = setRow(tr("tandem.keys"), listOf(Run(tr("tandem.join_from_an_invite_open_tandem_change", TandemClient.JOIN.translatedKeyMessage.string, TandemClient.OPEN.translatedKeyMessage.string), Weight.REGULAR, Tok.muted)), w,
			Button(tr("tandem.open_controls"), Kind.OUTLINE, Size.SMALL, null, Tok.raise) {
				val mc = Minecraft.getInstance(); mc.setScreen(KeyBindsScreen(mc.screen, mc.options))
			}, last = true)
		col.add(section(tr("tandem.tandem_on_this_pc"), card(0, 4, parts = arrayOf(
			setRow(tr("tandem.tell_me_when_a_friend_comes_online"), listOf(Run(tr("tandem.a_small_message_in_the_corner_also"), Weight.REGULAR, Tok.muted)), w, Toggle(Account.friendsOnline) { Account.friendsOnline = it }),
			setRow(tr("tandem.sound_for_invites"), listOf(Run(tr("tandem.a_short_quiet_sound_when_a_friend"), Weight.REGULAR, Tok.muted)), w, Toggle(Account.inviteSound) { Account.inviteSound = it }),
			setRow(tr("tandem.show_tandem_on_the_title_screen"), listOf(Run(tr("tandem.when_its_off_opens_tandem", TandemClient.OPEN.translatedKeyMessage.string), Weight.REGULAR, Tok.muted)), w, Toggle(Account.titleButton) { Account.titleButton = it }),
			setRow(tr("tandem.send_logs_by_itself_when_something_goes"), listOf(Run(tr("tandem.tandems_own_messages_and_the_games_errors"), Weight.REGULAR, Tok.muted)), w, Toggle(Account.autoLogs) { Account.autoLogs = it }),
			keys,
		))))
		val version = FabricLoader.getInstance().getModContainer("tandem").map { it.metadata.version.friendlyString }.orElse("dev")
		val details = tr("tandem.tandem_fabric", version, SharedConstants.getCurrentVersion().name)
		col.add(section(tr("tandem.about"), card(0, 4, parts = arrayOf(
			setRow(details.replace(", ", " for "), listOf(Run(tr("tandem.made_by_keplersync_removing_tandem_leaves_every"), Weight.REGULAR, Tok.muted)), w,
				if (RealFriends.signedIn) Button(tr("tandem.send_logs"), Kind.OUTLINE, Size.SMALL, null, Tok.raise) { dialogs.sendLogs() }
				else Button(tr("tandem.copy_details_for_support"), Kind.OUTLINE, Size.SMALL, null, Tok.raise) {
					Minecraft.getInstance().keyboardHandler.clipboard = details
					layers.toast(tr("tandem.copied_2"), tr("tandem.no_personal_details", details))
				}),
			setRow(tr("tandem.website_and_help"), listOf(Run(tr("tandem.keplersync_app_support_keplersync_app"), Weight.REGULAR, Tok.muted)), w,
				Button(tr("tandem.open_website"), Kind.OUTLINE, Size.SMALL, null, Tok.raise) { openLink("https://keplersync.app") },
				Button(tr("tandem.copy_email"), Kind.OUTLINE, Size.SMALL, null, Tok.raise) {
					Minecraft.getInstance().keyboardHandler.clipboard = "support@keplersync.app"
					layers.toast(tr("tandem.copied_support_keplersync_app"))
				}),
			setRow(tr("tandem.open_source_licences"), listOf(Run(tr("tandem.tandem_is_gpl_3_0_it_includes"), Weight.REGULAR, Tok.muted)), w,
				Button(tr("tandem.read_gpl_3_0"), Kind.OUTLINE, Size.SMALL, null, Tok.raise) { openLink("https://www.gnu.org/licenses/gpl-3.0.html") }, last = true),
		))))
		col.add(fine(tr("tandem.help_support_keplersync_app", Storage.DISCLAIMER)))
		UIContainer().constrain { y = SiblingConstraint(); height = 22.at } childOf into
	}

	/** A world's details: Overview, History, Shared with, Settings. */
	fun details(into: UIComponent, w: World, tab: String, select: (String) -> Unit, act: (World) -> Unit) {
		val col = Column(10).constrain { x = 24.at; y = 22.at; width = W.at } childOf into
		val back = row(4, Icon("back", 14, Tok.muted), Label(tr("tandem.worlds"), 13, Weight.MEDIUM, Tok.muted))
		back.onMouseEnter { back.children.forEach { (it as? Label)?.tint = Tok.ink; (it as? Icon)?.tint = Tok.ink } }
		back.onMouseLeave { back.children.forEach { (it as? Label)?.tint = Tok.muted; (it as? Icon)?.tint = Tok.muted } }
		back.onMouseClick { it.stopPropagation(); toWorlds() }
		Focus.add(back, 4.tp, toWorlds)
		col.add(back)
		val h = Column(0).constrain { width = W.at }
		h.add(Label(w.name, 24, Weight.BOLD).fitWithin(h))
		// Who it's shared with is on its own tab.
		h.add(Label(w.size.ifEmpty { " " }, 14, Weight.REGULAR, Tok.muted, 1.5f), 4)
		col.add(h)

		val bar = UIContainer().constrain { width = W.at; height = 39.at }
		for ((id, label) in listOf("over" to tr("tandem.overview"), "hist" to tr("tandem.history"), "mem" to tr("tandem.shared_with"), "set" to tr("tandem.settings"))) {
			val on = id == tab
			val b = UnderlineTab(label, on).constrain { x = SiblingConstraint(4.tp) } childOf bar
			b.onMouseClick { it.stopPropagation(); select(id) }
			Focus.add(b, 4.tp) { select(id) }
		}
		// After the tabs, so it isn't the sibling the first tab lines up after.
		Rule(Tok.line).constrain { x = 0.at; y = 0.fromEnd } childOf bar
		col.add(bar, 4)

		val hosted = w.state == WorldState.HOSTED
		when (tab) {
			"over" -> {
				val c = UIContainer().constrain { width = (W - 38).at; height = 96.at }
				Thumb(w.bands, 150, 96, 10, false, Tok.raise, w.icon) childOf c
				val t = Column(0).constrain { x = 168.at; y = CenterConstraint(); width = (W - 38 - 168).at } childOf c
				t.add(Label(w.name, 17, Weight.SEMIBOLD).fitWithin(t))
				t.add(statusLine(w, t), 3)
				val (label, kind) = actionFor(w)
				t.add(Button(label, kind, Size.NORMAL, null, Tok.raise) { act(w) }, 14)
				col.add(card(parts = arrayOf(c)), 4)
				w.storageProblem?.let { p -> col.add(safe("warn", if (w.mine) tr("tandem.your_storage_needs_fixing_see_settings", p.trimEnd('.')) else tr("tandem.s_storage_needs_fixing_so_sessions_stay", w.owner), W, Tok.warn), 4) }
				val facts = Column(10).constrain { width = W.at }
				val last = when (w.state) {
					WorldState.READY -> tr("tandem.who_ago", if (w.who == "you") tr("tandem.you") else w.who, w.ago)
					WorldState.HOSTED -> tr("tandem.is_playing_now", w.who)
					WorldState.VERSIONS -> if (w.cloudId != null) tr("tandem.versions_to_choose_from", 1 + w.branches.size) else tr("tandem.versions_from_tue_9_14_pm_to", Sample.versions.size)
					WorldState.SAVING -> tr("tandem.uploading_now", w.who)
					WorldState.OTHER -> if (w.savedAt > 0) tr("tandem.who_ago", if (w.savedBy == "you") tr("tandem.you") else w.savedBy, RealWorlds.ago(w.savedAt)) else tr("tandem.you_just_now")
					else -> tr("tandem.you_just_now")
				}
				val others = w.shared.filter { it != "You" && it != w.owner }
				for ((k, v) in listOf(
					tr("tandem.shared_with") to (if (others.isEmpty()) tr("tandem.nobody_yet") else names(others)),
					tr("tandem.synced_by") to (w.owner ?: "You"),
					// A real world in its first upload has no cloud id yet; it's this game's own, so its mods are too.
					tr("tandem.mods") to if (w.cloudId == null && !RealWorlds.on) tr("tandem.214_mods_fabric_1_21_1") else if (w.mods == null) tr("tandem.the_same_as_this_game") else if (w.mods.total == 1) tr("tandem.one_differs_from_this_game") else tr("tandem.differ_from_this_game", w.mods.total),
					tr("tandem.size") to w.size,
					tr("tandem.last_saved") to last,
				)) {
					val r = UIContainer().constrain { width = 100.percent; height = (14 * LINE_EM).at }
					Label(k, 14, Weight.REGULAR, Tok.muted) childOf r
					Label(v, 14).constrain { x = 156.at } childOf r
					facts.add(r)
				}
				col.add(facts, 8)
				col.add(Button(tr("tandem.download_as_zip"), Kind.OUTLINE, Size.NORMAL, null, Tok.panel) { zip(w) }, 8)
			}
			"hist" -> if (w.cloudId != null) realHistory(col, w, hosted)
			else if (RealWorlds.on) col.add(Label(tr("tandem.hasnt_finished_its_first_upload", w.name), 14, Weight.REGULAR, Tok.muted), 8)
			else {
				val a = w.shared.getOrNull(1) ?: tr("tandem.sam")
				val b = w.shared.getOrNull(2)?.takeIf { it != "You" } ?: tr("tandem.sam")
				val rows = listOf(
					listOf(tr("tandem.today"), tr("tandem.9_42_pm"), a, tr("tandem.s_session", a), tr("tandem.1_h_50_min")),
					listOf(tr("tandem.today"), tr("tandem.9_30_pm"), a, tr("tandem.saved_to_cloud"), tr("tandem.during_s_session", a)),
					listOf(tr("tandem.today"), tr("tandem.9_00_pm"), a, tr("tandem.saved_to_cloud"), tr("tandem.during_s_session", a)),
					listOf(tr("tandem.yesterday"), tr("tandem.11_05_pm"), "You", tr("tandem.your_session"), tr("tandem.3_h_12_min")),
					listOf(tr("tandem.yesterday"), tr("tandem.6_40_pm"), b, tr("tandem.s_session", b), tr("tandem.40_min")),
				)
				var day = ""
				for ((i, r) in rows.withIndex()) {
					if (r[0] != day) { day = r[0]; col.add(Label(day, 13, Weight.SEMIBOLD, Tok.sec), 8) }
					val s = box(12, Tok.raise, Tok.line).constrain { width = W.at; height = 52.at }
					Label(r[1], 14, Weight.REGULAR, Tok.muted).constrain { x = 15.at; y = CenterConstraint() } childOf s
					val who = if (r[2] == "You") null else Sample.friends.firstOrNull { it.name == r[2] }
					Face({ who?.skin ?: yourSkin() }, 28, 6, null, Tok.raise).constrain { x = (15 + 72 + 14).at; y = CenterConstraint() } childOf s
					row(4, Label(r[3], 14), Label("· ${r[4]}", 14, Weight.REGULAR, Tok.muted)).constrain { x = (15 + 72 + 14 + 28 + 14).at; y = CenterConstraint() } childOf s
					if (i == w.restored) pill(tr("tandem.current"), Tok.okText, Tok.over(Tok.raise, Tok.ok, 0.12)).constrain { x = 9.fromEnd; y = CenterConstraint() } childOf s
					else Button(tr("tandem.restore"), Kind.OUTLINE, Size.SMALL, null, Tok.raise) {
						val prev = w.restored
						w.restored = i; rebuild()
						layers.toast(tr("tandem.restored"), tr("tandem.its_the_current_version_now_the_one"), undo = { w.restored = prev; rebuild() })
					}.also { it.enabled = w.mine && !hosted }.constrain { x = 9.fromEnd; y = CenterConstraint() } childOf s
					col.add(s, -2)
				}
				val why = if (!w.mine) tr("tandem.only_who_synced_it_can_restore", w.owner) else if (hosted) tr("tandem.restore_once_stops_hosting", w.who) else ""
				col.add(foot(tr("tandem.restoring_adds_a_new_version_on_top", why)), 6)
			}
			"mem" -> {
				if (w.mine) col.add(Button(tr("tandem.add_friends"), Kind.OUTLINE, Size.NORMAL, null, Tok.panel) {
					dialogs.sync(w.name, w.size, emptyList(), true, w.shared) { picked ->
						if (picked.isEmpty()) return@sync
						if (w.cloudId != null) return@sync RealMembers.add(w, picked, layers, rebuild)
						w.shared += picked; rebuild()
						layers.toast(tr("tandem.shared_with_2", names(picked)), tr("tandem.they_can_play_and_host_it_when", w.name))
					}
				}, 4)
				for (name in w.shared.toList()) {
					val p = Sample.friends.firstOrNull { it.name == name }
					val r = box(12, Tok.raise, Tok.line).constrain { width = W.at; height = 58.at }
					Face({ p?.skin ?: yourSkin() }, 36, 8, if (name == "You" || p == null) null else p.online, Tok.raise).constrain { x = 11.at; y = CenterConstraint() } childOf r
					val t = Column(3).constrain { x = (11 + 36 + 14).at; y = CenterConstraint(); width = 600.at } childOf r
					t.add(Label(name, 15, Weight.SEMIBOLD).fitWithin(t))
					t.add(Label(if (name == "You") tr("tandem.this_pc") else p?.presence ?: tr("tandem.offline"), 13, Weight.REGULAR, Tok.muted).fitWithin(t))
					// The owner can take anyone else out: the ✕ at the end of their row, left of it whether they're an operator.
					val removable = w.mine && name != w.owner
					val end = if (removable) 13 + 34 + 8 else 13
					if (removable) IconButton("close", 34, Tok.raise) {
						if (w.cloudId != null) return@IconButton RealMembers.remove(w, name, layers, rebuild)
						val was = w.shared.toList()
						w.shared.remove(name); rebuild()
						layers.toast(tr("tandem.cant_host_any_more", name, w.name), tr("tandem.s_copy_stays_on_their_pc_as", name), undo = { w.shared.clear(); w.shared += was; rebuild() })
					}.constrain { x = 13.fromEnd; y = CenterConstraint() } childOf r
					if (name == w.owner) pill(tr("tandem.synced_it"), Tok.violetHi, Tok.over(Tok.raise, Tok.violet, 0.14)).constrain { x = 13.fromEnd; y = CenterConstraint() } childOf r
					else if (RealOperators.canChange(w, name)) {
						val op = name in w.operators
						Button(if (op) tr("tandem.operator_remove") else tr("tandem.make_operator"), Kind.OUTLINE, Size.SMALL, null, Tok.raise) {
							RealOperators.set(w, name, !op, layers) { rebuild() }
						}.constrain { x = end.fromEnd; y = CenterConstraint() } childOf r
					} else if (name in w.operators) pill(tr("tandem.operator"), Tok.violetHi, Tok.over(Tok.raise, Tok.violet, 0.14)).constrain { x = end.fromEnd; y = CenterConstraint() } childOf r
					col.add(r, -2)
				}
				col.add(foot(tr("tandem.everyone_here_can_host_when_nobody_else", w.name) +
					if (w.mine) tr("tandem.removing_someone_keeps_their_copy_on_their") else tr("tandem.only_can_change_who_its_shared_with", w.owner)), 6)
			}
			else -> if (w.cloudId != null && RealStorage.on) realSettings(col, w, hosted) else {
				val width = W - 38
				val last = if (w.mine) setRow(tr("tandem.stop_syncing"), listOf(Run(if (hosted) tr("tandem.is_hosting_right_now_stop_syncing_once", w.who) else tr("tandem.it_becomes_a_normal_world_nothing_is_2"), Weight.REGULAR, Tok.muted)), width,
					Button(tr("tandem.stop_syncing"), Kind.OUTLINE, Size.SMALL, null, Tok.raise) { dialogs.stopSyncing(w) { toWorlds() } }.also { it.enabled = !hosted }, last = true)
				else setRow(tr("tandem.remove_from_this_pc"), listOf(Run(tr("tandem.only_can_stop_syncing_removing_your_copy", w.owner, w.name, w.size), Weight.REGULAR, Tok.muted)), width,
					Button(tr("tandem.remove_from_this_pc"), Kind.OUTLINE, Size.SMALL, null, Tok.raise) {
						dialogs.removeFromPc(w) { Sample.worlds.remove(w); toWorlds() }
					}, last = true)
				col.add(card(0, 4, parts = arrayOf(
					setRow(tr("tandem.files_that_arent_synced"), listOf(Run(tr("tandem.distant_horizons_data_1_4_gb_and"), Weight.REGULAR, Tok.muted)), width),
					setRow(tr("tandem.stored_in"), listOf(Run(if (w.mine) (if (Storage.ownR2) tr("tandem.your_own_cloudflare_r2_tandem_worlds") else tr("tandem.your_keplersync_storage")) else tr("tandem.s_storage", w.owner), Weight.REGULAR, Tok.muted)), width),
					last,
				)), 4)
			}
		}
		UIContainer().constrain { y = SiblingConstraint(); height = 22.at } childOf into
	}

	fun zip(w: World) = if (w.cloudId != null || w.levelId != null) RealHistory.zip(w, null, layers)
	else layers.toast(tr("tandem.downloading_zip", w.name), tr("tandem.to_your_downloads_folder_a_plain_world"), "up")

	/** A real synced world's History: every version kept, newest first, with Restore and Zip. */
	private fun realHistory(col: Column, w: World, hosted: Boolean) {
		val history = RealHistory.of(w) { rebuild() }
		if (history == null) { col.add(Label(tr("tandem.loading_history"), 14, Weight.REGULAR, Tok.muted), 8); return }
		val me = WorldSync.get()?.player()
		val can = RealHistory.canRestore(w)
		var day = ""
		for (s in history) {
			val d = RealHistory.day(s.time)
			if (d != day) { day = d; col.add(Label(day, 13, Weight.SEMIBOLD, Tok.sec), 8) }
			val who = if (s.authorId == me) "You" else s.author ?: tr("tandem.someone")
			val by = if (who == "You") tr("tandem.by_you") else tr("tandem.by", who)
			val (title, detail) = when (s.kind) {
				"session" -> (if (who == "You") tr("tandem.your_session") else tr("tandem.s_session", who)) to bytesText(s.totalBytes)
				"checkpoint" -> tr("tandem.saved_to_cloud") to tr("tandem.during_session", whose(who))
				"import" -> tr("tandem.first_upload") to by
				else -> tr("tandem.restored") to by
			}
			val r = box(12, Tok.raise, Tok.line).constrain { width = W.at; height = 52.at }
			Label(RealHistory.time(s.time), 14, Weight.REGULAR, Tok.muted).constrain { x = 15.at; y = CenterConstraint() } childOf r
			val face = if (who == "You") yourSkin() else Sample.friends.firstOrNull { it.name == who }?.skin ?: yourSkin()
			Face({ face }, 28, 6, null, Tok.raise).constrain { x = (15 + 72 + 14).at; y = CenterConstraint() } childOf r
			row(4, Label(title, 14), Label("· $detail", 14, Weight.REGULAR, Tok.muted)).constrain { x = (15 + 72 + 14 + 28 + 14).at; y = CenterConstraint() } childOf r
			val zip = Button(tr("tandem.zip"), Kind.OUTLINE, Size.SMALL, null, Tok.raise) { RealHistory.zip(w, s.id, layers) }
			val right = when {
				// The newest is always the latest: every session, restore and settle lands on top.
				s === history.first() -> row(8, zip, pill(tr("tandem.current"), Tok.okText, Tok.over(Tok.raise, Tok.ok, 0.12)))
				else -> row(8, zip, Button(tr("tandem.restore"), Kind.OUTLINE, Size.SMALL, null, Tok.raise) {
					RealHistory.restore(w, s, layers) { rebuild() }
				}.also { it.enabled = can && !hosted })
			}
			if (s.id in w.branches) row(8, pill(tr("tandem.other_version"), Tok.warnText, Tok.over(Tok.raise, Tok.warn, 0.12)), right).constrain { x = 9.fromEnd; y = CenterConstraint() } childOf r
			else right.constrain { x = 9.fromEnd; y = CenterConstraint() } childOf r
			col.add(r, -2)
		}
		val why = if (!can) tr("tandem.only_who_synced_it_and_its_operators", w.owner) else if (hosted) tr("tandem.restore_once_stops_hosting", w.who) else ""
		col.add(foot(tr("tandem.restoring_adds_a_new_version_on_top_2", why)), 6)
	}

	/** A tab: 14 px, muted; the current one ink with a 2 px violet line under it. */
	private class UnderlineTab(text: String, private val on: Boolean) : UIComponent() {
		private val label = Label(text, 14, Weight.MEDIUM, if (on) Tok.ink else Tok.muted)

		init {
			label.constrain { x = 14.at; y = CenterConstraint() } childOf this
			constrain { width = basicWidthConstraint { label.getWidth() + 28.tp }; height = 39.at }
			onMouseEnter { label.tint = Tok.ink }
			onMouseLeave { label.tint = if (on) Tok.ink else Tok.muted }
		}

		override fun draw(matrixStack: UMatrixStack) {
			beforeDraw(matrixStack)
			if (on) rounded(matrixStack, getLeft(), getBottom() - 2.tp, getRight(), getBottom(), 0f, Tok.violet)
			super.draw(matrixStack)
		}
	}

	companion object {
		/** Dev screenshots: a Storage tab with stopped worlds, without KeplerSync. */
		var devStorage: app.keplersync.tandem.core.KeplerSyncApi.StorageOverview? = null

		/** The pages' content width: the 1040 px panel less 24 px each side and the 10 px scrollbar gutter. */
		const val W = 982

		fun names(list: List<String>) = if (list.size < 2) list.joinToString() else tr("tandem.list_and", list.dropLast(1).joinToString(", "), list.last())
	}
}

/** One status line: icon plus words, never colour alone. Names in it end in an ellipsis at [within]'s edge. */
fun statusLine(w: World, within: UIComponent? = null): UIComponent {
	fun fit(l: Label) = if (within != null) l.fitWithin(within) else l
	fun st(icon: String, words: String, c: Color) = row(5, Icon(icon, 14, c), fit(Label(words, 13, Weight.MEDIUM, c)))
	fun mute(t: String) = fit(Label(t, 13, Weight.REGULAR, Tok.muted))
	return when (w.state) {
		WorldState.VERSIONS -> st("warn", tr("tandem.n_versions", if (w.cloudId != null) 1 + w.branches.size else Sample.versions.size), Tok.warnText)
		WorldState.PC_ONLY -> st("warn", tr("tandem.saved_on_this_pc_only"), Tok.warnText)
		WorldState.HOSTED -> if (w.quiet) row(5, st("warn", tr("tandem.s_game_isnt_answering", w.who), Tok.warnText), mute(tr("tandem.free_again_soon")))
			else if (w.stopWhenFree) row(5, st("host", tr("tandem.is_hosting_2", w.who), Tok.violetHi), mute(tr("tandem.stops_syncing_after")))
			else st("host", tr("tandem.is_hosting_2", w.who), Tok.violetHi)
		WorldState.READY -> if (w.elsewhere) st("pc", tr("tandem.open_in_your_other_game"), Tok.warnText)
			else row(5, st("check", tr("tandem.ready"), Tok.okText), mute(tr("tandem.played", w.who, w.ago)))
		WorldState.FIRST -> row(5, Icon("up", 14, Tok.violetHi), Live(13, Weight.MEDIUM, Tok.violetHi) { if (waitingToUpload(w.upload)) tr("tandem.waiting_to_upload") else tr("tandem.uploading") },
			Live(13, Weight.REGULAR, Tok.muted) { if (waitingToUpload(w.upload)) "" else "${((w.upload?.value ?: 0f) * 100).toInt()}%" })
		WorldState.SAVING -> row(5, st("clock", tr("tandem.is_saving", w.who), Tok.warnText), mute(if (Sample.alexQuit) tr("tandem.until_is_back", w.who) else if (w.stopWhenFree) tr("tandem.then_stops_syncing", w.pct) else "${w.pct}%"))
		WorldState.LOCAL -> row(5, Icon("pc", 14, Tok.sec), mute(tr("tandem.only_on_this_pc_played", w.ago)))
		WorldState.OTHER -> row(5, mute(if (w.mc != null) tr("tandem.minecraft", w.mc) else tr("tandem.different_mods")))
	}
}

/** A world's main button: the one verb for where it stands. */
fun actionFor(w: World): Pair<String, Kind> = when (w.state) {
	WorldState.VERSIONS -> tr("tandem.choose_one") to Kind.WARN
	WorldState.PC_ONLY -> tr("tandem.fix_storage") to Kind.WARN
	WorldState.HOSTED -> tr("tandem.join", w.who) to Kind.FILLED
	WorldState.READY, WorldState.LOCAL, WorldState.FIRST -> tr("tandem.play") to Kind.OUTLINE
	WorldState.SAVING -> tr("tandem.play_when_ready") to Kind.OUTLINE
	WorldState.OTHER -> tr("tandem.see_whats_different") to Kind.OUTLINE
}
