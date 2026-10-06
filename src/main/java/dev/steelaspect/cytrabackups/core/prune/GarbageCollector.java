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
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Mark & sweep over the blob store. Live blobs are marked by the first 64 bits of their hash; a collision can
 * only keep a dead blob alive, never delete a live one. Blobs newer than the start of the run are never deleted.
 */
public final class GarbageCollector {
	/** {@code live}: hash prefixes of every blob some backup uses. */
	public record Result(long scannedBlobs, long deletedBlobs, long freedBytes, long liveBytes, List<Hash> deleted, LongHashSet live) {
	}

	private GarbageCollector() {
	}

	public static Result collect(BackupRepository repo, Progress progress, CancelToken cancel) throws IOException {
		long startedAt = System.currentTimeMillis();
		return sweep(repo, markLive(repo, Set.of(), progress, cancel), startedAt, progress, cancel);
	}

	/**
	 * Deletes local blobs not in {@code live}, which must cover every backup in the repository and have been marked
	 * from {@code markedAt} on (blobs written since then are kept).
	 */
	public static Result sweep(BackupRepository repo, LongHashSet live, long markedAt, Progress progress, CancelToken cancel) throws IOException {
		long startedAt = markedAt;
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
		return new Result(stats[0], stats[1], stats[2], stats[3], deleted, live);
	}

	/** Marks the blobs used by every backup except {@code except}. Throws if a manifest cannot be read. */
	public static LongHashSet markLive(BackupRepository repo, Collection<Integer> except, Progress progress, CancelToken cancel) throws IOException {
		progress.phase(Lang.get("cytrabackups.phase.gc_mark"));
		List<BackupMeta> backups = repo.list();
		progress.addTotal(backups.size(), 0);
		LongHashSet live = new LongHashSet(1 << 16);
		for (BackupMeta b : backups) {
			cancel.check();
			if (except.contains(b.id)) continue;
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
		return live;
	}

	/**
	 * Blobs of the backups in {@code removing} that no other backup uses: what deleting them frees, including data that is
	 * only stored off-site (which a sweep of the local store cannot find). Call before deleting them.
	 */
	public static List<Hash> exclusiveBlobs(BackupRepository repo, Collection<Integer> removing, Progress progress, CancelToken cancel) throws IOException {
		return exclusiveBlobs(repo, removing, markLive(repo, removing, progress, cancel), cancel);
	}

	/** As above, with {@code live} already marked from every backup except {@code removing}. */
	public static List<Hash> exclusiveBlobs(BackupRepository repo, Collection<Integer> removing, LongHashSet live, CancelToken cancel) throws IOException {
		Set<Integer> ids = new HashSet<>(removing);
		LongHashSet seen = new LongHashSet();
		List<Hash> out = new ArrayList<>();
		for (int id : ids) {
			cancel.check();
			Manifest m;
			try {
				m = repo.loadManifest(id);
			} catch (IOException e) {
				continue; // what only this backup used cannot be found; it just stays stored
			}
			m.forEachBlob(ref -> {
				long p = ref.hash().prefix64();
				if (!live.contains(p) && seen.add(p)) out.add(ref.hash());
			});
		}
		return out;
	}
}
