package dev.steelaspect.cytrabackups.core.backup;

import dev.steelaspect.cytrabackups.core.CancelToken;
import dev.steelaspect.cytrabackups.core.Hash;
import dev.steelaspect.cytrabackups.core.LongHashSet;
import dev.steelaspect.cytrabackups.core.Progress;
import dev.steelaspect.cytrabackups.core.manifest.FileEntry;
import dev.steelaspect.cytrabackups.core.manifest.Manifest;
import dev.steelaspect.cytrabackups.core.manifest.ManifestEntry;
import dev.steelaspect.cytrabackups.core.manifest.RegionEntry;
import dev.steelaspect.cytrabackups.core.store.BlobRef;
import dev.steelaspect.cytrabackups.core.store.BlobStore;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

/** Checks a backup's integrity without restoring it: manifest checksum, every blob's hash, and file hashes. */
public final class Verifier {
	public record Result(int backupId, long blobsChecked, long bytesChecked, List<String> problems) {
		public boolean ok() {
			return problems.isEmpty();
		}
	}

	private Verifier() {
	}

	public static Result verify(BackupRepository repo, int id, ExecutorService workers, Progress progress, CancelToken cancel) throws IOException {
		List<String> problems = Collections.synchronizedList(new ArrayList<>());
		repo.dropCache();
		Manifest m;
		try {
			m = repo.loadManifest(id);
		} catch (IOException e) {
			problems.add("Manifest: " + e.getMessage());
			return new Result(id, 0, 0, problems);
		}
		BlobStore blobs = repo.blobs();
		progress.phase("Verifying backup #" + id);
		LongHashSet scheduled = new LongHashSet(Math.max(16, m.size() * 4));
		List<Runnable> tasks = new ArrayList<>();
		AtomicLong checked = new AtomicLong();
		AtomicLong bytes = new AtomicLong();
		for (ManifestEntry e : m.entries()) {
			if (e instanceof RegionEntry r) {
				if (!RegionEntry.logicalHash(r.chunks()).equals(r.contentHash())) problems.add(r.path() + ": chunk table hash mismatch");
				for (var c : r.chunks()) {
					BlobRef b = c.blob();
					if (scheduled.add(b.hash().prefix64())) tasks.add(() -> checkBlob(blobs, b, e.path(), problems, checked, bytes));
				}
			} else if (e instanceof FileEntry f) {
				if (f.pieces().size() == 1 && !f.pieces().get(0).hash().equals(f.contentHash())) {
					problems.add(f.path() + ": content hash mismatch");
				}
				if (f.pieces().size() > 1) {
					f.pieces().forEach(p -> scheduled.add(p.hash().prefix64()));
					tasks.add(() -> checkMultiPiece(blobs, f, problems, checked, bytes));
				} else {
					for (BlobRef b : f.pieces()) {
						if (scheduled.add(b.hash().prefix64())) tasks.add(() -> checkBlob(blobs, b, e.path(), problems, checked, bytes));
					}
				}
			}
		}
		progress.addTotal(tasks.size(), 0);
		List<Future<?>> futures = new ArrayList<>();
		for (Runnable t : tasks) {
			futures.add(workers.submit(() -> {
				cancel.check();
				t.run();
				progress.addDone(1, 0);
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
			if (e.getCause() instanceof CancellationException ce) throw ce;
			throw new IOException("Verification worker failed", e.getCause());
		}
		return new Result(id, checked.get(), bytes.get(), problems);
	}

	private static void checkBlob(BlobStore blobs, BlobRef b, String path, List<String> problems, AtomicLong checked, AtomicLong bytes) {
		try {
			byte[] data = blobs.read(b.hash());
			if (data.length != b.rawLength()) problems.add(path + ": blob " + b.hash().shortHex() + " has wrong length");
			bytes.addAndGet(data.length);
		} catch (IOException e) {
			problems.add(path + ": " + e.getMessage());
		}
		checked.incrementAndGet();
	}

	private static void checkMultiPiece(BlobStore blobs, FileEntry f, List<String> problems, AtomicLong checked, AtomicLong bytes) {
		MessageDigest md = Hash.newDigest();
		try {
			for (BlobRef b : f.pieces()) {
				byte[] data = blobs.read(b.hash());
				md.update(data);
				bytes.addAndGet(data.length);
				checked.incrementAndGet();
			}
			if (!Hash.finish(md).equals(f.contentHash())) problems.add(f.path() + ": content hash mismatch");
		} catch (IOException e) {
			problems.add(f.path() + ": " + e.getMessage());
		}
	}
}
