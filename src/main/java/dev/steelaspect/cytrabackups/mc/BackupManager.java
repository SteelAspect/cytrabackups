package dev.steelaspect.cytrabackups.mc;

import dev.steelaspect.cytrabackups.CytraBackups;
import dev.steelaspect.cytrabackups.core.CancelToken;
import dev.steelaspect.cytrabackups.core.FileUtil;
import dev.steelaspect.cytrabackups.core.Formatting;
import dev.steelaspect.cytrabackups.core.Hash;
import dev.steelaspect.cytrabackups.core.Progress;
import dev.steelaspect.cytrabackups.core.backup.BackupMeta;
import dev.steelaspect.cytrabackups.core.backup.BackupService;
import dev.steelaspect.cytrabackups.core.backup.ChunkSelection;
import dev.steelaspect.cytrabackups.core.backup.Glob;
import dev.steelaspect.cytrabackups.core.backup.RepositoryState;
import dev.steelaspect.cytrabackups.core.backup.Trigger;
import dev.steelaspect.cytrabackups.core.backup.Verifier;
import dev.steelaspect.cytrabackups.core.config.ConfigIO;
import dev.steelaspect.cytrabackups.core.config.CytraConfig;
import dev.steelaspect.cytrabackups.core.diff.BackupDiff;
import dev.steelaspect.cytrabackups.core.manifest.Manifest;
import dev.steelaspect.cytrabackups.core.offsite.OffsiteSync;
import dev.steelaspect.cytrabackups.core.offsite.OffsiteTarget;
import dev.steelaspect.cytrabackups.core.offsite.S3Target;
import dev.steelaspect.cytrabackups.core.offsite.SftpTarget;
import dev.steelaspect.cytrabackups.core.offsite.WebDavTarget;
import dev.steelaspect.cytrabackups.core.prune.GarbageCollector;
import dev.steelaspect.cytrabackups.core.prune.PrunePolicy;
import dev.steelaspect.cytrabackups.core.prune.Pruner;
import dev.steelaspect.cytrabackups.core.restore.PendingOperation;
import dev.steelaspect.cytrabackups.core.restore.PendingOperationRunner;
import dev.steelaspect.cytrabackups.core.restore.RestoreRecord;
import dev.steelaspect.cytrabackups.core.restore.RestoreResult;
import dev.steelaspect.cytrabackups.core.transfer.WorldImporter;
import dev.steelaspect.cytrabackups.core.transfer.ZipExporter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Runtime coordinator for one running server/world. Owns the repository services, runs at most one job at a
 * time on a background thread, drives the scheduler, confirmations, restore countdown and progress display.
 */
public final class BackupManager {
	private static volatile BackupManager instance;

	public static BackupManager getOrNull() {
		return instance;
	}

	public static BackupManager get() {
		BackupManager m = instance;
		if (m == null) throw new IllegalStateException("CytraBackups is not running");
		return m;
	}

	/** A running job. {@code quiet} jobs (e.g. metadata edits) do not show a boss bar. */
	public record Job(String name, String requestedBy, Progress progress, CancelToken cancel, long startedAt, boolean quiet) {
	}

	private record Confirm(String requester, long expiresAt, String description, Runnable action) {
	}

	@FunctionalInterface
	interface JobBody {
		void run(Job job) throws Exception;
	}

	/** Thrown when the storage disk is too full for a backup. */
	static final class NotEnoughSpaceException extends RuntimeException {
		NotEnoughSpaceException(String message) {
			super(message);
		}
	}

	private final MinecraftServer server;
	private final Path worldDir;
	private final String levelName;
	private volatile CytraConfig config;
	private volatile Services services;
	private volatile OffsiteSync offsite;
	private final ExecutorService jobExecutor = Executors.newSingleThreadExecutor(Services.threadFactory("CytraBackups-Job"));
	private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor(Services.threadFactory("CytraBackups-IO"));
	private final ExecutorService offsiteExecutor = Executors.newSingleThreadExecutor(Services.threadFactory("CytraBackups-Offsite"));
	private final ExecutorService offsiteWorkers = Executors.newFixedThreadPool(3, Services.threadFactory("CytraBackups-Upload"));
	private final AtomicReference<Job> currentJob = new AtomicReference<>();
	private volatile Progress offsiteProgress;
	private final ProgressDisplay display = new ProgressDisplay();
	private final Notifier notifier;
	private final Map<String, Confirm> confirmations = new ConcurrentHashMap<>();
	private final SecureRandom random = new SecureRandom();
	private final long startedAt = System.currentTimeMillis();

	// server-thread state
	private Countdown countdown;
	private volatile boolean stoppingForRestore;
	private boolean applyOnStop;
	private volatile long nextScheduleCheck;
	private long lastLowDiskCheck;
	private long lastLowDiskWarning;
	private boolean leaveBackupPending;
	private int tickCounter;
	private RestoreResult startupResult;

	private BackupManager(MinecraftServer server) throws IOException {
		this.server = server;
		this.worldDir = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
		this.levelName = worldDir.getFileName().toString();
		this.config = ModEnv.loadConfigOrDefaults();
		this.services = Services.open(config, ModEnv.storageFor(config, levelName));
		this.offsite = new OffsiteSync(services.repo, services.storage.resolve("offsite"), msg -> CytraBackups.LOGGER.info("CytraBackups offsite: {}", msg));
		this.notifier = new Notifier(config, levelName);
	}

	public static void start(MinecraftServer server) {
		try {
			instance = new BackupManager(server);
			CytraBackups.LOGGER.info("CytraBackups: {} backups in {}", instance.services.repo.list().size(), instance.services.storage);
		} catch (Exception e) {
			instance = null;
			CytraBackups.LOGGER.error("CytraBackups failed to start; backups are disabled for this session", e);
		}
	}

	public CytraConfig config() {
		return config;
	}

	public Services services() {
		return services;
	}

	public Path worldDir() {
		return worldDir;
	}

	public MinecraftServer server() {
		return server;
	}

	public Job currentJob() {
		return currentJob.get();
	}

	/** Server thread: label of the running restore countdown, or null. */
	public String countdownLabel() {
		Countdown c = countdown;
		return c == null ? null : c.label();
	}

	public ZoneId zone() {
		return config.timeZone.equals("system") ? ZoneId.systemDefault() : ZoneId.of(config.timeZone);
	}

	// ------------------------------------------------------------------ jobs

