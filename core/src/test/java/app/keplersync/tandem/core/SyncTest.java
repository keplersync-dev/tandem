package app.keplersync.tandem.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Two players, each with their own saves folder, passing one world through a dev cloud. */
class SyncTest {
	@TempDir
	Path tmp;
	Path cloudDir;
	Path alexWorld;
	Path samWorld;
	DevCloud cloud;
	Sync alex;
	Sync sam;

	@BeforeEach
	void setUp() throws IOException {
		cloudDir = tmp.resolve("devcloud");
		alexWorld = tmp.resolve("alex/saves/Sky Base");
		samWorld = tmp.resolve("sam/saves/Sky Base");
		cloud = new DevCloud(cloudDir);
		alex = new Sync(cloud, new BlobCache(tmp.resolve("alex/saves/.tandem-cache")), "Alex");
		sam = new Sync(cloud, new BlobCache(tmp.resolve("sam/saves/.tandem-cache")), "Sam");
		write(alexWorld, "level.dat", "level 1");
		write(alexWorld, "region/r.0.0.mca", "chunks 1");
		write(alexWorld, "playerdata/alex.dat", "inventory");
		write(alexWorld, "session.lock", "locked");
		write(alexWorld, "data/DistantHorizons.sqlite", "alex's LODs");
	}

	@AfterEach
	void tearDown() {
		Io.lockedRetryMs = 10_000;
	}

	private static void write(Path world, String rel, String content) throws IOException {
		Path f = world.resolve(rel);
		Files.createDirectories(f.getParent());
		Files.write(f, content.getBytes(StandardCharsets.UTF_8));
	}

	private static String read(Path world, String rel) throws IOException {
		return new String(Files.readAllBytes(world.resolve(rel)), StandardCharsets.UTF_8);
	}

	private String upload() throws IOException {
		Cloud.World w = alex.firstUpload(alexWorld, "Sky Base", "1.21.1", List.of(), List.of("Sam"), new Progress());
		cloud.releaseLease(w.id, "Alex");
		return w.id;
	}

	@Test
	void aWorldGoesBackAndForth() throws IOException {
		String id = upload();
		sam.getLatest(samWorld, id, new Progress());
		assertEquals("chunks 1", read(samWorld, "region/r.0.0.mca"));
		assertFalse(Files.exists(samWorld.resolve("session.lock")));
		assertFalse(Files.exists(samWorld.resolve("data/DistantHorizons.sqlite")));

		write(samWorld, "region/r.0.0.mca", "chunks 2, Sam built a house");
		write(samWorld, "data/DistantHorizons.sqlite", "sam's LODs");
		assertTrue(sam.checkpoint(samWorld, Snapshot.SESSION) != null);
		assertTrue(sam.upload(samWorld, new Progress()));

		alex.getLatest(alexWorld, id, new Progress());
		assertEquals("chunks 2, Sam built a house", read(alexWorld, "region/r.0.0.mca"));
		assertEquals("alex's LODs", read(alexWorld, "data/DistantHorizons.sqlite"));
		assertEquals("inventory", read(alexWorld, "playerdata/alex.dat"));
		assertEquals(0, alex.localChanges(alexWorld).added.size() + alex.localChanges(alexWorld).changed.size());
	}

	@Test
	void aSaveNamingFilesOutsideTheWorldWritesNothing() throws IOException {
		String id = upload();
		Snapshot head = cloud.snapshot(id, cloud.world(id).head);
		// Another member's save, made by hand: one name climbs out of the world, one is an absolute path (C:/… on Windows).
		Path absolute = tmp.resolve("sam/absolute.txt");
		for (String name : new String[] {"../../../escaped.txt", absolute.toString().replace('\\', '/')}) {
			Snapshot bad = new Snapshot(cloud.world(id).head, "Mallory", Snapshot.SESSION, head.manifest());
			bad.files.put(name, head.files.get("level.dat"));
			cloud.commit(id, bad);
			assertThrows(IOException.class, () -> sam.getLatest(samWorld, id, new Progress()), name);
			assertThrows(IOException.class, () -> sam.exportZip(id, bad.id, "Sky Base", tmp.resolve("sam/Sky Base.zip"), new Progress()), name);
		}
		assertFalse(Files.exists(tmp.resolve("sam/saves/escaped.txt")));
		assertFalse(Files.exists(absolute));
		assertFalse(Files.exists(samWorld));
	}

