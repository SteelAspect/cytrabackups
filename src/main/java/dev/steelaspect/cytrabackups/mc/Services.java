package dev.steelaspect.cytrabackups.mc;

import dev.steelaspect.cytrabackups.core.backup.BackupEngine;
import dev.steelaspect.cytrabackups.core.backup.BackupRepository;
import dev.steelaspect.cytrabackups.core.backup.BackupService;
import dev.steelaspect.cytrabackups.core.backup.BackupSettings;
import dev.steelaspect.cytrabackups.core.backup.PathFilter;
import dev.steelaspect.cytrabackups.core.compress.Codec;
import dev.steelaspect.cytrabackups.core.compress.Compression;
import dev.steelaspect.cytrabackups.CytraBackups;
import dev.steelaspect.cytrabackups.core.config.CytraConfig;
import dev.steelaspect.cytrabackups.core.offsite.OffsiteBlobs;
import dev.steelaspect.cytrabackups.core.offsite.OffsiteSync;
import dev.steelaspect.cytrabackups.core.offsite.OffsiteTarget;
import dev.steelaspect.cytrabackups.core.restore.PendingOperationRunner;
import dev.steelaspect.cytrabackups.core.restore.RestoreEngine;
import dev.steelaspect.cytrabackups.core.store.BlobStore;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Wires the pure-Java core for one repository. Used at runtime and by the startup restore hook. */
public final class Services implements AutoCloseable {
	public final CytraConfig config;
	public final Path storage;
	public final int parallelism;
	public final ExecutorService workers;
	public final BlobStore blobs;
	public final BackupRepository repo;
	public final BackupEngine engine;
	public final BackupService backups;
	public final RestoreEngine restore;
	public final OffsiteSync offsite;
	/** Reads blobs that are only stored off-site; null when off-site copies are off and nothing lives only there. */
	private final OffsiteBlobs remote;
	private final ExecutorService remotePool;
	/** Off-site only mode is active: new backup data is uploaded and deleted here. */
	public final boolean offsiteOnly;

	private Services(CytraConfig config, Path storage) throws IOException {
		this.config = config;
		this.storage = storage;
		int cores = Runtime.getRuntime().availableProcessors();
		this.parallelism = config.workerThreads > 0 ? config.workerThreads : Math.max(1, Math.min(4, cores / 2));
		this.workers = Executors.newFixedThreadPool(parallelism, threadFactory("CytraBackups-Worker"));
		this.blobs = new BlobStore(storage.resolve("blobs"), storage.resolve("tmp"), compression(config), false);
		this.repo = new BackupRepository(storage, blobs);
		this.engine = new BackupEngine(blobs, workers, parallelism, settings(config));
		this.backups = new BackupService(repo, engine, ModEnv.minecraftVersion(), ModEnv.modVersion());
		this.restore = new RestoreEngine(blobs, workers, storage, config.trustModificationTime);
		this.offsite = new OffsiteSync(repo, storage.resolve("offsite"), msg -> CytraBackups.LOGGER.info("CytraBackups offsite: {}", msg));
		if (config.offsite.enabled || offsite.hasRemoteOnlyData()) {
			this.remotePool = Executors.newCachedThreadPool(threadFactory("CytraBackups-Download"));
			this.remote = new OffsiteBlobs(offsite, () -> OffsiteTargets.create(config, storage), remotePool, OffsiteTargets.threads(config));
			boolean sameTarget = boundToConfiguredTarget(config, storage, offsite);
			this.offsiteOnly = OffsiteTargets.offsiteOnly(config) && sameTarget;
			if (OffsiteTargets.offsiteOnly(config) && !sameTarget) {
				CytraBackups.LOGGER.warn("CytraBackups: off-site only mode is paused: the off-site settings are incomplete or do not point to where the backup data is ({})",
					offsite.queue().targetId.isEmpty() ? "nothing uploaded yet" : offsite.queue().targetId);
			}
			blobs.attachRemote(remote, offsiteOnly);
		} else {
			this.remotePool = null;
			this.remote = null;
			this.offsiteOnly = false;
		}
	}

	/** True if the configured destination is the one the upload state belongs to (or nothing was uploaded yet). */
	private static boolean boundToConfiguredTarget(CytraConfig config, Path storage, OffsiteSync offsite) {
		String bound = offsite.queue().targetId;
		try (OffsiteTarget t = OffsiteTargets.create(config, storage)) {
			return bound.isEmpty() || bound.equals(t.id());
		} catch (Exception e) {
			return false;
		}
	}

	public static Services open(CytraConfig config, Path storage) throws IOException {
		return new Services(config, storage);
	}

	public static ThreadFactory threadFactory(String name) {
		AtomicInteger n = new AtomicInteger();
		return r -> {
			Thread t = new Thread(r, name + "-" + n.incrementAndGet());
			t.setDaemon(true);
			t.setPriority(Thread.MIN_PRIORITY);
			return t;
		};
	}

	public static Compression compression(CytraConfig c) {
		return new Compression(Codec.parse(c.compression.algorithm), c.compression.level, Math.max(0, c.compression.minSavingsPercent) / 100.0);
	}

	public static BackupSettings settings(CytraConfig c) {
		return new BackupSettings(c.chunkDedup, c.trustModificationTime, BackupSettings.DEFAULT_PIECE_SIZE,
			Math.max(0, c.maxReadMiBPerSecond) * 1024L * 1024L, 3).withUnpackChunks(c.compression.recompressChunks, c.compression.chunkLevel);
	}

	public PathFilter filter() {
		return new PathFilter(config.include, config.exclude);
	}

	/** Never back up the storage folder itself if someone configured it inside the world. */
	public List<Path> excludedDirs(Path world) {
		Path w = world.toAbsolutePath().normalize();
		Path s = storage.toAbsolutePath().normalize();
		return s.startsWith(w) ? List.of(s) : List.of();
	}

	public PendingOperationRunner runner(String levelName, java.util.function.Consumer<String> log, Path world) {
		return new PendingOperationRunner(backups, restore, filter(), excludedDirs(world), levelName, config.restore.recycleBinKeep, log);
	}

	@Override
	public void close() {
		if (remote != null) {
			try {
				remote.close();
			} catch (IOException e) {
				CytraBackups.LOGGER.warn("CytraBackups: closing the off-site connection failed: {}", e.getMessage());
			}
			remotePool.shutdownNow();
		}
		workers.shutdownNow();
		try {
			workers.awaitTermination(10, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
