package app.keplersync.tandem.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class HostNeedsTest {
	private static final long GB = 1L << 30;

	@Test
	void aGameWithRoomThatRanTheWorldWellIsFine() {
		assertNull(HostNeeds.problem(8 * GB, 22, 4 * GB));
	}

	@Test
	void tooLittleMemoryForWhatTheWorldUses() {
		assertEquals(HostNeeds.Problem.LOW_MEMORY, HostNeeds.problem(4 * GB, 0, 3 * GB));
		// Low memory says more than a slow last time: it's what they'd run into first.
		assertEquals(HostNeeds.Problem.LOW_MEMORY, HostNeeds.problem(4 * GB, 48, 3 * GB));
	}

	@Test
	void aWorldThatLaggedWhenTheyHostedIt() {
		assertEquals(HostNeeds.Problem.LAGGED, HostNeeds.problem(8 * GB, 45, 2 * GB));
	}

	@Test
	void whatIsntKnownCountsAsFine() {
		// Never hosted it, the host's use not measured yet, a game with no memory limit, or a guest that sent nonsense.
		assertNull(HostNeeds.problem(2 * GB, 0, 0));
		assertNull(HostNeeds.problem(Long.MAX_VALUE, 0, 6 * GB));
		assertNull(HostNeeds.problem(0, Float.NaN, 6 * GB));
	}
}
