package dev.steelaspect.cytrabackups.core.offsite;

import dev.steelaspect.cytrabackups.core.CancelToken;
import dev.steelaspect.cytrabackups.core.Formatting;
import dev.steelaspect.cytrabackups.core.Hash;
import dev.steelaspect.cytrabackups.core.manifest.Manifest;
import dev.steelaspect.cytrabackups.core.store.BlobStore;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

/**
 * Off-site only mode: uploads every blob a backup writes while the backup is still running, then deletes it locally.
 * At most {@code bufferBytes} of not yet uploaded data are kept here; writers wait for uploads beyond that. A failed
 * upload is retried a few times, after that the backup is aborted (what was uploaded so far is reused by the next try).
 * Blobs an interrupted backup left here without uploading are uploaded first, so local data stays bounded.
 */
public final class StreamingUpload implements BlobStore.WriteHook, AutoCloseable {
	private static final long[] RETRY_DELAYS_MS = {2_000, 10_000, 30_000};

	private final OffsiteSync sync;
	private final BlobStore blobs;
	private final OffsiteTarget target;
	private final ExecutorService workers;
	private final int threads;
	private final long bufferBytes;
	private final Runnable onFirstWait;
	private final Consumer<String> log;
	private final Consumer<String> info;
	private final long[] retryDelaysMs;
	private final CancelToken cancel;
	private long lastReport = System.currentTimeMillis();
	private long reportedBlobs;
	private long reportedBytes;

	private long pendingBytes;
	private int pendingCount;
	private int running;
	private final java.util.ArrayDeque<Item> queue = new java.util.ArrayDeque<>();
	private IOException failure;
	private boolean waited;
	private boolean closed;
	private long uploaded;
	private long uploadedBytes;

	private record Item(Hash hash, long bytes) {
	}

	/**
	 * @param onFirstWait runs once, the first time a writer has to wait for uploads (the backup is then upload-bound)
	 * @param log         problems (failed attempts that are retried)
	 * @param info        a progress line about once a minute
	 */
	public StreamingUpload(OffsiteSync sync, BlobStore blobs, OffsiteTarget target, ExecutorService workers, int threads, long bufferBytes,
						   Runnable onFirstWait, Consumer<String> log, Consumer<String> info, CancelToken cancel) throws IOException {
		this(sync, blobs, target, workers, threads, bufferBytes, onFirstWait, log, info, cancel, RETRY_DELAYS_MS);
	}

	StreamingUpload(OffsiteSync sync, BlobStore blobs, OffsiteTarget target, ExecutorService workers, int threads, long bufferBytes,
					Runnable onFirstWait, Consumer<String> log, Consumer<String> info, CancelToken cancel, long[] retryDelaysMs) throws IOException {
		this.retryDelaysMs = retryDelaysMs;
		this.cancel = cancel;
		this.info = info;
		this.sync = sync;
		this.blobs = blobs;
		this.target = target;
		this.workers = workers;
		this.threads = Math.max(1, threads);
		this.bufferBytes = Math.max(1, bufferBytes);
		this.onFirstWait = onFirstWait;
		this.log = log;
		sync.bindTarget(target.id());
		sync.startSession();
		uploadLeftovers();
	}

	/** Queues blobs stored here that are not uploaded yet (left by an interrupted backup). */
	private void uploadLeftovers() throws IOException {
		List<Hash> hashes = new ArrayList<>();
		List<Long> sizes = new ArrayList<>();
		blobs.forEach((hash, attrs) -> {
			if (!sync.isUploaded(hash)) {
				hashes.add(hash);
				sizes.add(attrs.size());
			}
		});
		if (hashes.isEmpty()) return;
		long bytes = sizes.stream().mapToLong(Long::longValue).sum();
		info.accept("Uploading " + hashes.size() + " pieces (" + Formatting.bytes(bytes) + ") left here by an earlier backup first");
		for (int i = 0; i < hashes.size(); i++) written(hashes.get(i), sizes.get(i));
	}

	@Override
	public void beforeWrite(long bytes) throws IOException {
		boolean first = false;
		synchronized (this) {
			while (failure == null && pendingCount > 0 && pendingBytes + bytes > bufferBytes) {
				cancel.check();
				if (!waited) {
					waited = true;
					first = true;
					break;
				}
				await();
			}
			if (failure != null) throw new IOException("Off-site upload failed: " + failure.getMessage(), failure);
		}
		if (first) {
			onFirstWait.run();
			beforeWrite(bytes);
		}
	}

