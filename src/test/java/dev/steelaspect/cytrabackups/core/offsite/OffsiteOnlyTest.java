package dev.steelaspect.cytrabackups.core.offsite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.steelaspect.cytrabackups.core.CancelToken;
import dev.steelaspect.cytrabackups.core.Hash;
import dev.steelaspect.cytrabackups.core.LongHashSet;
import dev.steelaspect.cytrabackups.core.Progress;
import dev.steelaspect.cytrabackups.core.TestWorlds;
import dev.steelaspect.cytrabackups.core.backup.BackupMeta;
import dev.steelaspect.cytrabackups.core.backup.BackupService;
import dev.steelaspect.cytrabackups.core.backup.PathFilter;
import dev.steelaspect.cytrabackups.core.backup.Verifier;
import dev.steelaspect.cytrabackups.core.prune.GarbageCollector;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Off-site only mode: backup data is uploaded while the backup runs and then deleted locally. */
class OffsiteOnlyTest {
	private static final long[] NO_DELAY = {1, 1, 1};

	@TempDir
	Path dir;

	/** A repository in off-site only mode against a fake destination. */
	private static final class Rig implements AutoCloseable {
		final TestWorlds w;
		final OffsiteTest.FakeTarget target;
		final OffsiteSync sync;
		final ExecutorService pool = Executors.newCachedThreadPool();
		final OffsiteBlobs remote;
		boolean waited;

		Rig(Path dir, OffsiteTest.FakeTarget target) throws IOException {
			this.w = new TestWorlds(dir);
			this.target = target;
			this.sync = new OffsiteSync(w.repo, w.storage.resolve("offsite"), s -> {
			});
			this.remote = new OffsiteBlobs(sync, () -> target, pool, 4);
			w.blobs.attachRemote(remote, true);
		}

		/** One backup the way BackupManager makes it in off-site only mode: streamed, then synced and evicted. */
		BackupMeta backup(String comment, long bufferBytes) throws IOException {
			StreamingUpload up = new StreamingUpload(sync, w.blobs, target, pool, 3, bufferBytes, () -> waited = true, s -> {
			}, NO_DELAY);
			w.blobs.setWriteHook(up);
			BackupService.Outcome out = null;
			try {
				out = w.backup(comment);
				up.finish();
			} finally {
				w.blobs.setWriteHook(null);
				up.close(out != null ? out.scan().manifest : null);
			}
			sync.enqueueUpload(out.meta().id);
			sync.process(target, pool, 4, new Progress(), CancelToken.NONE);
			sync.evictUploaded(w.blobs);
			return out.meta();
		}

		long localBlobs() throws IOException {
			long[] n = {0};
			w.blobs.forEach((h, a) -> n[0]++);
			return n[0];
		}

		void restore(int id) throws IOException {
			var plan = w.restore.planFull(w.repo.loadManifest(id), id, false, w.world, PathFilter.ALL, List.of(), new Progress(), CancelToken.NONE);
			w.restore.execute(plan, w.world, new Progress(), CancelToken.NONE);
		}

		@Override
		public void close() {
			pool.shutdownNow();
			w.close();
		}
	}

	private static Map<Integer, Long> region00(long changedSeed) {
		Map<Integer, Long> r = new TreeMap<>();
		for (int i = 0; i < 40; i++) r.put(i, 1000L + i);
		r.put(7, changedSeed);
		return r;
	}

	@Test
	void keepsNothingLocallyAndRestoresFromTheOffsiteCopy() throws Exception {
		try (Rig r = new Rig(dir, new OffsiteTest.FakeTarget())) {
			r.w.populate();
			Map<String, String> original = r.w.snapshot();
			BackupMeta a = r.backup("a", 1L << 30);
			assertEquals(0, r.localBlobs(), "every blob was deleted here after uploading");
			assertTrue(r.sync.hasRemoteOnlyData());
			assertTrue(r.target.objects.containsKey("backups/000001/meta.json"));
			assertTrue(Verifier.verify(r.w.repo, a.id, r.w.workers, new Progress(), CancelToken.NONE).ok(), "verify reads from the off-site copy");

			Files.delete(r.w.world.resolve("region/r.0.0.mca"));
			r.w.write("level.dat", "broken");
			r.restore(a.id);
			assertEquals(original, r.w.snapshot(), "restored from data that only exists off-site");
			assertEquals(0, r.localBlobs(), "reading off-site data does not keep it here");
		}
	}