	boolean runJob(String name, String requestedBy, Feedback fb, boolean quiet, JobBody body) {
		if (countdown != null || stoppingForRestore) {
			fb.error("A restore is in progress; no other jobs can start.");
			return false;
		}
		Job job = new Job(name, requestedBy, new Progress(), new CancelToken(), System.currentTimeMillis(), quiet);
		if (!currentJob.compareAndSet(null, job)) {
			Job running = currentJob.get();
			fb.error("Busy: " + (running != null ? running.name() : "another job") + " is running. See /backup status or /backup cancel.");
			return false;
		}
		jobExecutor.execute(() -> {
			try {
				body.run(job);
			} catch (CancellationException e) {
				fb.warn(name + " cancelled.");
			} catch (Throwable t) {
				String msg = rootMessage(t);
				CytraBackups.LOGGER.error("CytraBackups: {} failed", name, t);
				fb.error(name + " failed: " + msg);
				if (name.equals("Backup")) notifier.backupFailure("Backup", msg);
			} finally {
				currentJob.compareAndSet(job, null);
			}
		});
		return true;
	}

	static String rootMessage(Throwable t) {
		Throwable c = t;
		while ((c instanceof ExecutionException || c instanceof java.util.concurrent.CompletionException
			|| c instanceof java.io.UncheckedIOException) && c.getCause() != null) c = c.getCause();
		return c.getMessage() != null ? c.getMessage() : c.toString();
	}

	/** Waits for a future (usually work on the server thread) while honouring job cancellation. */
	private <T> T await(CompletableFuture<T> future, Job job) throws Exception {
		while (true) {
			job.cancel().check();
			if (!server.isRunning() && !future.isDone()) throw new CancellationException("server is stopping");
			try {
				return future.get(200, TimeUnit.MILLISECONDS);
			} catch (TimeoutException ignored) {
			}
		}
	}

	private void async(Runnable r) {
		ioExecutor.execute(() -> {
			try {
				r.run();
			} catch (Throwable t) {
				CytraBackups.LOGGER.warn("CytraBackups: background task failed", t);
			}
		});
	}

	private void saveState() {
		async(() -> {
			try {
				services.repo.saveState();
			} catch (IOException e) {
				CytraBackups.LOGGER.warn("CytraBackups: could not save state: {}", e.getMessage());
			}
		});
	}

	// ------------------------------------------------------------------ backups

	public boolean createBackup(Trigger trigger, String comment, String creator, Feedback fb) {
		boolean automatic = trigger == Trigger.SCHEDULED || trigger == Trigger.PLAYER_LEAVE;
		return runJob("Backup", creator, fb, false, job -> {
			boolean ok = false;
			try {
				doBackup(job, trigger, comment, creator, fb, automatic);
				ok = true;
			} catch (NotEnoughSpaceException e) {
				notifier.lowDisk(FileUtil.usableSpace(services.storage), config.minFreeSpaceMiB * 1024 * 1024);
				throw e;
			} finally {
				if (automatic) {
					if (ok) {
						services.repo.state().lastScheduledBackup = System.currentTimeMillis();
						saveState();
					} else {
						nextScheduleCheck = System.currentTimeMillis() + 5 * 60_000L; // retry a failed automatic backup in 5 minutes
					}
				}
			}
		});
	}

	private void checkSpace(Services s, long bytesToWrite) {
		long free = FileUtil.usableSpace(s.storage);
		long reserve = config.minFreeSpaceMiB * 1024L * 1024L;
		if (free - bytesToWrite < reserve) {
			throw new NotEnoughSpaceException("Not enough disk space: the backup may need up to " + Formatting.bytes(bytesToWrite)
				+ " and " + Formatting.bytes(reserve) + " must stay free, but only " + Formatting.bytes(free) + " is available. Nothing was written.");
		}
	}

	private BackupMeta doBackup(Job job, Trigger trigger, String comment, String creator, Feedback fb, boolean automatic) throws Exception {
		Services s = services;
		CytraConfig cfg = config;
		checkSpace(s, 0);
		job.progress().phase(cfg.flushOnSave ? "Saving world (save-all flush)" : "Saving world");
		Map<ServerLevel, Boolean> previous = null;
		BackupService.Outcome out;
		try {
			previous = await(SaveControl.saveAndDisable(server, cfg.flushOnSave), job);
			BackupService.Request req = new BackupService.Request();
			req.worldDir = worldDir;
			req.levelName = levelName;
			req.trigger = trigger;
			req.comment = comment;
			req.creator = creator;
			req.filter = s.filter();
			req.excludedDirs = s.excludedDirs(worldDir);
			req.skipIfUnchanged = automatic && cfg.schedule.skipIfUnchanged;
			List<Glob> ignore = cfg.schedule.unchangedIgnore.stream().map(Glob::new).toList();
			req.unchangedIgnore = p -> ignore.stream().anyMatch(g -> g.matches(p));
			req.progress = job.progress();
			req.cancel = job.cancel();
			req.beforeWrite = bytes -> checkSpace(s, bytes);
			req.warnings = w -> CytraBackups.LOGGER.warn("CytraBackups: {}", w);
			out = s.backups.create(req);
		} finally {
			SaveControl.restore(server, previous);
		}
		RepositoryState st = s.repo.state();
		st.lastBackup = System.currentTimeMillis();
		st.playersSeenSinceBackup = !server.getPlayerList().getPlayers().isEmpty();
		saveState();
		if (out.skippedUnchanged()) {
			fb.info("Nothing changed since the last backup; automatic backup skipped.");
			return null;
		}
		BackupMeta m = out.meta();
		MutableComponent msg = Msg.success("Backup #" + m.id + " created in " + Formatting.duration(m.durationMillis) + ": "
			+ Formatting.bytes(m.totalSize) + ", " + Formatting.bytes(m.newStoredBytes) + " new (" + m.reusedFiles + "/" + m.fileCount + " files unchanged). ");
		msg.append(Msg.run("Info", "/backup info " + m.id, "Show details", ChatFormatting.AQUA));
		if (!out.scan().warnings.isEmpty()) msg.append(Msg.text(" " + out.scan().warnings.size() + " warning(s) in the server log.", ChatFormatting.YELLOW));
		fb.send(msg, false);
		if (automatic && cfg.progress.announceScheduled) {
			server.execute(() -> server.getPlayerList().broadcastSystemMessage(Msg.info("Automatic backup #" + m.id + " complete."), false));
		}
		notifier.backupSuccess(m);
		if (cfg.offsite.enabled) {
			offsite.enqueueUpload(m.id);
			kickOffsite();
		}
		checkLowDisk(true);
		return m;
	}

	// ------------------------------------------------------------------ confirmations

	/** Sends a clickable confirmation prompt; the action runs when the same source confirms in time. */
	public void prompt(CommandSourceStack src, String description, String details, Runnable action) {
		byte[] b = new byte[4];
		random.nextBytes(b);
		String token = Hash.compute(b).hex().substring(0, 8);
		int timeout = config.restore.confirmTimeoutSeconds;
		confirmations.put(token, new Confirm(src.getTextName(), System.currentTimeMillis() + timeout * 1000L, description, action));
		MutableComponent msg = Msg.warn(description + "? ").append(Msg.text(details + " ", ChatFormatting.GRAY))
			.append(Msg.run("Confirm", "/backup confirm " + token, "Click to confirm (expires in " + timeout + "s)", ChatFormatting.RED))
			.append(" ")
			.append(Msg.run("Cancel", "/backup deny " + token, "Click to cancel", ChatFormatting.GRAY));
		if (!src.isPlayer()) msg.append(Msg.text(" (type: backup confirm " + token + ")", ChatFormatting.GRAY));
		src.sendSuccess(() -> msg, false);
	}

