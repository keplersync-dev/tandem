package app.keplersync.tandem.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Kills the sync engine at random moments and checks nothing is lost: each run builds two players' worlds and a dev
 * cloud, starts one step in its own JVM, kills it part way, recovers the way a launch does, finishes the step, and
 * checks every file. Not part of the normal build: {@code ./gradlew :core:killTest [-Pruns=300] [-Pseed=…]}.
 */
@Tag("kill")
class KillTest {
	static final String[] STEPS = {"checkpoint", "upload", "get"};

	/** Alex changed the world since Sam last got it; one step moves the change along. */
	static final class Fixture {
		final Path root;
		final Path alexWorld;
		final Path samWorld;
		final DevCloud cloud;
		final Sync alex;
		final Sync sam;
		final BlobCache alexCache;
		final BlobCache samCache;

		Fixture(Path root) {
			this.root = root;
			alexWorld = root.resolve("alex/saves/Sky Base");
			samWorld = root.resolve("sam/saves/Sky Base");
			cloud = new DevCloud(root.resolve("devcloud"));
			alexCache = new BlobCache(root.resolve("alex/saves/.tandem-cache"));
			samCache = new BlobCache(root.resolve("sam/saves/.tandem-cache"));
			alex = new Sync(cloud, alexCache, "Alex", dir -> Long.MAX_VALUE);
			sam = new Sync(cloud, samCache, "Sam", dir -> Long.MAX_VALUE);
		}

		String worldId() throws IOException {
			return LocalState.load(alexWorld).worldId;
		}

		void run(String step) throws IOException {
			switch (step) {
				case "checkpoint":
					alex.checkpoint(alexWorld, Snapshot.SESSION);
					break;
				case "upload":
					alex.upload(alexWorld, new Progress());
					break;
				case "get":
					sam.getLatest(samWorld, worldId(), new Progress());
					break;
				default:
					throw new IllegalArgumentException(step);
			}
		}

		/** Version 1 everywhere, then Alex's version 2 on Alex's disk, then whatever comes before [step]. */
		void build(Random r, String step) throws IOException {
			for (int i = 0; i < 16; i++) bytes(r, alexWorld.resolve("region/r." + i + ".0.mca"), 1_000_000 + r.nextInt(2_000_000));
			for (int i = 0; i < 150; i++) bytes(r, alexWorld.resolve("data/d" + i + ".dat"), 200 + r.nextInt(8_000));
			bytes(r, alexWorld.resolve("level.dat"), 2_000);
			bytes(r, alexWorld.resolve("data/DistantHorizons.sqlite"), 3_000_000);
			Cloud.World w = alex.firstUpload(alexWorld, "Sky Base", "1.21.1", new ArrayList<String>(), new ArrayList<String>(), new Progress());
			cloud.releaseLease(w.id, "Alex");
			sam.getLatest(samWorld, w.id, new Progress());
			bytes(r, samWorld.resolve("DIM-1/data/DistantHorizons.sqlite"), 2_000_000);

			for (int i = 0; i < 16; i += 2) bytes(r, alexWorld.resolve("region/r." + i + ".0.mca"), 1_000_000 + r.nextInt(2_000_000));
			for (int i = 0; i < 150; i += 3) bytes(r, alexWorld.resolve("data/d" + i + ".dat"), 200 + r.nextInt(8_000));
			for (int i = 0; i < 4; i++) bytes(r, alexWorld.resolve("region/r.0." + (i + 1) + ".mca"), 1_500_000);
			Files.delete(alexWorld.resolve("data/d1.dat"));
			Files.delete(alexWorld.resolve("data/d4.dat"));
			// The same bytes under a second name: linked or copied from the one the world already has.
			Files.copy(alexWorld.resolve("region/r.1.0.mca"), alexWorld.resolve("region/r.9.9.mca"));
			bytes(r, alexWorld.resolve("level.dat"), 2_000);

			if (step.equals("upload") || step.equals("get")) alex.checkpoint(alexWorld, Snapshot.SESSION);
			if (step.equals("get")) alex.upload(alexWorld, new Progress());
		}

