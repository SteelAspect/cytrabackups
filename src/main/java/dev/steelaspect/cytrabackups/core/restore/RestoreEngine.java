package dev.steelaspect.cytrabackups.core.restore;

import dev.steelaspect.cytrabackups.core.Lang;
import dev.steelaspect.cytrabackups.core.CancelToken;
import dev.steelaspect.cytrabackups.core.FileUtil;
import dev.steelaspect.cytrabackups.core.Hash;
import dev.steelaspect.cytrabackups.core.Json;
import dev.steelaspect.cytrabackups.core.Progress;
import dev.steelaspect.cytrabackups.core.backup.BackupEngine;
import dev.steelaspect.cytrabackups.core.backup.ChunkSelection;
import dev.steelaspect.cytrabackups.core.backup.DimensionPaths;
import dev.steelaspect.cytrabackups.core.backup.PathFilter;
import dev.steelaspect.cytrabackups.core.manifest.ChunkRef;
import dev.steelaspect.cytrabackups.core.manifest.FileEntry;
import dev.steelaspect.cytrabackups.core.manifest.Manifest;
import dev.steelaspect.cytrabackups.core.manifest.ManifestEntry;
import dev.steelaspect.cytrabackups.core.manifest.RegionEntry;
import dev.steelaspect.cytrabackups.core.region.RegionFiles;
import dev.steelaspect.cytrabackups.core.store.BlobRef;
import dev.steelaspect.cytrabackups.core.store.BlobStore;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Applies restores to a world folder that is not in use (server stopped / before the world loads).
 *
 * <ol>
 *   <li>Everything is first materialized into a staging folder next to the world and verified against its hash.</li>
 *   <li>Replaced files are moved into the recycle bin, staged files are moved into place. Every step is journaled
 *       so a crash mid-swap is rolled back on the next start.</li>
 *   <li>Every placed file is re-hashed in place. Any mismatch rolls the whole swap back automatically.</li>
 * </ol>
 */
public final class RestoreEngine {
	private static final DateTimeFormatter ID_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

	private final BlobStore blobs;
	private final ExecutorService workers;
	private final Path storageRoot;
	private final Path journalFile;
	private final Path recycleRoot;
	private final Path recordsDir;
	private final boolean trustMtime;

	public RestoreEngine(BlobStore blobs, ExecutorService workers, Path storageRoot, boolean trustMtime) throws IOException {
		this.blobs = blobs;
		this.workers = workers;
		this.storageRoot = storageRoot;
		this.journalFile = storageRoot.resolve("restore-journal.log");
		this.recycleRoot = storageRoot.resolve("recycle");
		this.recordsDir = storageRoot.resolve("restores");
		this.trustMtime = trustMtime;
		Files.createDirectories(recycleRoot);
		Files.createDirectories(recordsDir);
	}

	public static final class VerificationException extends IOException {
		public VerificationException(String message) {
			super(message);
		}
	}

	public static String newRestoreId(RestoreRecord.Kind kind, int backupId) {
		return ID_TIME.format(LocalDateTime.now()) + "-" + kind.name().toLowerCase(java.util.Locale.ROOT) + "-" + backupId;
	}

