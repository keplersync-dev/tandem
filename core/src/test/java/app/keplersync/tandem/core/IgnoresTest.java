package app.keplersync.tandem.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IgnoresTest {
	@TempDir
	Path world;

	@Test
	void theSizeThatSyncsLeavesOutWhatNeverSyncs() throws IOException {
		Files.write(world.resolve("level.dat"), new byte[100]);
		Files.createDirectories(world.resolve("region"));
		Files.write(world.resolve("region/r.0.0.mca"), new byte[1000]);
		Files.write(world.resolve("session.lock"), new byte[7]);
		Files.createDirectories(world.resolve("DIM-1/data"));
		Files.write(world.resolve("DIM-1/data/DistantHorizons.sqlite"), new byte[50_000]);
		Files.createDirectories(world.resolve("voxy/cache"));
		Files.write(world.resolve("voxy/cache/a.bin"), new byte[20_000]);
		assertEquals(1100, Ignores.syncedBytes(world));
	}
}
