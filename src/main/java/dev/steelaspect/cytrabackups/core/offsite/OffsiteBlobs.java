package dev.steelaspect.cytrabackups.core.offsite;

import dev.steelaspect.cytrabackups.core.Hash;
import dev.steelaspect.cytrabackups.core.store.BlobStore;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;

/** The off-site copy as seen by the {@link BlobStore}: which blobs it holds, and downloads of the ones missing locally. */
public final class OffsiteBlobs implements BlobStore.RemoteBlobs, AutoCloseable {
	/** Creates the destination client; called once, on the first download. */
	public interface TargetFactory {
		OffsiteTarget create() throws IOException;
	}

	private final OffsiteSync sync;
	private final TargetFactory factory;
	private final ExecutorService pool;
	private final int threads;
	private OffsiteTarget target;

	public OffsiteBlobs(OffsiteSync sync, TargetFactory factory, ExecutorService pool, int threads) {
		this.sync = sync;
		this.factory = factory;
		this.pool = pool;
		this.threads = Math.max(1, threads);
	}

	@Override
	public boolean has(Hash hash) {
		return sync.isUploaded(hash);
	}

	private synchronized OffsiteTarget target() throws IOException {
		if (target == null) {
			try {
				target = factory.create();
			} catch (IllegalArgumentException e) {
				throw new IOException("Off-site copy not configured: " + e.getMessage(), e);
			}
		}
		return target;
	}

	@Override
	public void download(Hash hash, Path dest) throws IOException {
		target().download(OffsiteSync.blobKey(hash), dest);
	}

	@Override
	public void downloadAll(List<Hash> hashes, List<Path> dests) throws IOException {
		if (threads == 1 || hashes.size() < 2) {
			for (int i = 0; i < hashes.size(); i++) download(hashes.get(i), dests.get(i));
			return;
		}
		OffsiteTarget t = target();
		Semaphore inFlight = new Semaphore(threads);
		List<Future<?>> futures = new ArrayList<>(hashes.size());
		try {
			for (int i = 0; i < hashes.size(); i++) {
				Hash h = hashes.get(i);
				Path d = dests.get(i);
				inFlight.acquire();
				futures.add(pool.submit(() -> {
					try {
						t.download(OffsiteSync.blobKey(h), d);
						return null;
					} finally {
						inFlight.release();
					}
				}));
			}
			for (Future<?> f : futures) f.get();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			futures.forEach(f -> f.cancel(true));
			throw new InterruptedIOException("interrupted");
		} catch (ExecutionException e) {
			futures.forEach(f -> f.cancel(true));
			if (e.getCause() instanceof IOException io) throw io;
			throw new IOException(String.valueOf(e.getCause()), e.getCause());
		}
	}

	@Override
	public synchronized void close() throws IOException {
		if (target != null) target.close();
		target = null;
	}
}