		private static void bytes(Random r, Path file, int size) throws IOException {
			byte[] b = new byte[size];
			r.nextBytes(b);
			Files.createDirectories(file.getParent());
			Files.write(file, b);
		}
	}

	@TempDir
	Path tmp;

	@Test
	void nothingIsLostWhenTheGameIsKilled() throws Exception {
		int runs = Integer.parseInt(System.getProperty("tandem.kill.runs", "300"));
		String given = System.getProperty("tandem.kill.seed", "");
		long base = given.isEmpty() ? new Random().nextLong() : Long.parseLong(given);
		Map<String, Long> took = new TreeMap<>();
		int[] cut = new int[STEPS.length];
		for (int i = 0; i < runs; i++) {
			long seed = base + i;
			String step = STEPS[(int) Math.floorMod(seed, (long) STEPS.length)];
			Path dir = tmp.resolve("run" + i);
			try {
				if (!took.containsKey(step)) took.put(step, Math.max(50, time(dir.resolveSibling("time-" + step), seed, step)));
				Random r = new Random(seed);
				if (killedPartWay(dir, seed, step, (long) (r.nextDouble() * took.get(step) * 1.1))) cut[index(step)]++;
				WorldSwap.deleteTree(dir);
			} catch (Throwable e) {
				throw new AssertionError("Run " + i + " (" + step + ") failed; replay it with -Pseed=" + seed + " -Pruns=1", e);
			}
		}
		System.out.println("Kill test: " + runs + " runs from seed " + base + "; steps took " + took + " ms; killed part way "
				+ cut[0] + " checkpoints, " + cut[1] + " uploads, " + cut[2] + " gets");
	}

	private static int index(String step) {
		for (int i = 0; i < STEPS.length; i++) if (STEPS[i].equals(step)) return i;
		throw new IllegalArgumentException(step);
	}

	/** How long [step] takes with nothing killed. */
	private static long time(Path dir, long seed, String step) throws Exception {
		new Fixture(dir).build(new Random(seed), step);
		Process p = child(dir, step);
		BufferedReader out = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
		long ms = -1;
		for (String line; (line = out.readLine()) != null;) if (line.startsWith("done ")) ms = Long.parseLong(line.substring(5));
		assertEquals(0, p.waitFor(), "the step failed on its own");
		WorldSwap.deleteTree(dir);
		return ms;
	}

