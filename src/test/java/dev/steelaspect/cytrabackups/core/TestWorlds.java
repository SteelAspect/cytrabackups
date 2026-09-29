package dev.steelaspect.cytrabackups.core;

import dev.steelaspect.cytrabackups.core.backup.BackupEngine;
import dev.steelaspect.cytrabackups.core.backup.BackupRepository;
import dev.steelaspect.cytrabackups.core.backup.BackupService;
import dev.steelaspect.cytrabackups.core.backup.BackupSettings;
import dev.steelaspect.cytrabackups.core.backup.PathFilter;
import dev.steelaspect.cytrabackups.core.backup.Trigger;
import dev.steelaspect.cytrabackups.core.compress.Codec;
import dev.steelaspect.cytrabackups.core.compress.Compression;
import dev.steelaspect.cytrabackups.core.region.RegionFiles;
import dev.steelaspect.cytrabackups.core.restore.RestoreEngine;
import dev.steelaspect.cytrabackups.core.store.BlobStore;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.DeflaterOutputStream;

/** Builds synthetic worlds and a wired-up repository for tests. */
public final class TestWorlds implements AutoCloseable {
	public final Path root;
	public final Path world;
	public final Path storage;
	public final ExecutorService workers = Executors.newFixedThreadPool(3);
	public final BlobStore blobs;
	public final BackupRepository repo;
	public final BackupEngine engine;
	public final BackupService service;
	public final RestoreEngine restore;

	public TestWorlds(Path root) throws IOException {
		this(root, BackupSettings.defaults());
	}

	public TestWorlds(Path root, BackupSettings settings) throws IOException {
		this.root = root;
		this.world = root.resolve("world");
		this.storage = root.resolve("backups");
		Files.createDirectories(world);
		this.blobs = new BlobStore(storage.resolve("blobs"), storage.resolve("tmp"), new Compression(Codec.ZSTD, 3, 0.03), false);
		this.repo = new BackupRepository(storage, blobs);
		this.engine = new BackupEngine(blobs, workers, 3, settings);
		this.service = new BackupService(repo, engine, "1.21.11", "test");
		this.restore = new RestoreEngine(blobs, workers, storage, true);
	}

	/** zlib-compressed pseudo chunk NBT, deterministic per (seed). */
	public static byte[] chunkPayload(long seed, int size) {
		Random r = new Random(seed);
		byte[] raw = new byte[size];
		for (int i = 0; i < size; i++) raw[i] = (byte) ('a' + r.nextInt(6));
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		bos.write(2); // compression type: zlib
		try (DeflaterOutputStream d = new DeflaterOutputStream(bos)) {
			d.write(raw);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		return bos.toByteArray();
	}

	/** Writes a region file with the given slot -> seed map. */
	public static void writeRegion(Path file, Map<Integer, Long> seeds) throws IOException {
		List<RegionFiles.ChunkSlot> slots = new ArrayList<>();
		for (Map.Entry<Integer, Long> e : new TreeMap<>(seeds).entrySet()) {
			slots.add(new RegionFiles.ChunkSlot(e.getKey(), 1_700_000_000 + e.getKey(), chunkPayload(e.getValue(), 3000)));
		}
		RegionFiles.write(file, slots);
	}

	public Path write(String rel, String content) throws IOException {
		Path p = world.resolve(rel);
		Files.createDirectories(p.getParent());
		Files.writeString(p, content, StandardCharsets.UTF_8);
		return p;
	}

	/** A small world: level.dat, playerdata, 2 regions + entities + poi in the overworld, 1 nether region. */
	public void populate() throws IOException {
		write("level.dat", "level-data-v1");
		write("playerdata/abc.dat", "player-1");
		write("data/raids.dat", "raids");
		write("session.lock", "lock");
		Map<Integer, Long> r00 = new TreeMap<>();
		for (int i = 0; i < 40; i++) r00.put(i, 1000L + i);
		writeRegion(world.resolve("region/r.0.0.mca"), r00);
		writeRegion(world.resolve("region/r.-1.0.mca"), Map.of(5, 77L, 1023, 78L));
		writeRegion(world.resolve("entities/r.0.0.mca"), Map.of(0, 500L, 1, 501L));
		writeRegion(world.resolve("poi/r.0.0.mca"), Map.of(0, 600L));
		writeRegion(world.resolve("DIM-1/region/r.0.0.mca"), Map.of(3, 900L));
	}

	public BackupService.Outcome backup(String comment) throws IOException {
		BackupService.Request req = new BackupService.Request();
		req.worldDir = world;
		req.levelName = "world";
		req.trigger = Trigger.MANUAL;
		req.comment = comment;
		req.creator = "test";
		req.filter = defaultFilter();
		return service.create(req);
	}

	public static PathFilter defaultFilter() {
		return new PathFilter(List.of(), List.of("session.lock", "logs/**"));
	}

	/** Snapshot of relative path -> SHA-256 (region files compared chunk-logically) for equality checks. */
	public Map<String, String> snapshot() throws IOException {
		Map<String, String> out = new TreeMap<>();
		try (var s = Files.walk(world)) {
			for (Path p : s.filter(Files::isRegularFile).toList()) {
				String rel = FileUtil.relative(world, p);
				if (rel.equals("session.lock")) continue;
				boolean region = BackupEngine.isRegionPath(rel);
				out.put(rel, dev.steelaspect.cytrabackups.core.restore.ContentHasher.of(p, region).hex());
			}
		}
		return out;
	}

	@Override
	public void close() {
		workers.shutdownNow();
	}
}
