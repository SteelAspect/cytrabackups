package dev.steelaspect.cytrabackups.core.backup;

import dev.steelaspect.cytrabackups.core.FileUtil;
import dev.steelaspect.cytrabackups.core.Hash;
import dev.steelaspect.cytrabackups.core.Json;
import dev.steelaspect.cytrabackups.core.manifest.Manifest;
import dev.steelaspect.cytrabackups.core.store.BlobStore;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.stream.Stream;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * On-disk repository layout:
 * <pre>
 * storage/
 *   blobs/ab/cd/&lt;sha256&gt;        content-addressed blobs
 *   backups/000042/meta.json      metadata
 *   backups/000042/manifest.bin   file list pointing at blobs
 *   backups/000042/new-blobs.bin  blobs first written by this backup (for off-site sync)
 *   state.json                    id counter + scheduler timestamps
 * </pre>
 * A backup directory only becomes visible once fully written (atomic rename from {@code .partial}).
 */
public final class BackupRepository {
	private final Path root;
	private final Path backupsDir;
	private final BlobStore blobs;
	private final Map<Integer, BackupMeta> metas = new ConcurrentSkipListMap<>();
	private RepositoryState state;
	/** Soft so a huge world's manifest can be reclaimed under memory pressure; it is only a speed-up. */
	private volatile java.lang.ref.SoftReference<CachedManifest> cachedManifest = new java.lang.ref.SoftReference<>(null);

	private record CachedManifest(int id, Manifest manifest) {
	}

	public BackupRepository(Path root, BlobStore blobs) throws IOException {
		this.root = root;
		this.backupsDir = root.resolve("backups");
		this.blobs = blobs;
		Files.createDirectories(backupsDir);
		cleanupPartials();
		reload();
	}

	public Path root() {
		return root;
	}

	public BlobStore blobs() {
		return blobs;
	}

	private void cleanupPartials() throws IOException {
		try (Stream<Path> s = Files.list(backupsDir)) {
			for (Path p : s.toList()) {
				String name = p.getFileName().toString();
				if (name.endsWith(".partial") || name.endsWith(".deleting")) FileUtil.deleteRecursively(p);
			}
		}
	}

	public synchronized void reload() throws IOException {
		metas.clear();
		try (Stream<Path> s = Files.list(backupsDir)) {
			for (Path dir : s.toList()) {
				String name = dir.getFileName().toString();
				if (!name.matches("\\d+")) continue;
				try {
					BackupMeta meta = Json.read(dir.resolve("meta.json"), BackupMeta.class);
					metas.put(meta.id, meta);
				} catch (IOException e) {
					// unreadable metadata: skip, `verify` will report it
				}
			}
		}
		RepositoryState s = Json.readOrNull(root.resolve("state.json"), RepositoryState.class);
		state = s != null ? s : new RepositoryState();
		int maxId = metas.keySet().stream().mapToInt(Integer::intValue).max().orElse(0);
		if (state.nextId <= maxId) state.nextId = maxId + 1;
	}

	public static String dirName(int id) {
		return String.format(java.util.Locale.ROOT, "%06d", id);
	}

	public Path backupDir(int id) {
		return backupsDir.resolve(dirName(id));
	}

	public synchronized int allocateId() throws IOException {
		int id = state.nextId++;
		saveState();
		return id;
	}

	public synchronized RepositoryState state() {
		return state;
	}

	public synchronized void saveState() throws IOException {
		Json.write(root.resolve("state.json"), state);
	}

	/** All backups sorted by id (oldest first). */
	public List<BackupMeta> list() {
		return new ArrayList<>(metas.values());
	}

	public Optional<BackupMeta> get(int id) {
		return Optional.ofNullable(metas.get(id));
	}

	public Optional<BackupMeta> latest() {
		BackupMeta last = null;
		for (BackupMeta m : metas.values()) last = m;
		return Optional.ofNullable(last);
	}

	/** Latest complete (non-partial) backup, used as the baseline for incremental scans. */
	public Optional<BackupMeta> latestFull() {
		BackupMeta last = null;
		for (BackupMeta m : metas.values()) if (!m.partial) last = m;
		return Optional.ofNullable(last);
	}

