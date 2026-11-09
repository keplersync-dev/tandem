package app.keplersync.tandem.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Builds a {@link Manifest} from a world folder on disk. */
public final class WorldScanner {
	/** Globs, relative to the world folder. */
	public static final List<String> DEFAULT_IGNORES = Ignores.DEFAULTS;

	private WorldScanner() {}

	public static Manifest scan(Path world) throws IOException {
		return scan(world, DEFAULT_IGNORES);
	}

	public static Manifest scan(final Path world, List<String> ignores) throws IOException {
		final List<PathMatcher> matchers = new ArrayList<>();
		for (String glob : ignores) matchers.add(world.getFileSystem().getPathMatcher("glob:" + glob));

		final Map<String, Manifest.Entry> files = new HashMap<>();
		Files.walkFileTree(world, new SimpleFileVisitor<Path>() {
			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
				Path rel = world.relativize(file);
				for (PathMatcher m : matchers) {
					if (m.matches(rel)) return FileVisitResult.CONTINUE;
				}
				files.put(key(rel), new Manifest.Entry(attrs.size(), sha256(file)));
				return FileVisitResult.CONTINUE;
			}
		});
		return new Manifest(files);
	}

	static String key(Path rel) {
		StringBuilder sb = new StringBuilder();
		for (Path part : rel) {
			if (sb.length() > 0) sb.append('/');
			sb.append(part.toString());
		}
		return sb.toString();
	}

	public static String sha256(Path file) throws IOException {
		MessageDigest digest;
		try {
			digest = MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
		byte[] buf = new byte[1 << 16];
		try (InputStream in = Files.newInputStream(file)) {
			int n;
			while ((n = in.read(buf)) != -1) digest.update(buf, 0, n);
		}
		StringBuilder hex = new StringBuilder(64);
		for (byte b : digest.digest()) hex.append(String.format("%02x", b));
		return hex.toString();
	}
}