	private static Process child(Path dir, String step) throws IOException {
		String java = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
		return new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), KillChild.class.getName(), step, dir.toString())
				.redirectErrorStream(true)
				.start();
	}

	/** One run; true if the kill landed before the step finished. */
	private static boolean killedPartWay(Path dir, long seed, String step, long after) throws Exception {
		Fixture f = new Fixture(dir);
		f.build(new Random(seed), step);
		Map<String, String> v1 = tree(f.samWorld);
		Map<String, String> v2 = tree(f.alexWorld);
		Map<String, String> samIgnored = ignored(f.samWorld);

		Process p = child(dir, step);
		BufferedReader out = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
		String first = out.readLine();
		if (!"go".equals(first)) fail("The step didn't start: " + first);
		TimeUnit.MILLISECONDS.sleep(after);
		p.destroyForcibly();
		p.waitFor();
		boolean finished = false;
		for (String line; (line = out.readLine()) != null;) finished |= line.startsWith("done ");

		// The next launch: unfinished swaps first, then the step again.
		WorldSwap.recover(f.alexWorld);
		WorldSwap.recover(f.samWorld);
		blobsAreWhole(dir.resolve("alex/saves/.tandem-cache"));
		blobsAreWhole(dir.resolve("sam/saves/.tandem-cache"));
		assertEquals(v2, tree(f.alexWorld), "Alex's world changed");
		Map<String, String> sam = tree(f.samWorld);
		assertTrue(sam.equals(v1) || sam.equals(v2), "Sam's world is neither version");
		assertEquals(samIgnored, ignored(f.samWorld), "Sam's own level-of-detail data went");
		Manifest.Diff samChanges = f.sam.localChanges(f.samWorld);
		assertTrue(samChanges.isEmpty(), "Sam's world reads as changed outside Tandem: " + samChanges);

		switch (step) {
			case "checkpoint":
				f.alex.checkpoint(f.alexWorld, Snapshot.SESSION);
				LocalState st = LocalState.load(f.alexWorld);
				assertNotNull(st.pending);
				assertEquals(v2, shas(st.pending));
				for (String sha : v2.values()) assertTrue(f.alexCache.has(sha));
				f.alex.upload(f.alexWorld, new Progress());
				headIs(f, v2);
				break;
			case "upload":
				f.alex.upload(f.alexWorld, new Progress());
				headIs(f, v2);
				break;
			default:
				f.sam.getLatest(f.samWorld, f.worldId(), new Progress());
				assertEquals(v2, tree(f.samWorld));
				assertEquals(samIgnored, ignored(f.samWorld));
				LocalState s = LocalState.load(f.samWorld);
				assertEquals(f.cloud.world(f.worldId()).head, s.base.id);
				assertNull(s.pending);
				assertTrue(f.sam.localChanges(f.samWorld).isEmpty());
				assertFalse(Files.exists(WorldSwap.stageDir(f.samWorld)));
				assertFalse(Files.exists(WorldSwap.oldDir(f.samWorld)));
				assertFalse(Files.exists(WorldSwap.nextState(f.samWorld)));
		}
		return !finished;
	}

	private static void headIs(Fixture f, Map<String, String> want) throws IOException {
		Cloud.World w = f.cloud.world(f.worldId());
		assertTrue(w.branches.isEmpty(), "a retry split the world");
		Snapshot head = f.cloud.snapshot(f.worldId(), w.head);
		assertEquals(want, shas(head));
		blobsAreWhole(f.root.resolve("devcloud"));
		LocalState st = LocalState.load(f.alexWorld);
		assertNull(st.pending);
		assertEquals(w.head, st.base.id);
	}

	private static Map<String, String> shas(Snapshot s) {
		Map<String, String> out = new TreeMap<>();
		for (Map.Entry<String, Manifest.Entry> e : s.files.entrySet()) out.put(e.getKey(), e.getValue().sha256);
		return out;
	}

	/** Every stored file under [dir] that's named by a hash has that hash; half-written ones never get a name. */
	private static void blobsAreWhole(Path dir) throws IOException {
		if (!Files.isDirectory(dir)) return;
		final List<String> bad = new ArrayList<>();
		Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
				String name = file.getFileName().toString();
				if (name.matches("[0-9a-f]{64}") && !WorldScanner.sha256(file).equals(name)) bad.add(name);
				return FileVisitResult.CONTINUE;
			}
		});
		assertTrue(bad.isEmpty(), "damaged blobs: " + bad);
	}

	/** The world as it syncs: each file's hash, without what never syncs. */
	private static Map<String, String> tree(final Path world) throws IOException {
		return walk(world, false);
	}

	/** Only what never syncs (the marker aside): this PC's own data. */
	private static Map<String, String> ignored(final Path world) throws IOException {
		return walk(world, true);
	}

	private static Map<String, String> walk(final Path world, final boolean ignoredOnes) throws IOException {
		final Map<String, String> out = new TreeMap<>();
		if (!Files.isDirectory(world)) return out;
		final Ignores ignores = new Ignores(world.getFileSystem(), new ArrayList<String>());
		Files.walkFileTree(world, new SimpleFileVisitor<Path>() {
			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
				Path rel = world.relativize(file);
				if (rel.toString().equals(LocalState.MARKER)) return FileVisitResult.CONTINUE;
				if (ignores.matches(rel) == ignoredOnes) out.put(WorldScanner.key(rel), WorldScanner.sha256(file));
				return FileVisitResult.CONTINUE;
			}
		});
		return out;
	}
}