	public void confirm(String token, CommandSourceStack src, boolean accept) {
		Confirm c = confirmations.remove(token);
		if (c == null) {
			src.sendFailure(Msg.error("Unknown or already used confirmation."));
			return;
		}
		if (!c.requester.equals(src.getTextName())) {
			confirmations.put(token, c);
			src.sendFailure(Msg.error("This confirmation belongs to " + c.requester + "."));
			return;
		}
		if (System.currentTimeMillis() > c.expiresAt) {
			src.sendFailure(Msg.error("Confirmation expired. Run the command again."));
			return;
		}
		if (!accept) {
			src.sendSuccess(() -> Msg.info("Cancelled: " + c.description + "."), false);
			return;
		}
		c.action.run();
	}

	// ------------------------------------------------------------------ restores

	private String describe(BackupMeta m) {
		return "#" + m.id + " (" + Formatting.dateTime(m.createdAt, zone()) + ", " + m.trigger.displayName()
			+ (m.comment.isBlank() ? "" : ", \"" + m.comment + "\"") + ")";
	}

	public void requestFullRestore(int id, CommandSourceStack src) {
		Optional<BackupMeta> meta = services.repo.get(id);
		if (meta.isEmpty()) {
			src.sendFailure(Msg.error("No backup #" + id + "."));
			return;
		}
		String who = src.getTextName();
		Feedback fb = Feedback.of(src).and(Feedback.console());
		prompt(src, "Restore the whole world to backup " + describe(meta.get()),
			"A pre-restore backup is taken automatically; everyone is kicked after a " + config.restore.countdownSeconds
				+ "s countdown and the server " + (config.restore.applyMode.equals("startup") ? "restarts to apply it." : "stops and applies it."),
			() -> startFullRestore(id, who, fb));
	}

	/** Also used by the GUI after its own confirmation dialog. */
	public void startFullRestore(int id, String who, Feedback fb) {
		PendingOperation op = new PendingOperation();
		op.type = PendingOperation.Type.FULL_RESTORE;
		op.backupId = id;
		op.worldDir = worldDir.toString();
		op.requestedBy = who;
		op.requestedAt = System.currentTimeMillis();
		beginStopCountdown(op, "Restoring backup #" + id, fb);
	}

	private void beginStopCountdown(PendingOperation op, String label, Feedback fb) {
		server.execute(() -> {
			if (countdown != null || stoppingForRestore) {
				fb.error("A restore countdown is already running.");
				return;
			}
			Job job = currentJob.get();
			if (job != null) {
				fb.error(job.name() + " is running. Wait for it or use /backup cancel, then try again.");
				return;
			}
			countdown = new Countdown(config.restore.countdownSeconds, label, () -> finishStop(op, fb));
			fb.success(label + ": countdown started (" + config.restore.countdownSeconds + "s). Use /backup cancel to abort.");
			notifier.restore("Restore scheduled", op.describe() + " requested by " + op.requestedBy + ". The server stops in "
				+ config.restore.countdownSeconds + "s.", false);
		});
	}

	private void finishStop(PendingOperation op, Feedback fb) {
		countdown = null;
		try {
			PendingOperation existing = PendingOperationRunner.readPending(services.storage);
			if (existing != null && existing.type != op.type) {
				CytraBackups.LOGGER.warn("CytraBackups: replacing queued {} with {}", existing.describe(), op.describe());
			}
			PendingOperationRunner.writePending(services.storage, op);
		} catch (IOException e) {
			fb.error("Could not queue the restore: " + e.getMessage() + ". The server keeps running.");
			return;
		}
		stoppingForRestore = true;
		applyOnStop = config.restore.applyMode.equals("shutdown");
		CytraBackups.LOGGER.info("CytraBackups: stopping the server to apply {} ({})", op.describe(),
			applyOnStop ? "applied after shutdown" : "applied on next start, before the world loads");
		Component kick = Component.literal(config.restore.kickMessage);
		for (ServerPlayer p : new ArrayList<>(server.getPlayerList().getPlayers())) p.connection.disconnect(kick);
		server.halt(false);
	}

	public void requestChunkRestore(int id, ServerLevel level, ChunkSelection sel, CommandSourceStack src) {
		Optional<BackupMeta> meta = services.repo.get(id);
		if (meta.isEmpty()) {
			src.sendFailure(Msg.error("No backup #" + id + "."));
			return;
		}
		long n = sel.chunkCount();
		if (n > config.restore.maxChunks) {
			src.sendFailure(Msg.error("Selection has " + n + " chunks; the limit is " + config.restore.maxChunks + " (restore.maxChunks)."));
			return;
		}
		String who = src.getTextName();
		Feedback fb = Feedback.of(src).and(Feedback.console());
		prompt(src, "Restore " + n + " chunk(s) (" + sel.describe() + ") in " + level.dimension().identifier() + " from backup " + describe(meta.get()),
			"Terrain, entities and POI are restored; the area is backed up first.",
			() -> startChunkRestore(id, level, sel, who, fb));
	}

	public void startChunkRestore(int id, ServerLevel level, ChunkSelection sel, String who, Feedback fb) {
		runJob("Chunk restore", who, fb, false, job -> doChunkRestore(job, id, level, sel, who, fb));
	}

	String dimensionFolder(ServerLevel level) {
		Path folder = DimensionType.getStorageFolder(level.dimension(), worldDir);
		return folder.equals(worldDir) ? "" : FileUtil.relative(worldDir, folder);
	}