	@Override
	public void written(Hash hash, long storedBytes) {
		synchronized (this) {
			pendingBytes += storedBytes;
			pendingCount++;
			queue.add(new Item(hash, storedBytes));
			if (running >= threads) return;
			running++;
		}
		try {
			workers.execute(this::drainQueue);
		} catch (java.util.concurrent.RejectedExecutionException e) {
			synchronized (this) {
				running--;
				if (failure == null) failure = new IOException("upload workers stopped", e);
				notifyAll();
			}
		}
	}

	/** Upload loop of one worker: takes items until the queue is empty. */
	private void drainQueue() {
		while (true) {
			Item item;
			synchronized (this) {
				item = failure == null && !closed ? queue.poll() : null;
				if (item == null) {
					running--;
					notifyAll();
					return;
				}
			}
			try {
				upload(item.hash);
				String line = null;
				synchronized (this) {
					uploaded++;
					uploadedBytes += item.bytes;
					long now = System.currentTimeMillis();
					if (now - lastReport >= 60_000L) {
						long rate = (uploadedBytes - reportedBytes) * 1000L / Math.max(1, now - lastReport);
						line = "Uploaded " + uploaded + " pieces (" + Formatting.bytes(uploadedBytes) + ") so far, " + Formatting.bytes(rate) + "/s ("
							+ (uploaded - reportedBlobs) * 1000L / Math.max(1, now - lastReport) + " pieces/s), " + Formatting.bytes(Math.max(0, pendingBytes - item.bytes))
							+ " waiting";
						lastReport = now;
						reportedBlobs = uploaded;
						reportedBytes = uploadedBytes;
					}
				}
				if (line != null) info.accept(line);
			} catch (IOException | RuntimeException e) {
				synchronized (this) {
					if (failure == null) failure = e instanceof IOException io ? io : new IOException(e.toString(), e);
				}
			} finally {
				synchronized (this) {
					pendingBytes -= item.bytes;
					pendingCount--;
					notifyAll();
				}
			}
		}
	}

	private void upload(Hash hash) throws IOException {
		Path file = blobs.pathFor(hash);
		for (int attempt = 0; ; attempt++) {
			try {
				target.upload(OffsiteSync.blobKey(hash), file);
				break;
			} catch (NoSuchFileException e) {
				if (sync.isUploaded(hash) || !Files.exists(file)) return; // uploaded (and evicted) by the background sync
				throw e;
			} catch (IOException e) {
				if (attempt >= retryDelaysMs.length) throw e;
				log.accept("Upload of " + hash.shortHex() + " failed (" + e.getMessage() + "), retrying");
				sleep(retryDelaysMs[attempt]);
				synchronized (this) {
					if (closed) throw new InterruptedIOException("upload stopped");
				}
			}
		}
		sync.markUploaded(hash);
		sync.recordSessionUpload(hash);
		sync.evicted(blobs, List.of(hash));
	}

	private static void sleep(long ms) throws InterruptedIOException {
		try {
			Thread.sleep(ms);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new InterruptedIOException("interrupted");
		}
	}

	private void await() throws InterruptedIOException {
		try {
			wait(1000);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new InterruptedIOException("interrupted");
		}
	}

	/** Waits until every written blob is uploaded. Throws the upload failure, if any. */
	public void finish() throws IOException {
		synchronized (this) {
			while (failure == null && (pendingCount > 0 || running > 0)) {
				cancel.check();
				await();
			}
			if (failure != null) throw new IOException("Off-site upload failed: " + failure.getMessage(), failure);
		}
	}

	/** Bytes written here and not uploaded yet. */
	public synchronized long pendingBytes() {
		return pendingBytes;
	}

	/** True if a writer had to wait for uploads at some point. */
	public synchronized boolean waited() {
		return waited;
	}

	public synchronized long uploadedBlobs() {
		return uploaded;
	}

	public synchronized long uploadedBytes() {
		return uploadedBytes;
	}

	/**
	 * Stops uploading (blobs not uploaded yet stay local; the regular sync uploads them later) and ends the session:
	 * {@code committed} is the manifest of the backup that was made, or null if it failed.
	 */
	public void close(Manifest committed) throws IOException {
		synchronized (this) {
			closed = true;
			while (running > 0) await();
		}
		sync.endSession(committed);
		sync.checkpoint();
	}

	@Override
	public void close() throws IOException {
		close(null);
	}
}
