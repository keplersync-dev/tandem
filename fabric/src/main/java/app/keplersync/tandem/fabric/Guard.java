package app.keplersync.tandem.fabric;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Runs one of Tandem's hooks into the game. An error in it turns that piece of Tandem off until the game restarts,
 * logged (so it goes with the logs), instead of crashing Minecraft.
 */
public final class Guard {
	private static final Set<String> OFF = ConcurrentHashMap.newKeySet();

	private Guard() {}

	public static void run(String piece, Runnable hook) {
		if (OFF.contains(piece)) return;
		try {
			hook.run();
		} catch (VirtualMachineError e) {
			throw e;
		} catch (Throwable t) {
			off(piece, t);
		}
	}

	/** A hook that answers: [fallback] when it fails, and from then on. */
	public static <T> T get(String piece, T fallback, Supplier<T> hook) {
		if (OFF.contains(piece)) return fallback;
		try {
			return hook.get();
		} catch (VirtualMachineError e) {
			throw e;
		} catch (Throwable t) {
			off(piece, t);
			return fallback;
		}
	}

	private static void off(String piece, Throwable t) {
		if (OFF.add(piece)) TandemClient.LOG.error("Tandem turned off its {} until the game restarts, after an error", piece, t);
	}
}