	@Test
	void aCheckpointMadeDuringAnUploadIsKeptAndBuiltOnIt() throws IOException {
		String id = upload();
		write(alexWorld, "region/r.0.0.mca", "chunks 2");
		alex.checkpoint(alexWorld, Snapshot.SESSION);
		// While KeplerSync saves that session, the game saves again (a long upload, a checkpoint every 5 minutes).
		DevCloud racing = new DevCloud(cloudDir) {
			@Override
			public Committed commit(String world, Snapshot snapshot) throws IOException {
				write(alexWorld, "region/r.0.0.mca", "chunks 3, saved during the upload");
				alex.checkpoint(alexWorld, Snapshot.CHECKPOINT);
				return super.commit(world, snapshot);
			}
		};
		new Sync(racing, new BlobCache(tmp.resolve("alex/saves/.tandem-cache")), "Alex").upload(alexWorld, new Progress());
		LocalState st = LocalState.load(alexWorld);
		assertTrue(st.pending != null, "the checkpoint saved meanwhile is still there");
		assertEquals(st.base.id, st.pending.parent, "and built on the session just sent");
		assertTrue(alex.upload(alexWorld, new Progress()), "so it lands as the latest, not as a second version");
		assertTrue(cloud.world(id).branches.isEmpty());
		sam.getLatest(samWorld, id, new Progress());
		assertEquals("chunks 3, saved during the upload", read(samWorld, "region/r.0.0.mca"));
	}

	@Test
	void syncThisWorldAskedTwiceMakesOneWorld() throws IOException {
		// The world was made but the answer never came (or the game closed first): the request is noted on this PC.
		DevCloud lost = new DevCloud(cloudDir) {
			boolean answered;

			@Override
			public World createWorld(String key, String name, String mcVersion, List<String> mods, String player, List<String> sharedWith)
					throws IOException {
				World w = super.createWorld(key, name, mcVersion, mods, player, sharedWith);
				if (!answered) {
					answered = true;
					throw new IOException("the answer was lost");
				}
				return w;
			}
		};
		Sync s = new Sync(lost, new BlobCache(tmp.resolve("alex/saves/.tandem-cache")), "Alex");
		assertThrows(IOException.class, () -> s.firstUpload(alexWorld, "Sky Base", "1.21.1", List.of(), List.of("Sam"), new Progress()));
		assertTrue(LocalState.load(alexWorld).creating != null);
		Cloud.World w = s.resumeFirstUpload(alexWorld, "1.21.1", List.of(), new Progress());
		assertEquals(1, cloud.worlds("Alex").size());
		assertEquals(List.of("Sam"), w.sharedWith);
		assertEquals(w.id, LocalState.load(alexWorld).worldId);
		assertNull(LocalState.load(alexWorld).creating);
		assertTrue(cloud.world(w.id).head != null);
		// A request left by a world deleted since doesn't carry over to a new world made in its folder.
		Path other = tmp.resolve("alex/saves/Other");
		write(other, "level.dat", "other");
		LocalState half = new LocalState();
		half.creating = new LocalState.Creating("key-2", "Other", List.of());
		half.save(other);
		Files.delete(other.resolve(LocalState.MARKER));
		assertNull(LocalState.load(other).creating);
	}

	@Test
	void onlyNamesEverySystemCanHoldAreSynced() {
		for (String ok : new String[] {"level.dat", "region/r.0.0.mca", "DIM-1/data/raids.dat", "stats/a b.json", "data/console.dat"}) {
			assertTrue(Sync.portable(ok), ok);
		}
		for (String bad : new String[] {"data/minecraft:thing.dat", "a|b", "data/CON", "nul.txt", "COM1", "a/b.", "a/b ", "a\u0001b", "a\\b"}) {
			assertFalse(Sync.portable(bad), bad);
		}
	}

	@Test
	void aNewWorldInADeletedSyncedWorldsFolderIsntSynced() throws IOException {
		upload();
		assertTrue(LocalState.load(alexWorld).synced());
		// Deleted in Minecraft's own list, then a new world made with the same name.
		WorldSwap.deleteTree(alexWorld);
		assertFalse(LocalState.load(alexWorld).synced(), "nothing to sync any more");
		write(alexWorld, "level.dat", "a brand new world");
		assertFalse(LocalState.load(alexWorld).synced());
	}

