package dev.steelaspect.cytrabackups.it;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.steelaspect.cytrabackups.core.CancelToken;
import dev.steelaspect.cytrabackups.core.Progress;
import dev.steelaspect.cytrabackups.core.backup.BackupMeta;
import dev.steelaspect.cytrabackups.core.offsite.S3Target;
import dev.steelaspect.cytrabackups.core.prune.GarbageCollector;
import dev.steelaspect.cytrabackups.core.backup.Verifier;
import dev.steelaspect.cytrabackups.core.offsite.OffsiteBlobs;
import dev.steelaspect.cytrabackups.core.offsite.OffsiteSync;
import dev.steelaspect.cytrabackups.core.offsite.StreamingUpload;
import dev.steelaspect.cytrabackups.core.backup.PathFilter;
import dev.steelaspect.cytrabackups.core.restore.ContentHasher;
import dev.steelaspect.cytrabackups.core.restore.RestoreEngine;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.List;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import org.gaul.s3proxy.AuthenticationType;
import org.gaul.s3proxy.S3Proxy;
import org.jclouds.ContextBuilder;
import org.jclouds.blobstore.BlobStore;
import org.jclouds.blobstore.BlobStoreContext;
import org.jclouds.blobstore.domain.StorageMetadata;
import org.jclouds.blobstore.options.ListContainerOptions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Talks to S3Proxy, a real S3-compatible server that verifies every AWS Signature V4. */
class S3IntegrationTest {
	static final String ACCESS = "AKIACYTRATEST";
	static final String SECRET = "cytra/Secret+Key=with/special+chars";
	static final String BUCKET = "cytra-bucket";
	static S3Proxy proxy;
	static BlobStoreContext context;
	static BlobStore store;
	static String endpoint;

	@BeforeAll
	static void start() throws Exception {
		Properties props = new Properties();
		context = ContextBuilder.newBuilder("transient").credentials("x", "y").overrides(props).build(BlobStoreContext.class);
		store = context.getBlobStore();
		store.createContainerInLocation(null, BUCKET);
		proxy = S3Proxy.builder()
			.blobStore(store)
			.endpoint(URI.create("http://127.0.0.1:0"))
			.awsAuthentication(AuthenticationType.AWS_V4, ACCESS, SECRET)
			.build();
		proxy.start();
		while (!proxy.getState().equals("STARTED")) Thread.sleep(10);
		endpoint = "http://127.0.0.1:" + proxy.getPort();
	}

	@AfterAll
	static void stop() throws Exception {
		proxy.stop();
		context.close();
	}

	static S3Target target(String prefix, String secret) {
		return new S3Target(endpoint, "us-east-1", BUCKET, prefix, ACCESS, secret, true);
	}

	static Set<String> remoteKeys(String prefix) {
		Set<String> keys = new TreeSet<>();
		for (StorageMetadata m : store.list(BUCKET, ListContainerOptions.Builder.recursive().prefix(prefix))) {
			if (m.getType() == org.jclouds.blobstore.domain.StorageType.BLOB) keys.add(m.getName());
		}
		return keys;
	}

	static byte[] remoteBytes(String key) throws IOException {
		try (InputStream in = store.getBlob(BUCKET, key).getPayload().openStream()) {
			return in.readAllBytes();
		}
	}

	@Test
	void putHeadDeleteWithSignedRequests(@TempDir Path dir) throws Exception {
		Path f = dir.resolve("x.bin");
		Files.writeString(f, "hello s3");
		S3Target t = target("raw/", SECRET);
		assertFalse(t.exists("a b/c+d=e~f.txt"));
		t.upload("a b/c+d=e~f.txt", f); // key needs percent-encoding in the signed canonical URI
		assertTrue(t.exists("a b/c+d=e~f.txt"));
		assertArrayEquals("hello s3".getBytes(), remoteBytes("raw/a b/c+d=e~f.txt"));
		t.delete("a b/c+d=e~f.txt");
		assertFalse(t.exists("a b/c+d=e~f.txt"));
		t.delete("never-existed"); // deleting a missing object is not an error
	}

	@Test
	void wrongSecretIsRejected(@TempDir Path dir) throws Exception {
		Path f = dir.resolve("x.bin");
		Files.writeString(f, "nope");
		IOException e = assertThrows(IOException.class, () -> target("", "wrong-secret").upload("x.bin", f));
		assertTrue(e.getMessage().contains("403"), e.getMessage());
	}