	@Test
	void laterBackupsUploadOnlyWhatChanged() throws Exception {
		try (Rig r = new Rig(dir, new OffsiteTest.FakeTarget())) {
			r.w.populate();
			r.backup("a", 1L << 30);
			int before = r.target.uploads;

			TestWorlds.writeRegion(r.w.world.resolve("region/r.0.0.mca"), region00(9999L));
			Map<String, String> changed = r.w.snapshot();
			BackupMeta b = r.backup("b", 1L << 30);
			assertEquals(1, b.newBlobs, "the 39 unchanged chunks of the re-read region are referenced, not stored again");
			assertEquals(1 + 3, r.target.uploads - before, "one chunk plus manifest, new-blobs and meta");
			assertEquals(0, r.localBlobs());

			r.w.write("level.dat", "broken");
			r.restore(b.id);
			assertEquals(changed, r.w.snapshot());
		}
	}

	@Test
	void writersWaitWhenTheUploadBufferIsFull() throws Exception {
		AtomicInteger maxLocal = new AtomicInteger();
		Rig[] rig = new Rig[1];
		OffsiteTest.FakeTarget slow = new OffsiteTest.FakeTarget() {
			@Override
			public synchronized void upload(String key, Path file) throws IOException {
				maxLocal.accumulateAndGet((int) rig[0].localBlobs(), Math::max);
				try {
					Thread.sleep(5);
				} catch (InterruptedException e) {
					throw new IOException(e);
				}
				super.upload(key, file);
			}
		};
		try (Rig r = new Rig(dir, slow)) {
			rig[0] = r;
			r.w.populate();
			Map<String, String> original = r.w.snapshot();
			BackupMeta a = r.backup("a", 1);
			assertTrue(r.waited, "the backup had to wait for uploads");
			assertTrue(maxLocal.get() <= 4, "never more than a few blobs waiting here, saw " + maxLocal.get());
			r.restore(a.id);
			assertEquals(original, r.w.snapshot());
		}
	}

	@Test
	void anInterruptedBackupResumesWithoutUploadingEverythingAgain() throws Exception {
		AtomicBoolean broken = new AtomicBoolean(true);
		AtomicInteger okUploads = new AtomicInteger();
		OffsiteTest.FakeTarget flaky = new OffsiteTest.FakeTarget() {
			@Override
			public synchronized void upload(String key, Path file) throws IOException {
				if (broken.get() && okUploads.get() >= 10) throw new IOException("connection reset");
				okUploads.incrementAndGet();
				super.upload(key, file);
			}
		};
		try (Rig r = new Rig(dir, flaky)) {
			r.w.populate();
			Map<String, String> original = r.w.snapshot();
			assertThrows(IOException.class, () -> r.backup("a", 1));
			assertTrue(r.w.repo.list().isEmpty(), "the failed backup was not committed");
			int firstTry = flaky.uploads;
			assertEquals(10, firstTry);

			broken.set(false);
			BackupMeta a = r.backup("a", 1);
			LongHashSet distinct = new LongHashSet();
			r.w.repo.loadManifest(a.id).forEachBlob(ref -> distinct.add(ref.hash().prefix64()));
			assertEquals(distinct.size() - 10 + 3, flaky.uploads - firstTry, "only what was not uploaded before, plus the backup's metadata");

			// the first try's uploads are used by the backup now: garbage collection keeps them
			GarbageCollector.Result gc = GarbageCollector.collect(r.w.repo, new Progress(), CancelToken.NONE);
			assertEquals(0, r.sync.sweepOrphans(gc.live()));
			Files.delete(r.w.world.resolve("region/r.0.0.mca"));
			r.restore(a.id);
			assertEquals(original, r.w.snapshot());
		}
	}