	private void doChunkRestore(Job job, int id, ServerLevel level, ChunkSelection sel, String who, Feedback fb) throws Exception {
		Services s = services;
		CytraConfig cfg = config;
		String dimId = level.dimension().identifier().toString();
		String dimFolder = dimensionFolder(level);
		Manifest manifest = s.repo.loadManifest(id);
		if (!cfg.restore.livePartialRestore) {
			queueChunkRestore(id, dimId, dimFolder, sel, who, "live partial restore is disabled in the config", fb);
			return;
		}
		int viewDistance = server.getPlayerList().getViewDistance();
		job.progress().phase("Checking the area");
		LiveChunkRestore.Safety safety = await(server.submit(() -> LiveChunkRestore.quickCheck(level, sel, viewDistance)), job);
		if (!safety.safe()) {
			queueChunkRestore(id, dimId, dimFolder, sel, who, safety.reason(), fb);
			return;
		}
		job.progress().phase("Waiting for chunks to unload");
		long deadline = System.currentTimeMillis() + cfg.restore.liveUnloadTimeoutSeconds * 1000L;
		while (true) {
			int loaded = await(server.submit(() -> LiveChunkRestore.countLoaded(level, sel)), job);
			if (loaded == 0) break;
			job.progress().detail(loaded + " chunk(s) still loaded");
			if (System.currentTimeMillis() > deadline) {
				LiveChunkRestore.Safety again = await(server.submit(() -> LiveChunkRestore.quickCheck(level, sel, viewDistance)), job);
				queueChunkRestore(id, dimId, dimFolder, sel, who, loaded + " chunk(s) stayed loaded for " + cfg.restore.liveUnloadTimeoutSeconds + "s"
					+ (again.safe() ? " (held by tickets such as portals, ender pearls or other mods)" : ": " + again.reason()), fb);
				return;
			}
			Thread.sleep(250);
		}
		job.progress().phase("Flushing chunk IO");
		await(await(server.submit(() -> LiveChunkRestore.flush(level)), job), job);

		job.progress().phase("Backing up the area first");
		BackupService.Request pre = new BackupService.Request();
		pre.worldDir = worldDir;
		pre.levelName = levelName;
		pre.trigger = Trigger.PRE_RESTORE;
		pre.comment = "Automatic backup of the area before chunk restore from #" + id;
		pre.creator = who;
		pre.filter = s.filter();
		pre.excludedDirs = s.excludedDirs(worldDir);
		java.util.Set<String> affected = PendingOperationRunner.affectedPaths(dimFolder, sel);
		pre.only = affected::contains;
		pre.scope = sel.describe() + " in " + dimId;
		pre.restoreTarget = id;
		pre.progress = job.progress();
		pre.cancel = job.cancel();
		pre.beforeWrite = bytes -> checkSpace(s, bytes);
		BackupMeta preMeta = s.backups.create(pre).meta();

		job.progress().phase("Reading chunks from backup #" + id);
		List<LiveChunkRestore.Write> writes = LiveChunkRestore.prepare(manifest, s.blobs, worldDir, dimFolder, sel);
		job.progress().phase("Writing chunks");
		String problem = await(server.submit(() -> {
			LiveChunkRestore.Safety q = LiveChunkRestore.quickCheck(level, sel, viewDistance);
			if (!q.safe()) return q.reason();
			int still = LiveChunkRestore.countLoaded(level, sel);
			if (still > 0) return still + " chunk(s) were loaded again while the restore was being prepared";
			LiveChunkRestore.apply(level, writes);
			return null;
		}), job);
		if (problem != null) {
			queueChunkRestore(id, dimId, dimFolder, sel, who, problem, fb);
			return;
		}
		await(await(server.submit(() -> LiveChunkRestore.flush(level)), job), job);
		long chunks = writes.stream().filter(w -> w.kind() == LiveChunkRestore.Kind.REGION).count();
		String text = "Restored " + chunks + " chunk(s) of " + sel.describe() + " in " + dimId + " from backup #" + id + " LIVE ("
			+ writes.size() + " region/entity/POI records written). Why live: no player close enough to keep the area in memory, nothing force-loaded, "
			+ "and every selected chunk was fully unloaded, so the data was written through Minecraft's own region IO and cached "
			+ "entity/POI data was refreshed. Pre-restore backup of the area: #" + preMeta.id + ".";
		fb.success(text);
		notifier.restore("Chunks restored live", text, false);
	}

	private void queueChunkRestore(int id, String dimId, String dimFolder, ChunkSelection sel, String who, String reason, Feedback fb) throws IOException {
		PendingOperation existing = PendingOperationRunner.readPending(services.storage);
		if (existing != null) {
			fb.error("Live restore not possible (" + reason + "), and another operation is already queued: " + existing.describe()
				+ ". Cancel it with /backup pending cancel first.");
			return;
		}
		PendingOperation op = new PendingOperation();
		op.type = PendingOperation.Type.CHUNK_RESTORE;
		op.backupId = id;
		op.dimension = dimId;
		op.dimensionFolder = dimFolder;
		op.boxes = new ArrayList<>(sel.boxes());
		op.worldDir = worldDir.toString();
		op.requestedBy = who;
		op.requestedAt = System.currentTimeMillis();
		op.reason = reason;
		PendingOperationRunner.writePending(services.storage, op);
		boolean startup = config.restore.applyMode.equals("startup");
		String when = server.isDedicatedServer()
			? (startup ? "on the next server start, before the world loads" : "when the server next stops")
			: (startup ? "the next time this world is opened, before it loads" : "when you leave this world");
		MutableComponent msg = Msg.warn("Live restore not safe: " + reason + ". The chunk restore was QUEUED and will be applied " + when
			+ " (with a pre-restore backup of the area). ");
		msg.append(Msg.run("Restart now", "/backup pending apply", "Countdown, kick everyone and stop the server to apply it", ChatFormatting.RED));
		fb.send(msg, false);
	}

	public void requestRollback(CommandSourceStack src) {
		// Restore records can be large for big worlds: read them off the server thread, prompt back on it.
		async(() -> {
			Optional<RestoreRecord> rec;
			try {
				rec = services.restore.latestUndoable();
			} catch (IOException e) {
				server.execute(() -> src.sendFailure(Msg.error("Cannot read restore history: " + e.getMessage())));
				return;
			}
			server.execute(() -> promptRollback(src, rec));
		});
	}

	private void promptRollback(CommandSourceStack src, Optional<RestoreRecord> rec) {
		if (rec.isEmpty()) {
			src.sendFailure(Msg.error("There is no restore to roll back (recycle bins are kept for the last " + config.restore.recycleBinKeep + " restores)."));
			return;
		}
		RestoreRecord r = rec.get();
		String who = src.getTextName();
		Feedback fb = Feedback.of(src).and(Feedback.console());
		prompt(src, "Roll back the last restore (" + r.description + ", applied " + Formatting.dateTime(r.appliedAt, zone()) + ")",
			"Files replaced by that restore are moved back from the recycle bin; current files go to a new recycle bin. Requires a restart.",
			() -> {
				PendingOperation op = new PendingOperation();
				op.type = PendingOperation.Type.ROLLBACK;
				op.restoreId = r.restoreId;
				op.worldDir = worldDir.toString();
				op.requestedBy = who;
				op.requestedAt = System.currentTimeMillis();
				beginStopCountdown(op, "Rolling back restore " + r.restoreId, fb);
			});
	}

	/** Reads the queued operation off the server thread and hands it (or an error) back on the server thread. */
	private void withPending(CommandSourceStack src, java.util.function.Consumer<PendingOperation> onServer) {
		async(() -> {
			try {
				PendingOperation op = PendingOperationRunner.readPending(services.storage);
				server.execute(() -> {
					if (op == null) src.sendFailure(Msg.error("Nothing is queued."));
					else onServer.accept(op);
				});
			} catch (IOException e) {
				server.execute(() -> src.sendFailure(Msg.error(e.getMessage())));
			}
		});
	}

