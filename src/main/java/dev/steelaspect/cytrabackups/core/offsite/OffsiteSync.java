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
import dev.steelaspect.cytrabackups.core.store.BlobStore;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.function.Consumer;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * Mirrors the repository to an {@link OffsiteTarget}. A persistent queue survives restarts; a local index of
 * uploaded blobs avoids re-uploading deduplicated data. meta.json is uploaded last so a remote backup only
 * "exists" once all of its data is there.
 *
 * <p>In off-site only mode uploaded blobs are deleted locally ("evicted"); the index then also says where they are.
 * Blobs uploaded while a backup is still being made are listed in {@code session-blobs.bin}; the ones its manifest
 * does not use end up in {@code orphans.bin} for the next garbage collection.
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
		/** Some backup data was deleted here after uploading: it only exists at {@link #targetId}. */
		public boolean remoteOnly;
	}

	public record Result(int backupsUploaded, long blobsUploaded, long bytesUploaded, int deleted) {
	}

	private final BackupRepository repo;
	private final Path dir;
	private final Consumer<String> log;
	private Queue queue;
	private LongHashSet uploadedBlobs;
	long lastIndexSave = System.currentTimeMillis();
	/** Times the upload index was written (tests check that uploads do not rewrite it every time). */
	int indexWrites;
	private DataOutputStream session;

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

	/** True if the blob is stored at the destination (uploaded and not queued for deletion). */
	public synchronized boolean isUploaded(Hash h) {
		return uploadedBlobs.contains(h.prefix64());
	}

	/** True if some backup data exists only at the destination. */
	public synchronized boolean hasRemoteOnlyData() {
		return queue.remoteOnly;
	}

	/** Records a finished blob upload. A deletion of the same blob that is still queued is dropped. */
	public synchronized void markUploaded(Hash h) throws IOException {
		uploadedBlobs.add(h.prefix64());
		queue.deleteKeys.remove(blobKey(h));
		if (System.currentTimeMillis() - lastIndexSave > 120_000L) checkpoint();
	}

	/** Saves the upload index (and the session list) so a restart knows what is already stored off-site. */
	public synchronized void checkpoint() throws IOException {
		save();
		saveIndex();
		if (session != null) session.flush();
	}

	/**
	 * Deletes local blob files that are stored at the destination (off-site only mode). Returns the bytes freed.
	 * Blobs queued for remote deletion are not in the index, so they are never evicted.
	 */
	public long evictUploaded(BlobStore blobs) throws IOException {
		List<Hash> uploaded = new ArrayList<>();
		long[] bytes = {0};
		blobs.forEach((hash, attrs) -> {
			if (isUploaded(hash)) {
				uploaded.add(hash);
				bytes[0] += attrs.size();
			}
		});
		evicted(blobs, uploaded);
		return bytes[0];
	}

	/** Deletes the local files of blobs that were just uploaded. */
	public void evicted(BlobStore blobs, List<Hash> hashes) throws IOException {
		if (hashes.isEmpty()) return;
		synchronized (this) {
			if (!queue.remoteOnly) {
				queue.remoteOnly = true;
				save();
			}
		}
		for (Hash h : hashes) {
			if (isUploaded(h)) blobs.delete(h);
		}
	}

	/** Starts listing the blobs uploaded while a backup is being made. Leftovers of an interrupted backup become orphans. */
	public synchronized void startSession() throws IOException {
		endSession(null);
		session = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(dir.resolve("session-blobs.bin"),
			StandardOpenOption.CREATE, StandardOpenOption.APPEND), 1 << 16));
	}

	public synchronized void recordSessionUpload(Hash h) throws IOException {
		if (session != null) h.writeTo(session);
	}

	/**
	 * Ends the session. Uploaded blobs that {@code committed} (the new backup's manifest, or null if it failed) does not
	 * use are moved to the orphan list.
	 */
	public synchronized void endSession(Manifest committed) throws IOException {
		if (session != null) {
			session.close();
			session = null;
		}
		Path file = dir.resolve("session-blobs.bin");
		if (!Files.exists(file)) return;
		LongHashSet used = new LongHashSet();
		if (committed != null) committed.forEachBlob(ref -> used.add(ref.hash().prefix64()));
		DataOutputStream[] out = {null};
		try {
			forEachHash(file, h -> {
				if (used.contains(h.prefix64())) return;
				if (out[0] == null) {
					out[0] = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(dir.resolve("orphans.bin"),
						StandardOpenOption.CREATE, StandardOpenOption.APPEND)));
				}
				h.writeTo(out[0]);
			});
		} finally {
			if (out[0] != null) out[0].close();
		}
		Files.delete(file);
	}

	/**
	 * Queues the remote deletion of orphaned uploads that no backup uses ({@code live}: every blob of every local backup,
	 * by hash prefix). Returns how many were queued.
	 */
	public synchronized int sweepOrphans(LongHashSet live) throws IOException {
		Path file = dir.resolve("orphans.bin");
		if (!Files.exists(file)) return 0;
		List<Hash> dead = new ArrayList<>();
		LongHashSet seen = new LongHashSet();
		forEachHash(file, h -> {
			if (!live.contains(h.prefix64()) && seen.add(h.prefix64())) dead.add(h);
		});
		int before = queue.deleteKeys.size();
		enqueueDeleteBlobs(dead);
		Files.delete(file);
		return queue.deleteKeys.size() - before;
	}

	private interface HashVisitor {
		void accept(Hash h) throws IOException;
	}

	/** Streams a list of 32-byte hashes; a torn last record (crash while appending) is ignored. */
	private static void forEachHash(Path file, HashVisitor visitor) throws IOException {
		long count = Files.size(file) / 32;
		try (DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(Files.newInputStream(file), 1 << 16))) {
			for (long i = 0; i < count; i++) visitor.accept(Hash.readFrom(in));
		}
	}

	/**
	 * Binds the upload state to a destination. When the destination changes (type, host, bucket, prefix, folder...),
	 * everything is considered not uploaded there yet and pending remote deletions for the old one are dropped.
	 * Returns true if the destination is new (the caller then queues every backup for it).
	 */
	public synchronized boolean bindTarget(String targetId) throws IOException {
		if (targetId.equals(queue.targetId)) return false;
		if (queue.remoteOnly && !queue.targetId.isEmpty()) {
			throw new IOException(Lang.get("cytrabackups.offsite.remote_only_moved", queue.targetId));
		}
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
		return process(target, workers, parallel ? 3 : 1, progress, cancel);
	}

	/** Processes the queue against the target with up to {@code threads} uploads at a time (1: one after another). */
	public Result process(OffsiteTarget target, ExecutorService workers, int threads, Progress progress, CancelToken cancel) throws IOException {
		boolean parallel = threads > 1;
		Semaphore inFlight = new Semaphore(Math.max(1, threads));
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
						if (!Files.exists(file)) return 0L; // garbage-collected, or uploaded and evicted meanwhile
						long size;
						try {
							size = Files.size(file);
							target.upload(blobKey(ref.hash()), file);
						} catch (NoSuchFileException e) {
							if (isUploaded(ref.hash())) return 0L; // uploaded and evicted by a running backup meanwhile
							throw e;
						}
						markUploaded(ref.hash());
						progress.addDone(1, ref.storedLength());
						return size;
					};
					if (parallel) {
						inFlight.acquireUninterruptibly();
						futures.add(workers.submit(() -> {
							try {
								return task.call();
							} finally {
								inFlight.release();
							}
						}));
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

	/** A backup stored at the target, with its metadata (downloaded on the spot). */
	public record RemoteBackup(int id, BackupMeta meta) {
	}

	/** Backups present at the target, oldest first. */
	public List<RemoteBackup> listRemote(OffsiteTarget target) throws IOException {
		List<RemoteBackup> out = new ArrayList<>();
		Path tmp = Files.createTempFile(dir, "remote-meta", ".json");
		try {
			TreeSet<Integer> ids = new TreeSet<>();
			for (String key : target.list("backups/")) {
				if (!key.endsWith("/meta.json")) continue;
				String name = key.substring("backups/".length(), key.length() - "/meta.json".length());
				if (name.matches("\\d+")) ids.add(Integer.parseInt(name));
			}
			for (int id : ids) {
				target.download("backups/" + BackupRepository.dirName(id) + "/meta.json", tmp);
				BackupMeta meta = Json.read(tmp, BackupMeta.class);
				out.add(new RemoteBackup(id, meta));
			}
		} finally {
			Files.deleteIfExists(tmp);
		}
		return out;
	}

	/**
	 * Downloads backup {@code id} from the target into the local repository: metadata, manifest and every blob not already
	 * stored here (each verified against its hash). Returns the number of blobs downloaded.
	 */
	public int fetch(OffsiteTarget target, int id, ExecutorService workers, boolean parallel, Progress progress, CancelToken cancel) throws IOException {
		return fetch(target, id, workers, parallel ? 3 : 1, progress, cancel);
	}

	public int fetch(OffsiteTarget target, int id, ExecutorService workers, int threads, Progress progress, CancelToken cancel) throws IOException {
		return fetch(target, id, workers, threads, true, progress, cancel);
	}

	/** {@code withBlobs} false (off-site only mode): only the backup's metadata is fetched; its data is read off-site when needed. */
	public int fetch(OffsiteTarget target, int id, ExecutorService workers, int threads, boolean withBlobs, Progress progress, CancelToken cancel) throws IOException {
		boolean parallel = threads > 1;
		Semaphore inFlight = new Semaphore(Math.max(1, threads));
		if (repo.get(id).isPresent()) throw new IOException(Lang.get("cytrabackups.offsite.already_local", id));
		String prefix = "backups/" + BackupRepository.dirName(id) + "/";
		Path staging = repo.stagingDir(id);
		FileUtil.deleteRecursively(staging);
		Files.createDirectories(staging);
		progress.phase(Lang.get("cytrabackups.phase.fetch", id, target.describe()));
		try {
			target.download(prefix + "meta.json", staging.resolve("meta.json"));
		} catch (java.nio.file.NoSuchFileException e) {
			FileUtil.deleteRecursively(staging);
			throw new IOException(Lang.get("cytrabackups.offsite.not_remote", id, target.describe()));
		}
		target.download(prefix + "manifest.bin", staging.resolve("manifest.bin"));
		try {
			target.download(prefix + "new-blobs.bin", staging.resolve("new-blobs.bin"));
		} catch (java.nio.file.NoSuchFileException ignored) {
		}
		BackupMeta meta = Json.read(staging.resolve("meta.json"), BackupMeta.class);
		if (meta.id != id) throw new IOException("Remote metadata of #" + id + " belongs to #" + meta.id);
		Manifest manifest;
		java.security.MessageDigest md = Hash.newDigest();
		try (InputStream in = new java.security.DigestInputStream(Files.newInputStream(staging.resolve("manifest.bin")), md)) {
			manifest = Manifest.read(in);
			in.transferTo(java.io.OutputStream.nullOutputStream());
		}
		if (!meta.manifestSha256.isEmpty() && !meta.manifestSha256.equals(Hash.finish(md).hex())) {
			throw new IOException("Downloaded manifest of #" + id + " is corrupt (checksum mismatch)");
		}
		List<BlobRef> todo = new ArrayList<>();
		LongHashSet seen = new LongHashSet();
		if (withBlobs) {
			manifest.forEachBlob(ref -> {
				if (seen.add(ref.hash().prefix64()) && !repo.blobs().exists(ref.hash())) todo.add(ref);
			});
		} else {
			synchronized (this) {
				bindTarget(target.id());
			}
		}
		progress.addTotal(todo.size(), todo.stream().mapToLong(BlobRef::storedLength).sum());
		Path tmpDir = dir.resolve("fetch-tmp");
		Files.createDirectories(tmpDir);
		List<Future<?>> futures = new ArrayList<>();
		try {
			for (BlobRef ref : todo) {
				cancel.check();
				java.util.concurrent.Callable<Void> task = () -> {
					cancel.check();
					Path tmp = tmpDir.resolve(ref.hash().hex() + ".blob");
					target.download(blobKey(ref.hash()), tmp);
					repo.blobs().adopt(tmp, ref.hash());
					progress.addDone(1, ref.storedLength());
					return null;
				};
				if (parallel) {
					futures.add(workers.submit(task));
				} else {
					try {
						task.call();
					} catch (IOException | RuntimeException e) {
						throw e;
					} catch (Exception e) {
						throw new IOException(e);
					}
				}
			}
			for (Future<?> f : futures) {
				try {
					f.get();
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
		} catch (IOException | RuntimeException e) {
			FileUtil.deleteRecursively(staging);
			throw e;
		} finally {
			FileUtil.deleteRecursively(tmpDir);
		}
		repo.adopt(id);
		synchronized (this) {
			bindTarget(target.id()); // what was fetched from this destination is known to exist there
			queue.uploads.remove(id);
			queue.uploaded.add(id);
			manifest.forEachBlob(ref -> uploadedBlobs.add(ref.hash().prefix64()));
			if (!withBlobs) queue.remoteOnly = true;
			save();
			saveIndex();
		}
		return todo.size();
	}

	private void save() throws IOException {
		Json.write(dir.resolve("queue.json"), queue);
	}

	private void saveIndex() throws IOException {
		lastIndexSave = System.currentTimeMillis();
		indexWrites++;
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
