package dev.steelaspect.cytrabackups.core.prune;

import dev.steelaspect.cytrabackups.core.Lang;
import dev.steelaspect.cytrabackups.core.CancelToken;
import dev.steelaspect.cytrabackups.core.Hash;
import dev.steelaspect.cytrabackups.core.LongHashSet;
import dev.steelaspect.cytrabackups.core.Progress;
import dev.steelaspect.cytrabackups.core.backup.BackupMeta;
import dev.steelaspect.cytrabackups.core.backup.BackupRepository;
import dev.steelaspect.cytrabackups.core.manifest.Manifest;
import dev.steelaspect.cytrabackups.core.store.BlobStore;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Mark & sweep over the blob store. Live blobs are marked by the first 64 bits of their hash; a collision can
 * only keep a dead blob alive, never delete a live one. Blobs newer than the start of the run are never deleted.
 */
public final class GarbageCollector {
	public record Result(long scannedBlobs, long deletedBlobs, long freedBytes, long liveBytes, List<Hash> deleted) {
	}

	private GarbageCollector() {
	}

	public static Result collect(BackupRepository repo, Progress progress, CancelToken cancel) throws IOException {
		long startedAt = System.currentTimeMillis();
		progress.phase(Lang.get("cytrabackups.phase.gc_mark"));
		List<BackupMeta> backups = repo.list();
		progress.addTotal(backups.size(), 0);
		LongHashSet live = new LongHashSet(1 << 16);
		for (BackupMeta b : backups) {
			cancel.check();
			Manifest m;
			try {
				m = repo.loadManifest(b.id);
			} catch (IOException e) {
				// A manifest we cannot read makes marking incomplete: refuse to sweep rather than lose data.
				throw new IOException("Cannot read manifest of backup #" + b.id + "; garbage collection aborted to protect its blobs: " + e.getMessage(), e);
			}
			m.forEachBlob(ref -> live.add(ref.hash().prefix64()));
			progress.addDone(1, 0);
		}

		progress.phase(Lang.get("cytrabackups.phase.gc_sweep"));
		BlobStore store = repo.blobs();
		long[] stats = new long[4]; // scanned, deleted, freed, live bytes
		List<Hash> deleted = new ArrayList<>();
		List<Hash> doomed = new ArrayList<>();
		store.forEach((hash, attrs) -> {
			stats[0]++;
			if (live.contains(hash.prefix64()) || attrs.lastModifiedTime().toMillis() >= startedAt) {
				stats[3] += attrs.size();
			} else {
				doomed.add(hash);
				stats[2] += attrs.size();
			}
		});
		for (Hash h : doomed) {
			cancel.check();
			if (store.delete(h)) {
				stats[1]++;
				deleted.add(h);
			}
		}
		store.cleanTemp(3_600_000L);
		return new Result(stats[0], stats[1], stats[2], stats[3], deleted);
	}
}
