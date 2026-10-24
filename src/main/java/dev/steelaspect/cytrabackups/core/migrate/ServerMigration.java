package dev.steelaspect.cytrabackups.core.migrate;

import dev.steelaspect.cytrabackups.core.CancelToken;
import dev.steelaspect.cytrabackups.core.FileUtil;
import dev.steelaspect.cytrabackups.core.Hash;
import dev.steelaspect.cytrabackups.core.Json;
import dev.steelaspect.cytrabackups.core.Lang;
import dev.steelaspect.cytrabackups.core.Progress;
import dev.steelaspect.cytrabackups.core.backup.BackupMeta;
import dev.steelaspect.cytrabackups.core.backup.BackupRepository;
import dev.steelaspect.cytrabackups.core.backup.PathFilter;
import dev.steelaspect.cytrabackups.core.compress.Compression;
import dev.steelaspect.cytrabackups.core.manifest.FileEntry;
import dev.steelaspect.cytrabackups.core.manifest.Manifest;
import dev.steelaspect.cytrabackups.core.manifest.ManifestEntry;
import dev.steelaspect.cytrabackups.core.manifest.RegionEntry;
import dev.steelaspect.cytrabackups.core.offsite.OffsiteSync;
import dev.steelaspect.cytrabackups.core.offsite.OffsiteTarget;
import dev.steelaspect.cytrabackups.core.restore.RestoreEngine;
import dev.steelaspect.cytrabackups.core.restore.RestorePlan;
import dev.steelaspect.cytrabackups.core.restore.RestoreRecord;
import dev.steelaspect.cytrabackups.core.store.BlobStore;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * Moving a whole server through the off-site copy (/cbackup migrate). A server backup is an ordinary backup of the
 * server folder ({@link BackupMeta#SCOPE_SERVER}); importing one replaces this server's folder with it, reading the
 * data straight from where the old server uploaded it, through the normal restore engine (staged, verified, journaled,
 * replaced files kept in the recycle bin so a rollback undoes the move).
 */
public final class ServerMigration {
	/** This server's own backup settings: kept on import unless asked, so its destination and keys stay its own. */
	public static final String BACKUP_SETTINGS = "config/cytrabackups.json";

	private ServerMigration() {
	}

	/** What a server backup holds and an import replaces: the migrate filter, never lock files, optionally not our settings. */
	public static PathFilter filter(List<String> include, List<String> exclude, boolean withBackupSettings) {
		List<String> ex = new ArrayList<>(exclude);
		ex.add("**/session.lock");
		if (!withBackupSettings) ex.add(BACKUP_SETTINGS);
		return new PathFilter(include, ex);
	}

	/**
	 * Files a new server backup can reuse by size and modification time: the latest server backup's, plus the latest
	 * world backup's under {@code worldFolder}, so an unchanged world is not read again.
	 */
	public static Manifest baseline(BackupRepository repo, String worldFolder) {
		Map<String, ManifestEntry> out = new LinkedHashMap<>();
		repo.latestFull().ifPresent(world -> {
			try {
				String prefix = worldFolder.isEmpty() ? "" : worldFolder + "/";
				for (ManifestEntry e : repo.loadManifest(world.id).entries()) out.put(prefix + e.path(), rebase(e, prefix + e.path()));
			} catch (IOException ignored) {
				// no baseline: the scan reads everything
			}
		});
		BackupMeta lastServer = null;
		for (BackupMeta m : repo.list()) if (m.serverScope()) lastServer = m;
		if (lastServer != null) {
			try {
				for (ManifestEntry e : repo.loadManifest(lastServer.id).entries()) out.put(e.path(), e);
			} catch (IOException ignored) {
			}
		}
		return new Manifest(out.values());
	}

	private static ManifestEntry rebase(ManifestEntry e, String path) {
		if (e instanceof RegionEntry r) return new RegionEntry(path, r.size(), r.mtime(), r.contentHash(), r.chunks(), r.unpacked());
		FileEntry f = (FileEntry) e;
		return new FileEntry(path, f.size(), f.mtime(), f.contentHash(), f.pieces());
	}

	/** A server backup's metadata and file list, downloaded for an import. */
	public record Prepared(BackupMeta meta, Manifest manifest) {
	}

	/** Folder in the storage that holds an import's downloaded file list and its staging area. */
	public static Path workDir(Path storage, int id) {
		return storage.resolve("migrate").resolve(BackupRepository.dirName(id));
	}

	/** Downloads backup {@code id}'s metadata and file list from {@code source} into {@code dir} and checks them. */
	public static Prepared prepare(OffsiteTarget source, int id, Path dir) throws IOException {
		String prefix = "backups/" + BackupRepository.dirName(id) + "/";
		Files.createDirectories(dir);
		Path metaFile = dir.resolve("meta.json"), manifestFile = dir.resolve("manifest.bin");
		try {
			source.download(prefix + "meta.json", metaFile);
		} catch (NoSuchFileException e) {
			throw new IOException(Lang.get("cytrabackups.migrate.not_found", id, source.describe()));
		}
		BackupMeta meta = Json.read(metaFile, BackupMeta.class);
		if (meta.id != id) throw new IOException("Remote metadata of #" + id + " belongs to #" + meta.id);
		if (!meta.serverScope()) throw new IOException(Lang.get("cytrabackups.migrate.not_server", id));
		source.download(prefix + "manifest.bin", manifestFile);
		return new Prepared(meta, readManifest(meta, manifestFile));
	}

	/** Reads a prepared import back (at the next start). */
	public static Prepared load(Path dir) throws IOException {
		BackupMeta meta = Json.read(dir.resolve("meta.json"), BackupMeta.class);
		return new Prepared(meta, readManifest(meta, dir.resolve("manifest.bin")));
	}

	private static Manifest readManifest(BackupMeta meta, Path file) throws IOException {
		MessageDigest md = Hash.newDigest();
		Manifest manifest;
		try (InputStream in = new DigestInputStream(Files.newInputStream(file), md)) {
			manifest = Manifest.read(in);
			in.transferTo(OutputStream.nullOutputStream());
		}
		if (!meta.manifestSha256.isEmpty() && !meta.manifestSha256.equals(Hash.finish(md).hex())) {
			throw new IOException("The file list of server backup #" + meta.id + " is corrupt (checksum mismatch)");
		}
		return manifest;
	}

	/** Bytes that must be free to import: the staged copy of every file, plus the reserve. */
	public static long spaceNeeded(Prepared p, long reserveBytes) {
		return p.meta.totalSize + reserveBytes;
	}

	/**
	 * Replaces {@code serverDir} with the prepared server backup. Blobs are read from {@code source} as each file is
	 * written; nothing beyond the staged files is kept here. Replaced and removed files go to the recycle bin of the
	 * restore engine at {@code storage}, with a {@link RestoreRecord.Kind#MIGRATE} record.
	 */
	public static Result apply(Prepared p, OffsiteTarget source, Path dir, Path storage, Path serverDir, PathFilter filter,
							   List<Path> excludedDirs, Compression compression, ExecutorService workers, ExecutorService downloads,
							   int threads, boolean trustMtime, Progress progress, CancelToken cancel) throws IOException {
		Path blobsDir = dir.resolve("blobs"), tmp = dir.resolve("tmp");
		FileUtil.deleteRecursively(blobsDir);
		FileUtil.deleteRecursively(tmp);
		BlobStore scratch = new BlobStore(blobsDir, tmp, compression, false);
		scratch.attachRemote(new SourceBlobs(source, downloads, threads), false);
		RestoreEngine engine = new RestoreEngine(scratch, workers, storage, trustMtime);
		try {
			RestorePlan full = engine.planFull(p.manifest, p.meta.id, false, serverDir, filter, excludedDirs, progress, cancel);
			// Files at the top of the server folder are only added or replaced, never removed: the panel's start command may
			// name a server jar the old server didn't have.
			List<RestorePlan.Op> ops = full.ops().stream().filter(o -> o.action() == RestorePlan.Action.PLACE || o.path().contains("/")).toList();
			RestorePlan plan = new RestorePlan(RestoreRecord.Kind.MIGRATE, p.meta.id, Lang.get("cytrabackups.migrate.description", p.meta.id),
				ops, full.unchanged(), full.notes());
			RestoreRecord record = engine.execute(plan, serverDir, dir, progress, cancel);
			long placed = plan.ops().stream().filter(o -> o.action() == RestorePlan.Action.PLACE).count();
			long removed = plan.ops().size() - placed;
			return new Result(record, placed, removed, plan.unchanged());
		} finally {
			FileUtil.deleteRecursively(blobsDir);
			FileUtil.deleteRecursively(tmp);
		}
	}

	/** {@code placed} files written, {@code removed} files that only this server had, {@code unchanged} files already equal. */
	public record Result(RestoreRecord record, long placed, long removed, int unchanged) {
	}

	/** The source location as the blob store's remote: every blob of the backup is there. */
	private record SourceBlobs(OffsiteTarget target, ExecutorService pool, int threads) implements BlobStore.RemoteBlobs {
		@Override
		public boolean has(Hash hash) {
			return true;
		}

		@Override
		public void download(Hash hash, Path dest) throws IOException {
			target.download(OffsiteSync.blobKey(hash), dest);
		}

		@Override
		public void downloadAll(List<Hash> hashes, List<Path> dests) throws IOException {
			if (threads <= 1 || hashes.size() < 2) {
				for (int i = 0; i < hashes.size(); i++) download(hashes.get(i), dests.get(i));
				return;
			}
			List<Future<?>> futures = new ArrayList<>(hashes.size());
			for (int i = 0; i < hashes.size(); i++) {
				Hash h = hashes.get(i);
				Path d = dests.get(i);
				futures.add(pool.submit(() -> {
					download(h, d);
					return null;
				}));
			}
			try {
				for (Future<?> f : futures) f.get();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				futures.forEach(f -> f.cancel(true));
				throw new CancellationException("interrupted");
			} catch (ExecutionException e) {
				futures.forEach(f -> f.cancel(true));
				if (e.getCause() instanceof IOException io) throw io;
				throw new IOException(e.getCause());
			}
		}
	}
}
