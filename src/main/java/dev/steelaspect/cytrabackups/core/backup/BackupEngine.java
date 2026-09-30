package dev.steelaspect.cytrabackups.core.backup;

import dev.steelaspect.cytrabackups.core.Lang;
import dev.steelaspect.cytrabackups.core.CancelToken;
import dev.steelaspect.cytrabackups.core.FileUtil;
import dev.steelaspect.cytrabackups.core.Hash;
import dev.steelaspect.cytrabackups.core.Progress;
import dev.steelaspect.cytrabackups.core.RateLimiter;
import dev.steelaspect.cytrabackups.core.manifest.ChunkRef;
import dev.steelaspect.cytrabackups.core.manifest.FileEntry;
import dev.steelaspect.cytrabackups.core.manifest.Manifest;
import dev.steelaspect.cytrabackups.core.manifest.ManifestEntry;
import dev.steelaspect.cytrabackups.core.manifest.RegionEntry;
import dev.steelaspect.cytrabackups.core.region.RegionFiles;
import dev.steelaspect.cytrabackups.core.store.BlobRef;
import dev.steelaspect.cytrabackups.core.store.BlobStore;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.function.Consumer;
import java.util.function.LongConsumer;
import java.util.function.Predicate;

/**
 * Scans a world folder into a {@link Manifest}, storing new content in the {@link BlobStore}.
 * Runs on the calling (job) thread and fans file work out to a worker pool; never touches the server thread.
 */
public final class BackupEngine {
	private final BlobStore blobs;
	private final ExecutorService workers;
	private final int parallelism;
	private final BackupSettings settings;
	private final RateLimiter rateLimiter;
	private final ThreadLocal<byte[]> buffers;

	public BackupEngine(BlobStore blobs, ExecutorService workers, int parallelism, BackupSettings settings) {
		this.blobs = blobs;
		this.workers = workers;
		this.parallelism = Math.max(1, parallelism);
		this.settings = settings;
		this.rateLimiter = new RateLimiter(settings.maxReadBytesPerSecond());
		this.buffers = ThreadLocal.withInitial(() -> new byte[settings.pieceSize()]);
	}

	/** Inputs for one scan. */
	public static final class Request {
		public Path worldDir;
		public PathFilter filter = PathFilter.ALL;
		/** Previous full manifest for size+mtime reuse; may be empty. */
		public Manifest previous = Manifest.empty();
		/** Optional restriction to specific paths (partial backups). */
		public Predicate<String> only = p -> true;
		/** Absolute paths never to descend into (e.g. the backup storage if it lives inside the world). */
		public List<Path> excludedDirs = List.of();
		public Progress progress = new Progress();
		public CancelToken cancel = CancelToken.NONE;
		/** Called with the estimated number of bytes that must be read before anything is written. May throw. */
		public LongConsumer beforeWrite = bytes -> {
		};
		public Consumer<String> warnings = w -> {
		};
	}

	public static final class Result {
		public Manifest manifest;
		public final List<Hash> newBlobs = Collections.synchronizedList(new ArrayList<>());
		public long newStoredBytes;
		public long reusedFiles;
		public long readFiles;
		public long readBytes;
		public final List<String> warnings = Collections.synchronizedList(new ArrayList<>());
	}

	private record Candidate(String path, Path file, long size, long mtime) {
	}

	private record TaskOutput(ManifestEntry entry, List<Hash> newBlobs, long newBytes, long readBytes) {
	}

