package dev.steelaspect.cytrabackups.core.diff;

import dev.steelaspect.cytrabackups.core.manifest.ChunkRef;
import dev.steelaspect.cytrabackups.core.manifest.Manifest;
import dev.steelaspect.cytrabackups.core.manifest.ManifestEntry;
import dev.steelaspect.cytrabackups.core.manifest.RegionEntry;
import dev.steelaspect.cytrabackups.core.region.RegionFiles;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** File- and chunk-level comparison of two manifests. */
public final class BackupDiff {
	public record ChunkPos(int x, int z) {
	}

	public record RegionChange(int added, int removed, int modified, List<ChunkPos> chunks) {
		public int total() {
			return added + removed + modified;
		}
	}

	public record FileChange(String path, long oldSize, long newSize, RegionChange region) {
	}

	public record Result(List<String> added, List<String> removed, List<FileChange> changed, int unchanged,
						 long changedChunks) {
		public boolean isEmpty() {
			return added.isEmpty() && removed.isEmpty() && changed.isEmpty();
		}
	}

	private BackupDiff() {
	}

	public static Result compare(Manifest older, Manifest newer, int maxChunksPerRegion) {
		List<String> added = new ArrayList<>();
		List<String> removed = new ArrayList<>();
		List<FileChange> changed = new ArrayList<>();
		int unchanged = 0;
		long changedChunks = 0;
		for (ManifestEntry n : newer.entries()) {
			ManifestEntry o = older.get(n.path());
			if (o == null) {
				added.add(n.path());
			} else if (!o.contentHash().equals(n.contentHash())) {
				RegionChange rc = null;
				if (o instanceof RegionEntry ro && n instanceof RegionEntry rn) {
					rc = compareRegions(ro, rn, maxChunksPerRegion);
					changedChunks += rc.total();
				}
				changed.add(new FileChange(n.path(), o.size(), n.size(), rc));
			} else {
				unchanged++;
			}
		}
		for (ManifestEntry o : older.entries()) {
			if (newer.get(o.path()) == null) removed.add(o.path());
		}
		return new Result(added, removed, changed, unchanged, changedChunks);
	}

	static RegionChange compareRegions(RegionEntry older, RegionEntry newer, int maxChunks) {
		int[] rc = RegionFiles.regionCoords(newer.fileName());
		Map<Integer, ChunkRef> old = new HashMap<>();
		for (ChunkRef c : older.chunks()) old.put(c.index(), c);
		int added = 0, removed = 0, modified = 0;
		List<ChunkPos> positions = new ArrayList<>();
		for (ChunkRef c : newer.chunks()) {
			ChunkRef o = old.remove(c.index());
			boolean diff;
			if (o == null) {
				added++;
				diff = true;
			} else if (!o.blob().hash().equals(c.blob().hash())) {
				modified++;
				diff = true;
			} else {
				diff = false;
			}
			if (diff && rc != null && positions.size() < maxChunks) {
				positions.add(new ChunkPos(RegionFiles.chunkX(rc[0], c.index()), RegionFiles.chunkZ(rc[1], c.index())));
			}
		}
		for (ChunkRef o : old.values()) {
			removed++;
			if (rc != null && positions.size() < maxChunks) {
				positions.add(new ChunkPos(RegionFiles.chunkX(rc[0], o.index()), RegionFiles.chunkZ(rc[1], o.index())));
			}
		}
		return new RegionChange(added, removed, modified, positions);
	}
}
