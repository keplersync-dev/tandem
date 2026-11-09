package app.keplersync.tandem.core;

import java.util.concurrent.CancellationException;

/** How far a transfer has got, read by the screens while it runs, and its Cancel button. */
public final class Progress {
	private volatile long started = System.nanoTime();
	// The transfer writes these, from a few threads at once; screens only read them.
	private volatile long done;
	private volatile long total;
	private volatile boolean cancelled;

	public void total(long bytes) {
		total = bytes;
	}

	public synchronized void add(long bytes) {
		done += bytes;
	}

	/** Counting starts over: a transfer trying again after the connection dropped. */
	public synchronized void restart() {
		done = 0;
		started = System.nanoTime();
	}

	public long done() {
		return done;
	}

	public long total() {
		return total;
	}

	public float fraction() {
		long t = total;
		return t <= 0 ? 0f : Math.min(1f, (float) done / t);
	}

	public double bytesPerSecond() {
		double secs = (System.nanoTime() - started) / 1e9;
		return secs <= 0 ? 0 : done / secs;
	}

	/** Seconds until done at the speed so far, or -1 before anything has arrived. */
	public long secondsLeft() {
		double rate = bytesPerSecond();
		return rate <= 0 ? -1 : (long) Math.max(0, (total - done) / rate);
	}

	public void cancel() {
		cancelled = true;
	}

	public boolean cancelled() {
		return cancelled;
	}

	void check() {
		if (cancelled) throw new CancellationException("Cancelled");
	}
}