	public Result run(Request req) throws IOException {
		Progress progress = req.progress;
		progress.phase(Lang.get("cytrabackups.phase.scanning"));
		List<Candidate> candidates = walk(req);
		req.cancel.check();

		Result result = new Result();
		List<ManifestEntry> entries = new ArrayList<>(candidates.size());
		List<Candidate> toRead = new ArrayList<>();
		long estimate = 0;
		for (Candidate c : candidates) {
			ManifestEntry prev = req.previous.get(c.path);
			if (settings.trustMtime() && prev != null && prev.size() == c.size && prev.mtime() == c.mtime && typeMatches(prev, c.path)) {
				entries.add(prev);
				result.reusedFiles++;
			} else {
				toRead.add(c);
				estimate += c.size;
			}
		}
		req.beforeWrite.accept(estimate);

		progress.phase(Lang.get("cytrabackups.phase.storing"));
		progress.addTotal(toRead.size(), estimate);
		progress.detail(Lang.get("cytrabackups.phase.detail.changed", toRead.size(), result.reusedFiles));

		Semaphore inFlight = new Semaphore(parallelism * 2);
		List<Future<TaskOutput>> futures = new ArrayList<>(toRead.size());
		try {
			for (Candidate c : toRead) {
				req.cancel.check();
				inFlight.acquire();
				futures.add(workers.submit(() -> {
					try {
						req.cancel.check();
						TaskOutput out = process(c, req, result);
						progress.addDone(1, c.size);
						return out;
					} finally {
						inFlight.release();
					}
				}));
			}
			for (Future<TaskOutput> f : futures) {
				TaskOutput out = f.get();
				if (out == null) continue;
				entries.add(out.entry);
				result.newBlobs.addAll(out.newBlobs);
				result.newStoredBytes += out.newBytes;
				result.readBytes += out.readBytes;
				result.readFiles++;
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			futures.forEach(f -> f.cancel(true));
			throw new CancellationException("interrupted");
		} catch (ExecutionException e) {
			futures.forEach(f -> f.cancel(true));
			Throwable cause = e.getCause();
			if (cause instanceof CancellationException ce) throw ce;
			if (cause instanceof IOException io) throw io;
			throw new IOException("Backup worker failed: " + cause, cause);
		} catch (CancellationException e) {
			futures.forEach(f -> f.cancel(true));
			throw e;
		}
		result.manifest = new Manifest(entries);
		return result;
	}

	private boolean typeMatches(ManifestEntry prev, String path) {
		boolean region = settings.chunkDedup() && isRegionPath(path);
		return region == (prev instanceof RegionEntry);
	}

	public static boolean isRegionPath(String path) {
		int slash = path.lastIndexOf('/');
		return RegionFiles.isRegionFileName(slash < 0 ? path : path.substring(slash + 1));
	}

	private List<Candidate> walk(Request req) throws IOException {
		List<Candidate> out = new ArrayList<>();
		Path world = req.worldDir.toAbsolutePath().normalize();
		List<Path> excluded = req.excludedDirs.stream().map(p -> p.toAbsolutePath().normalize()).toList();
		Files.walkFileTree(world, new SimpleFileVisitor<>() {
			@Override
			public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
				if (dir.equals(world)) return FileVisitResult.CONTINUE;
				for (Path ex : excluded) if (dir.equals(ex)) return FileVisitResult.SKIP_SUBTREE;
				String rel = FileUtil.relative(world, dir);
				if (req.filter.skipDirectory(rel)) return FileVisitResult.SKIP_SUBTREE;
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
				if (!attrs.isRegularFile()) return FileVisitResult.CONTINUE; // skip symlinks & specials
				String rel = FileUtil.relative(world, file);
				if (req.filter.test(rel) && req.only.test(rel)) {
					out.add(new Candidate(rel, file, attrs.size(), attrs.lastModifiedTime().toMillis()));
				}
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFileFailed(Path file, IOException exc) {
				if (!(exc instanceof NoSuchFileException)) req.warnings.accept("Cannot read " + file + ": " + exc.getMessage());
				return FileVisitResult.CONTINUE;
			}
		});
		return out;
	}

	private TaskOutput process(Candidate c, Request req, Result result) throws IOException {
		boolean region = settings.chunkDedup() && isRegionPath(c.path) && c.size >= RegionFiles.HEADER;
		BasicFileAttributes before;
		try {
			before = Files.readAttributes(c.file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
		} catch (NoSuchFileException e) {
			warn(req, result, "File disappeared during backup: " + c.path);
			return null;
		}
		TaskOutput out = null;
		int attempts = Math.max(1, settings.maxAttempts());
		for (int attempt = 1; attempt <= attempts; attempt++) {
			req.cancel.check();
			long size = before.size();
			long mtime = before.lastModifiedTime().toMillis();
			try {
				if (region) {
					try {
						out = readRegion(c, size, mtime, req);
					} catch (RegionFiles.RegionFormatException e) {
						if (attempt < attempts) {
							sleepQuietly(100);
							before = attrs(c.file);
							continue;
						}
						warn(req, result, "Region " + c.path + " is not a valid Anvil file (" + e.getMessage() + "); stored as a whole file");
						out = readPlain(c, size, mtime, req);
					}
				} else {
					out = readPlain(c, size, mtime, req);
				}
			} catch (NoSuchFileException e) {
				warn(req, result, "File disappeared during backup: " + c.path);
				return null;
			}
			BasicFileAttributes after = attrs(c.file);
			if (after == null) {
				warn(req, result, "File disappeared during backup: " + c.path);
				return null;
			}
			if (after.size() == before.size() && after.lastModifiedTime().equals(before.lastModifiedTime())) return out;
			if (attempt < attempts) {
				before = after;
				sleepQuietly(50);
			}
		}
		warn(req, result, "File kept changing while being read, stored last read: " + c.path);
		return out;
	}

	private static BasicFileAttributes attrs(Path file) throws IOException {
		try {
			return Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
		} catch (NoSuchFileException e) {
			return null;
		}
	}

	private static void warn(Request req, Result result, String msg) {
		result.warnings.add(msg);
		req.warnings.accept(msg);
	}

	private static void sleepQuietly(long ms) {
		try {
			Thread.sleep(ms);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new CancellationException("interrupted");
		}
	}

	private TaskOutput readPlain(Candidate c, long size, long mtime, Request req) throws IOException {
		byte[] buf = buffers.get();
		MessageDigest whole = Hash.newDigest();
		List<BlobRef> pieces = new ArrayList<>();
		List<Hash> fresh = new ArrayList<>();
		long newBytes = 0;
		long read = 0;
		boolean compressedFormat = false;
		try (InputStream in = Files.newInputStream(c.file)) {
			while (true) {
				int n = in.readNBytes(buf, 0, buf.length);
				if (n <= 0) break;
				req.cancel.check();
				rateLimiter.acquire(n);
				if (read == 0) compressedFormat = dev.steelaspect.cytrabackups.core.compress.Compression.looksCompressed(buf, 0, n);
				whole.update(buf, 0, n);
				BlobStore.PutResult put = blobs.put(buf, 0, n, compressedFormat);
				pieces.add(put.ref());
				if (put.isNew()) {
					fresh.add(put.ref().hash());
					newBytes += put.ref().storedLength();
				}
				read += n;
				if (n < buf.length) break;
			}
		}
		return new TaskOutput(new FileEntry(c.path, read, mtime, Hash.finish(whole), pieces), fresh, newBytes, read);
	}

	private TaskOutput readRegion(Candidate c, long size, long mtime, Request req) throws IOException {
		List<ChunkRef> chunks = new ArrayList<>();
		List<Hash> fresh = new ArrayList<>();
		long[] counters = new long[2]; // newBytes, readBytes
		try (FileChannel ch = FileChannel.open(c.file, StandardOpenOption.READ)) {
			rateLimiter.acquire(RegionFiles.HEADER);
			RegionFiles.read(ch, slot -> {
				req.cancel.check();
				rateLimiter.acquire(slot.payload().length);
				BlobStore.PutResult put = blobs.put(slot.payload(), 0, slot.payload().length, slot.isCompressed());
				chunks.add(new ChunkRef(slot.index(), slot.timestamp(), put.ref()));
				if (put.isNew()) {
					fresh.add(put.ref().hash());
					counters[0] += put.ref().storedLength();
				}
				counters[1] += slot.payload().length;
			});
		}
		return new TaskOutput(RegionEntry.create(c.path, size, mtime, chunks), fresh, counters[0], counters[1] + RegionFiles.HEADER);
	}
}
