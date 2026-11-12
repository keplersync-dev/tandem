package app.keplersync.tandem.core;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ProgressTest {
	/** 64 KB at a time, the way an upload counts its bytes. */
	private static long send(Progress p, int kb) {
		long start = System.nanoTime();
		for (int i = 0; i < kb / 64; i++) {
			p.add(64 << 10);
			p.check();
		}
		return (System.nanoTime() - start) / 1_000_000;
	}

	@Test
	void aCappedTransferKeepsToItsCap() {
		Progress p = new Progress();
		p.cap(1 << 20);
		// 1 MB at 1 MB a second, a quarter of a second of it allowed straight away.
		long ms = send(p, 1024);
		assertTrue(ms >= 650 && ms < 1500, "took " + ms + " ms");
	}

	@Test
	void anUncappedOneDoesntWait() {
		assertTrue(send(new Progress(), 1024) < 100);
	}

	@Test
	void itsSpeedCountsFromTheFirstByte() throws InterruptedException {
		Progress p = new Progress();
		Thread.sleep(500);
		p.add(1 << 20);
		Thread.sleep(100);
		// About 10 MB/s; counted from when it was made, the half second before would make it under 2.
		assertTrue(p.bytesPerSecond() > 5 << 20, "measured " + p.bytesPerSecond());
	}
}
