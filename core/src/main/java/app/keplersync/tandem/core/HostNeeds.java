package app.keplersync.tandem.core;

/**
 * Whether a player's PC would struggle to host a world, from what their game measured: the hand-off list puts them
 * below everyone who wouldn't. Hosting runs the world's server on their PC, in the same memory as their own game.
 */
public final class HostNeeds {
	private HostNeeds() {}

	public enum Problem { LOW_MEMORY, LAGGED }

	/** Minecraft has 50 ms for each tick; a world that averaged more than this while they hosted it lagged. */
	public static final float LAGGED_MS = 40;
	/** A game with less memory than this many times what the world uses spends its time collecting garbage. */
	public static final double HEADROOM = 1.5;

	/**
	 * [maxMemory]: bytes their game may use. [msPerTick]: how long this world's ticks took on average the last time
	 * they hosted it, 0 if they never have. [inUse]: bytes the world's game uses on the host's PC now, 0 if not known
	 * yet. Null when nothing suggests they'd struggle.
	 */
	public static Problem problem(long maxMemory, float msPerTick, long inUse) {
		if (maxMemory > 0 && inUse > 0 && maxMemory < inUse * HEADROOM) return Problem.LOW_MEMORY;
		if (msPerTick > LAGGED_MS) return Problem.LAGGED;
		return null;
	}
}
