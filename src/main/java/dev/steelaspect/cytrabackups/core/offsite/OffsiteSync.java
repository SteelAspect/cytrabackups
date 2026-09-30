package dev.steelaspect.cytrabackups.core.offsite;

import dev.steelaspect.cytrabackups.core.Lang;
import dev.steelaspect.cytrabackups.core.CancelToken;
import dev.steelaspect.cytrabackups.core.FileUtil;
import dev.steelaspect.cytrabackups.core.Hash;
import dev.steelaspect.cytrabackups.core.Json;
import dev.steelaspect.cytrabackups.core.LongHashSet;
import dev.steelaspect.cytrabackups.core.Progress;
import dev.steelaspect.cytrabackups.core.backup.BackupMeta;
import dev.steelaspect.cytrabackups.core.backup.BackupRepository;
import dev.steelaspect.cytrabackups.core.manifest.Manifest;
import dev.steelaspect.cytrabackups.core.store.BlobRef;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * Mirrors the repository to an {@link OffsiteTarget}. A persistent queue survives restarts; a local index of
 * uploaded blobs avoids re-uploading deduplicated data. meta.json is uploaded last so a remote backup only
 * "exists" once all of its data is there.
 */
public final class OffsiteSync {
	public static final class Queue {
		public TreeSet<Integer> uploads = new TreeSet<>();
		public TreeSet<Integer> uploaded = new TreeSet<>();
		public LinkedHashSet<String> deleteKeys = new LinkedHashSet<>();
		public long lastSuccess;
		public String lastError = "";
		/** Identity of the destination the "uploaded" state refers to; a different destination starts from scratch. */
		public String targetId = "";
	}

	public record Result(int backupsUploaded, long blobsUploaded, long bytesUploaded, int deleted) {
	}

	private final BackupRepository repo;
	private final Path dir;
	private final Consumer<String> log;
	private Queue queue;
	private LongHashSet uploadedBlobs;

	public OffsiteSync(BackupRepository repo, Path dir, Consumer<String> log) throws IOException {
		this.repo = repo;
		this.dir = dir;
		this.log = log;
		Files.createDirectories(dir);
		Queue q = Json.readOrNull(dir.resolve("queue.json"), Queue.class);
		this.queue = q != null ? q : new Queue();
		this.uploadedBlobs = loadIndex(dir.resolve("uploaded-blobs.bin"));
	}

	public synchronized Queue queue() {
		return queue;
	}

	public synchronized void enqueueUpload(int id) throws IOException {
		queue.uploads.add(id);
		save();
	}

	/** Queues every local backup that has not been uploaded yet. Returns how many were queued. */
	public synchronized int enqueueMissing() throws IOException {
		int n = 0;
		for (BackupMeta m : repo.list()) {
			if (!queue.uploaded.contains(m.id) && queue.uploads.add(m.id)) n++;
		}
		save();
		return n;
	}

	public synchronized void enqueueDeleteBackup(int id) throws IOException {
		queue.uploads.remove(id);
		if (queue.uploaded.remove(id)) {
			String d = "backups/" + BackupRepository.dirName(id) + "/";
			queue.deleteKeys.add(d + "meta.json");
			queue.deleteKeys.add(d + "manifest.bin");
			queue.deleteKeys.add(d + "new-blobs.bin");
		}
		save();
	}

	public synchronized void enqueueDeleteBlobs(List<Hash> hashes) throws IOException {
		for (Hash h : hashes) {
			if (uploadedBlobs.remove(h.prefix64())) queue.deleteKeys.add(blobKey(h));
		}
		save();
		saveIndex();
	}

	/**
	 * Binds the upload state to a destination. When the destination changes (type, host, bucket, prefix, folder...),
	 * everything is considered not uploaded there yet and pending remote deletions for the old one are dropped.
	 * Returns true if the destination is new (the caller then queues every backup for it).
	 */
	public synchronized boolean bindTarget(String targetId) throws IOException {
		if (targetId.equals(queue.targetId)) return false;
		queue.targetId = targetId;
		queue.uploaded.clear();
		queue.deleteKeys.clear();
		queue.lastSuccess = 0;
		queue.lastError = "";
		uploadedBlobs = new LongHashSet();
		save();
		saveIndex();
		return true;
	}

	public synchronized boolean hasWork() {
		return !queue.uploads.isEmpty() || !queue.deleteKeys.isEmpty();
	}

	static String blobKey(Hash h) {
		String hex = h.hex();
		return "blobs/" + hex.substring(0, 2) + "/" + hex.substring(2, 4) + "/" + hex;
	}

