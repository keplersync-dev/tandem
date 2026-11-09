package app.keplersync.tandem.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalStateTest {
	@TempDir
	Path saves;

	@Test
	void droppingALinkLeavesANormalWorld() throws IOException {
		Path world = saves.resolve("Sky Base");
		Files.createDirectories(world);
		Files.write(world.resolve("level.dat"), "map".getBytes(StandardCharsets.UTF_8));
		LocalState st = new LocalState();
		st.worldId = "w1";
		st.save(world);
		Files.createDirectories(WorldSwap.stageDir(world));
		Files.write(WorldSwap.stageDir(world).resolve("level.dat"), "staged".getBytes(StandardCharsets.UTF_8));
		assertTrue(LocalState.load(world).synced());

		LocalState.drop(world);

		assertFalse(LocalState.load(world).synced());
		assertFalse(Files.exists(world.resolve(LocalState.MARKER)));
		assertFalse(Files.exists(WorldSwap.workDir(world)));
		assertEquals("map", new String(Files.readAllBytes(world.resolve("level.dat")), StandardCharsets.UTF_8), "the world itself stays");
	}
}
