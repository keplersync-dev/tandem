package app.keplersync.tandem.core;

import java.nio.file.Paths;

/** One sync step in its own JVM, for {@link KillTest} to kill part way. Prints "go" as the step starts. */
public final class KillChild {
	public static void main(String[] args) throws Exception {
		KillTest.Fixture f = new KillTest.Fixture(Paths.get(args[1]));
		System.out.println("go");
		System.out.flush();
		long start = System.nanoTime();
		f.run(args[0]);
		System.out.println("done " + (System.nanoTime() - start) / 1_000_000);
		System.out.flush();
	}

	private KillChild() {}
}
