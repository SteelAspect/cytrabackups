package dev.steelaspect.cytrabackups.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.steelaspect.cytrabackups.core.CancelToken;
import dev.steelaspect.cytrabackups.core.Progress;
import dev.steelaspect.cytrabackups.core.backup.BackupEngine;
import dev.steelaspect.cytrabackups.core.backup.BackupMeta;
import dev.steelaspect.cytrabackups.core.backup.BackupRepository;
import dev.steelaspect.cytrabackups.core.backup.BackupService;
import dev.steelaspect.cytrabackups.core.backup.BackupSettings;
import dev.steelaspect.cytrabackups.core.backup.PathFilter;
import dev.steelaspect.cytrabackups.core.compress.Codec;
import dev.steelaspect.cytrabackups.core.compress.Compression;
import dev.steelaspect.cytrabackups.core.config.CytraConfig;
import dev.steelaspect.cytrabackups.core.migrate.ServerMigration;
import dev.steelaspect.cytrabackups.core.offsite.OffsiteSync;
import dev.steelaspect.cytrabackups.core.offsite.S3Target;
import dev.steelaspect.cytrabackups.core.region.RegionFiles;
import dev.steelaspect.cytrabackups.core.restore.ContentHasher;
import dev.steelaspect.cytrabackups.core.restore.RestoreEngine;
import dev.steelaspect.cytrabackups.core.restore.RestorePlan;
import dev.steelaspect.cytrabackups.core.restore.RestoreRecord;
import dev.steelaspect.cytrabackups.core.store.BlobStore;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.gaul.s3proxy.AuthenticationType;
import org.gaul.s3proxy.S3Proxy;
import org.jclouds.ContextBuilder;
import org.jclouds.blobstore.BlobStoreContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * /cbackup migrate end to end against S3Proxy: server A exports a server backup to prefix "a/", server B (its own
 * storage and prefix) imports it from there, and a rollback puts B back.
 */
class MigrateIntegrationTest {
	static final String ACCESS = "AKIAMIGRATE";
	static final String SECRET = "migrate/Secret+Key";
	static final String BUCKET = "migrate-bucket";
	static S3Proxy proxy;
	static BlobStoreContext context;
	static String endpoint;

	@BeforeAll
	static void start() throws Exception {
		context = ContextBuilder.newBuilder("transient").credentials("x", "y").overrides(new Properties()).build(BlobStoreContext.class);
		context.getBlobStore().createContainerInLocation(null, BUCKET);
		proxy = S3Proxy.builder().blobStore(context.getBlobStore()).endpoint(URI.create("http://127.0.0.1:0"))
			.awsAuthentication(AuthenticationType.AWS_V4, ACCESS, SECRET).build();
		proxy.start();
		while (!proxy.getState().equals("STARTED")) Thread.sleep(10);
		endpoint = "http://127.0.0.1:" + proxy.getPort();
	}

	@AfterAll
	static void stop() throws Exception {
		proxy.stop();
		context.close();
	}

	static S3Target target(String prefix) {
		return new S3Target(endpoint, "us-east-1", BUCKET, prefix, ACCESS, SECRET, true);
	}

	/** One server folder with its own backup storage inside it, as on a panel host. */
	static final class Server {
		final Path root, storage;
		final BlobStore blobs;
		final BackupRepository repo;
		final BackupService service;
		final OffsiteSync sync;

		Server(Path root, ExecutorService workers) throws IOException {
			this.root = root;
			this.storage = root.resolve("backups/cytrabackups");
			blobs = new BlobStore(storage.resolve("blobs"), storage.resolve("tmp"), compression(), false);
			repo = new BackupRepository(storage, blobs);
			service = new BackupService(repo, new BackupEngine(blobs, workers, 3, BackupSettings.defaults()), "1.21.11", "it");
			sync = new OffsiteSync(repo, storage.resolve("offsite"), s -> {
			});
		}

		void write(String rel, String text) throws IOException {
			Path f = root.resolve(rel);
			Files.createDirectories(f.getParent());
			Files.writeString(f, text);
		}

		String read(String rel) throws IOException {
			return Files.readString(root.resolve(rel));
		}

		boolean has(String rel) {
			return Files.exists(root.resolve(rel));
		}
	}

	static Compression compression() {
		return new Compression(Codec.ZSTD, 3, 0.03);
	}

	static void region(Path file, int seed) throws IOException {
		Files.createDirectories(file.getParent());
		List<RegionFiles.ChunkSlot> slots = new ArrayList<>();
		Random r = new Random(seed);
		for (int i = 0; i < 12; i++) {
			byte[] payload = new byte[1500];
			r.nextBytes(payload);
			payload[0] = 2;
			slots.add(new RegionFiles.ChunkSlot(i, 1_700_000_000, payload));
		}
		RegionFiles.write(file, slots);
	}

	@Test
	void serverFilterSkipsCachesLocksAndOurSettings() {
		CytraConfig.MigrateSettings m = new CytraConfig.MigrateSettings();
		PathFilter keep = ServerMigration.filter(m.include, m.exclude, false);
		PathFilter withSettings = ServerMigration.filter(m.include, m.exclude, true);
		assertTrue(keep.test("mods/a.jar"));
		assertTrue(keep.test("world/region/r.0.0.mca"));
		assertTrue(keep.test("server.properties"));
		assertFalse(keep.test("libraries/x/y.jar"));
		assertFalse(keep.test(".fabric/remappedJars/a.jar"));
		assertFalse(keep.test("logs/latest.log"));
		assertFalse(keep.test("world/session.lock"));
		assertFalse(keep.test(ServerMigration.BACKUP_SETTINGS));
		assertTrue(withSettings.test(ServerMigration.BACKUP_SETTINGS));
	}

