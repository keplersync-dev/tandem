package app.keplersync.tandem.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Each recovery test recreates what the disk looks like after a crash at one step of {@link WorldSwap#swap}. */
class WorldSwapTest {
	@TempDir
	Path saves;
	Path world;

	@BeforeEach
	void setUp() {
		world = saves.resolve("Create Nights");
	}

	private static void level(Path dir, String content) throws IOException {
		Files.createDirectories(dir);
		Files.write(dir.resolve("level.dat"), content.getBytes(StandardCharsets.UTF_8));
	}

	private static String read(Path dir) throws IOException {
		return new String(Files.readAllBytes(dir.resolve("level.dat")), StandardCharsets.UTF_8);
	}

	private void journal() throws IOException {
		Files.createDirectories(WorldSwap.workDir(world));
		Files.write(WorldSwap.journal(world), new byte[0]);
	}

	private void assertClean() {
		assertFalse(Files.exists(WorldSwap.journal(world)));
		assertFalse(Files.exists(WorldSwap.oldDir(world)));
	}

	@Test
	void swapReplacesTheWorld() throws IOException {
		level(world, "old");
		level(WorldSwap.stageDir(world), "new");

		WorldSwap.swap(world);

		assertEquals("new", read(world));
		assertFalse(Files.exists(WorldSwap.stageDir(world)));
		assertClean();
	}

	@Test
	void swapWorksForAWorldThatIsNotHereYet() throws IOException {
		level(WorldSwap.stageDir(world), "new");

		WorldSwap.swap(world);

		assertEquals("new", read(world));
		assertClean();
	}

	@Test
	void swapRefusesWithoutAStage() throws IOException {
		level(world, "old");

		assertThrows(IOException.class, () -> WorldSwap.swap(world));
		assertEquals("old", read(world));
	}

	@Test
	void crashAfterJournalKeepsTheOldWorldAndTheStage() throws IOException {
		level(world, "old");
		level(WorldSwap.stageDir(world), "new");
		journal();

		assertEquals(WorldSwap.Recovery.ABORTED, WorldSwap.recover(world));
		assertEquals("old", read(world));
		assertEquals("new", read(WorldSwap.stageDir(world)));
		assertClean();
	}

	@Test
	void crashAfterMovingTheOldWorldFinishesTheSwap() throws IOException {
		level(WorldSwap.oldDir(world), "old");
		level(WorldSwap.stageDir(world), "new");
		journal();

		assertEquals(WorldSwap.Recovery.FINISHED, WorldSwap.recover(world));
		assertEquals("new", read(world));
		assertClean();
	}

	@Test
	void crashAfterMovingTheStageCleansUp() throws IOException {
		level(WorldSwap.oldDir(world), "old");
		level(world, "new");
		journal();

		assertEquals(WorldSwap.Recovery.FINISHED, WorldSwap.recover(world));
		assertEquals("new", read(world));
		assertClean();
	}

	@Test
	void missingStagePutsTheOldWorldBack() throws IOException {
		level(WorldSwap.oldDir(world), "old");
		journal();

		assertEquals(WorldSwap.Recovery.ROLLED_BACK, WorldSwap.recover(world));
		assertEquals("old", read(world));
		assertClean();
	}

	@Test
	void leftoverOldCopyIsDeletedWithoutAJournal() throws IOException {
		level(world, "new");
		level(WorldSwap.oldDir(world), "old");

		assertEquals(WorldSwap.Recovery.NOTHING, WorldSwap.recover(world));
		assertEquals("new", read(world));
		assertClean();
	}

	private void nextState(String worldId) throws IOException {
		Files.createDirectories(WorldSwap.workDir(world));
		Files.write(WorldSwap.nextState(world), ("{\"worldId\":\"" + worldId + "\"}").getBytes(StandardCharsets.UTF_8));
	}

	private String stateWorldId() throws IOException {
		return new String(Files.readAllBytes(LocalState.file(world)), StandardCharsets.UTF_8).replaceAll("(?s).*\"worldId\":\s*\"([^\"]*)\".*", "$1");
	}

	@Test
	void theNewCopysNotesArriveWithTheSwap() throws IOException {
		level(world, "old");
		level(WorldSwap.stageDir(world), "new");
		nextState("new-notes");
		WorldSwap.swap(world);
		assertEquals("new-notes", stateWorldId());
		assertFalse(Files.exists(WorldSwap.nextState(world)));
	}

	@Test
	void notesForASwapThatNeverStartedAreDropped() throws IOException {
		level(world, "old");
		level(WorldSwap.stageDir(world), "new");
		nextState("new-notes");
		assertEquals(WorldSwap.Recovery.NOTHING, WorldSwap.recover(world));
		assertFalse(Files.exists(WorldSwap.nextState(world)));
		assertFalse(Files.exists(LocalState.file(world)));
	}

	@Test
	void notesArriveWhenRecoveryFinishesTheSwap() throws IOException {
		level(WorldSwap.stageDir(world), "new");
		nextState("new-notes");
		journal();
		assertEquals(WorldSwap.Recovery.FINISHED, WorldSwap.recover(world));
		assertEquals("new", read(world));
		assertEquals("new-notes", stateWorldId());
	}
}
