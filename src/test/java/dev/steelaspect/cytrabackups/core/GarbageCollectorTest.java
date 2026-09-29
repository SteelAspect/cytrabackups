package dev.steelaspect.cytrabackups.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.steelaspect.cytrabackups.core.backup.BackupMeta;
import dev.steelaspect.cytrabackups.core.backup.Verifier;
import dev.steelaspect.cytrabackups.core.prune.GarbageCollector;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GarbageCollectorTest {
	@TempDir
	Path dir;

	@Test
	void deletesOnlyUnreferencedBlobs() throws Exception {
		try (TestWorlds w = new TestWorlds(dir)) {
			w.populate();
			BackupMeta first = w.backup("a").meta();
			w.write("playerdata/abc.dat", "player-changed");
			TestWorlds.writeRegion(w.world.resolve("region/r.-1.0.mca"), Map.of(5, 12345L));
			BackupMeta second = w.backup("b").meta();
			assertEquals(2, second.newBlobs);

			// Nothing unreferenced yet
			GarbageCollector.Result r0 = GarbageCollector.collect(w.repo, new Progress(), CancelToken.NONE);
			assertEquals(0, r0.deletedBlobs());

			w.repo.delete(first.id);
			// The blobs only referenced by #1 must go; wait a moment so mtimes are older than the GC start.
			Thread.sleep(20);
			GarbageCollector.Result r1 = GarbageCollector.collect(w.repo, new Progress(), CancelToken.NONE);
			assertEquals(3, r1.deletedBlobs(), "old player file + both old chunks of r.-1.0");
			Verifier.Result v = Verifier.verify(w.repo, second.id, w.workers, new Progress(), CancelToken.NONE);
			assertTrue(v.ok(), "surviving backup intact: " + v.problems());
		}
	}

	@Test
	void verifyDetectsCorruption() throws Exception {
		try (TestWorlds w = new TestWorlds(dir)) {
			w.populate();
			BackupMeta b = w.backup("a").meta();
			assertTrue(Verifier.verify(w.repo, b.id, w.workers, new Progress(), CancelToken.NONE).ok());
			var entry = w.repo.loadManifest(b.id).get("level.dat");
			Hash h = ((dev.steelaspect.cytrabackups.core.manifest.FileEntry) entry).pieces().get(0).hash();
			java.nio.file.Files.write(w.blobs.pathFor(h), new byte[]{1, 2, 3});
			Verifier.Result v = Verifier.verify(w.repo, b.id, w.workers, new Progress(), CancelToken.NONE);
			assertEquals(1, v.problems().size());
			assertTrue(v.problems().get(0).startsWith("level.dat"));
		}
	}
}
