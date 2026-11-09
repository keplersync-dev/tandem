package app.keplersync.tandem.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Set;

/**
 * File contents stored once each, named by sha256 ({@code ab/abcdef…}). Every copy is hashed as it's written to a
 * temporary file and only then renamed to its hash, so a blob with a real name is always complete.
 */
public final class BlobCache {
	private final Path dir;

	public BlobCache(Path dir) {
		this.dir = dir;
	}

	public Path path(String sha) {
		return dir.resolve(sha.substring(0, 2)).resolve(sha);
	}

	public boolean has(String sha) {
		return Files.isRegularFile(path(sha));
	}

	/** Copies [file] in, retrying if another program has it locked, and returns its sha256. [name] is for errors. */
	public String add(final Path file, String name, final Progress progress) throws IOException {
		final Path tmp = tmp();
		String sha = Io.retryLocked(name, () -> {
			try (InputStream in = Files.newInputStream(file)) {
				return Io.copyHashing(in, tmp, progress);
			}
		});
		store(tmp, sha);
		return sha;
	}

	/** Stores a download if it hashes to [sha]; returns false (and keeps nothing) if it was damaged. */
	public boolean addVerified(InputStream in, String sha, Progress progress) throws IOException {
		Path tmp = tmp();
		String got;
		try {
			got = Io.copyHashing(in, tmp, progress);
		} catch (IOException | RuntimeException e) {
			Files.deleteIfExists(tmp);
			throw e;
		}
		if (!got.equals(sha)) {
			Files.deleteIfExists(tmp);
			return false;
		}
		store(tmp, sha);
		return true;
	}

	/**
	 * Deletes blobs not in [keep] and not added in the last [graceMs], and temporary files a closed game left behind.
	 * The grace keeps a download that stopped part way, which no world uses yet, for its next try. Returns the bytes
	 * freed.
	 */
	public long prune(final Set<String> keep, long graceMs) throws IOException {
		if (!Files.isDirectory(dir)) return 0;
		final long before = System.currentTimeMillis() - graceMs;
		final long[] freed = {0};
		Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
				boolean temp = file.getParent().getFileName().toString().equals("tmp");
				if ((temp || !keep.contains(file.getFileName().toString())) && attrs.lastModifiedTime().toMillis() < before) {
					Files.deleteIfExists(file);
					freed[0] += attrs.size();
				}
				return FileVisitResult.CONTINUE;
			}
		});
		return freed[0];
	}

	private Path tmp() throws IOException {
		Path t = dir.resolve("tmp");
		Files.createDirectories(t);
		return Files.createTempFile(t, "blob", ".part");
	}

	private void store(Path tmp, String sha) throws IOException {
		Path to = path(sha);
		if (Files.exists(to)) {
			Files.delete(tmp);
			return;
		}
		Files.createDirectories(to.getParent());
		try {
			Files.move(tmp, to, StandardCopyOption.ATOMIC_MOVE);
		} catch (FileAlreadyExistsException e) {
			Files.deleteIfExists(tmp);
		}
	}
}