	public void pendingInfo(CommandSourceStack src) {
		withPending(src, op -> {
			MutableComponent msg = Msg.info("Queued: " + op.describe() + " (by " + op.requestedBy + ", " + Formatting.ago(op.requestedAt, System.currentTimeMillis())
				+ (op.reason.isBlank() ? "" : "; queued because " + op.reason) + "). ");
			msg.append(Msg.run("Apply now", "/backup pending apply", "Countdown and stop the server", ChatFormatting.RED)).append(" ")
				.append(Msg.run("Cancel", "/backup pending cancel", "Remove the queued operation", ChatFormatting.GRAY));
			src.sendSuccess(() -> msg, false);
		});
	}

	public void pendingCancel(CommandSourceStack src) {
		withPending(src, op -> async(() -> {
			try {
				PendingOperationRunner.clearPending(services.storage);
				server.execute(() -> src.sendSuccess(() -> Msg.success("Removed queued " + op.describe() + "."), true));
			} catch (IOException e) {
				server.execute(() -> src.sendFailure(Msg.error(e.getMessage())));
			}
		}));
	}

	public void pendingApply(CommandSourceStack src) {
		withPending(src, op -> {
			Feedback fb = Feedback.of(src).and(Feedback.console());
			prompt(src, "Stop the server now to apply " + op.describe(), "Everyone is kicked after the countdown.",
				() -> beginStopCountdown(op, "Applying " + op.describe(), fb));
		});
	}

	// ------------------------------------------------------------------ maintenance

	public void requestDelete(int id, CommandSourceStack src) {
		Optional<BackupMeta> meta = services.repo.get(id);
		if (meta.isEmpty()) {
			src.sendFailure(Msg.error("No backup #" + id + "."));
			return;
		}
		if (meta.get().pinned) {
			src.sendFailure(Msg.error("Backup #" + id + " is pinned. Unpin it first with /backup unpin " + id + "."));
			return;
		}
		String who = src.getTextName();
		Feedback fb = Feedback.of(src);
		prompt(src, "Delete backup " + describe(meta.get()), "This cannot be undone.", () -> delete(id, who, fb));
	}

	public void delete(int id, String who, Feedback fb) {
		runJob("Delete", who, fb, true, job -> {
			BackupMeta m = services.repo.get(id).orElseThrow(() -> new IOException("No backup #" + id));
			if (m.pinned) throw new IOException("Backup #" + id + " is pinned");
			services.repo.delete(id);
			if (config.offsite.enabled && config.offsite.mirrorDeletes) {
				offsite.enqueueDeleteBackup(id);
				kickOffsite();
			}
			fb.success("Deleted backup #" + id + ". Unreferenced data is freed by the next prune or /backup gc.");
		});
	}

	public void setPinned(int id, boolean pinned, Feedback fb) {
		async(() -> {
			try {
				BackupMeta m = services.repo.get(id).orElseThrow(() -> new IOException("No backup #" + id)).copy();
				m.pinned = pinned;
				services.repo.updateMeta(m);
				fb.success((pinned ? "Pinned" : "Unpinned") + " backup #" + id + (pinned ? " (it will never be pruned)." : "."));
			} catch (IOException e) {
				fb.error(e.getMessage());
			}
		});
	}

	public void setComment(int id, String comment, Feedback fb) {
		async(() -> {
			try {
				BackupMeta m = services.repo.get(id).orElseThrow(() -> new IOException("No backup #" + id)).copy();
				m.comment = comment;
				services.repo.updateMeta(m);
				fb.success("Updated the comment of backup #" + id + ".");
			} catch (IOException e) {
				fb.error(e.getMessage());
			}
		});
	}

	PrunePolicy policy() {
		CytraConfig.PruneSettings p = config.prune;
		return new PrunePolicy(p.keepLast, p.keepHourly, p.keepDaily, p.keepWeekly, p.keepMonthly, p.maxAgeDays,
			(long) (p.maxTotalSizeGiB * 1024 * 1024 * 1024), p.alwaysKeepLatest, p.keepPreRestore, p.preRestoreMaxAgeDays);
	}

	public boolean prune(boolean dryRun, String who, Feedback fb) {
		return runJob(dryRun ? "Prune (dry run)" : "Prune", who, fb, dryRun, job -> doPrune(job, dryRun, fb));
	}

	private void doPrune(Job job, boolean dryRun, Feedback fb) throws Exception {
		Services s = services;
		job.progress().phase("Planning");
		PrunePolicy policy = policy();
		Pruner pruner = new Pruner(policy, zone());
		List<Pruner.Decision> plan = pruner.plan(s.repo.list(), System.currentTimeMillis(), meta -> {
			Map<Hash, Long> blobs = new HashMap<>();
			s.repo.loadManifest(meta.id).forEachBlob(ref -> blobs.put(ref.hash(), ref.storedLength()));
			return blobs;
		});
		List<Pruner.Decision> deletions = plan.stream().filter(d -> !d.keep()).toList();
		if (dryRun) {
			if (deletions.isEmpty()) {
				fb.info("Dry run: nothing would be deleted (" + plan.size() + " backups kept).");
				return;
			}
			fb.warn("Dry run: " + deletions.size() + " of " + plan.size() + " backup(s) would be deleted:");
			int shown = 0;
			for (Pruner.Decision d : deletions) {
				if (shown++ >= 25) {
					fb.info("... and " + (deletions.size() - 25) + " more.");
					break;
				}
				fb.info(" #" + d.backup().id + " " + Formatting.dateTime(d.backup().createdAt, zone()) + " " + d.backup().trigger.displayName()
					+ " — " + String.join(", ", d.reasons()));
			}
			return;
		}
		job.progress().phase("Deleting backups");
		job.progress().addTotal(deletions.size(), 0);
		for (Pruner.Decision d : deletions) {
			job.cancel().check();
			s.repo.delete(d.backup().id);
			if (config.offsite.enabled && config.offsite.mirrorDeletes) offsite.enqueueDeleteBackup(d.backup().id);
			job.progress().addDone(1, 0);
			CytraBackups.LOGGER.info("CytraBackups: pruned backup #{} ({})", d.backup().id, String.join(", ", d.reasons()));
		}
		long freed = 0;
		if (config.prune.garbageCollect && !deletions.isEmpty()) {
			GarbageCollector.Result gc = GarbageCollector.collect(s.repo, job.progress(), job.cancel());
			freed = gc.freedBytes();
			if (config.offsite.enabled && config.offsite.mirrorDeletes) offsite.enqueueDeleteBlobs(gc.deleted());
		}
		s.repo.state().lastPrune = System.currentTimeMillis();
		saveState();
		if (!deletions.isEmpty()) {
			fb.success("Pruned " + deletions.size() + " backup(s), freed " + Formatting.bytes(freed) + ".");
			notifier.prune(deletions.size(), freed);
			if (config.offsite.enabled) kickOffsite();
		} else {
			fb.info("Prune: nothing to delete.");
		}
	}

