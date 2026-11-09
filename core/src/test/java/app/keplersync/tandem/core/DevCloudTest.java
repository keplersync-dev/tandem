package app.keplersync.tandem.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DevCloudTest {
	@TempDir
	Path root;
	final AtomicLong now = new AtomicLong(1_000_000);
	DevCloud alex;
	DevCloud sam;
	Cloud.World world;

	@BeforeEach
	void setUp() throws IOException {
		// Two games sharing one folder, as two dev clients do.
		alex = new DevCloud(root, now::get);
		sam = new DevCloud(root, now::get);
		world = alex.createWorld("key-1", "Sky Base", "1.21.1", List.of("create@0.5.1"), "Alex", List.of("Sam"));
		alex.releaseLease(world.id, "Alex");
	}

	@Test
	void onlyOnePlayerGetsTheLease() throws IOException {
		assertTrue(alex.takeLease(world.id, "Alex", Cloud.HOSTING).heldBy("Alex"));
		Cloud.Lease seen = sam.takeLease(world.id, "Sam", Cloud.DOWNLOADING);
		assertEquals("Alex", seen.holder);
		assertEquals(Cloud.HOSTING, seen.state);
	}

	@Test
	void aLeaseRunsOutWithoutHeartbeats() throws IOException {
		alex.takeLease(world.id, "Alex", Cloud.HOSTING);
		now.addAndGet(DevCloud.LEASE_MS - 1);
		assertTrue(alex.heartbeat(world.id, "Alex", Cloud.HOSTING, 0));
		now.addAndGet(DevCloud.LEASE_MS + 1);
		assertNull(sam.lease(world.id));
		assertTrue(sam.takeLease(world.id, "Sam", Cloud.DOWNLOADING).heldBy("Sam"));
		assertFalse(alex.heartbeat(world.id, "Alex", Cloud.HOSTING, 0));
	}

	@Test
	void anUploadKeepsItsLeaseForADay() throws IOException {
		alex.takeLease(world.id, "Alex", Cloud.UPLOADING);
		now.addAndGet(DevCloud.UPLOADING_MS - 1);
		assertEquals("Alex", sam.takeLease(world.id, "Sam", Cloud.DOWNLOADING).holder);
	}

	@Test
	void onlyTheHolderReleases() throws IOException {
		alex.takeLease(world.id, "Alex", Cloud.HOSTING);
		sam.releaseLease(world.id, "Sam");
		assertEquals("Alex", alex.lease(world.id).holder);
		alex.releaseLease(world.id, "Alex");
		assertNull(alex.lease(world.id));
	}

	@Test
	void worldsAreListedForTheirPeopleOnly() throws IOException {
		assertEquals(1, sam.worlds("Sam").size());
		assertEquals(0, sam.worlds("Noor").size());
	}

	@Test
	void aStaleParentKeepsBothSessions() throws IOException {
		Snapshot first = snapshot(null, "a");
		assertTrue(alex.commit(world.id, first).head);
		Snapshot alexs = snapshot(first.id, "b");
		Snapshot sams = snapshot(first.id, "c");
		assertTrue(alex.commit(world.id, alexs).head);
		assertFalse(sam.commit(world.id, sams).head);

		Cloud.World w = sam.world(world.id);
		assertEquals(sams.id, w.head);
		assertEquals(List.of(alexs.id), w.branches);
		assertEquals(alexs.id, sam.snapshot(world.id, alexs.id).id);
	}

	@Test
	void aSnapshotNeedsItsBlobs() {
		Snapshot s = new Snapshot(null, "Alex", Snapshot.SESSION, new Manifest(Map.of("level.dat", new Manifest.Entry(1, "00ff"))));
		assertThrows(IOException.class, () -> alex.commit(world.id, s));
	}

	private Snapshot snapshot(String parent, String content) throws IOException {
		Path f = Files.createTempFile(root, "f", ".txt");
		Files.write(f, content.getBytes(StandardCharsets.UTF_8));
		String sha = WorldScanner.sha256(f);
		alex.putBlob(sha, f);
		Map<String, Manifest.Entry> files = new TreeMap<>();
		files.put("level.dat", new Manifest.Entry(Files.size(f), sha));
		return new Snapshot(parent, "Alex", Snapshot.SESSION, new Manifest(files));
	}
}