	public RestorePlan planFull(Manifest manifest, int backupId, boolean partialBackup, Path world, PathFilter filter,
								List<Path> excludedDirs, Progress progress, CancelToken cancel) throws IOException {
		progress.phase(Lang.get("cytrabackups.phase.plan_restore"));
		List<String> notes = new ArrayList<>();
		Map<String, Path> current = walkWorld(world, filter, excludedDirs);
		List<Callable<RestorePlan.Op>> tasks = new ArrayList<>();
		int[] skippedByFilter = {0};
		for (ManifestEntry e : manifest.entries()) {
			if (!filter.test(e.path())) {
				skippedByFilter[0]++;
				continue;
			}
			Path existing = current.get(e.path());
			tasks.add(() -> {
				if (existing == null) return new RestorePlan.Op(e.path(), RestorePlan.Action.PLACE, new RestorePlan.FromEntry(e), null, false);
				boolean region = e instanceof RegionEntry;
				Hash cur = currentHashFor(existing, e, region);
				if (cur != null && cur.equals(e.contentHash())) return null;
				if (cur == null) { // unreadable as region: hash as plain for the recycle record
					cur = ContentHasher.plain(existing);
					region = false;
				}
				return new RestorePlan.Op(e.path(), RestorePlan.Action.PLACE, new RestorePlan.FromEntry(e), cur, region);
			});
		}
		if (skippedByFilter[0] > 0) notes.add(Lang.get("cytrabackups.restore.note.excluded", skippedByFilter[0]));
		if (!partialBackup) {
			for (Map.Entry<String, Path> c : current.entrySet()) {
				if (manifest.get(c.getKey()) != null) continue;
				tasks.add(() -> {
					boolean region = BackupEngine.isRegionPath(c.getKey());
					Hash h = safeHash(c.getValue(), region);
					if (h == null) {
						h = ContentHasher.plain(c.getValue());
						region = false;
					}
					return new RestorePlan.Op(c.getKey(), RestorePlan.Action.REMOVE, null, h, region);
				});
			}
		}
		progress.addTotal(tasks.size(), 0);
		List<RestorePlan.Op> ops = runAll(tasks, progress, cancel);
		int unchanged = (int) (tasks.size() - ops.size());
		String desc = partialBackup ? "files from partial backup #" + backupId : "full world from backup #" + backupId;
		return new RestorePlan(RestoreRecord.Kind.FULL, backupId, desc, ops, unchanged, notes);
	}

	private Hash currentHashFor(Path existing, ManifestEntry e, boolean region) throws IOException {
		if (trustMtime) {
			BasicFileAttributes a = Files.readAttributes(existing, BasicFileAttributes.class);
			if (a.size() == e.size() && a.lastModifiedTime().toMillis() == e.mtime()) return e.contentHash();
		}
		return safeHash(existing, region);
	}

	private static Hash safeHash(Path file, boolean region) throws IOException {
		if (!region) return ContentHasher.plain(file);
		try {
			return ContentHasher.region(file);
		} catch (RegionFiles.RegionFormatException e) {
			return null;
		}
	}

	public RestorePlan planChunks(Manifest manifest, int backupId, Path world, String dimensionFolder, ChunkSelection selection,
								  Progress progress, CancelToken cancel) throws IOException {
		progress.phase(Lang.get("cytrabackups.phase.plan_restore"));
		List<RestorePlan.Op> ops = new ArrayList<>();
		List<String> notes = new ArrayList<>();
		int unchanged = 0;
		for (ChunkSelection.RegionPos rp : selection.regions()) {
			cancel.check();
			List<Integer> indices = selection.indicesIn(rp.x(), rp.z());
			for (String kind : DimensionPaths.REGION_KINDS) {
				String path = DimensionPaths.regionPath(dimensionFolder, kind, rp.x(), rp.z());
				ManifestEntry backupEntry = manifest.get(path);
				Path existing = FileUtil.resolveSafe(world, path);
				boolean exists = Files.isRegularFile(existing);
				if (backupEntry == null && !exists) continue;
				RegionEntry backupRegion = backupEntry instanceof RegionEntry r ? r : null;
				ManifestEntry whole = backupEntry instanceof FileEntry f ? f : null;
				Hash cur = null;
				boolean curRegion = false;
				if (exists) {
					try {
						cur = ContentHasher.region(existing);
						curRegion = true;
					} catch (RegionFiles.RegionFormatException e) {
						throw new IOException("Current file " + path + " is not a valid region file; cannot merge chunks into it (" + e.getMessage() + ")");
					}
				}
				ops.add(new RestorePlan.Op(path, RestorePlan.Action.PLACE, new RestorePlan.MergeRegion(backupRegion, whole, indices), cur, curRegion));
				// External (oversized) chunk streams live next to the region file as c.X.Z.mcc
				String dir = path.substring(0, path.lastIndexOf('/'));
				for (int idx : indices) {
					String mcc = dir + "/" + RegionFiles.externalChunkFileName(RegionFiles.chunkX(rp.x(), idx), RegionFiles.chunkZ(rp.z(), idx));
					ManifestEntry bm = manifest.get(mcc);
					Path cm = FileUtil.resolveSafe(world, mcc);
					boolean cmExists = Files.isRegularFile(cm);
					if (bm != null) {
						Hash h = cmExists ? ContentHasher.plain(cm) : null;
						if (h != null && h.equals(bm.contentHash())) {
							unchanged++;
						} else {
							ops.add(new RestorePlan.Op(mcc, RestorePlan.Action.PLACE, new RestorePlan.FromEntry(bm), h, false));
						}
					} else if (cmExists) {
						ops.add(new RestorePlan.Op(mcc, RestorePlan.Action.REMOVE, null, ContentHasher.plain(cm), false));
					}
				}
			}
		}
		if (ops.isEmpty()) notes.add(Lang.get("cytrabackups.restore.note.empty_area"));
		return new RestorePlan(RestoreRecord.Kind.CHUNKS, backupId, Lang.get("cytrabackups.restore.chunks_from", selection.describe(), dimensionFolder.isEmpty() ? "overworld" : dimensionFolder, backupId), ops, unchanged, notes);
	}

