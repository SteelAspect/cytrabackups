package dev.steelaspect.cytrabackups.core.backup;

import dev.steelaspect.cytrabackups.core.Lang;
import dev.steelaspect.cytrabackups.core.CancelToken;
import dev.steelaspect.cytrabackups.core.LongHashSet;
import dev.steelaspect.cytrabackups.core.Progress;
import dev.steelaspect.cytrabackups.core.manifest.Manifest;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.LongConsumer;
import java.util.function.Predicate;

/** Creates backups: scan via {@link BackupEngine}, then commit metadata + manifest to the repository. */
public final class BackupService {
	private final BackupRepository repo;
	private final BackupEngine engine;
	private final String minecraftVersion;
	private final String modVersion;

	public BackupService(BackupRepository repo, BackupEngine engine, String minecraftVersion, String modVersion) {
		this.repo = repo;
		this.engine = engine;
		this.minecraftVersion = minecraftVersion;
		this.modVersion = modVersion;
	}

	public BackupRepository repository() {
		return repo;
	}

	public static final class Request {
		public Path worldDir;
		public String levelName = "";
		public Trigger trigger = Trigger.MANUAL;
		public String comment = "";
		public String creator = "";
		public PathFilter filter = PathFilter.ALL;
		public List<Path> excludedDirs = List.of();
		/** Restrict to some paths; makes the backup "partial". */
		public Predicate<String> only;
		public String scope = "";
		public Integer restoreTarget;
		/** Skip committing if content equals the previous backup (ignoring {@link #unchangedIgnore}). */
		public boolean skipIfUnchanged;
		public Predicate<String> unchangedIgnore = p -> false;
		public Progress progress = new Progress();
		public CancelToken cancel = CancelToken.NONE;
		public LongConsumer beforeWrite = b -> {
		};
		public Consumer<String> warnings = w -> {
		};
		/** Disable size+mtime reuse of the previous manifest (imports of foreign folders). */
		public boolean noReuse;
	}

	/** {@code meta} is null when the backup was skipped because nothing changed. */
	public record Outcome(BackupMeta meta, BackupEngine.Result scan, boolean skippedUnchanged) {
	}

	public Outcome create(Request req) throws IOException {
		long start = System.currentTimeMillis();
		Manifest previous = Manifest.empty();
		var latest = repo.latestFull();
		if (latest.isPresent() && !req.noReuse) {
			try {
				previous = repo.loadManifest(latest.get().id);
			} catch (IOException e) {
				req.warnings.accept("Previous manifest unreadable, doing a full scan: " + e.getMessage());
			}
		}
		BackupEngine.Request er = new BackupEngine.Request();
		er.worldDir = req.worldDir;
		er.filter = req.filter;
		er.previous = req.noReuse ? Manifest.empty() : previous;
		er.excludedDirs = req.excludedDirs;
		if (req.only != null) er.only = req.only;
		er.progress = req.progress;
		er.cancel = req.cancel;
		er.beforeWrite = req.beforeWrite;
		er.warnings = req.warnings;
		BackupEngine.Result scan = engine.run(er);

		if (req.skipIfUnchanged && req.only == null && latest.isPresent() && scan.manifest.sameContent(previous, req.unchangedIgnore)) {
			return new Outcome(null, scan, true);
		}

		req.progress.phase(Lang.get("cytrabackups.phase.manifest"));
		BackupMeta meta = new BackupMeta();
		meta.id = repo.allocateId();
		meta.createdAt = System.currentTimeMillis();
		meta.comment = req.comment == null ? "" : req.comment;
		meta.creator = req.creator == null ? "" : req.creator;
		meta.trigger = req.trigger;
		meta.partial = req.only != null;
		meta.scope = req.scope == null ? "" : req.scope;
		meta.levelName = req.levelName;
		meta.restoreTarget = req.restoreTarget;
		meta.totalSize = scan.manifest.totalSize();
		meta.fileCount = scan.manifest.size();
		meta.chunkCount = scan.manifest.chunkCount();
		meta.newStoredBytes = scan.newStoredBytes;
		meta.newBlobs = scan.newBlobs.size();
		meta.reusedFiles = scan.reusedFiles;
		meta.referencedStoredBytes = referencedBytes(scan.manifest);
		meta.minecraftVersion = minecraftVersion;
		meta.modVersion = modVersion;
		meta.durationMillis = System.currentTimeMillis() - start;
		repo.save(meta, scan.manifest, scan.newBlobs);
		return new Outcome(meta, scan, false);
	}

	/** Stored size of each distinct blob referenced by the manifest. */
	public static long referencedBytes(Manifest m) {
		LongHashSet seen = new LongHashSet(Math.max(16, m.size() * 4));
		long[] sum = {0};
		m.forEachBlob(ref -> {
			if (seen.add(ref.hash().prefix64())) sum[0] += ref.storedLength();
		});
		return sum[0];
	}
}