	@Test
	void deletingABackupAlsoDeletesWhatOnlyItUsedOffsite() throws Exception {
		try (Rig r = new Rig(dir, new OffsiteTest.FakeTarget())) {
			r.w.populate();
			BackupMeta a = r.backup("a", 1L << 30);
			TestWorlds.writeRegion(r.w.world.resolve("region/r.0.0.mca"), region00(9999L));
			Map<String, String> changed = r.w.snapshot();
			BackupMeta b = r.backup("b", 1L << 30);
			long blobsBefore = r.target.objects.keySet().stream().filter(k -> k.startsWith("blobs/")).count();

			List<Hash> dead = GarbageCollector.exclusiveBlobs(r.w.repo, List.of(a.id), new Progress(), CancelToken.NONE);
			assertEquals(1, dead.size(), "only the old version of the changed chunk");
			r.w.repo.delete(a.id);
			r.sync.enqueueDeleteBackup(a.id);
			r.sync.enqueueDeleteBlobs(dead);
			r.sync.process(r.target, r.pool, 4, new Progress(), CancelToken.NONE);
			assertFalse(r.target.objects.containsKey("backups/000001/meta.json"));
			assertFalse(r.target.objects.containsKey(OffsiteSync.blobKey(dead.getFirst())));
			assertEquals(blobsBefore - 1, r.target.objects.keySet().stream().filter(k -> k.startsWith("blobs/")).count());

			r.w.write("level.dat", "broken");
			r.restore(b.id);
			assertEquals(changed, r.w.snapshot(), "the remaining backup is intact");
		}
	}

	@Test
	void unusedUploadsOfAFailedBackupAreDeletedByGarbageCollection() throws Exception {
		try (Rig r = new Rig(dir, new OffsiteTest.FakeTarget())) {
			Hash h = r.w.blobs.put("never committed".repeat(10).getBytes(), 0, 150, false).ref().hash();
			r.sync.bindTarget(r.target.id());
			r.sync.startSession();
			r.target.upload(OffsiteSync.blobKey(h), r.w.blobs.pathFor(h));
			r.sync.markUploaded(h);
			r.sync.recordSessionUpload(h);
			r.sync.endSession(null);
			assertEquals(1, r.sync.sweepOrphans(new LongHashSet()));
			r.sync.process(r.target, r.pool, 4, new Progress(), CancelToken.NONE);
			assertFalse(r.target.objects.containsKey(OffsiteSync.blobKey(h)));
			assertFalse(r.sync.isUploaded(h));
		}
	}

	@Test
	void dataStoredOnlyOffsitePinsTheDestination() throws Exception {
		try (Rig r = new Rig(dir, new OffsiteTest.FakeTarget())) {
			r.w.populate();
			r.backup("a", 1L << 30);
			IOException e = assertThrows(IOException.class, () -> r.sync.bindTarget("somewhere-else"));
			assertTrue(e.getMessage().contains("fake"), e.getMessage());
			assertEquals("fake", r.sync.queue().targetId, "the upload state still points at the data");
		}
	}

	@Test
	void uploadingAgainCancelsAQueuedDeletion() throws Exception {
		try (Rig r = new Rig(dir, new OffsiteTest.FakeTarget())) {
			Hash h = r.w.blobs.put("content".repeat(20).getBytes(), 0, 140, false).ref().hash();
			r.sync.markUploaded(h);
			r.sync.enqueueDeleteBlobs(List.of(h));
			assertTrue(r.sync.queue().deleteKeys.contains(OffsiteSync.blobKey(h)));
			r.sync.markUploaded(h); // the same content was stored again before the deletion ran
			assertFalse(r.sync.queue().deleteKeys.contains(OffsiteSync.blobKey(h)), "the new upload must not be deleted");
		}
	}

	@Test
	void withALocalCopyMissingDataIsStoredHereAgain() throws Exception {
		try (Rig r = new Rig(dir, new OffsiteTest.FakeTarget())) {
			r.w.populate();
			r.backup("a", 1L << 30);
			assertEquals(0, r.localBlobs());
			r.w.blobs.attachRemote(r.remote, false); // "Keep local copy" turned back on
			TestWorlds.writeRegion(r.w.world.resolve("region/r.0.0.mca"), region00(9999L));
			BackupMeta b = r.w.backup("b").meta();
			assertEquals(40, b.newBlobs, "every chunk of the re-read region is stored here again");
			assertEquals(40, r.localBlobs());
		}
	}
}
