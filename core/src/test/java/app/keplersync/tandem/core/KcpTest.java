package app.keplersync.tandem.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** KCP over a simulated link, on a virtual clock: loss, reordering, a slow home upload, wrapping sequence numbers. */
class KcpTest {
	/** One direction of a link: drops, delays and a bandwidth limit with a router queue that drops when full. */
	static final class Link {
		final Random random;
		final double loss;
		final int minDelay, jitter;
		final int bytesPerMs;
		final int queueLimit;
		final List<long[]> inFlight = new ArrayList<>();
		final List<byte[]> payloads = new ArrayList<>();
		long busyUntil;
		int queued;

		Link(long seed, double loss, int minDelay, int jitter, int bytesPerMs, int queueLimit) {
			this.random = new Random(seed);
			this.loss = loss;
			this.minDelay = minDelay;
			this.jitter = jitter;
			this.bytesPerMs = bytesPerMs;
			this.queueLimit = queueLimit;
		}

		void send(long now, byte[] data, int len) {
			if (random.nextDouble() < loss) return;
			long start = now;
			if (bytesPerMs > 0) {
				start = Math.max(now, busyUntil);
				if ((start - now) * bytesPerMs + len > queueLimit) return;
				busyUntil = start + Math.max(1, len / bytesPerMs);
			}
			long arrives = start + minDelay + (jitter > 0 ? random.nextInt(jitter) : 0);
			inFlight.add(new long[] {arrives, payloads.size()});
			payloads.add(Arrays.copyOf(data, len));
		}

		void deliver(long now, Kcp to) {
			for (int i = 0; i < inFlight.size(); i++) {
				long[] p = inFlight.get(i);
				if (p[0] <= now) {
					byte[] data = payloads.get((int) p[1]);
					to.input(data, 0, data.length);
					inFlight.remove(i--);
				}
			}
		}
	}

	static Kcp game(int conv, Kcp.Output out) {
		Kcp k = new Kcp(conv, out);
		Punch.configure(k);
		return k;
	}

	/** Sends [total] bytes from a to b over the two links; the virtual milliseconds it took, or -1 past [limitMs]. */
	static long transfer(int total, Link ab, Link ba, int startAt, long readPauseFrom, long readPauseTo, long limitMs, int[] maxWaiting) {
		long[] now = {0};
		Kcp[] ends = new Kcp[2];
		ends[0] = game(7, (d, n) -> ab.send(now[0], d, n));
		ends[1] = game(7, (d, n) -> ba.send(now[0], d, n));
		ends[0].startAt(startAt);
		ends[1].startAt(startAt);
		Kcp a = ends[0], b = ends[1];
		byte[] sent = new byte[total];
		new Random(42).nextBytes(sent);
		ByteArrayOutputStream got = new ByteArrayOutputStream();
		byte[] buf = new byte[64 * 1024];
		int off = 0;
		for (; now[0] < limitMs; now[0] += 10) {
			while (off < total && a.waitSnd() < 2 * a.sndWnd) {
				int n = Math.min(16 * 1024, total - off);
				a.send(sent, off, n);
				off += n;
			}
			if (maxWaiting != null) maxWaiting[0] = Math.max(maxWaiting[0], a.waitSnd());
			a.update((int) now[0]);
			b.update((int) now[0]);
			ab.deliver(now[0], b);
			ba.deliver(now[0], a);
			if (now[0] < readPauseFrom || now[0] >= readPauseTo) {
				int n;
				while ((n = b.recv(buf)) > 0) got.write(buf, 0, n);
			}
			if (got.size() == total) {
				assertArrayEquals(sent, got.toByteArray(), "every byte, in order");
				return now[0];
			}
		}
		return -1;
	}

	@Test
	void aLossyReorderingLinkLosesNothing() {
		long ms = transfer(4 << 20, new Link(1, 0.2, 20, 40, 0, 0), new Link(2, 0.2, 20, 40, 0, 0), 0, 0, 0, 600_000, null);
		assertTrue(ms > 0, "4 MB through 20% loss each way");
	}

	@Test
	void aSlowHomeUploadIsMostlyUsedWithoutFloodingIt() {
		// 1 MB/s, a 64 KB router queue, 40 ms round trip.
		int[] waiting = {0};
		long ms = transfer(4 << 20, new Link(3, 0, 20, 0, 1024, 64 * 1024), new Link(4, 0, 20, 0, 1024, 64 * 1024), 0, 0, 0, 60_000, waiting);
		assertTrue(ms > 0, "it got there");
		double share = (4 << 20) / 1024.0 / ms;
		assertTrue(share >= 0.6, "used " + Math.round(share * 100) + "% of the link in " + ms + " ms");
		assertTrue(waiting[0] <= 2 * Punch.WINDOW + 16, "the sender's queue stays bounded: " + waiting[0]);
	}

	@Test
	void sequenceNumbersWrapPast32Bits() {
		long ms = transfer(1 << 20, new Link(5, 0.05, 10, 10, 0, 0), new Link(6, 0.05, 10, 10, 0, 0), 0xFFFFFF00, 0, 0, 120_000, null);
		assertTrue(ms > 0);
	}

	@Test
	void aReaderThatStopsForFiveSecondsLosesNothingAndHoldsLittle() {
		int[] waiting = {0};
		long ms = transfer(2 << 20, new Link(7, 0.02, 20, 0, 0, 0), new Link(8, 0.02, 20, 0, 0, 0), 0, 200, 5200, 120_000, waiting);
		assertTrue(ms > 5200, "it waited for the reader");
		assertTrue(waiting[0] <= 2 * Punch.WINDOW + 16, "bounded while the reader was away: " + waiting[0]);
	}
}