	public boolean garbageCollect(String who, Feedback fb) {
		return runJob("Garbage collection", who, fb, false, job -> {
			GarbageCollector.Result gc = GarbageCollector.collect(services.repo, job.progress(), job.cancel());
			if (config.offsite.enabled && config.offsite.mirrorDeletes) {
				offsite.enqueueDeleteBlobs(gc.deleted());
				kickOffsite();
			}
			fb.success("Garbage collection: removed " + gc.deletedBlobs() + " of " + gc.scannedBlobs() + " blobs, freed "
				+ Formatting.bytes(gc.freedBytes()) + "; store now " + Formatting.bytes(gc.liveBytes()) + ".");
		});
	}

	public boolean verify(int id, String who, Feedback fb) {
		return runJob("Verify", who, fb, false, job -> {
			Verifier.Result r = Verifier.verify(services.repo, id, services.workers, job.progress(), job.cancel());
			if (r.ok()) {
				fb.success("Backup #" + id + " is intact: " + r.blobsChecked() + " blobs (" + Formatting.bytes(r.bytesChecked()) + ") verified against their SHA-256.");
			} else {
				fb.error("Backup #" + id + " has " + r.problems().size() + " problem(s):");
				r.problems().stream().limit(10).forEach(p -> fb.error(" - " + p));
				notifier.backupFailure("Verification of #" + id, String.join("\n", r.problems().subList(0, Math.min(10, r.problems().size()))));
			}
		});
	}

	public boolean export(int id, String who, Feedback fb) {
		return runJob("Export", who, fb, false, job -> {
			Path out = ZipExporter.export(services.repo, id, services.storage.resolve("exports"), job.progress(), job.cancel());
			Path game = ModEnv.gameDir();
			String shown = out.startsWith(game) ? FileUtil.relative(game, out) : out.toString();
			fb.success("Exported backup #" + id + " to " + shown + " (" + Formatting.bytes(Files.size(out)) + "). Download it with your host's file manager.");
		});
	}

	public boolean importWorld(String path, String comment, String who, Feedback fb) {
		return runJob("Import", who, fb, false, job -> {
			Path game = ModEnv.gameDir();
			Path source = game.resolve(path).toAbsolutePath().normalize();
			if (!source.startsWith(game)) throw new IOException("Import path must be inside the server folder");
			if (source.startsWith(worldDir)) throw new IOException("Cannot import the live world folder; use /backup create");
			BackupService.Outcome out = WorldImporter.importWorld(services.backups, source, services.storage.resolve("tmp"), levelName, who, comment,
				services.filter(), job.progress(), job.cancel());
			fb.success("Imported " + path + " as backup #" + out.meta().id + " (" + Formatting.bytes(out.meta().totalSize) + ").");
			if (config.offsite.enabled) {
				offsite.enqueueUpload(out.meta().id);
				kickOffsite();
			}
		});
	}

	public void diff(int a, int b, Feedback fb) {
		async(() -> {
			try {
				int older = Math.min(a, b), newer = Math.max(a, b);
				BackupDiff.Result d = BackupDiff.compare(services.repo.loadManifest(older), services.repo.loadManifest(newer), 8);
				if (d.isEmpty()) {
					fb.info("Backups #" + older + " and #" + newer + " are identical (" + d.unchanged() + " files).");
					return;
				}
				fb.info("Diff #" + older + " -> #" + newer + ": " + d.added().size() + " added, " + d.removed().size() + " removed, "
					+ d.changed().size() + " changed files (" + d.changedChunks() + " chunks), " + d.unchanged() + " unchanged.");
				d.added().stream().limit(8).forEach(p -> fb.send(Msg.text("  + " + p, ChatFormatting.GREEN), false));
				d.removed().stream().limit(8).forEach(p -> fb.send(Msg.text("  - " + p, ChatFormatting.RED), false));
				d.changed().stream().limit(12).forEach(c -> {
					String extra = c.region() == null ? " (" + Formatting.bytes(c.oldSize()) + " -> " + Formatting.bytes(c.newSize()) + ")"
						: " (" + c.region().modified() + " modified, " + c.region().added() + " new, " + c.region().removed() + " removed chunks"
						+ (c.region().chunks().isEmpty() ? "" : "; e.g. " + c.region().chunks().stream().limit(4).map(p -> p.x() + "," + p.z()).toList()) + ")";
					fb.send(Msg.text("  ~ " + c.path() + extra, ChatFormatting.YELLOW), false);
				});
				int more = Math.max(0, d.added().size() - 8) + Math.max(0, d.removed().size() - 8) + Math.max(0, d.changed().size() - 12);
				if (more > 0) fb.info("... and " + more + " more.");
			} catch (IOException e) {
				fb.error(e.getMessage());
			}
		});
	}

	public boolean cancel(CommandSourceStack src) {
		if (countdown != null) {
			String label = countdown.label();
			countdown = null;
			server.getPlayerList().broadcastSystemMessage(Msg.success(label + " was cancelled by " + src.getTextName() + "."), false);
			return true;
		}
		Job job = currentJob.get();
		if (job == null) return false;
		job.cancel().cancel("cancelled by " + src.getTextName());
		src.sendSuccess(() -> Msg.info("Cancelling " + job.name() + "..."), true);
		return true;
	}

	public void reload(Feedback fb) {
		if (currentJob.get() != null) {
			fb.error("Wait for the running job to finish before reloading.");
			return;
		}
		async(() -> {
			try {
				CytraConfig cfg = ConfigIO.load(ModEnv.configFile());
				Services old = services;
				Services fresh = Services.open(cfg, ModEnv.storageFor(cfg, levelName));
				OffsiteSync freshOffsite = new OffsiteSync(fresh.repo, fresh.storage.resolve("offsite"), msg -> CytraBackups.LOGGER.info("CytraBackups offsite: {}", msg));
				config = cfg;
				services = fresh;
				offsite = freshOffsite;
				notifier.configure(cfg);
				old.close();
				fb.success("Configuration reloaded (" + fresh.repo.list().size() + " backups in " + fresh.storage + ").");
			} catch (Exception e) {
				fb.error("Reload failed, keeping the old configuration: " + rootMessage(e));
			}
		});
	}

	// ------------------------------------------------------------------ off-site