	@Test
	void aWorldSyncedBeforeTheMarkerStaysSynced() throws IOException {
		upload();
		Files.delete(alexWorld.resolve(LocalState.MARKER));
		assertTrue(LocalState.load(alexWorld).synced());
	}

	@Test
	void aWorldComesOutAsAZip() throws IOException {
		String id = upload();
		Path zip = tmp.resolve("sam/Downloads/Sky Base.zip");
		// Sam has none of its files yet: they're fetched first.
		sam.exportZip(id, cloud.world(id).head, "Sky Base", zip, new Progress());
		java.util.Map<String, String> entries = new java.util.TreeMap<>();
		try (java.util.zip.ZipInputStream in = new java.util.zip.ZipInputStream(Files.newInputStream(zip))) {
			for (java.util.zip.ZipEntry e; (e = in.getNextEntry()) != null; ) {
				java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
				byte[] buf = new byte[4096];
				for (int n; (n = in.read(buf)) != -1; ) b.write(buf, 0, n);
				entries.put(e.getName(), new String(b.toByteArray(), StandardCharsets.UTF_8));
			}
		}
		assertEquals("chunks 1", entries.get("Sky Base/region/r.0.0.mca"));
		assertEquals("inventory", entries.get("Sky Base/playerdata/alex.dat"));
		assertFalse(entries.containsKey("Sky Base/session.lock"), "what isn't synced isn't in it");
		assertFalse(Files.exists(zip.resolveSibling("Sky Base.zip.part")));
	}

	@Test
	void anUnchangedWorldMakesNoCheckpoint() throws IOException {
		upload();
		assertNull(alex.checkpoint(alexWorld, Snapshot.CHECKPOINT));
	}

	@Test
	void aCheckpointOnlyReadsFilesThatChanged() throws IOException {
		upload();
		Path level = alexWorld.resolve("level.dat");
		FileTime time = Files.getLastModifiedTime(level);
		// Same size and time but different bytes: only a re-read would notice.
		write(alexWorld, "level.dat", "level X");
		Files.setLastModifiedTime(level, time);
		write(alexWorld, "region/r.0.0.mca", "chunks 3");

		Snapshot s = alex.checkpoint(alexWorld, Snapshot.CHECKPOINT);
		Snapshot before = LocalState.load(alexWorld).base;
		assertEquals(before.files.get("level.dat"), s.files.get("level.dat"));
		assertFalse(before.files.get("region/r.0.0.mca").equals(s.files.get("region/r.0.0.mca")));
	}

	@Test
	void changesMadeOutsideTandemAreNoticed() throws IOException {
		String id = upload();
		sam.getLatest(samWorld, id, new Progress());
		assertTrue(sam.localChanges(samWorld).isEmpty());
		write(samWorld, "region/r.1.0.mca", "opened from the Singleplayer list");
		assertEquals(List.of("region/r.1.0.mca"), sam.localChanges(samWorld).added);
	}

	@Test
	void notEnoughSpaceTouchesNothing() throws IOException {
		String id = upload();
		Sync full = new Sync(cloud, new BlobCache(tmp.resolve("sam/saves/.tandem-cache")), "Sam", dir -> 10);
		Sync.NotEnoughSpace e = assertThrows(Sync.NotEnoughSpace.class, () -> full.getLatest(samWorld, id, new Progress()));
		assertTrue(e.needed > e.free);
		assertFalse(Files.exists(samWorld));
	}

	@Test
	void aDamagedFileIsFetchedAgain() throws IOException {
		String id = upload();
		AtomicInteger opens = new AtomicInteger();
		DevCloud flaky = new DevCloud(cloudDir) {
			@Override
			public InputStream openBlob(String world, String sha) throws IOException {
				if (opens.incrementAndGet() == 1) return new ByteArrayInputStream("garbage".getBytes(StandardCharsets.UTF_8));
				return super.openBlob(world, sha);
			}
		};
		new Sync(flaky, new BlobCache(tmp.resolve("sam/saves/.tandem-cache")), "Sam").getLatest(samWorld, id, new Progress());
		assertEquals("chunks 1", read(samWorld, "region/r.0.0.mca"));
		assertEquals(4, opens.get());
	}