	public RestorePlan planRollback(RestoreRecord record, Path world, Progress progress, CancelToken cancel) throws IOException {
		progress.phase(Lang.get("cytrabackups.phase.plan_restore"));
		if (record.rolledBack) throw new IOException("Restore " + record.restoreId + " was already rolled back");
		Path recycle = Path.of(record.recycleDir);
		if (!Files.isDirectory(recycle)) throw new IOException("Recycle bin for " + record.restoreId + " no longer exists");
		List<RestorePlan.Op> ops = new ArrayList<>();
		for (RestoreRecord.Item item : record.items) {
			cancel.check();
			Path existing = FileUtil.resolveSafe(world, item.path);
			boolean exists = Files.isRegularFile(existing);
			Hash cur = null;
			boolean curRegion = false;
			if (exists) {
				curRegion = BackupEngine.isRegionPath(item.path);
				cur = safeHash(existing, curRegion);
				if (cur == null) {
					cur = ContentHasher.plain(existing);
					curRegion = false;
				}
			}
			if (item.recycled) {
				Path src = FileUtil.resolveSafe(recycle, item.path);
				if (!Files.isRegularFile(src)) throw new IOException("Recycled file missing: " + item.path);
				ops.add(new RestorePlan.Op(item.path, RestorePlan.Action.PLACE,
					new RestorePlan.FromFile(src, Hash.fromHex(item.recycledHash), item.recycledIsRegion), cur, curRegion));
			} else if (item.placed && exists) {
				ops.add(new RestorePlan.Op(item.path, RestorePlan.Action.REMOVE, null, cur, curRegion));
			}
		}
		return new RestorePlan(RestoreRecord.Kind.ROLLBACK, record.backupId, "rollback of restore " + record.restoreId, ops, 0, List.of());
	}

	private Map<String, Path> walkWorld(Path world, PathFilter filter, List<Path> excludedDirs) throws IOException {
		Map<String, Path> out = new LinkedHashMap<>();
		if (!Files.isDirectory(world)) return out;
		Path root = world.toAbsolutePath().normalize();
		List<Path> excluded = excludedDirs.stream().map(p -> p.toAbsolutePath().normalize()).toList();
		Files.walkFileTree(root, new SimpleFileVisitor<>() {
			@Override
			public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
				if (dir.equals(root)) return FileVisitResult.CONTINUE;
				if (excluded.contains(dir)) return FileVisitResult.SKIP_SUBTREE;
				return filter.skipDirectory(FileUtil.relative(root, dir)) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
				if (!attrs.isRegularFile()) return FileVisitResult.CONTINUE;
				String rel = FileUtil.relative(root, file);
				if (filter.test(rel)) out.put(rel, file);
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFileFailed(Path file, IOException exc) {
				return FileVisitResult.CONTINUE;
			}
		});
		return out;
	}

