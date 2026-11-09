package app.keplersync.tandem.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/** Every file in a world: its path (with `/` separators), size and sha256. */
public final class Manifest {
	public static final class Entry {
		public final long size;
		public final String sha256;

		public Entry(long size, String sha256) {
			this.size = size;
			this.sha256 = sha256;
		}

		@Override
		public boolean equals(Object o) {
			if (!(o instanceof Entry)) return false;
			Entry e = (Entry) o;
			return size == e.size && sha256.equals(e.sha256);
		}

		@Override
		public int hashCode() {
			return sha256.hashCode();
		}
	}

	/** What turns one manifest into another. */
	public static final class Diff {
		public final List<String> added;
		public final List<String> changed;
		public final List<String> removed;
		/** Bytes of the added and changed files, which are what a download fetches. */
		public final long bytesToFetch;

		Diff(List<String> added, List<String> changed, List<String> removed, long bytesToFetch) {
			this.added = Collections.unmodifiableList(added);
			this.changed = Collections.unmodifiableList(changed);
			this.removed = Collections.unmodifiableList(removed);
			this.bytesToFetch = bytesToFetch;
		}

		public boolean isEmpty() {
			return added.isEmpty() && changed.isEmpty() && removed.isEmpty();
		}
	}

	private final SortedMap<String, Entry> files;

	public Manifest(Map<String, Entry> files) {
		this.files = Collections.unmodifiableSortedMap(new TreeMap<>(files));
	}

	public SortedMap<String, Entry> files() {
		return files;
	}

	public long totalBytes() {
		long total = 0;
		for (Entry e : files.values()) total += e.size;
		return total;
	}

	public Diff diffTo(Manifest target) {
		List<String> added = new ArrayList<>();
		List<String> changed = new ArrayList<>();
		List<String> removed = new ArrayList<>();
		long bytes = 0;
		for (Map.Entry<String, Entry> t : target.files.entrySet()) {
			Entry mine = files.get(t.getKey());
			if (mine == null) {
				added.add(t.getKey());
				bytes += t.getValue().size;
			} else if (!mine.equals(t.getValue())) {
				changed.add(t.getKey());
				bytes += t.getValue().size;
			}
		}
		for (String path : files.keySet()) {
			if (!target.files.containsKey(path)) removed.add(path);
		}
		return new Diff(added, changed, removed, bytes);
	}
}