	@Test
	void aFileDamagedThreeTimesStops() throws IOException {
		String id = upload();
		DevCloud broken = new DevCloud(cloudDir) {
			@Override
			public InputStream openBlob(String world, String sha) {
				return new ByteArrayInputStream("garbage".getBytes(StandardCharsets.UTF_8));
			}
		};
		BlobCache cache = new BlobCache(tmp.resolve("sam/saves/.tandem-cache"));
		assertThrows(Sync.Damaged.class, () -> new Sync(broken, cache, "Sam").getLatest(samWorld, id, new Progress()));
		assertFalse(Files.exists(samWorld));
	}

	@Test
	void aCancelledDownloadResumes() throws IOException {
		String id = upload();
		Progress first = new Progress();
		AtomicInteger opens = new AtomicInteger();
		DevCloud counting = new DevCloud(cloudDir) {
			@Override
			public InputStream openBlob(String world, String sha) throws IOException {
				if (opens.incrementAndGet() == 2) first.cancel();
				return super.openBlob(world, sha);
			}
		};
		BlobCache cache = new BlobCache(tmp.resolve("sam/saves/.tandem-cache"));
		Sync s = new Sync(counting, cache, "Sam");
		assertThrows(CancellationException.class, () -> s.getLatest(samWorld, id, first));
		assertFalse(Files.exists(samWorld));

		// Three files, fetched a few at a time: whichever finished before the cancel isn't fetched again.
		int kept = 0;
		for (Manifest.Entry e : cloud.snapshot(id, cloud.world(id).head).files.values()) if (cache.has(e.sha256)) kept++;
		opens.set(10);
		s.getLatest(samWorld, id, new Progress());
		assertEquals(10 + 3 - kept, opens.get());
		assertEquals("inventory", read(samWorld, "playerdata/alex.dat"));
	}

	@Test
	void aLockedFileIsWaitedFor() throws Exception {
		assumeTrue(System.getProperty("os.name").startsWith("Windows"), "Only Windows locks files against reading");
		upload();
		write(alexWorld, "region/r.0.0.mca", "chunks 4");
		Io.lockedRetryMs = 3_000;
		try (RandomAccessFile raf = new RandomAccessFile(alexWorld.resolve("region/r.0.0.mca").toFile(), "rw")) {
			FileChannel ch = raf.getChannel();
			FileLock lock = ch.lock();
			Thread release = new Thread(() -> {
				try {
					Thread.sleep(400);
					lock.release();
				} catch (Exception ignored) {
				}
			});
			release.start();
			assertTrue(alex.checkpoint(alexWorld, Snapshot.CHECKPOINT) != null);
			release.join();
		}
	}

	@Test
	void aFileLockedTooLongIsNamed() throws Exception {
		assumeTrue(System.getProperty("os.name").startsWith("Windows"), "Only Windows locks files against reading");
		upload();
		write(alexWorld, "region/r.0.0.mca", "chunks 5");
		Io.lockedRetryMs = 300;
		try (RandomAccessFile raf = new RandomAccessFile(alexWorld.resolve("region/r.0.0.mca").toFile(), "rw");
				FileLock ignored = raf.getChannel().lock()) {
			IOException e = assertThrows(IOException.class, () -> alex.checkpoint(alexWorld, Snapshot.CHECKPOINT));
			assertTrue(e.getMessage().startsWith("region/r.0.0.mca is in use"), e.getMessage());
		}
	}

	@Test
	void anUnsentSessionSurvivesUntilUploaded() throws IOException {
		String id = upload();
		write(alexWorld, "region/r.0.0.mca", "chunks 6");
		alex.checkpoint(alexWorld, Snapshot.SESSION);
		// The game closed here; the next launch finds the session and sends it.
		assertTrue(LocalState.load(alexWorld).pending != null);
		Sync nextLaunch = new Sync(cloud, new BlobCache(tmp.resolve("alex/saves/.tandem-cache")), "Alex");
		nextLaunch.upload(alexWorld, new Progress());
		sam.getLatest(samWorld, id, new Progress());
		assertEquals("chunks 6", read(samWorld, "region/r.0.0.mca"));
	}