	/** Processes the queue against the target. Parallel uploads unless the target is SFTP (one channel). */
	public Result process(OffsiteTarget target, ExecutorService workers, boolean parallel, Progress progress, CancelToken cancel) throws IOException {
		if (bindTarget(target.id())) {
			int n = enqueueMissing();
			log.accept("New off-site destination " + target.describe() + ": queued all " + n + " backup(s) for upload");
		}
		int backups = 0;
		long blobCount = 0, bytes = 0;
		int deleted = 0;
		try {
			List<Integer> ids;
			synchronized (this) {
				ids = new ArrayList<>(queue.uploads);
			}
			for (int id : ids) {
				cancel.check();
				if (repo.get(id).isEmpty()) {
					synchronized (this) {
						queue.uploads.remove(id);
						save();
					}
					continue;
				}
				progress.phase(Lang.get("cytrabackups.phase.upload", id, target.describe()));
				Manifest m = repo.loadManifest(id);
				List<BlobRef> todo = new ArrayList<>();
				LongHashSet seen = new LongHashSet();
				synchronized (this) {
					m.forEachBlob(ref -> {
						long p = ref.hash().prefix64();
						if (seen.add(p) && !uploadedBlobs.contains(p)) todo.add(ref);
					});
				}
				progress.addTotal(todo.size(), todo.stream().mapToLong(BlobRef::storedLength).sum());
				List<Future<Long>> futures = new ArrayList<>();
				for (BlobRef ref : todo) {
					cancel.check();
					java.util.concurrent.Callable<Long> task = () -> {
						cancel.check();
						Path file = repo.blobs().pathFor(ref.hash());
						if (!Files.exists(file)) return 0L; // garbage-collected: nothing references it anymore
						target.upload(blobKey(ref.hash()), file);
						synchronized (OffsiteSync.this) {
							uploadedBlobs.add(ref.hash().prefix64());
						}
						progress.addDone(1, ref.storedLength());
						return Files.size(file);
					};
					if (parallel) {
						futures.add(workers.submit(task));
					} else {
						try {
							bytes += task.call();
							blobCount++;
						} catch (IOException | RuntimeException e) {
							throw e;
						} catch (Exception e) {
							throw new IOException(e);
						}
					}
				}
				for (Future<Long> f : futures) {
					try {
						bytes += f.get();
						blobCount++;
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
						throw new CancellationException("interrupted");
					} catch (ExecutionException e) {
						futures.forEach(x -> x.cancel(true));
						if (e.getCause() instanceof IOException io) throw io;
						if (e.getCause() instanceof CancellationException ce) throw ce;
						throw new IOException(e.getCause());
					}
				}
				Path bdir = repo.backupDir(id);
				String prefix = "backups/" + BackupRepository.dirName(id) + "/";
				target.upload(prefix + "manifest.bin", bdir.resolve("manifest.bin"));
				if (Files.exists(bdir.resolve("new-blobs.bin"))) target.upload(prefix + "new-blobs.bin", bdir.resolve("new-blobs.bin"));
				target.upload(prefix + "meta.json", bdir.resolve("meta.json"));
				synchronized (this) {
					queue.uploads.remove(id);
					queue.uploaded.add(id);
					save();
					saveIndex();
				}
				backups++;
			}
			List<String> keys;
			synchronized (this) {
				keys = new ArrayList<>(queue.deleteKeys);
			}
			if (!keys.isEmpty()) progress.phase(Lang.get("cytrabackups.phase.remote_delete", keys.size()));
			for (String key : keys) {
				cancel.check();
				target.delete(key);
				deleted++;
				synchronized (this) {
					queue.deleteKeys.remove(key);
					if (deleted % 200 == 0) save();
				}
			}
			synchronized (this) {
				queue.lastSuccess = System.currentTimeMillis();
				queue.lastError = "";
				save();
			}
		} catch (IOException | RuntimeException e) {
			synchronized (this) {
				queue.lastError = String.valueOf(e.getMessage());
				save();
				saveIndex();
			}
			throw e;
		}
		return new Result(backups, blobCount, bytes, deleted);
	}

	private void save() throws IOException {
		Json.write(dir.resolve("queue.json"), queue);
	}

	private void saveIndex() throws IOException {
		LongHashSet set = uploadedBlobs;
		FileUtil.writeAtomic(dir.resolve("uploaded-blobs.bin"), out -> {
			DeflaterOutputStream def = new DeflaterOutputStream(out);
			DataOutputStream d = new DataOutputStream(def);
			d.writeInt(set.size());
			IOException[] err = {null};
			set.forEach(v -> {
				try {
					d.writeLong(v);
				} catch (IOException e) {
					err[0] = e;
				}
			});
			if (err[0] != null) throw err[0];
			d.flush();
			def.finish();
		});
	}

	private static LongHashSet loadIndex(Path file) throws IOException {
		if (!Files.exists(file)) return new LongHashSet();
		try (InputStream in = Files.newInputStream(file)) {
			DataInputStream d = new DataInputStream(new InflaterInputStream(in));
			int n = d.readInt();
			LongHashSet set = new LongHashSet(Math.max(16, n));
			for (int i = 0; i < n; i++) set.add(d.readLong());
			return set;
		}
	}

	/** Remote keys of a backup, for logging/tests. */
	public static Set<String> backupKeys(int id) {
		String d = "backups/" + BackupRepository.dirName(id) + "/";
		return Set.of(d + "meta.json", d + "manifest.bin", d + "new-blobs.bin");
	}
}
