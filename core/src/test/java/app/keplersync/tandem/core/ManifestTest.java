package app.keplersync.tandem.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManifestTest {
	@TempDir
	Path dir;

	private Path write(String rel, String content) throws IOException {
		Path file = dir.resolve(rel);
		Files.createDirectories(file.getParent());
		Files.write(file, content.getBytes(StandardCharsets.UTF_8));
		return file;
	}

	@Test
	void scanHashesFilesWithSlashPathsAndSkipsTheLock() throws IOException {
		write("level.dat", "abc");
		write("region/r.0.0.mca", "region");
		write("session.lock", "locked");

		Manifest m = WorldScanner.scan(dir);

		assertEquals(List.of("level.dat", "region/r.0.0.mca"), List.copyOf(m.files().keySet()));
		assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", m.files().get("level.dat").sha256);
		assertEquals(9, m.totalBytes());
	}

	@Test
	void diffFindsAddedChangedAndRemoved() throws IOException {
		write("level.dat", "v1");
		write("region/r.0.0.mca", "same");
		write("region/r.1.0.mca", "gone soon");
		Manifest before = WorldScanner.scan(dir);

		write("level.dat", "v2!");
		Files.delete(dir.resolve("region/r.1.0.mca"));
		write("region/r.2.0.mca", "new");
		Manifest after = WorldScanner.scan(dir);

		Manifest.Diff diff = before.diffTo(after);
		assertEquals(List.of("region/r.2.0.mca"), diff.added);
		assertEquals(List.of("level.dat"), diff.changed);
		assertEquals(List.of("region/r.1.0.mca"), diff.removed);
		assertEquals(6, diff.bytesToFetch);
		assertTrue(after.diffTo(after).isEmpty());
		assertFalse(diff.isEmpty());
	}
}
