package dev.steelaspect.cytrabackups.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.steelaspect.cytrabackups.core.backup.BackupMeta;
import dev.steelaspect.cytrabackups.core.backup.BackupService;
import dev.steelaspect.cytrabackups.core.backup.BackupSettings;
import dev.steelaspect.cytrabackups.core.manifest.FileEntry;
import dev.steelaspect.cytrabackups.core.manifest.Manifest;
import dev.steelaspect.cytrabackups.core.manifest.RegionEntry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DedupTest {
	@TempDir
	Path dir;

	@Test
	void unchangedWorldAddsNoBlobs() throws Exception {
		try (TestWorlds w = new TestWorlds(dir)) {
			w.populate();
			BackupMeta first = w.backup("first").meta();
			assertTrue(first.newBlobs > 40, "first backup stores every chunk");
			assertFalse(w.repo.loadManifest(first.id).entries().stream().anyMatch(e -> e.path().equals("session.lock")), "session.lock excluded");
			BackupMeta second = w.backup("second").meta();
			assertEquals(0, second.newBlobs, "identical world must not add blobs");
			assertEquals(0, second.newStoredBytes);
			assertEquals(first.totalSize, second.totalSize);
			assertEquals(first.fileCount, second.reusedFiles, "all files reused by size+mtime");
		}
	}

	@Test
	void oneChangedChunkStoresOnlyThatChunk() throws Exception {
		try (TestWorlds w = new TestWorlds(dir)) {
			w.populate();
			BackupMeta first = w.backup("first").meta();
			// rewrite r.0.0.mca with only slot 7 changed
			Map<Integer, Long> r00 = new TreeMap<>();
			for (int i = 0; i < 40; i++) r00.put(i, 1000L + i);
			r00.put(7, 424242L);
			Path region = w.world.resolve("region/r.0.0.mca");
			TestWorlds.writeRegion(region, r00);
			Files.setLastModifiedTime(region, FileTime.fromMillis(System.currentTimeMillis() + 5000));
			BackupMeta second = w.backup("second").meta();
			assertEquals(1, second.newBlobs, "only the changed chunk is new");
			assertTrue(second.newStoredBytes < 4000, "a single chunk, not the whole region: " + second.newStoredBytes);
			Manifest m1 = w.repo.loadManifest(first.id);
			Manifest m2 = w.repo.loadManifest(second.id);
			RegionEntry a = (RegionEntry) m1.get("region/r.0.0.mca");
			RegionEntry b = (RegionEntry) m2.get("region/r.0.0.mca");
			assertEquals(a.chunk(6).blob(), b.chunk(6).blob());
			assertFalse(a.chunk(7).blob().hash().equals(b.chunk(7).blob().hash()));
		}
	}

	@Test
	void duplicateFilesShareBlobsAndWholeFileModeWorks() throws Exception {
		BackupSettings noChunks = new BackupSettings(false, true, BackupSettings.DEFAULT_PIECE_SIZE, 0, 3);
		try (TestWorlds w = new TestWorlds(dir, noChunks)) {
			w.populate();
			w.write("data/copy1.dat", "same-content-same-content");
			w.write("data/copy2.dat", "same-content-same-content");
			BackupMeta m = w.backup("whole").meta();
			Manifest man = w.repo.loadManifest(m.id);
			assertInstanceOf(FileEntry.class, man.get("region/r.0.0.mca"), "chunk dedup disabled -> whole file");
			assertEquals(((FileEntry) man.get("data/copy1.dat")).pieces(), ((FileEntry) man.get("data/copy2.dat")).pieces());
		}
	}

	@Test
	void largeFilesAreSplitIntoPieces() throws Exception {
		BackupSettings small = new BackupSettings(true, true, 64 * 1024, 0, 3);
		try (TestWorlds w = new TestWorlds(dir, small)) {
			byte[] big = new byte[200_000];
			new java.util.Random(3).nextBytes(big);
			Files.createDirectories(w.world);
			Files.write(w.world.resolve("big.bin"), big);
			BackupMeta m = w.backup("big").meta();
			FileEntry e = (FileEntry) w.repo.loadManifest(m.id).get("big.bin");
			assertEquals(4, e.pieces().size());
			assertEquals(Hash.compute(big), e.contentHash());
		}
	}

	@Test
	void skipIfUnchanged() throws Exception {
		try (TestWorlds w = new TestWorlds(dir)) {
			w.populate();
			assertNotNull(w.backup("base").meta());
			w.write("level.dat", "level-data-v2"); // only level.dat changed (e.g. game time)
			BackupService.Request req = new BackupService.Request();
			req.worldDir = w.world;
			req.filter = TestWorlds.defaultFilter();
			req.skipIfUnchanged = true;
			req.unchangedIgnore = p -> p.equals("level.dat");
			BackupService.Outcome out = w.service.create(req);
			assertTrue(out.skippedUnchanged());
			assertNull(out.meta());
			w.write("playerdata/abc.dat", "player-2");
			req.progress = new Progress();
			assertNotNull(w.service.create(req).meta(), "real change is backed up");
		}
	}
}