	@Test
	void moveServerThroughTheBucketAndRollBack(@TempDir Path dir) throws Exception {
		ExecutorService workers = Executors.newFixedThreadPool(3);
		ExecutorService downloads = Executors.newCachedThreadPool();
		try {
			CytraConfig.MigrateSettings settings = new CytraConfig.MigrateSettings();
			// --- old server A ---
			Server a = new Server(dir.resolve("a"), workers);
			a.write("world/level.dat", "A-level");
			region(a.root.resolve("world/region/r.0.0.mca"), 1);
			a.write("world/session.lock", "lock");
			// a big plain file (a mod's database): many pieces, downloaded in batches on import
			byte[] big = new byte[20 * 1024 * 1024 + 123];
			new Random(7).nextBytes(big);
			Files.createDirectories(a.root.resolve("world/data"));
			Files.write(a.root.resolve("world/data/big.sqlite"), big);
			a.write("mods/a.jar", "mod-a");
			a.write("config/x.json", "{\"a\":1}");
			a.write(ServerMigration.BACKUP_SETTINGS, "A-settings");
			a.write("server.properties", "level-name=world");
			a.write("libraries/lib.jar", "library");
			a.write("logs/latest.log", "log");

			// a world backup first: the server backup reuses its world files instead of reading them again
			BackupService.Request world = new BackupService.Request();
			world.worldDir = a.root.resolve("world");
			a.service.create(world);
			BackupService.Request req = new BackupService.Request();
			req.worldDir = a.root;
			req.filter = ServerMigration.filter(settings.include, settings.exclude, true);
			req.excludedDirs = List.of(a.storage);
			req.scope = BackupMeta.SCOPE_SERVER;
			req.worldFolder = "world";
			req.baseline = ServerMigration.baseline(a.repo, "world");
			BackupService.Outcome out = a.service.create(req);
			BackupMeta exported = out.meta();
			assertTrue(exported.serverScope());
			assertEquals(3, out.scan().reusedFiles, "level.dat, the region file and the big file come from the world backup");
			assertTrue(a.repo.latestFull().map(m -> !m.serverScope()).orElse(false), "a server backup is never the world baseline");
			a.sync.enqueueMissing();
			a.sync.process(target("a/"), workers, 3, new Progress(), CancelToken.NONE);
			assertTrue(a.sync.isBackupUploaded(exported.id));

			// --- new server B: its own storage and prefix, same bucket ---
			Server b = new Server(dir.resolve("b"), workers);
			b.write("world/level.dat", "B-level");
			b.write("mods/b.jar", "mod-b");
			b.write(ServerMigration.BACKUP_SETTINGS, "B-settings");
			b.write("server.jar", "b-launcher");
			b.write("logs/b.log", "b-log");
			Path work = ServerMigration.workDir(b.storage, exported.id);

			assertThrows(IOException.class, () -> ServerMigration.prepare(target("a/"), 999, ServerMigration.workDir(b.storage, 999)));
			ServerMigration.Prepared p = ServerMigration.prepare(target("a/"), exported.id, work);
			p = ServerMigration.load(work); // what the next start reads
			ServerMigration.Result r = ServerMigration.apply(p, target("a/"), work, b.storage, b.root,
				ServerMigration.filter(settings.include, settings.exclude, false), List.of(b.storage), compression(), workers, downloads, 3, true,
				new Progress(), CancelToken.NONE);

			assertEquals("A-level", b.read("world/level.dat"));
			assertEquals(ContentHasher.region(a.root.resolve("world/region/r.0.0.mca")), ContentHasher.region(b.root.resolve("world/region/r.0.0.mca")));
			assertTrue(java.util.Arrays.equals(big, Files.readAllBytes(b.root.resolve("world/data/big.sqlite"))), "the big file arrives intact");
			assertEquals("mod-a", b.read("mods/a.jar"));
			assertFalse(b.has("mods/b.jar"), "mods only the new server had are removed");
			assertEquals("{\"a\":1}", b.read("config/x.json"));
			assertEquals("level-name=world", b.read("server.properties"));
			assertEquals("B-settings", b.read(ServerMigration.BACKUP_SETTINGS), "this server's own backup settings are kept");
			assertEquals("b-launcher", b.read("server.jar"), "top-level files are never removed");
			assertEquals("b-log", b.read("logs/b.log"), "excluded folders are left alone");
			assertFalse(b.has("libraries/lib.jar"));
			assertFalse(b.has("world/session.lock"));
			assertTrue(r.placed() >= 5);
			assertFalse(Files.exists(work.resolve("blobs")), "no blobs are kept after the import");

			// --- undo the move ---
			RestoreEngine engine = new RestoreEngine(b.blobs, workers, b.storage, true);
			RestoreRecord record = engine.record(r.record().restoreId).orElseThrow();
			assertEquals(RestoreRecord.Kind.MIGRATE, record.kind);
			RestorePlan undo = engine.planRollback(record, b.root, new Progress(), CancelToken.NONE);
			engine.execute(undo, b.root, b.storage, new Progress(), CancelToken.NONE);
			assertEquals("B-level", b.read("world/level.dat"));
			assertEquals("mod-b", b.read("mods/b.jar"));
			assertFalse(b.has("mods/a.jar"));
			assertFalse(b.has("world/region/r.0.0.mca"));
		} finally {
			workers.shutdownNow();
			downloads.shutdownNow();
		}
	}
}
