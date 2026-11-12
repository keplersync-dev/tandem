package app.keplersync.tandem.core;

import java.util.concurrent.CancellationException;

/** How far a transfer has got, read by the screens while it runs, and its Cancel button. */
public final class Progress {
	/** How far a capped transfer may run ahead at full speed, making up for the requests between files. */
	private static final long BURST_NS = 250_000_000L;

	private volatile long started = System.nanoTime();
	// The transfer writes these, from a few threads at once; screens only read them.
	private volatile long done;
	private volatile long total;
	private volatile boolean cancelled;
	/** Bytes a second it may go at; 0 for as fast as the connection allows. */
	private volatile long cap;
	/** When the bytes counted so far may have gone, at the cap. */
	private long paced;

	public void total(long bytes) {
		total = bytes;
	}

	public void cap(long bytesPerSecond) {
		cap = bytesPerSecond;
	}

	public synchronized void add(long bytes) {
		long now = System.nanoTime();
		// The speed counts from the first byte, not from the checks and copies before it.
		if (done == 0 && bytes > 0) started = now;
		done += bytes;
		long c = cap;
		if (c > 0 && bytes > 0) paced = Math.max(paced, now - BURST_NS) + bytes * 1_000_000_000L / c;
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

	/** Stops a cancelled transfer, and holds a capped one back until its bytes fit the cap. */
	void check() {
		if (cancelled) throw new CancellationException("Cancelled");
		long wait;
		synchronized (this) {
			wait = paced - System.nanoTime();
		}
		if (wait <= 0) return;
		try {
			Thread.sleep(wait / 1_000_000, (int) (wait % 1_000_000));
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new CancellationException("Interrupted");
		}
	}
}
