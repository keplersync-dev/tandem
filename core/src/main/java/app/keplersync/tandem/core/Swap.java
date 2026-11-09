package app.keplersync.tandem.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * Puts a downloaded Tandem in place of the jar the game ran, once the game has closed: Windows keeps a running jar
 * locked, and swapping it under a running game could load half of each. Its own process, started by the game with
 * stdin as a pipe, which closes however the game ends (quit, crash, killed). Run from its .class file alone, so it
 * locks neither jar; it uses nothing but Java itself, so keep it that way.
 *
 * <p>Arguments: the jar to replace, then the checked download.
 */
public final class Swap {
	private Swap() {}

	public static void main(String[] args) throws Exception {
		Path target = Paths.get(args[0]);
		Path staged = Paths.get(args[1]);
		while (System.in.read() != -1) {
			// The game is still running.
		}
		// Removed Tandem meanwhile, or another update already went in: leave it be.
		if (!Files.isRegularFile(target) || !Files.isRegularFile(staged)) {
			System.out.println("Nothing to update: " + target + " or " + staged + " is gone");
			return;
		}
		// Copied beside the jar first (not a .jar, so the game never loads it), then renamed over it in one step.
		Path next = target.resolveSibling(target.getFileName() + ".tandem-new");
		IOException last = null;
		for (int i = 0; i < 120; i++) {
			try {
				if (!Files.exists(next)) Files.copy(staged, next);
				Files.move(next, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
				Files.deleteIfExists(staged);
				System.out.println("Tandem updated " + target);
				return;
			} catch (IOException e) {
				// Windows lets go of the jar a moment after the game closes; antivirus can take longer.
				last = e;
				Thread.sleep(500);
			}
		}
		Files.deleteIfExists(next);
		System.out.println("Tandem couldn't update " + target + ": " + last);
	}
}