	private record Expected(Hash hash, boolean region) {
	}

	/** Executes a plan. The world must not be in use. Returns the saved record (with recycle bin location). */
	public RestoreRecord execute(RestorePlan plan, Path world, Progress progress, CancelToken cancel) throws IOException {
		recoverInterrupted(msg -> {
		});
		String restoreId = newRestoreId(plan.kind(), plan.backupId());
		Path worldAbs = world.toAbsolutePath().normalize();
		Path staging = worldAbs.resolveSibling("." + worldAbs.getFileName() + "-cytrabackups-staging-" + restoreId);
		Path recycle = recycleRoot.resolve(restoreId);
		FileUtil.deleteRecursively(staging);
		Files.createDirectories(staging);

		Map<String, Expected> expected = new ConcurrentHashMap<>();
		try {
			// 1. stage + verify
			progress.phase(Lang.get("cytrabackups.phase.stage"));
			List<Callable<Void>> stageTasks = new ArrayList<>();
			for (RestorePlan.Op op : plan.ops()) {
				if (op.action() != RestorePlan.Action.PLACE) continue;
				stageTasks.add(() -> {
					Path target = FileUtil.resolveSafe(staging, op.path());
					Files.createDirectories(target.getParent());
					Expected exp = materialize(op, worldAbs, target);
					Hash actual = ContentHasher.of(target, exp.region);
					if (!actual.equals(exp.hash)) {
						throw new VerificationException("Staged file " + op.path() + " failed verification (expected " + exp.hash.shortHex() + ", got " + actual.shortHex() + ")");
					}
					expected.put(op.path(), exp);
					return null;
				});
			}
			progress.addTotal(stageTasks.size(), 0);
			runAll(stageTasks, progress, cancel);
			cancel.check();

			// 2. swap (journaled)
			progress.phase(Lang.get("cytrabackups.phase.swap"));
			progress.addTotal(plan.ops().size(), 0);
			RestoreRecord record = new RestoreRecord();
			record.restoreId = restoreId;
			record.kind = plan.kind();
			record.backupId = plan.backupId();
			record.description = plan.description();
			record.worldDir = worldAbs.toString();
			record.recycleDir = recycle.toAbsolutePath().toString();
			Files.createDirectories(recycle);
			try (BufferedWriter journal = Files.newBufferedWriter(journalFile, StandardCharsets.UTF_8,
				StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
				journal.write("BEGIN\t" + restoreId + "\t" + worldAbs + "\t" + recycle.toAbsolutePath() + "\t" + staging);
				journal.newLine();
				journal.flush();
				for (RestorePlan.Op op : plan.ops()) {
					Path worldFile = FileUtil.resolveSafe(worldAbs, op.path());
					RestoreRecord.Item item = new RestoreRecord.Item();
					item.path = op.path();
					if (Files.exists(worldFile)) {
						journal.write("RECYCLE\t" + op.path());
						journal.newLine();
						journal.flush();
						FileUtil.move(worldFile, FileUtil.resolveSafe(recycle, op.path()), true);
						item.recycled = true;
						item.recycledHash = op.currentHash() != null ? op.currentHash().hex() : ContentHasher.plain(FileUtil.resolveSafe(recycle, op.path())).hex();
						item.recycledIsRegion = op.currentHash() != null && op.currentIsRegion();
					}
					if (op.action() == RestorePlan.Action.PLACE) {
						journal.write("PLACE\t" + op.path());
						journal.newLine();
						journal.flush();
						FileUtil.move(FileUtil.resolveSafe(staging, op.path()), worldFile, true);
						Expected exp = expected.get(op.path());
						item.placed = true;
						item.placedHash = exp.hash.hex();
						item.placedIsRegion = exp.region;
					}
					record.items.add(item);
					progress.addDone(1, 0);
				}

				// 3. verify in place; roll back everything on any mismatch
				progress.phase(Lang.get("cytrabackups.phase.verify_restore"));
				List<Callable<Void>> verifyTasks = new ArrayList<>();
				List<String> failures = Collections.synchronizedList(new ArrayList<>());
				for (RestoreRecord.Item item : record.items) {
					if (!item.placed) continue;
					verifyTasks.add(() -> {
						Path f = FileUtil.resolveSafe(worldAbs, item.path);
						Hash actual;
						try {
							actual = ContentHasher.of(f, item.placedIsRegion);
						} catch (IOException e) {
							failures.add(item.path + " (" + e.getMessage() + ")");
							return null;
						}
						if (!actual.hex().equals(item.placedHash)) failures.add(item.path);
						return null;
					});
				}
				progress.addTotal(verifyTasks.size(), 0);
				try {
					runAll(verifyTasks, progress, CancelToken.NONE);
				} catch (IOException e) {
					failures.add("verification error: " + e.getMessage());
				}
				if (!failures.isEmpty()) {
					journal.close();
					List<String> log = new ArrayList<>();
					recoverInterrupted(log::add);
					throw new VerificationException("Restored files failed verification and the restore was rolled back: "
						+ String.join(", ", failures.subList(0, Math.min(5, failures.size()))) + (failures.size() > 5 ? " (+" + (failures.size() - 5) + " more)" : ""));
				}
				journal.write("END");
				journal.newLine();
				journal.flush();
			}
			record.appliedAt = System.currentTimeMillis();
			Json.write(recordsDir.resolve(restoreId + ".json"), record);
			Files.deleteIfExists(journalFile);
			FileUtil.deleteRecursively(staging);
			return record;
		} catch (IOException | RuntimeException e) {
			if (Files.exists(journalFile)) recoverInterrupted(msg -> {
			});
			FileUtil.deleteRecursively(staging);
			if (Files.isDirectory(recycle)) {
				try (Stream<Path> s = Files.list(recycle)) {
					if (s.findAny().isEmpty()) Files.deleteIfExists(recycle);
				}
			}
			throw e;
		}
	}

	private Expected materialize(RestorePlan.Op op, Path world, Path target) throws IOException {
		RestorePlan.Source src = op.source();
		if (src instanceof RestorePlan.FromEntry fe) {
			ManifestEntry e = fe.entry();
			if (e instanceof FileEntry f) {
				writeFileEntry(f, target);
				Files.setLastModifiedTime(target, FileTime.fromMillis(f.mtime()));
				return new Expected(f.contentHash(), false);
			}
			RegionEntry r = (RegionEntry) e;
			writeRegion(target, r.chunks());
			Files.setLastModifiedTime(target, FileTime.fromMillis(r.mtime()));
			return new Expected(r.contentHash(), true);
		}
		if (src instanceof RestorePlan.FromFile ff) {
			Files.copy(ff.file(), target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
			return new Expected(ff.expected(), ff.region());
		}
		RestorePlan.MergeRegion mr = (RestorePlan.MergeRegion) src;
		return mergeRegion(mr, FileUtil.resolveSafe(world, op.path()), target);
	}

	private void writeFileEntry(FileEntry f, Path target) throws IOException {
		MessageDigest md = Hash.newDigest();
		try (OutputStream out = new java.io.BufferedOutputStream(Files.newOutputStream(target), 1 << 16)) {
			for (BlobRef piece : f.pieces()) {
				byte[] data = blobs.read(piece.hash());
				md.update(data);
				out.write(data);
			}
		}
		Hash h = Hash.finish(md);
		if (!h.equals(f.contentHash())) throw new VerificationException("Content of " + f.path() + " does not match its recorded hash");
	}

	private void writeRegion(Path target, List<ChunkRef> chunks) throws IOException {
		List<RegionFiles.SlotInfo> slots = new ArrayList<>();
		Map<Integer, Hash> byIndex = new HashMap<>();
		for (ChunkRef c : chunks) {
			slots.add(new RegionFiles.SlotInfo(c.index(), c.timestamp(), c.blob().rawLength()));
			byIndex.put(c.index(), c.blob().hash());
		}
		try (OutputStream out = new java.io.BufferedOutputStream(Files.newOutputStream(target), 1 << 16)) {
			RegionFiles.write(out, slots, idx -> blobs.read(byIndex.get(idx)));
		}
	}

	private Expected mergeRegion(RestorePlan.MergeRegion mr, Path currentFile, Path target) throws IOException {
		Map<Integer, RegionFiles.ChunkSlot> result = new HashMap<>();
		if (Files.isRegularFile(currentFile)) {
			for (RegionFiles.ChunkSlot s : RegionFiles.readAll(currentFile)) result.put(s.index(), s);
		}
		Map<Integer, RegionFiles.ChunkSlot> backup = new HashMap<>();
		if (mr.backupRegion() != null) {
			for (ChunkRef c : mr.backupRegion().chunks()) {
				if (mr.indices().contains(c.index())) backup.put(c.index(), new RegionFiles.ChunkSlot(c.index(), c.timestamp(), blobs.read(c.blob().hash())));
			}
		} else if (mr.backupWholeFile() instanceof FileEntry f) {
			Path tmp = target.resolveSibling(target.getFileName() + ".src");
			writeFileEntry(f, tmp);
			try {
				for (RegionFiles.ChunkSlot s : RegionFiles.readAll(tmp)) if (mr.indices().contains(s.index())) backup.put(s.index(), s);
			} finally {
				Files.deleteIfExists(tmp);
			}
		}
		for (int idx : mr.indices()) {
			RegionFiles.ChunkSlot b = backup.get(idx);
			if (b != null) result.put(idx, b);
			else result.remove(idx);
		}
		List<RegionFiles.ChunkSlot> slots = new ArrayList<>(result.values());
		slots.sort(Comparator.comparingInt(RegionFiles.ChunkSlot::index));
		RegionFiles.write(target, slots);
		List<ChunkRef> refs = new ArrayList<>();
		for (RegionFiles.ChunkSlot s : slots) {
			refs.add(new ChunkRef(s.index(), s.timestamp(), new BlobRef(Hash.compute(s.payload()), s.payload().length, 0)));
		}
		return new Expected(RegionEntry.logicalHash(refs), true);
	}

	/**
	 * If a previous restore was interrupted mid-swap, undo its partial changes. Returns true if anything was
	 * recovered. Safe to call at any time while the world is not in use.
	 */
	public boolean recoverInterrupted(Consumer<String> log) throws IOException {
		if (!Files.exists(journalFile)) return false;
		List<String> lines = Files.readAllLines(journalFile, StandardCharsets.UTF_8);
		if (lines.isEmpty() || !lines.get(0).startsWith("BEGIN\t")) {
			Files.deleteIfExists(journalFile);
			return false;
		}
		String[] begin = lines.get(0).split("\t");
		Path world = Path.of(begin[2]);
		Path recycle = Path.of(begin[3]);
		Path staging = Path.of(begin[4]);
		if (lines.get(lines.size() - 1).equals("END")) {
			Files.deleteIfExists(journalFile);
			FileUtil.deleteRecursively(staging);
			return false;
		}
		log.accept("Rolling back interrupted restore " + begin[1]);
		Set<String> recycled = new HashSet<>();
		for (String l : lines) if (l.startsWith("RECYCLE\t")) recycled.add(l.substring(8));
		for (int i = lines.size() - 1; i >= 1; i--) {
			String l = lines.get(i);
			if (l.startsWith("PLACE\t")) {
				String path = l.substring(6);
				Path w = FileUtil.resolveSafe(world, path);
				Path s = FileUtil.resolveSafe(staging, path);
				if (!Files.exists(s) || !recycled.contains(path)) Files.deleteIfExists(w);
			}
		}
		for (int i = lines.size() - 1; i >= 1; i--) {
			String l = lines.get(i);
			if (l.startsWith("RECYCLE\t")) {
				String path = l.substring(8);
				Path r = FileUtil.resolveSafe(recycle, path);
				Path w = FileUtil.resolveSafe(world, path);
				if (!Files.exists(r)) continue;
				// Placing is an atomic rename, so a world file that still exists here was never moved out: the recycle bin
				// only holds a partial copy of it (a crash during a copy to another disk). Keep the original.
				if (Files.exists(w)) Files.delete(r);
				else FileUtil.move(r, w, true);
			}
		}
		FileUtil.deleteRecursively(staging);
		FileUtil.deleteRecursively(recycle);
		Files.deleteIfExists(journalFile);
		return true;
	}

	public List<RestoreRecord> listRecords() throws IOException {
		List<RestoreRecord> out = new ArrayList<>();
		if (!Files.isDirectory(recordsDir)) return out;
		try (Stream<Path> s = Files.list(recordsDir)) {
			for (Path p : s.filter(p -> p.toString().endsWith(".json")).toList()) {
				try {
					out.add(Json.read(p, RestoreRecord.class));
				} catch (IOException ignored) {
				}
			}
		}
		out.sort(Comparator.comparingLong(r -> r.appliedAt));
		return out;
	}

	/** The most recent restore (of any kind) that has not been rolled back yet. */
	public Optional<RestoreRecord> latestUndoable() throws IOException {
		List<RestoreRecord> all = listRecords();
		for (int i = all.size() - 1; i >= 0; i--) {
			RestoreRecord r = all.get(i);
			if (!r.rolledBack && Files.isDirectory(Path.of(r.recycleDir))) return Optional.of(r);
		}
		return Optional.empty();
	}

	public Optional<RestoreRecord> record(String restoreId) throws IOException {
		return Optional.ofNullable(Json.readOrNull(recordsDir.resolve(restoreId + ".json"), RestoreRecord.class));
	}

	public void markRolledBack(RestoreRecord record, String byRestoreId) throws IOException {
		record.rolledBack = true;
		record.rolledBackBy = byRestoreId;
		Json.write(recordsDir.resolve(record.restoreId + ".json"), record);
	}

	/** Keeps the recycle bins of the newest {@code keep} restores; older bins (and their records) are deleted. */
	public int pruneRecycleBin(int keep) throws IOException {
		List<RestoreRecord> all = listRecords();
		int removed = 0;
		for (int i = 0; i < all.size() - Math.max(0, keep); i++) {
			RestoreRecord r = all.get(i);
			FileUtil.deleteRecursively(Path.of(r.recycleDir));
			Files.deleteIfExists(recordsDir.resolve(r.restoreId + ".json"));
			removed++;
		}
		return removed;
	}

	public Path storageRoot() {
		return storageRoot;
	}

	private <T> List<T> runAll(List<Callable<T>> tasks, Progress progress, CancelToken cancel) throws IOException {
		List<Future<T>> futures = new ArrayList<>(tasks.size());
		for (Callable<T> t : tasks) {
			futures.add(workers.submit(() -> {
				cancel.check();
				T r = t.call();
				progress.addDone(1, 0);
				return r;
			}));
		}
		List<T> out = new ArrayList<>();
		try {
			for (Future<T> f : futures) {
				T r = f.get();
				if (r != null) out.add(r);
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			futures.forEach(f -> f.cancel(true));
			throw new CancellationException("interrupted");
		} catch (ExecutionException e) {
			futures.forEach(f -> f.cancel(true));
			Throwable c = e.getCause();
			if (c instanceof CancellationException ce) throw ce;
			if (c instanceof IOException io) throw io;
			throw new IOException(String.valueOf(c), c);
		}
		return out;
	}
}
