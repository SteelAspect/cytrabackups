package dev.steelaspect.cytrabackups.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.steelaspect.cytrabackups.core.backup.BackupMeta;
import dev.steelaspect.cytrabackups.core.diff.BackupDiff;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DiffTest {
	@TempDir
	Path dir;

	@Test
	void reportsFilesAndChunks() throws Exception {
		try (TestWorlds w = new TestWorlds(dir)) {
			w.populate();
			BackupMeta a = w.backup("a").meta();
			Map<Integer, Long> r = new TreeMap<>();
			for (int i = 0; i < 40; i++) r.put(i, 1000L + i);
			r.put(33, 1L);      // modified: slot 33 = chunk (1,1)
			r.remove(39);       // removed
			r.put(500, 2L);     // added
			TestWorlds.writeRegion(w.world.resolve("region/r.0.0.mca"), r);
			w.write("stats/x.json", "{}");
			Files.delete(w.world.resolve("data/raids.dat"));
			BackupMeta b = w.backup("b").meta();
			BackupDiff.Result d = BackupDiff.compare(w.repo.loadManifest(a.id), w.repo.loadManifest(b.id), 50);
			assertEquals(java.util.List.of("stats/x.json"), d.added());
			assertEquals(java.util.List.of("data/raids.dat"), d.removed());
			assertEquals(1, d.changed().size());
			BackupDiff.RegionChange rc = d.changed().get(0).region();
			assertEquals(1, rc.added());
			assertEquals(1, rc.removed());
			assertEquals(1, rc.modified());
			assertTrue(rc.chunks().contains(new BackupDiff.ChunkPos(1, 1)));
		}
	}
}