	OffsiteTarget createTarget(CytraConfig cfg) {
		CytraConfig.OffsiteSettings o = cfg.offsite;
		return switch (o.type) {
			case "sftp" -> new SftpTarget(o.sftp.host, o.sftp.port, o.sftp.username, o.sftp.password,
				o.sftp.privateKey.isBlank() ? null : ModEnv.gameDir().resolve(o.sftp.privateKey), o.sftp.privateKeyPassphrase,
				o.sftp.remoteDir, o.sftp.hostKeyChecking, services.storage.resolve("offsite").resolve("known_hosts"));
			case "webdav" -> new WebDavTarget(o.webdav.url, o.webdav.username, o.webdav.password);
			default -> new S3Target(o.s3.endpoint, o.s3.region, o.s3.bucket, o.s3.prefix, o.s3.accessKey, o.s3.secretKey, o.s3.pathStyle);
		};
	}

	public void kickOffsite() {
		CytraConfig cfg = config;
		if (!cfg.offsite.enabled || offsiteProgress != null) return;
		OffsiteSync sync = offsite;
		if (!sync.hasWork()) return;
		Progress p = new Progress();
		offsiteProgress = p;
		offsiteExecutor.execute(() -> {
			try (OffsiteTarget target = createTarget(cfg)) {
				OffsiteSync.Result r = sync.process(target, offsiteWorkers, !cfg.offsite.type.equals("sftp"), p, CancelToken.NONE);
				CytraBackups.LOGGER.info("CytraBackups: off-site sync to {} done: {} backup(s), {} blobs ({}), {} deletions",
					target.describe(), r.backupsUploaded(), r.blobsUploaded(), Formatting.bytes(r.bytesUploaded()), r.deleted());
			} catch (Exception e) {
				CytraBackups.LOGGER.warn("CytraBackups: off-site sync failed (will retry later): {}", rootMessage(e));
				notifier.backupFailure("Off-site copy", rootMessage(e));
			} finally {
				offsiteProgress = null;
			}
		});
	}

	public void offsiteSyncAll(Feedback fb) {
		if (!config.offsite.enabled) {
			fb.error("Off-site copies are disabled (offsite.enabled in the config).");
			return;
		}
		async(() -> {
			try (OffsiteTarget target = createTarget(config)) {
				offsite.bindTarget(target.id());
				int n = offsite.enqueueMissing();
				fb.success("Queued " + n + " backup(s) for upload to " + target.describe() + ".");
				kickOffsite();
			} catch (Exception e) {
				fb.error("Off-site sync failed: " + rootMessage(e));
			}
		});
	}

	public List<String> offsiteStatus() {
		List<String> out = new ArrayList<>();
		if (!config.offsite.enabled) {
			out.add("Off-site: disabled");
			return out;
		}
		OffsiteSync.Queue q = offsite.queue();
		out.add("Off-site (" + config.offsite.type + "): " + q.uploaded.size() + " backup(s) uploaded, " + q.uploads.size() + " queued, "
			+ q.deleteKeys.size() + " deletion(s) queued");
		if (q.lastSuccess > 0) out.add("Last successful sync: " + Formatting.dateTime(q.lastSuccess, zone()));
		if (!q.lastError.isBlank()) out.add("Last error: " + q.lastError);
		Progress p = offsiteProgress;
		if (p != null) out.add("Uploading: " + p.phase() + " " + Formatting.percent(p.fraction()));
		return out;
	}

	// ------------------------------------------------------------------ status

	/** Server thread: snapshots in-memory state, then does the disk reads (free space, queue) off-thread. */
	public void sendStatus(CommandSourceStack src) {
		List<Component> lines = new ArrayList<>();
		long now = System.currentTimeMillis();
		Job job = currentJob.get();
		if (countdown != null) lines.add(Msg.warn(countdown.label() + ": server stops in " + countdown.secondsLeft() + "s"));
		if (job != null) {
			Progress p = job.progress();
			String eta = "";
			float f = p.fraction();
			if (f > 0.02f) {
				long elapsed = now - job.startedAt();
				eta = ", ETA " + Formatting.duration((long) (elapsed / f - elapsed));
			}
			lines.add(Msg.info("Running: " + ProgressDisplay.line(job) + " — started by " + job.requestedBy() + " " + Formatting.ago(job.startedAt(), now)
				+ eta + (p.detail().isBlank() ? "" : " [" + p.detail() + "]")));
		} else {
			lines.add(Msg.info("No job running."));
		}
		List<BackupMeta> all = services.repo.list();
		RepositoryState st = services.repo.state();
		lines.add(Msg.info(all.size() + " backups; last backup " + (st.lastBackup > 0 ? Formatting.ago(st.lastBackup, now) : "never")
			+ "; next automatic backup " + nextBackupDescription(now) + "."));
		List<String> offsiteLines = offsiteStatus();
		async(() -> {
			long free = FileUtil.usableSpace(services.storage);
			lines.add(Msg.info("Storage: " + services.storage + " (" + Formatting.bytes(free) + " free)"));
			try {
				PendingOperation op = PendingOperationRunner.readPending(services.storage);
				if (op != null) lines.add(Msg.warn("Queued for restart: " + op.describe()));
			} catch (IOException ignored) {
			}
			for (String s : offsiteLines) lines.add(Msg.info(s));
			server.execute(() -> {
				for (Component line : lines) src.sendSuccess(() -> line, false);
			});
		});
	}

	private String nextBackupDescription(long now) {
		CytraConfig.ScheduleSettings s = config.schedule;
		if (!s.enabled) return "disabled";
		long next = nextScheduledTime(now);
		if (next == Long.MAX_VALUE) return "not scheduled";
		return next <= now ? "due now" : "in " + Formatting.duration(next - now);
	}

	// ------------------------------------------------------------------ scheduler & tick (server thread)

	long nextScheduledTime(long now) {
		CytraConfig.ScheduleSettings s = config.schedule;
		long last = services.repo.state().lastScheduledBackup;
		long earliest = startedAt + s.startupDelayMinutes * 60_000L;
		long next = Long.MAX_VALUE;
		if (s.intervalMinutes > 0) next = Math.max(last + s.intervalMinutes * 60_000L, earliest);
		ZoneId zone = zone();
		ZonedDateTime nowZ = Instant.ofEpochMilli(now).atZone(zone);
		for (String t : s.timesOfDay) {
			LocalTime lt = LocalTime.parse(t.length() == 4 ? "0" + t : t);
			ZonedDateTime slot = nowZ.with(lt);
			if (slot.toInstant().toEpochMilli() > now) slot = slot.minusDays(1);
			long slotMs = slot.toInstant().toEpochMilli();
			long candidate = slotMs > last && now - slotMs < 6 * 3_600_000L ? Math.max(slotMs, earliest) : slot.plusDays(1).toInstant().toEpochMilli();
			next = Math.min(next, candidate);
		}
		return next;
	}

	public void tick() {
		tickCounter++;
		if (countdown != null && countdown.tick(server)) countdown = null;
		if (tickCounter % 10 == 0) display.update(server, config, currentJob.get());
		if (tickCounter % 20 == 0) {
			long now = System.currentTimeMillis();
			confirmations.values().removeIf(c -> c.expiresAt < now);
			schedulerTick(now);
		}
	}

