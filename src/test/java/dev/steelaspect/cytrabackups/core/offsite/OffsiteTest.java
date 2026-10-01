package dev.steelaspect.cytrabackups.core.offsite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.steelaspect.cytrabackups.core.CancelToken;
import dev.steelaspect.cytrabackups.core.Progress;
import dev.steelaspect.cytrabackups.core.TestWorlds;
import dev.steelaspect.cytrabackups.core.backup.BackupMeta;
import dev.steelaspect.cytrabackups.core.prune.GarbageCollector;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OffsiteTest {
	@TempDir
	Path dir;

	/** In-memory target recording uploads. */
	static class FakeTarget implements OffsiteTarget {
		final Map<String, byte[]> objects = new ConcurrentHashMap<>();
		int uploads;

		@Override
		public synchronized void upload(String key, Path file) throws IOException {
			uploads++;
			objects.put(key, Files.readAllBytes(file));
		}

		@Override
		public boolean exists(String key) {
			return objects.containsKey(key);
		}

		@Override
		public void delete(String key) {
			objects.remove(key);
		}

		@Override
		public void download(String key, Path file) throws IOException {
			byte[] data = objects.get(key);
			if (data == null) throw new java.nio.file.NoSuchFileException(key);
			Files.write(file, data);
		}

		@Override
		public List<String> list(String prefix) {
			return objects.keySet().stream().filter(k -> k.startsWith(prefix)).sorted().toList();
		}

		@Override
		public String describe() {
			return "fake";
		}
	}

	@Test
	void fetchRebuildsABackupInAnEmptyRepository() throws Exception {
		FakeTarget t = new FakeTarget();
		Map<String, String> original;
		int id;
		try (TestWorlds w = new TestWorlds(dir.resolve("a"))) {
			w.populate();
			original = w.snapshot();
			BackupMeta a = w.backup("first").meta();
			id = a.id;
			OffsiteSync sync = new OffsiteSync(w.repo, w.storage.resolve("offsite"), s -> {
			});
			sync.enqueueUpload(a.id);
			sync.process(t, w.workers, true, new Progress(), CancelToken.NONE);
		}
		// a new server with an empty repository and the same off-site settings
		try (TestWorlds w = new TestWorlds(dir.resolve("b"))) {
			OffsiteSync sync = new OffsiteSync(w.repo, w.storage.resolve("offsite"), s -> {
			});
			List<OffsiteSync.RemoteBackup> remote = sync.listRemote(t);
			assertEquals(1, remote.size());
			assertEquals("first", remote.get(0).meta().comment);
			int blobs = sync.fetch(t, id, w.workers, true, new Progress(), CancelToken.NONE);
			assertTrue(blobs > 0);
			BackupMeta fetched = w.repo.get(id).orElseThrow();
			assertEquals("first", fetched.comment);
			assertTrue(dev.steelaspect.cytrabackups.core.backup.Verifier.verify(w.repo, id, w.workers, new Progress(), CancelToken.NONE).ok(), "every blob verified");
			assertEquals(id + 1, w.repo.state().nextId, "ids continue after the fetched backup");
			assertTrue(sync.queue().uploaded.contains(id), "a fetched backup is not uploaded again");
			IOException missing = assertThrows(IOException.class, () -> sync.fetch(t, id + 100, w.workers, true, new Progress(), CancelToken.NONE));
			assertTrue(missing.getMessage().contains("no backup #" + (id + 100)), missing.getMessage());
			IOException twice = assertThrows(IOException.class, () -> sync.fetch(t, id, w.workers, true, new Progress(), CancelToken.NONE));
			assertTrue(twice.getMessage().contains("already here"), twice.getMessage());
			var plan = w.restore.planFull(w.repo.loadManifest(id), id, false, w.world, dev.steelaspect.cytrabackups.core.backup.PathFilter.ALL, List.of(), new Progress(), CancelToken.NONE);
			w.restore.execute(plan, w.world, new Progress(), CancelToken.NONE);
			assertEquals(original, w.snapshot(), "the fetched backup restores the original world");
		}
	}

	@Test
	void uploadsOnlyNewBlobsAndMirrorsDeletes() throws Exception {
		try (TestWorlds w = new TestWorlds(dir)) {
			w.populate();
			BackupMeta a = w.backup("a").meta();
			OffsiteSync sync = new OffsiteSync(w.repo, w.storage.resolve("offsite"), s -> {
			});
			FakeTarget t = new FakeTarget();
			sync.enqueueUpload(a.id);
			sync.process(t, w.workers, true, new Progress(), CancelToken.NONE);
			assertTrue(t.objects.containsKey("backups/000001/meta.json"));
			int afterFirst = t.uploads;
			assertEquals(a.newBlobs + 3, afterFirst, "every blob once + manifest, new-blobs, meta");

			w.write("level.dat", "changed");
			BackupMeta b = w.backup("b").meta();
			sync.enqueueUpload(b.id);
			sync.process(t, w.workers, false, new Progress(), CancelToken.NONE);
			assertEquals(afterFirst + 1 + 3, t.uploads, "only the changed blob is uploaded again");

			// delete backup #1 locally, GC, mirror deletes
			w.repo.delete(a.id);
			sync.enqueueDeleteBackup(a.id);
			Thread.sleep(20);
			GarbageCollector.Result gc = GarbageCollector.collect(w.repo, new Progress(), CancelToken.NONE);
			sync.enqueueDeleteBlobs(gc.deleted());
			sync.process(t, w.workers, true, new Progress(), CancelToken.NONE);
			assertFalse(t.objects.containsKey("backups/000001/meta.json"));
			assertEquals(1, gc.deletedBlobs());
			assertFalse(sync.hasWork());
		}
	}

	@Test
	void changingDestinationUploadsEverythingAgain() throws Exception {
		try (TestWorlds w = new TestWorlds(dir)) {
			w.populate();
			BackupMeta a = w.backup("a").meta();
			OffsiteSync sync = new OffsiteSync(w.repo, w.storage.resolve("offsite"), s -> {
			});
			FakeTarget first = new FakeTarget();
			sync.process(first, w.workers, true, new Progress(), CancelToken.NONE); // first binding queues existing backups
			assertTrue(first.objects.containsKey("backups/000001/meta.json"), "existing backups uploaded when off-site is first enabled");
			FakeTarget second = new FakeTarget() {
				@Override
				public String id() {
					return "second-destination";
				}
			};
			sync.process(second, w.workers, true, new Progress(), CancelToken.NONE);
			assertEquals(first.objects.keySet(), second.objects.keySet(), "a new destination receives a full copy");
			int uploads = second.uploads;
			sync.process(second, w.workers, true, new Progress(), CancelToken.NONE);
			assertEquals(uploads, second.uploads, "nothing re-uploaded to the same destination");
			assertEquals(a.id, sync.queue().uploaded.first());
		}
	}

	@Test
	void sigV4SigningKeyMatchesAwsExample() {
		// From the AWS Signature Version 4 documentation ("Deriving the signing key").
		byte[] k = S3Target.hmac(("AWS4" + "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY").getBytes(), "20120215");
		k = S3Target.hmac(k, "us-east-1");
		k = S3Target.hmac(k, "iam");
		k = S3Target.hmac(k, "aws4_request");
		assertEquals("f4780e2d9f65fa895f9c67b32ce1baf0b0d8a43505a000a1a9e090d414db404d", HexFormat.of().formatHex(k));
	}

	@Test
	void s3UrlsAreEncoded() {
		S3Target t = new S3Target("https://s3.example.com", "eu-1", "my-bucket", "srv/", "a", "b", true);
		assertEquals("https://s3.example.com/my-bucket/srv/backups/000001/meta.json", t.uriFor("backups/000001/meta.json").toString());
		S3Target v = new S3Target("https://s3.example.com:9000/", "eu-1", "bkt", "", "a", "b", false);
		assertEquals("https://bkt.s3.example.com:9000/a%20b/c%2Bd", v.uriFor("a b/c+d").toString());
	}
}
