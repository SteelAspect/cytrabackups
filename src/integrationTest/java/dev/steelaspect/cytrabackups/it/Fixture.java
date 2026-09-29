package dev.steelaspect.cytrabackups.it;

import dev.steelaspect.cytrabackups.core.backup.BackupEngine;
import dev.steelaspect.cytrabackups.core.backup.BackupMeta;
import dev.steelaspect.cytrabackups.core.backup.BackupRepository;
import dev.steelaspect.cytrabackups.core.backup.BackupService;
import dev.steelaspect.cytrabackups.core.backup.BackupSettings;
import dev.steelaspect.cytrabackups.core.compress.Codec;
import dev.steelaspect.cytrabackups.core.compress.Compression;
import dev.steelaspect.cytrabackups.core.offsite.OffsiteSync;
import dev.steelaspect.cytrabackups.core.region.RegionFiles;
import dev.steelaspect.cytrabackups.core.store.BlobStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** A small world + repository with helpers to create backups, shared by the off-site integration tests. */
final class Fixture implements AutoCloseable {
	final Path world;
	final Path storage;
	final ExecutorService workers = Executors.newFixedThreadPool(3);
	final BlobStore blobs;
	final BackupRepository repo;
	final BackupService service;
	final OffsiteSync sync;

	Fixture(Path root) throws IOException {
		world = root.resolve("world");
		storage = root.resolve("backups");
		Files.createDirectories(world.resolve("region"));
		blobs = new BlobStore(storage.resolve("blobs"), storage.resolve("tmp"), new Compression(Codec.ZSTD, 3, 0.03), false);
		repo = new BackupRepository(storage, blobs);
		service = new BackupService(repo, new BackupEngine(blobs, workers, 3, BackupSettings.defaults()), "1.21.11", "it");
		sync = new OffsiteSync(repo, storage.resolve("offsite"), s -> {
		});
		Files.writeString(world.resolve("level.dat"), "level");
		Files.createDirectories(world.resolve("playerdata"));
		Files.writeString(world.resolve("playerdata/p.dat"), "player");
		List<RegionFiles.ChunkSlot> slots = new ArrayList<>();
		Random r = new Random(1);
		for (int i = 0; i < 20; i++) {
			byte[] payload = new byte[2000];
			r.nextBytes(payload);
			payload[0] = 2;
			slots.add(new RegionFiles.ChunkSlot(i, 1_700_000_000, payload));
		}
		RegionFiles.write(world.resolve("region/r.0.0.mca"), slots);
	}

	BackupMeta backup(String comment) throws IOException {
		BackupService.Request req = new BackupService.Request();
		req.worldDir = world;
		req.levelName = "world";
		req.comment = comment;
		return service.create(req).meta();
	}

	/** Remote keys a complete upload of the repository must contain. */
	List<String> expectedKeys(BackupMeta m) throws IOException {
		List<String> keys = new ArrayList<>();
		repo.loadManifest(m.id).forEachBlob(ref -> {
			String hex = ref.hash().hex();
			String k = "blobs/" + hex.substring(0, 2) + "/" + hex.substring(2, 4) + "/" + hex;
			if (!keys.contains(k)) keys.add(k);
		});
		keys.addAll(OffsiteSync.backupKeys(m.id));
		return keys;
	}

	@Override
	public void close() {
		workers.shutdownNow();
	}
}