	public void save(BackupMeta meta, Manifest manifest, List<Hash> newBlobs) throws IOException {
		Path finalDir = backupDir(meta.id);
		Path partial = backupsDir.resolve(dirName(meta.id) + ".partial");
		FileUtil.deleteRecursively(partial);
		Files.createDirectories(partial);
		MessageDigest md = Hash.newDigest();
		FileUtil.writeAtomic(partial.resolve("manifest.bin"), out -> {
			DigestOutputStream dout = new DigestOutputStream(out, md);
			manifest.write(dout);
			dout.flush();
		});
		meta.manifestSha256 = Hash.finish(md).hex();
		FileUtil.writeAtomic(partial.resolve("new-blobs.bin"), out -> writeHashes(out, newBlobs));
		Json.write(partial.resolve("meta.json"), meta);
		if (Files.exists(finalDir)) throw new IOException("Backup directory already exists: " + finalDir);
		FileUtil.move(partial, finalDir, false);
		metas.put(meta.id, meta);
		cachedManifest = new java.lang.ref.SoftReference<>(new CachedManifest(meta.id, manifest));
	}

	public void updateMeta(BackupMeta meta) throws IOException {
		if (!metas.containsKey(meta.id)) throw new IOException("No backup #" + meta.id);
		Json.write(backupDir(meta.id).resolve("meta.json"), meta);
		metas.put(meta.id, meta);
	}

	public Manifest loadManifest(int id) throws IOException {
		CachedManifest c = cachedManifest.get();
		if (c != null && c.id == id) return c.manifest;
		BackupMeta meta = metas.get(id);
		if (meta == null) throw new NoSuchFileException("No backup #" + id);
		Path file = backupDir(id).resolve("manifest.bin");
		MessageDigest md = Hash.newDigest();
		Manifest m;
		try (InputStream in = new DigestInputStream(Files.newInputStream(file), md)) {
			m = Manifest.read(in);
			in.transferTo(java.io.OutputStream.nullOutputStream());
		}
		String actual = Hash.finish(md).hex();
		if (!meta.manifestSha256.isEmpty() && !meta.manifestSha256.equals(actual)) {
			throw new IOException("Manifest of backup #" + id + " is corrupt (checksum mismatch)");
		}
		cachedManifest = new java.lang.ref.SoftReference<>(new CachedManifest(id, m));
		return m;
	}

	public List<Hash> loadNewBlobs(int id) throws IOException {
		Path file = backupDir(id).resolve("new-blobs.bin");
		if (!Files.exists(file)) return List.of();
		try (InputStream in = Files.newInputStream(file)) {
			return readHashes(in);
		}
	}

	public void delete(int id) throws IOException {
		Path dir = backupDir(id);
		Path trash = backupsDir.resolve(dirName(id) + ".deleting");
		if (Files.exists(dir)) {
			FileUtil.move(dir, trash, true);
			FileUtil.deleteRecursively(trash);
		}
		metas.remove(id);
		CachedManifest c = cachedManifest.get();
		if (c != null && c.id == id) cachedManifest.clear();
	}

	/** Invalidates the in-memory manifest cache (e.g. before verification, to force a disk read). */
	public void dropCache() {
		cachedManifest.clear();
	}

	static void writeHashes(java.io.OutputStream raw, List<Hash> hashes) throws IOException {
		DeflaterOutputStream def = new DeflaterOutputStream(raw);
		DataOutputStream out = new DataOutputStream(def);
		out.writeInt(hashes.size());
		for (Hash h : hashes) h.writeTo(out);
		out.flush();
		def.finish();
	}

	static List<Hash> readHashes(InputStream raw) throws IOException {
		DataInputStream in = new DataInputStream(new InflaterInputStream(raw));
		int n = in.readInt();
		List<Hash> out = new ArrayList<>(n);
		for (int i = 0; i < n; i++) out.add(Hash.readFrom(in));
		return out;
	}
}
