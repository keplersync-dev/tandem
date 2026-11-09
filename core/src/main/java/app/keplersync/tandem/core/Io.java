package app.keplersync.tandem.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** File helpers the sync engine shares: hashing copies, JSON files written whole, and retrying locked files. */
final class Io {
	/** How long a file another program has locked is retried before giving up. Tests shorten it. */
	static long lockedRetryMs = 10_000;

	static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	interface Action<T> {
		T run() throws IOException;
	}

	interface Each<T> {
		void run(T item) throws IOException;
	}

	/** Files sent or fetched at once: each one waits on the network more than on the disk. */
	static final int TRANSFERS = 4;

	private Io() {}

	/**
	 * Runs [action], retrying with backoff while the file seems locked (antivirus and backup tools on Windows), then
	 * fails naming the file. A missing file fails at once.
	 */
	static <T> T retryLocked(String name, Action<T> action) throws IOException {
		long until = System.currentTimeMillis() + lockedRetryMs;
		long wait = 50;
		while (true) {
			try {
				return action.run();
			} catch (NoSuchFileException e) {
				throw e;
			} catch (IOException e) {
				if (System.currentTimeMillis() >= until) {
					throw new IOException(name + " is in use by another program, often an antivirus or backup tool. Close it and try again.", e);
				}
				try {
					Thread.sleep(wait);
				} catch (InterruptedException ie) {
					Thread.currentThread().interrupt();
					throw e;
				}
				wait = Math.min(1000, wait * 2);
			}
		}
	}

	/**
	 * Runs [each] on every item, [threads] at a time. The first failure is thrown and the rest are told to stop; a
	 * cancelled transfer's {@link java.util.concurrent.CancellationException} comes through as it is.
	 */
	static <T> void parallel(int threads, java.util.Collection<T> items, final Each<T> each) throws IOException {
		if (threads <= 1 || items.size() <= 1) {
			for (T item : items) each.run(item);
			return;
		}
		java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(Math.min(threads, items.size()), r -> {
			Thread t = new Thread(r, "Tandem transfer");
			t.setDaemon(true);
			return t;
		});
		try {
			java.util.List<java.util.concurrent.Future<?>> running = new java.util.ArrayList<>();
			for (final T item : items) running.add(pool.submit(() -> {
				each.run(item);
				return null;
			}));
			for (java.util.concurrent.Future<?> f : running) {
				try {
					f.get();
				} catch (java.util.concurrent.ExecutionException e) {
					pool.shutdownNow();
					Throwable cause = e.getCause();
					if (cause instanceof IOException) throw (IOException) cause;
					if (cause instanceof RuntimeException) throw (RuntimeException) cause;
					if (cause instanceof Error) throw (Error) cause;
					throw new IOException(cause);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					throw new java.io.InterruptedIOException();
				}
			}
		} finally {
			pool.shutdownNow();
		}
	}

	/** Copies [in] to [to], counting into [progress] (may be null), and returns the sha256 of what was copied. */
	static String copyHashing(InputStream in, Path to, Progress progress) throws IOException {
		MessageDigest digest = sha256();
		byte[] buf = new byte[1 << 16];
		try (OutputStream out = Files.newOutputStream(to)) {
			int n;
			while ((n = in.read(buf)) != -1) {
				digest.update(buf, 0, n);
				out.write(buf, 0, n);
				if (progress != null) {
					progress.add(n);
					progress.check();
				}
			}
		}
		return hex(digest.digest());
	}

	static MessageDigest sha256() {
		try {
			return MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	static String hex(byte[] bytes) {
		StringBuilder sb = new StringBuilder(bytes.length * 2);
		for (byte b : bytes) sb.append(String.format("%02x", b));
		return sb.toString();
	}

	static <T> T readJson(Path file, Class<T> type) throws IOException {
		if (!Files.exists(file)) return null;
		try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			return GSON.fromJson(r, type);
		}
	}

	/** Writes beside the file, then renames over it, so a crash never leaves half a file. */
	static void writeJson(Path file, Object value) throws IOException {
		Files.createDirectories(file.getParent());
		Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
		try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
			GSON.toJson(value, w);
		}
		Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}
}