	@Test
	void onlyFilesTheWorldLacksAreDownloaded() throws IOException {
		String id = upload();
		sam.getLatest(samWorld, id, new Progress());
		write(alexWorld, "region/r.0.0.mca", "chunks 7");
		alex.checkpoint(alexWorld, Snapshot.SESSION);
		alex.upload(alexWorld, new Progress());

		AtomicInteger opens = new AtomicInteger();
		DevCloud counting = new DevCloud(cloudDir) {
			@Override
			public InputStream openBlob(String world, String sha) throws IOException {
				opens.incrementAndGet();
				return super.openBlob(world, sha);
			}
		};
		// An empty cache: what Sam's world already has comes from the world itself.
		new Sync(counting, new BlobCache(tmp.resolve("sam/empty-cache")), "Sam").getLatest(samWorld, id, new Progress());
		assertEquals(1, opens.get());
		assertEquals("chunks 7", read(samWorld, "region/r.0.0.mca"));
		assertEquals("inventory", read(samWorld, "playerdata/alex.dat"));
		assertEquals(0, sam.localChanges(samWorld).added.size() + sam.localChanges(samWorld).changed.size());
	}

	@Test
	void aCommitSentAgainAfterItsAnswerWasLostIsSavedOnce() throws IOException {
		String id = upload();
		write(alexWorld, "region/r.0.0.mca", "chunks 8");
		Snapshot s = alex.checkpoint(alexWorld, Snapshot.SESSION);
		DevCloud lost = new DevCloud(cloudDir) {
			@Override
			public Committed commit(String world, Snapshot snapshot) throws IOException {
				super.commit(world, snapshot);
				throw new IOException("The connection dropped before the answer arrived");
			}
		};
		BlobCache cache = new BlobCache(tmp.resolve("alex/saves/.tandem-cache"));
		assertThrows(IOException.class, () -> new Sync(lost, cache, "Alex").upload(alexWorld, new Progress()));
		assertTrue(alex.upload(alexWorld, new Progress()));
		Cloud.World w = cloud.world(id);
		assertEquals(s.id, w.head);
		assertTrue(w.branches.isEmpty(), "no split from a retry");
	}

	@Test
	void theCacheKeepsOnlyWhatWorldsStillUse() throws IOException {
		upload();
		BlobCache cache = new BlobCache(tmp.resolve("alex/saves/.tandem-cache"));
		String stray = cache.add(tmp.resolve("alex/saves/Sky Base/level.dat"), "level.dat", null);
		write(alexWorld, "level.dat", "level 2");
		alex.checkpoint(alexWorld, Snapshot.CHECKPOINT);
		Set<String> inUse = LocalState.inUse(tmp.resolve("alex/saves"));
		assertTrue(inUse.contains(stray), "the uploaded snapshot still uses level 1");

		write(alexWorld, "level.dat", "level 3");
		alex.checkpoint(alexWorld, Snapshot.CHECKPOINT);
		String level2 = WorldScanner.sha256(writeTemp("level 2"));
		assertTrue(cache.has(level2));
		assertEquals(0, cache.prune(LocalState.inUse(tmp.resolve("alex/saves")), 60_000), "too new to go");
		assertTrue(cache.prune(LocalState.inUse(tmp.resolve("alex/saves")), 0) > 0);
		assertFalse(cache.has(level2), "a checkpoint replaced by a newer one");
		assertTrue(cache.has(stray));
		alex.upload(alexWorld, new Progress());
	}

	private Path writeTemp(String content) throws IOException {
		Path f = Files.createTempFile(tmp, "f", ".txt");
		Files.write(f, content.getBytes(StandardCharsets.UTF_8));
		return f;
	}

	@Test
	void levelOfDetailDataIsIgnored() {
		Ignores ignores = new Ignores(tmp.getFileSystem(), List.of());
		for (String p : new String[] {"data/DistantHorizons.sqlite", "DIM-1/data/DistantHorizons.sqlite-wal",
				"dimensions/aether/the_aether/data/DistantHorizons.sqlite", "voxy/db3bee0b/storage/MANIFEST-000005", "voxyserver/x.db"}) {
			assertTrue(ignores.matches(tmp.getFileSystem().getPath(p)), p);
		}
		assertFalse(ignores.matches(tmp.getFileSystem().getPath("data/raids.dat")));
	}
}
