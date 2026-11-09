package app.keplersync.tandem.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * Replaces a world folder with a fully downloaded copy, so a crash at any point leaves either
 * the old world or the new one, never half of each.
 *
 * <p>Working files live in {@code saves/.tandem/<world>/}, beside the world rather than inside it.
 * Minecraft only lists folders that hold a {@code level.dat}, so none of them show up as worlds.
 */
public final class WorldSwap {
	public enum Recovery {
		/** No swap was in progress. */
		NOTHING,
		/** A swap had moved the old world aside; the new copy is now in place. */
		FINISHED,
		/** A swap hadn't touched the world yet; the staged copy is kept for the next try. */
		ABORTED,
		/** The staged copy was gone, so the old world was put back. */
		ROLLED_BACK
	}

	private WorldSwap() {}

	public static Path workDir(Path world) {
		return world.getParent().resolve(".tandem").resolve(world.getFileName().toString());
	}

	/** Where a download is assembled before {@link #swap}. */
	public static Path stageDir(Path world) {
		return workDir(world).resolve("stage");
	}

	static Path oldDir(Path world) {
		return workDir(world).resolve("old");
	}

	static Path journal(Path world) {
		return workDir(world).resolve("swap.journal");
	}

	/** {@link LocalState}'s notes for the staged copy, put in place by the swap. */
	static Path nextState(Path world) {
		return workDir(world).resolve("state.next.json");
	}

	private static void takeNextState(Path world) throws IOException {
		Path next = nextState(world);
		if (Files.exists(next)) Files.move(next, LocalState.file(world), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}

	/** Moves the staged copy into place. The stage must be complete and verified. */
	public static void swap(Path world) throws IOException {
		Path stage = stageDir(world);
		if (!Files.isDirectory(stage)) throw new IOException("Nothing staged for " + world.getFileName());
		Path old = oldDir(world);
		if (Files.exists(old)) deleteTree(old);

		Files.write(journal(world), world.getFileName().toString().getBytes(StandardCharsets.UTF_8));
		if (Files.exists(world)) Files.move(world, old, StandardCopyOption.ATOMIC_MOVE);
		Files.move(stage, world, StandardCopyOption.ATOMIC_MOVE);
		takeNextState(world);
		Files.delete(journal(world));
		if (Files.exists(old)) deleteTree(old);
	}

	/** Finishes or undoes a swap that a crash interrupted. Safe to call on every launch. */
	public static Recovery recover(Path world) throws IOException {
		Path stage = stageDir(world);
		Path old = oldDir(world);
		Path journal = journal(world);

		if (!Files.exists(journal)) {
			if (Files.exists(old)) deleteTree(old);
			Files.deleteIfExists(nextState(world));
			return Recovery.NOTHING;
		}

		boolean hasWorld = Files.exists(world);
		boolean hasStage = Files.exists(stage);
		Recovery result;
		if (hasWorld && hasStage) {
			result = Recovery.ABORTED;
		} else if (!hasWorld && hasStage) {
			Files.move(stage, world, StandardCopyOption.ATOMIC_MOVE);
			result = Recovery.FINISHED;
		} else if (hasWorld) {
			result = Recovery.FINISHED;
		} else if (Files.exists(old)) {
			Files.move(old, world, StandardCopyOption.ATOMIC_MOVE);
			result = Recovery.ROLLED_BACK;
		} else {
			result = Recovery.NOTHING;
		}
		if (result == Recovery.FINISHED) takeNextState(world);
		else Files.deleteIfExists(nextState(world));
		Files.delete(journal);
		if (Files.exists(old)) deleteTree(old);
		return result;
	}

	public static void deleteTree(Path root) throws IOException {
		Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
				Files.delete(file);
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult postVisitDirectory(Path dir, IOException e) throws IOException {
				if (e != null) throw e;
				Files.delete(dir);
				return FileVisitResult.CONTINUE;
			}
		});
	}
}
