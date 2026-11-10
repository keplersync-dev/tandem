package app.keplersync.tandem.fabric;

import app.keplersync.tandem.core.Sync;
import app.keplersync.tandem.core.WorldSwap;
import app.keplersync.tandem.fabric.account.Account;
import app.keplersync.tandem.fabric.account.LogBook;
import app.keplersync.tandem.fabric.play.Hosting;
import app.keplersync.tandem.fabric.play.Joining;
import app.keplersync.tandem.fabric.play.WorldRules;
import app.keplersync.tandem.fabric.sync.WorldSync;
import app.keplersync.tandem.fabric.ui.Ingame;
import app.keplersync.tandem.fabric.ui.PausePanel;
import app.keplersync.tandem.fabric.ui.PlayScreen;
import app.keplersync.tandem.fabric.ui.TitleButton;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;

public final class TandemClient implements ClientModInitializer {
	public static final Logger LOG = LogUtils.getLogger();

	private static final ResourceLocation LATE = ResourceLocation.fromNamespaceAndPath("tandem", "late");

	// H by default (free in vanilla and the big pack mods); players change it in Options › Controls › Key Binds › Tandem.
	public static final KeyMapping OPEN = KeyBindingHelper.registerKeyBinding(
			new KeyMapping("key.tandem.open", GLFW.GLFW_KEY_H, "key.categories.tandem"));

	/** Joins the friend whose invite is showing (J by default). */
	public static final KeyMapping JOIN = KeyBindingHelper.registerKeyBinding(
			new KeyMapping("key.tandem.join", GLFW.GLFW_KEY_J, "key.categories.tandem"));

	public static Path savesDir() {
		return FabricLoader.getInstance().getGameDir().resolve("saves");
	}

	@Override
	public void onInitializeClient() {
		LogBook.install();
		// Each file a world keeps on this PC only, because Windows, macOS or Linux couldn't hold its name: said once.
		java.util.Set<String> told = java.util.concurrent.ConcurrentHashMap.newKeySet();
		Sync.skipped = name -> {
			if (told.add(name)) LOG.info("Tandem doesn't sync {}: not every system can have a file with that name", name);
		};
		recoverSwaps();
		// Tandem places its own pieces after every other mod has laid out a screen, so it can find the free space.
		ScreenEvents.AFTER_INIT.addPhaseOrdering(Event.DEFAULT_PHASE, LATE);
		ScreenEvents.AFTER_INIT.register(LATE, (client, screen, width, height) -> {
			if (screen instanceof TitleScreen) {
				Guard.run("title screen button", () -> addTitleButton(screen, width, height, () -> client.setScreen(new PlayScreen())));
				Guard.run("title screen invites", () -> Ingame.onTitle(screen));
			}
			if (screen instanceof PauseScreen pause) Guard.run("pause card", () -> PausePanel.attach(client, pause, width, height));
		});
		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			// Key bindings only fire in game, so menus Tandem can open from listen for the key themselves.
			if (screen instanceof TitleScreen || screen instanceof PauseScreen) {
				ScreenKeyboardEvents.afterKeyPress(screen).register((s, key, scancode, mods) -> Guard.run("keys", () -> {
					if (OPEN.matches(key, scancode)) client.setScreen(new PlayScreen());
					else if (JOIN.matches(key, scancode)) Ingame.joinInvite(client);
				}));
			}
		});
		ClientTickEvents.END_CLIENT_TICK.register(client -> Guard.run("keys", () -> {
			while (OPEN.consumeClick()) client.setScreen(new PlayScreen());
		}));
		Ingame.INSTANCE.install();
		Account.init();
		WorldSync.init();
		Hosting.init();
		Joining.init();
		WorldRules.init();
		Updates.init();
		devRun();
	}

	/**
	 * The screenshot run and the dev checks, each started by its -Dtandem flag. Found by name, because the release jar
	 * leaves the dev package out (fabric/build.gradle).
	 */
	private static void devRun() {
		String[][] runs = {{"shots", "start"}, {"synccheck", "syncCheck"}, {"friendcheck", "friendCheck"}, {"leasecheck", "leaseCheck"},
				{"playcheck", "playCheck"}, {"handoffcheck", "handoffCheck"}, {"rescuecheck", "rescueCheck"}, {"crashcheck", "crashCheck"}, {"historycheck", "historyCheck"}, {"owncheck", "ownCheck"},
				{"roundcheck", "roundCheck"}, {"walkcheck", "walkCheck"}, {"bigcheck", "bigCheck"}, {"shooting", "shoot"}};
		for (String[] run : runs) {
			if (!Boolean.getBoolean("tandem." + run[0])) continue;
			try {
				Class<?> shots = Class.forName("app.keplersync.tandem.fabric.dev.Shots");
				shots.getMethod(run[1]).invoke(shots.getField("INSTANCE").get(null));
			} catch (ReflectiveOperationException e) {
				LOG.error("Tandem's dev run {} isn't in this build", run[0], e);
			}
			return;
		}
	}

	/** Adds the Tandem button beside Multiplayer, or the tile in the first free spot (see {@link TitleButton#add}). */
	private static void addTitleButton(Screen screen, int width, int height, Runnable open) {
		TitleButton.add(Screens.getButtons(screen), width, height, open);
	}

	/** Finishes or undoes any world swap a crash interrupted, before a world can be opened. */
	private static void recoverSwaps() {
		Path work = savesDir().resolve(".tandem");
		if (!Files.isDirectory(work)) return;
		try (DirectoryStream<Path> worlds = Files.newDirectoryStream(work)) {
			for (Path w : worlds) {
				Path world = savesDir().resolve(w.getFileName().toString());
				WorldSwap.Recovery r = WorldSwap.recover(world);
				if (r != WorldSwap.Recovery.NOTHING) LOG.info("Tandem recovered {}: {}", world.getFileName(), r);
			}
		} catch (IOException e) {
			LOG.error("Tandem couldn't check for interrupted world swaps", e);
		}
	}
}
