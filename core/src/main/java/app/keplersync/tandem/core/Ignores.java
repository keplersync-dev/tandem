package app.keplersync.tandem.core;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Files in a world that never sync: what the game holds open, and data that rebuilds on each PC. */
public final class Ignores {
	/**
	 * Globs relative to the world folder. Level-of-detail mods keep databases that can be gigabytes and rebuild on any
	 * PC: Distant Horizons in each dimension's {@code data} folder, Voxy in {@code voxy/} (and its server add-on in
	 * {@code voxyserver/}).
	 */
	// shortcut: Distant Horizons' path is from a real install (3.3.3); Voxy's is from its source. Check Voxy installed.
	public static final List<String> DEFAULTS = Collections.unmodifiableList(Arrays.asList(
			"session.lock",
			LocalState.MARKER,
			"data/DistantHorizons.sqlite*",
			"**/data/DistantHorizons.sqlite*",
			"voxy/**",
			"voxyserver/**"));

	private final List<PathMatcher> matchers = new ArrayList<>();

	/** The defaults plus a world's own [extra] globs. */
	public Ignores(FileSystem fs, List<String> extra) {
		for (String glob : DEFAULTS) matchers.add(fs.getPathMatcher("glob:" + glob));
		for (String glob : extra) matchers.add(fs.getPathMatcher("glob:" + glob));
	}

	public boolean matches(Path relative) {
		for (PathMatcher m : matchers) if (m.matches(relative)) return true;
		return false;
	}

	/** How much of [world] would sync: every file but the ignored ones. For "will it fit" before syncing starts. */
	public static long syncedBytes(Path world) throws IOException {
		Ignores ignores = new Ignores(world.getFileSystem(), Collections.<String>emptyList());
		long[] total = {0};
		Files.walkFileTree(world, new SimpleFileVisitor<Path>() {
			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
				if (!ignores.matches(world.relativize(file))) total[0] += attrs.size();
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFileFailed(Path file, IOException e) {
				// A file the game is writing right now: counted at the next look.
				return FileVisitResult.CONTINUE;
			}
		});
		return total[0];
	}
}