	@Test
	void offsiteSyncMirrorsRepository(@TempDir Path dir) throws Exception {
		try (Fixture fx = new Fixture(dir)) {
			BackupMeta a = fx.backup("a");
			Files.writeString(fx.world.resolve("level.dat"), "level-changed");
			BackupMeta b = fx.backup("b");
			S3Target t = target("server1/", SECRET);
			fx.sync.enqueueMissing();
			fx.sync.process(t, fx.workers, true, new Progress(), CancelToken.NONE);
			Set<String> expected = new TreeSet<>();
			for (String k : fx.expectedKeys(a)) expected.add("server1/" + k);
			for (String k : fx.expectedKeys(b)) expected.add("server1/" + k);
			assertEquals(expected, remoteKeys("server1/"));
			assertArrayEquals(Files.readAllBytes(fx.repo.backupDir(b.id).resolve("meta.json")), remoteBytes("server1/backups/000002/meta.json"));

			fx.repo.delete(a.id);
			fx.sync.enqueueDeleteBackup(a.id);
			Thread.sleep(20);
			GarbageCollector.Result gc = GarbageCollector.collect(fx.repo, new Progress(), CancelToken.NONE);
			fx.sync.enqueueDeleteBlobs(gc.deleted());
			fx.sync.process(t, fx.workers, true, new Progress(), CancelToken.NONE);
			Set<String> remaining = new TreeSet<>();
			for (String k : fx.expectedKeys(b)) remaining.add("server1/" + k);
			assertEquals(remaining, remoteKeys("server1/"), "remote mirrors local after prune + GC");
			assertFalse(fx.sync.hasWork());
		}
	}

	@Test
	void listAndFetchFromAnEmptyRepository(@TempDir Path dir) throws Exception {
		S3Target t = target("server2/", SECRET);
		int id;
		try (Fixture fx = new Fixture(dir.resolve("src"))) {
			id = fx.backup("to fetch").id;
			fx.sync.enqueueMissing();
			fx.sync.process(t, fx.workers, true, new Progress(), CancelToken.NONE);
		}
		try (Fixture fx = new Fixture(dir.resolve("dst"))) {
			List<OffsiteSync.RemoteBackup> remote = fx.sync.listRemote(t);
			assertEquals(1, remote.size());
			assertEquals("to fetch", remote.get(0).meta().comment);
			int blobs = fx.sync.fetch(t, id, fx.workers, true, new Progress(), CancelToken.NONE);
			assertTrue(blobs > 0);
			assertTrue(Verifier.verify(fx.repo, id, fx.workers, new Progress(), CancelToken.NONE).ok(), "fetched blobs verify");
			assertEquals("to fetch", fx.repo.get(id).orElseThrow().comment);
		}
	}

	@Test
	void offsiteOnlyKeepsDataAtS3AndRestoresFromIt(@TempDir Path dir) throws Exception {
		ExecutorService pool = Executors.newCachedThreadPool();
		try (Fixture fx = new Fixture(dir)) {
			Path region = fx.world.resolve("region/r.0.0.mca");
			String before = ContentHasher.region(region).hex();
			S3Target t = target("server3/", SECRET);
			fx.blobs.attachRemote(new OffsiteBlobs(fx.sync, () -> target("server3/", SECRET), pool, 8), true);
			StreamingUpload up = new StreamingUpload(fx.sync, fx.blobs, t, pool, 8, 1, () -> {
			}, msg -> {
			}, msg -> {
			}, CancelToken.NONE);
			fx.blobs.setWriteHook(up);
			BackupMeta a;
			try {
				a = fx.backup("a");
				up.finish();
			} finally {
				fx.blobs.setWriteHook(null);
				up.close();
			}
			fx.sync.enqueueUpload(a.id);
			fx.sync.process(t, pool, 8, new Progress(), CancelToken.NONE);
			fx.sync.evictUploaded(fx.blobs);
			long[] local = {0};
			fx.blobs.forEach((h, attrs) -> local[0]++);
			assertEquals(0, local[0], "nothing kept locally");
			Set<String> expected = new TreeSet<>();
			for (String k : fx.expectedKeys(a)) expected.add("server3/" + k);
			assertEquals(expected, remoteKeys("server3/"), "the whole backup is at S3");
			assertTrue(Verifier.verify(fx.repo, a.id, fx.workers, new Progress(), CancelToken.NONE).ok(), "verified through S3");

			Files.delete(region);
			RestoreEngine restore = new RestoreEngine(fx.blobs, fx.workers, fx.storage, true);
			restore.execute(restore.planFull(fx.repo.loadManifest(a.id), a.id, false, fx.world, PathFilter.ALL, List.of(), new Progress(), CancelToken.NONE),
				fx.world, new Progress(), CancelToken.NONE);
			assertEquals(before, ContentHasher.region(region).hex(), "restored from S3");
		} finally {
			pool.shutdownNow();
		}
	}
}