	private void schedulerTick(long now) {
		if (countdown != null || stoppingForRestore) return;
		CytraConfig cfg = config;
		RepositoryState st = services.repo.state();
		boolean playersOnline = !server.getPlayerList().getPlayers().isEmpty();
		if (playersOnline && !st.playersSeenSinceBackup) {
			st.playersSeenSinceBackup = true;
			saveState();
		}
		if (now - lastLowDiskCheck > 10 * 60_000L) {
			lastLowDiskCheck = now;
			async(() -> checkLowDisk(false));
		}
		if (currentJob.get() != null || now < nextScheduleCheck) return;
		if (leaveBackupPending && !playersOnline) {
			leaveBackupPending = false;
			nextScheduleCheck = now + 60_000L;
			createBackup(Trigger.PLAYER_LEAVE, "Last player left", "scheduler", Feedback.console());
			return;
		}
		if (cfg.schedule.enabled && now >= nextScheduledTime(now)) {
			nextScheduleCheck = now + 60_000L;
			if (cfg.schedule.onlyIfPlayersWereOnline && !st.playersSeenSinceBackup && !playersOnline) {
				st.lastScheduledBackup = now;
				saveState();
				CytraBackups.LOGGER.info("CytraBackups: automatic backup skipped - no players were online since the last backup");
				return;
			}
			createBackup(Trigger.SCHEDULED, "", "scheduler", Feedback.console());
			return;
		}
		if (cfg.prune.enabled && cfg.prune.intervalMinutes > 0 && now - st.lastPrune >= cfg.prune.intervalMinutes * 60_000L
			&& now - startedAt > cfg.schedule.startupDelayMinutes * 60_000L) {
			nextScheduleCheck = now + 60_000L;
			prune(false, "scheduler", Feedback.console());
			return;
		}
		if (cfg.offsite.enabled && offsiteProgress == null && tickCounter % (20 * 300) == 0) kickOffsite();
	}

	private void checkLowDisk(boolean force) {
		long free = FileUtil.usableSpace(services.storage);
		long threshold = config.discord.lowDiskWarningMiB * 1024L * 1024L;
		long now = System.currentTimeMillis();
		if (free < threshold && (force || now - lastLowDiskWarning > 6 * 3_600_000L) && now - lastLowDiskWarning > 3_600_000L) {
			lastLowDiskWarning = now;
			CytraBackups.LOGGER.warn("CytraBackups: low disk space on the backup disk: {} free", Formatting.bytes(free));
			notifier.lowDisk(free, threshold);
		}
	}

	public void onPlayerJoin(ServerPlayer player) {
		RestoreResult r = startupResult;
		if (r != null && System.currentTimeMillis() - r.finishedAt < 3_600_000L && Perms.check(player, Perms.RESTORE)) {
			player.sendSystemMessage(r.success ? Msg.success("Last restore: " + r.message) : Msg.error("Last restore FAILED: " + r.message));
		}
	}

	public void onPlayerLeave(ServerPlayer player) {
		if (config.schedule.backupWhenLastPlayerLeaves && server.getPlayerList().getPlayers().size() <= 1 && !stoppingForRestore) {
			leaveBackupPending = true;
		}
	}

	public void onServerStarted() {
		async(() -> {
			try {
				RestoreResult r = PendingOperationRunner.readResult(services.storage);
				if (r != null && !r.reported) {
					startupResult = r;
					r.reported = true;
					PendingOperationRunner.writeResult(services.storage, r);
					// Discord was already notified when the restore was applied (StartupRestore); only players are told here.
				}
				PendingOperation op = PendingOperationRunner.readPending(services.storage);
				if (op != null) CytraBackups.LOGGER.warn("CytraBackups: {} is queued and will be applied on the next restart", op.describe());
			} catch (IOException e) {
				CytraBackups.LOGGER.warn("CytraBackups: {}", e.getMessage());
			}
		});
	}

	/** SERVER_STOPPING: abort the running job so shutdown is not delayed. */
	public void onServerStopping() {
		countdown = null;
		Job job = currentJob.get();
		if (job != null) job.cancel().cancel("server is stopping");
	}

	/** SERVER_STOPPED: the world is saved and closed. Optional shutdown backup, then "shutdown"-mode restores. */
	public void onServerStopped() {
		jobExecutor.shutdown();
		try {
			if (!jobExecutor.awaitTermination(60, TimeUnit.SECONDS)) CytraBackups.LOGGER.warn("CytraBackups: job did not stop in time");
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		display.clear();
		CytraConfig cfg = config;
		Services s = services;
		try {
			boolean pending = PendingOperationRunner.readPending(s.storage) != null;
			if (cfg.schedule.backupOnStop && !stoppingForRestore) {
				CytraBackups.LOGGER.info("CytraBackups: creating shutdown backup");
				BackupService.Request req = new BackupService.Request();
				req.worldDir = worldDir;
				req.levelName = levelName;
				req.trigger = Trigger.SHUTDOWN;
				req.comment = "Server stop";
				req.creator = "server";
				req.filter = s.filter();
				req.excludedDirs = s.excludedDirs(worldDir);
				req.skipIfUnchanged = cfg.schedule.skipIfUnchanged;
				List<Glob> ignore = cfg.schedule.unchangedIgnore.stream().map(Glob::new).toList();
				req.unchangedIgnore = p -> ignore.stream().anyMatch(g -> g.matches(p));
				req.beforeWrite = bytes -> checkSpace(s, bytes);
				BackupService.Outcome out = s.backups.create(req);
				if (out.meta() != null) {
					CytraBackups.LOGGER.info("CytraBackups: shutdown backup #{} created", out.meta().id);
					notifier.backupSuccess(out.meta());
					if (cfg.offsite.enabled) offsite.enqueueUpload(out.meta().id);
				}
			}
			if (pending && (applyOnStop || cfg.restore.applyMode.equals("shutdown"))) {
				StartupRestore.apply(cfg, s.storage, levelName, worldDir, "shutdown");
			}
		} catch (Exception e) {
			CytraBackups.LOGGER.error("CytraBackups: shutdown work failed", e);
			notifier.backupFailure("Shutdown backup/restore", rootMessage(e));
		} finally {
			ioExecutor.shutdown();
			offsiteExecutor.shutdownNow();
			offsiteWorkers.shutdownNow();
			try {
				ioExecutor.awaitTermination(10, TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			s.close();
			notifier.close();
			instance = null;
		}
	}

	// ------------------------------------------------------------------ GUI support

	public ServerLevel levelOf(String dimensionId) {
		for (ServerLevel l : server.getAllLevels()) {
			if (l.dimension().identifier().toString().equals(dimensionId)) return l;
		}
		return null;
	}

	public String levelName() {
		return levelName;
	}

	public static Predicate<CommandSourceStack> isRunning() {
		return src -> instance != null;
	}
}
