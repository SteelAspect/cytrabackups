package dev.steelaspect.cytrabackups.mc;

import dev.steelaspect.cytrabackups.CytraBackups;
import dev.steelaspect.cytrabackups.core.CancelToken;
import dev.steelaspect.cytrabackups.core.FileUtil;
import dev.steelaspect.cytrabackups.core.Formatting;
import dev.steelaspect.cytrabackups.core.Hash;
import dev.steelaspect.cytrabackups.core.Lang;
import dev.steelaspect.cytrabackups.core.LongHashSet;
import dev.steelaspect.cytrabackups.core.Progress;
import dev.steelaspect.cytrabackups.core.backup.BackupMeta;
import dev.steelaspect.cytrabackups.core.backup.BackupService;
import dev.steelaspect.cytrabackups.core.backup.ChunkSelection;
import dev.steelaspect.cytrabackups.net.CytraNetworking;
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
import dev.steelaspect.cytrabackups.core.offsite.StreamingUpload;
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
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
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

	/** A running job. {@code name} is a lang key. {@code quiet} jobs (e.g. metadata edits) do not show a boss bar. */
	public record Job(String name, String requestedBy, Progress progress, CancelToken cancel, long startedAt, boolean quiet) {
		public String displayName() {
			return Lang.get(name);
		}
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

	static final String JOB_BACKUP = "cytrabackups.job.backup";

	private final MinecraftServer server;
	private final Path worldDir;
	private final String levelName;
	private volatile CytraConfig config;
	private volatile Services services;
	private volatile OffsiteSync offsite;
	private final ExecutorService jobExecutor = Executors.newSingleThreadExecutor(Services.threadFactory("CytraBackups-Job"));
	private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor(Services.threadFactory("CytraBackups-IO"));
	private final ExecutorService offsiteExecutor = Executors.newSingleThreadExecutor(Services.threadFactory("CytraBackups-Offsite"));
	private final ExecutorService offsiteWorkers = Executors.newCachedThreadPool(Services.threadFactory("CytraBackups-Upload"));
	private final AtomicReference<Job> currentJob = new AtomicReference<>();
	private final AtomicReference<Progress> offsiteProgress = new AtomicReference<>();
	/** The running backup's uploads (off-site only mode), for the status. */
	private volatile StreamingUpload activeStream;
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
		this.offsite = services.offsite;
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

	boolean runJob(String name, String requestedBy, Feedback fb, boolean quiet, JobBody body) {
		if (countdown != null || stoppingForRestore) {
			fb.error("cytrabackups.error.restore_in_progress");
			return false;
		}
		Job job = new Job(name, requestedBy, new Progress(), new CancelToken(), System.currentTimeMillis(), quiet);
		if (!currentJob.compareAndSet(null, job)) {
			Job running = currentJob.get();
			fb.error("cytrabackups.error.busy", running != null ? running.displayName() : Lang.get("cytrabackups.job.other"));
			return false;
		}
		jobExecutor.execute(() -> {
			try {
				body.run(job);
			} catch (CancellationException e) {
				fb.info("cytrabackups.job.cancelled", job.displayName());
			} catch (Throwable t) {
				String msg = rootMessage(t);
				CytraBackups.LOGGER.error("CytraBackups: {} failed", job.displayName(), t);
				fb.error("cytrabackups.job.failed", job.displayName(), msg);
				if (name.equals(JOB_BACKUP)) notifier.backupFailure(job.displayName(), msg);
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

	public boolean createBackup(Trigger trigger, String comment, String creator, Feedback fb) {
		boolean automatic = trigger == Trigger.SCHEDULED || trigger == Trigger.PLAYER_LEAVE;
		return runJob(JOB_BACKUP, creator, fb, false, job -> {
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

	/** Off-site only: most bytes of not yet uploaded backup data kept here. */
	private static long bufferBytes(CytraConfig cfg) {
		return Math.max(1, cfg.offsite.localBufferGiB) * 1024L * 1024L * 1024L;
	}

	private void checkSpace(Services s, long bytesToWrite) {
		long free = FileUtil.usableSpace(s.storage);
		long reserve = config.minFreeSpaceMiB * 1024L * 1024L;
		if (free - bytesToWrite < reserve) {
			throw new NotEnoughSpaceException(Lang.get("cytrabackups.error.disk_space", Formatting.bytes(free), Formatting.bytes(bytesToWrite), Formatting.bytes(reserve)));
		}
	}

	private BackupMeta doBackup(Job job, Trigger trigger, String comment, String creator, Feedback fb, boolean automatic) throws Exception {
		Services s = services;
		CytraConfig cfg = config;
		checkSpace(s, 0);
		job.progress().phase(Lang.get("cytrabackups.phase.saving"));
		AtomicReference<Map<ServerLevel, Boolean>> previous = new AtomicReference<>();
		BackupService.Outcome out = null;
		OffsiteTarget streamTarget = null;
		StreamingUpload stream = null;
		try {
			previous.set(await(SaveControl.saveAndDisable(server, cfg.flushOnSave), job));
			if (s.offsiteOnly) {
				streamTarget = OffsiteTargets.create(cfg, s.storage);
				stream = new StreamingUpload(s.offsite, s.blobs, streamTarget, offsiteWorkers, OffsiteTargets.threads(cfg), bufferBytes(cfg), () -> {
					// Upload-bound from here on: let the world save meanwhile instead of holding it for what can be hours.
					CytraBackups.LOGGER.info("CytraBackups: uploading is slower than reading the world; world saving is back on for the rest of this backup");
					SaveControl.restore(server, previous.get());
				}, msg -> CytraBackups.LOGGER.warn("CytraBackups offsite: {}", msg), msg -> CytraBackups.LOGGER.info("CytraBackups offsite: {}", msg), job.cancel());
				s.blobs.setWriteHook(stream);
				activeStream = stream;
			}
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
			// off-site only: at most the upload buffer is ever written here
			req.beforeWrite = bytes -> checkSpace(s, s.offsiteOnly ? Math.min(bytes, bufferBytes(cfg)) : bytes);
			req.warnings = w -> CytraBackups.LOGGER.warn("CytraBackups: {}", w);
			out = s.backups.create(req);
			SaveControl.restore(server, previous.get()); // the world is read; do not hold saving while the last uploads finish
			if (stream != null) s.offsite.checkpoint(); // the new backup uses evicted data: persist where it is now
			if (stream != null) {
				job.progress().phase(Lang.get("cytrabackups.phase.upload_rest"));
				try {
					stream.finish();
				} catch (IOException e) {
					// The backup itself is complete here; the regular off-site sync uploads the rest later.
					CytraBackups.LOGGER.warn("CytraBackups: {}; the rest is uploaded by the next off-site sync", e.getMessage());
				}
			}
		} finally {
			s.blobs.setWriteHook(null);
			activeStream = null;
			try {
				if (stream != null) stream.close(out != null && out.meta() != null ? out.scan().manifest : null);
			} finally {
				if (streamTarget != null) streamTarget.close();
				SaveControl.restore(server, previous.get());
			}
		}
		if (stream != null && stream.waited()) fb.info("cytrabackups.backup.upload_bound");
		RepositoryState st = s.repo.state();
		st.lastBackup = System.currentTimeMillis();
		st.playersSeenSinceBackup = !server.getPlayerList().getPlayers().isEmpty();
		saveState();
		if (out.skippedUnchanged()) {
			fb.info("cytrabackups.backup.skipped");
			return null;
		}
		BackupMeta m = out.meta();
		MutableComponent msg = Msg.success("cytrabackups.backup.created", m.id, Formatting.bytes(m.totalSize), Formatting.bytes(m.newStoredBytes),
			Formatting.duration(m.durationMillis));
		msg.append(" ").append(Msg.button("cytrabackups.button.info", Msg.command("info", m.id), "cytrabackups.hover.info", m.id));
		if (!out.scan().warnings.isEmpty()) msg.append(" ").append(Msg.detail("cytrabackups.backup.warnings", out.scan().warnings.size()));
		fb.send(msg, false);
		if (automatic && cfg.progress.announceScheduled) {
			server.execute(() -> server.getPlayerList().broadcastSystemMessage(Msg.info("cytrabackups.backup.announce", m.id), false));
		}
		notifier.backupSuccess(m);
		if (cfg.offsite.enabled) {
			offsite.enqueueUpload(m.id);
			kickOffsite();
		}
		checkLowDisk(true);
		return m;
	}

	/** Sends a clickable confirmation prompt; the action runs when the same source confirms in time. */
	public void prompt(CommandSourceStack src, String description, String details, Runnable action) {
		byte[] b = new byte[4];
		random.nextBytes(b);
		String token = Hash.compute(b).hex().substring(0, 8);
		int timeout = config.restore.confirmTimeoutSeconds;
		confirmations.put(token, new Confirm(src.getTextName(), System.currentTimeMillis() + timeout * 1000L, description, action));
		MutableComponent msg = Msg.info("cytrabackups.confirm.question", description);
		src.sendSuccess(() -> msg, false);
		MutableComponent answer = Msg.detail("cytrabackups.confirm.details", details).append(" ")
			.append(Msg.dangerButton("cytrabackups.button.confirm", Msg.command("confirm", token), "cytrabackups.hover.confirm", timeout)).append(" ")
			.append(Msg.button("cytrabackups.button.cancel", Msg.command("deny", token), "cytrabackups.hover.cancel"));
		if (!src.isPlayer()) answer.append(" ").append(Msg.detail("cytrabackups.confirm.console", Msg.command("confirm", token).substring(1)));
		src.sendSuccess(() -> answer, false);
		CytraNetworking.sendPrompt(src, token, Lang.get("cytrabackups.confirm.question", description), details, timeout);
	}

	public void confirm(String token, CommandSourceStack src, boolean accept) {
		Confirm c = confirmations.remove(token);
		if (c == null) {
			src.sendFailure(Msg.error("cytrabackups.error.confirm_unknown"));
			return;
		}
		if (!c.requester.equals(src.getTextName())) {
			confirmations.put(token, c);
			src.sendFailure(Msg.error("cytrabackups.error.confirm_other", c.requester));
			return;
		}
		if (System.currentTimeMillis() > c.expiresAt) {
			src.sendFailure(Msg.error("cytrabackups.error.confirm_expired"));
			return;
		}
		if (!accept) {
			src.sendSuccess(() -> Msg.info("cytrabackups.confirm.cancelled"), false);
			return;
		}
		c.action.run();
	}

	private String describe(BackupMeta m) {
		String when = Formatting.dateTimeShort(m.createdAt, zone());
		return m.comment.isBlank() ? Lang.get("cytrabackups.backup.describe", m.id, when) : Lang.get("cytrabackups.backup.describe_comment", m.id, when, m.comment);
	}

	/** Whole-world restore, or for an area backup: restore just that area again (live when possible). */
	public void requestFullRestore(int id, CommandSourceStack src) {
		Optional<BackupMeta> meta = services.repo.get(id);
		if (meta.isEmpty()) {
			src.sendFailure(Msg.error("cytrabackups.error.no_backup", id));
			return;
		}
		BackupMeta m = meta.get();
		if (m.partial && m.areaBoxes != null && !m.areaBoxes.isEmpty() && m.areaDimension != null) {
			Identifier dim = Identifier.tryParse(m.areaDimension);
			ServerLevel level = dim == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, dim));
			if (level == null) {
				src.sendFailure(Msg.error("cytrabackups.error.no_dimension", m.areaDimension));
				return;
			}
			requestChunkRestore(id, level, new ChunkSelection(m.areaBoxes), src);
			return;
		}
		String who = src.getTextName();
		Feedback fb = Feedback.of(src).and(Feedback.console());
		String details = Lang.get(config.restore.applyMode.equals("startup") ? "cytrabackups.restore.full.details_restart" : "cytrabackups.restore.full.details_stop",
			config.restore.countdownSeconds);
		if (m.partial) details = Lang.get("cytrabackups.restore.partial.details") + " " + details;
		prompt(src, Lang.get(m.partial ? "cytrabackups.restore.partial.question" : "cytrabackups.restore.full.question", describe(m)), details,
			() -> startFullRestore(id, who, fb));
	}

	public void startFullRestore(int id, String who, Feedback fb) {
		PendingOperation op = new PendingOperation();
		op.type = PendingOperation.Type.FULL_RESTORE;
		op.backupId = id;
		op.worldDir = worldDir.toString();
		op.requestedBy = who;
		op.requestedAt = System.currentTimeMillis();
		beginStopCountdown(op, Lang.get("cytrabackups.countdown.restore", id), fb);
	}

	private void beginStopCountdown(PendingOperation op, String label, Feedback fb) {
		server.execute(() -> {
			if (countdown != null || stoppingForRestore) {
				fb.error("cytrabackups.error.countdown_running");
				return;
			}
			Job job = currentJob.get();
			if (job != null) {
				fb.error("cytrabackups.error.busy", job.displayName());
				return;
			}
			countdown = new Countdown(config.restore.countdownSeconds, label, () -> finishStop(op, fb));
			fb.info("cytrabackups.countdown.started", label, config.restore.countdownSeconds);
			notifier.restore(Lang.get("cytrabackups.discord.restore_scheduled"),
				Lang.get("cytrabackups.discord.restore_scheduled.text", op.describe(), op.requestedBy, config.restore.countdownSeconds), false);
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
			fb.error("cytrabackups.error.queue_failed", e.getMessage());
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
			src.sendFailure(Msg.error("cytrabackups.error.no_backup", id));
			return;
		}
		long n = sel.chunkCount();
		if (n > config.restore.maxChunks) {
			src.sendFailure(Msg.error("cytrabackups.error.too_many_chunks", n, config.restore.maxChunks));
			return;
		}
		String who = src.getTextName();
		Feedback fb = Feedback.of(src).and(Feedback.console());
		String details = Lang.get("cytrabackups.restore.area.details");
		if (sel.boxes().size() == 1) {
			ChunkSelection.Box b = sel.boxes().getFirst();
			details = Lang.get("cytrabackups.restore.area.blocks", b.minX() * 16, b.maxX() * 16 + 15, b.minZ() * 16, b.maxZ() * 16 + 15) + " " + details;
		}
		prompt(src, Lang.get(Lang.plural("cytrabackups.restore.area.question", n), n, level.dimension().identifier(), describe(meta.get())), details,
			() -> startChunkRestore(id, level, sel, who, fb));
	}

	public void startChunkRestore(int id, ServerLevel level, ChunkSelection sel, String who, Feedback fb) {
		runJob("cytrabackups.job.area_restore", who, fb, false, job -> doChunkRestore(job, id, level, sel, who, fb));
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
			queueChunkRestore(id, dimId, dimFolder, sel, who, Lang.get("cytrabackups.restore.live_disabled"), fb);
			return;
		}
		int viewDistance = server.getPlayerList().getViewDistance();
		job.progress().phase(Lang.get("cytrabackups.phase.check_area"));
		LiveChunkRestore.Safety safety = await(server.submit(() -> LiveChunkRestore.quickCheck(level, sel, viewDistance)), job);
		if (!safety.safe()) {
			queueChunkRestore(id, dimId, dimFolder, sel, who, safety.reason(), fb);
			return;
		}
		job.progress().phase(Lang.get("cytrabackups.phase.wait_unload"));
		long deadline = System.currentTimeMillis() + cfg.restore.liveUnloadTimeoutSeconds * 1000L;
		while (true) {
			int loaded = await(server.submit(() -> LiveChunkRestore.countLoaded(level, sel)), job);
			if (loaded == 0) break;
			job.progress().detail(Lang.get("cytrabackups.phase.detail.loaded", loaded));
			if (System.currentTimeMillis() > deadline) {
				LiveChunkRestore.Safety again = await(server.submit(() -> LiveChunkRestore.quickCheck(level, sel, viewDistance)), job);
				queueChunkRestore(id, dimId, dimFolder, sel, who, again.safe()
					? Lang.get("cytrabackups.restore.stayed_loaded", loaded, cfg.restore.liveUnloadTimeoutSeconds) : again.reason(), fb);
				return;
			}
			Thread.sleep(250);
		}
		job.progress().phase(Lang.get("cytrabackups.phase.flush"));
		await(await(server.submit(() -> LiveChunkRestore.flush(level)), job), job);

		job.progress().phase(Lang.get("cytrabackups.phase.backup_area"));
		BackupService.Request pre = new BackupService.Request();
		pre.worldDir = worldDir;
		pre.levelName = levelName;
		pre.trigger = Trigger.PRE_RESTORE;
		pre.comment = Lang.get("cytrabackups.restore.comment.area", id);
		pre.creator = who;
		pre.filter = s.filter();
		pre.excludedDirs = s.excludedDirs(worldDir);
		java.util.Set<String> affected = PendingOperationRunner.affectedPaths(dimFolder, sel);
		pre.only = affected::contains;
		pre.scope = Lang.get("cytrabackups.restore.scope", sel.describe(), dimId);
		pre.restoreTarget = id;
		pre.areaDimension = dimId;
		pre.areaBoxes = sel.boxes();
		pre.progress = job.progress();
		pre.cancel = job.cancel();
		pre.beforeWrite = bytes -> checkSpace(s, bytes);
		BackupMeta preMeta = s.backups.create(pre).meta();

		job.progress().phase(Lang.get("cytrabackups.phase.read_chunks", id));
		List<LiveChunkRestore.Write> writes = LiveChunkRestore.prepare(manifest, s.blobs, worldDir, dimFolder, sel);
		job.progress().phase(Lang.get("cytrabackups.phase.write_chunks"));
		String problem = await(server.submit(() -> {
			LiveChunkRestore.Safety q = LiveChunkRestore.quickCheck(level, sel, viewDistance);
			if (!q.safe()) return q.reason();
			int still = LiveChunkRestore.countLoaded(level, sel);
			if (still > 0) return Lang.get("cytrabackups.restore.reloaded", still);
			LiveChunkRestore.apply(level, writes);
			return null;
		}), job);
		if (problem != null) {
			queueChunkRestore(id, dimId, dimFolder, sel, who, problem, fb);
			return;
		}
		await(await(server.submit(() -> LiveChunkRestore.flush(level)), job), job);
		long chunks = writes.stream().filter(w -> w.kind() == LiveChunkRestore.Kind.REGION).count();
		fb.send(Msg.success(Lang.plural("cytrabackups.restore.area.done", chunks), chunks, dimId, id, preMeta.id).append(" ")
			.append(Msg.dangerButton("cytrabackups.button.undo", Msg.command("restore", preMeta.id), "cytrabackups.hover.undo_area", preMeta.id)), false);
		notifier.restore(Lang.get("cytrabackups.discord.area_restored"), Lang.get(Lang.plural("cytrabackups.restore.area.done", chunks), chunks, dimId, id, preMeta.id), false);
	}

	private void queueChunkRestore(int id, String dimId, String dimFolder, ChunkSelection sel, String who, String reason, Feedback fb) throws IOException {
		PendingOperation existing = PendingOperationRunner.readPending(services.storage);
		if (existing != null) {
			fb.error("cytrabackups.error.already_queued", reason, existing.describe());
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
		String when = Lang.get(server.isDedicatedServer()
			? (startup ? "cytrabackups.restore.when.next_start" : "cytrabackups.restore.when.next_stop")
			: (startup ? "cytrabackups.restore.when.next_open" : "cytrabackups.restore.when.leave"));
		fb.send(Msg.info("cytrabackups.restore.queued", reason, when).append(" ")
			.append(Msg.dangerButton("cytrabackups.button.apply", Msg.command("pending", "apply"), "cytrabackups.hover.apply")), false);
	}

	public void requestRollback(CommandSourceStack src) {
		// Restore records can be large for big worlds: read them off the server thread, prompt back on it.
		async(() -> {
			Optional<RestoreRecord> rec;
			try {
				rec = services.restore.latestUndoable();
			} catch (IOException e) {
				server.execute(() -> src.sendFailure(Msg.error("cytrabackups.error.history", e.getMessage())));
				return;
			}
			server.execute(() -> promptRollback(src, rec));
		});
	}

	private void promptRollback(CommandSourceStack src, Optional<RestoreRecord> rec) {
		if (rec.isEmpty()) {
			src.sendFailure(Msg.error("cytrabackups.error.no_rollback", config.restore.recycleBinKeep));
			return;
		}
		RestoreRecord r = rec.get();
		String who = src.getTextName();
		Feedback fb = Feedback.of(src).and(Feedback.console());
		prompt(src, Lang.get("cytrabackups.rollback.question", r.description, Formatting.dateTimeShort(r.appliedAt, zone())),
			Lang.get("cytrabackups.rollback.details"),
			() -> {
				PendingOperation op = new PendingOperation();
				op.type = PendingOperation.Type.ROLLBACK;
				op.restoreId = r.restoreId;
				op.worldDir = worldDir.toString();
				op.requestedBy = who;
				op.requestedAt = System.currentTimeMillis();
				beginStopCountdown(op, Lang.get("cytrabackups.countdown.rollback"), fb);
			});
	}

	/** Reads the queued operation off the server thread and hands it (or an error) back on the server thread. */
	private void withPending(CommandSourceStack src, java.util.function.Consumer<PendingOperation> onServer) {
		async(() -> {
			try {
				PendingOperation op = PendingOperationRunner.readPending(services.storage);
				server.execute(() -> {
					if (op == null) src.sendFailure(Msg.error("cytrabackups.error.nothing_queued"));
					else onServer.accept(op);
				});
			} catch (IOException e) {
				server.execute(() -> src.sendFailure(Msg.error(e.getMessage())));
			}
		});
	}

	public void pendingInfo(CommandSourceStack src) {
		withPending(src, op -> {
			src.sendSuccess(() -> Msg.info("cytrabackups.pending.info", op.describe(), op.requestedBy, Msg.ago(op.requestedAt, zone())), false);
			MutableComponent line = op.reason.isBlank() ? Component.empty() : Msg.detail("cytrabackups.pending.reason", op.reason).append(" ");
			line.append(Msg.dangerButton("cytrabackups.button.apply", Msg.command("pending", "apply"), "cytrabackups.hover.apply")).append(" ")
				.append(Msg.button("cytrabackups.button.cancel", Msg.command("pending", "cancel"), "cytrabackups.hover.pending_cancel"));
			src.sendSuccess(() -> line, false);
		});
	}

	public void pendingCancel(CommandSourceStack src) {
		withPending(src, op -> async(() -> {
			try {
				PendingOperationRunner.clearPending(services.storage);
				server.execute(() -> src.sendSuccess(() -> Msg.success("cytrabackups.pending.cancelled", op.describe()), true));
			} catch (IOException e) {
				server.execute(() -> src.sendFailure(Msg.error(e.getMessage())));
			}
		}));
	}

	public void pendingApply(CommandSourceStack src) {
		withPending(src, op -> {
			Feedback fb = Feedback.of(src).and(Feedback.console());
			prompt(src, Lang.get("cytrabackups.pending.apply_question", op.describe()), Lang.get("cytrabackups.pending.apply_details"),
				() -> beginStopCountdown(op, Lang.get("cytrabackups.countdown.apply", op.describe()), fb));
		});
	}

	public void requestDelete(int id, CommandSourceStack src) {
		Optional<BackupMeta> meta = services.repo.get(id);
		if (meta.isEmpty()) {
			src.sendFailure(Msg.error("cytrabackups.error.no_backup", id));
			return;
		}
		if (meta.get().pinned) {
			src.sendFailure(Msg.error("cytrabackups.error.pinned", id));
			return;
		}
		String who = src.getTextName();
		Feedback fb = Feedback.of(src).and(Feedback.console());
		prompt(src, Lang.get("cytrabackups.delete.question", describe(meta.get())), Lang.get("cytrabackups.delete.details"), () -> delete(id, who, fb));
	}

	public void delete(int id, String who, Feedback fb) {
		runJob("cytrabackups.job.delete", who, fb, true, job -> {
			BackupMeta m = services.repo.get(id).orElseThrow(() -> new IOException(Lang.get("cytrabackups.error.no_backup", id)));
			if (m.pinned) throw new IOException(Lang.get("cytrabackups.error.pinned", id));
			List<Hash> remoteDead = exclusiveRemoteBlobs(List.of(id), job);
			services.repo.delete(id);
			if (config.offsite.enabled && config.offsite.mirrorDeletes) {
				offsite.enqueueDeleteBackup(id);
				dropBlobs(remoteDead);
				kickOffsite();
			}
			fb.success(remoteDead.isEmpty() ? "cytrabackups.delete.done" : "cytrabackups.delete.done_offsite", id);
		});
	}

	public void setPinned(int id, boolean pinned, Feedback fb) {
		async(() -> {
			try {
				BackupMeta m = services.repo.get(id).orElseThrow(() -> new IOException(Lang.get("cytrabackups.error.no_backup", id))).copy();
				m.pinned = pinned;
				services.repo.updateMeta(m);
				fb.success(pinned ? "cytrabackups.pin.done" : "cytrabackups.unpin.done", id);
			} catch (IOException e) {
				fb.error("cytrabackups.error.plain", e.getMessage());
			}
		});
	}

	public void setComment(int id, String comment, Feedback fb) {
		async(() -> {
			try {
				BackupMeta m = services.repo.get(id).orElseThrow(() -> new IOException(Lang.get("cytrabackups.error.no_backup", id))).copy();
				m.comment = comment;
				services.repo.updateMeta(m);
				fb.success(comment.isBlank() ? "cytrabackups.comment.cleared" : "cytrabackups.comment.done", id);
			} catch (IOException e) {
				fb.error("cytrabackups.error.plain", e.getMessage());
			}
		});
	}

	/** Deleting backups also deletes their data off-site, including data that is no longer stored here. */
	private boolean remoteCleanup() {
		return config.offsite.enabled && config.offsite.mirrorDeletes && offsite.hasRemoteOnlyData();
	}

	/**
	 * Blobs only the given backups use, computed before they are deleted: with backup data stored only off-site, a sweep
	 * of the local store afterwards cannot find them. Empty if that is not needed or not possible (then nothing is lost,
	 * the data just stays off-site).
	 */
	private List<Hash> exclusiveRemoteBlobs(Collection<Integer> ids, Job job) {
		if (ids.isEmpty() || !remoteCleanup()) return List.of();
		try {
			return GarbageCollector.exclusiveBlobs(services.repo, ids, job.progress(), job.cancel());
		} catch (IOException e) {
			CytraBackups.LOGGER.warn("CytraBackups: could not work out which off-site data backup(s) {} use alone; it stays off-site: {}", ids, e.getMessage());
			return List.of();
		}
	}

	/** Deletes blobs no backup uses any more, here and (queued) off-site. */
	private void dropBlobs(List<Hash> dead) throws IOException {
		if (dead.isEmpty()) return;
		for (Hash h : dead) services.blobs.delete(h);
		offsite.enqueueDeleteBlobs(dead);
	}

	PrunePolicy policy() {
		CytraConfig.PruneSettings p = config.prune;
		return new PrunePolicy(p.keepLast, p.keepHourly, p.keepDaily, p.keepWeekly, p.keepMonthly, p.maxAgeDays,
			(long) (p.maxTotalSizeGiB * 1024 * 1024 * 1024), p.alwaysKeepLatest, p.keepPreRestore, p.preRestoreMaxAgeDays);
	}

	public boolean prune(boolean dryRun, String who, Feedback fb) {
		return runJob(dryRun ? "cytrabackups.job.prune_dry" : "cytrabackups.job.prune", who, fb, dryRun, job -> doPrune(job, dryRun, fb));
	}

	private void doPrune(Job job, boolean dryRun, Feedback fb) throws Exception {
		Services s = services;
		job.progress().phase(Lang.get("cytrabackups.phase.plan_prune"));
		PrunePolicy policy = policy();
		Pruner pruner = new Pruner(policy, zone());
		Set<Integer> needed = new HashSet<>();
		PendingOperation queued = PendingOperationRunner.readPending(s.storage);
		if (queued != null && queued.type != PendingOperation.Type.ROLLBACK) needed.add(queued.backupId);
		List<Pruner.Decision> plan = pruner.plan(s.repo.list(), System.currentTimeMillis(), meta -> {
			Map<Hash, Long> blobs = new HashMap<>();
			s.repo.loadManifest(meta.id).forEachBlob(ref -> blobs.put(ref.hash(), ref.storedLength()));
			return blobs;
		}, needed);
		List<Pruner.Decision> deletions = plan.stream().filter(d -> !d.keep()).toList();
		if (dryRun) {
			if (deletions.isEmpty()) {
				fb.info("cytrabackups.prune.preview_none", plan.size());
				return;
			}
			fb.info("cytrabackups.prune.preview", deletions.size(), plan.size());
			for (Pruner.Decision d : deletions.subList(0, Math.min(25, deletions.size()))) {
				fb.send(Msg.detail("cytrabackups.prune.preview_line", d.backup().id, Formatting.dateTimeShort(d.backup().createdAt, zone()),
					d.backup().trigger.displayName(), String.join(", ", d.reasons())), false);
			}
			if (deletions.size() > 25) fb.send(Msg.detail("cytrabackups.more", deletions.size() - 25), false);
			return;
		}
		// Mark what the kept backups use before deleting: with data stored only off-site, what the deleted backups used
		// alone can only be found through their manifests.
		boolean collect = config.prune.garbageCollect && !deletions.isEmpty();
		List<Integer> deletedIds = deletions.stream().map(d -> d.backup().id).toList();
		long markedAt = System.currentTimeMillis();
		LongHashSet live = collect ? GarbageCollector.markLive(s.repo, deletedIds, job.progress(), job.cancel()) : null;
		List<Hash> remoteDead = collect && remoteCleanup() ? GarbageCollector.exclusiveBlobs(s.repo, deletedIds, live, job.cancel()) : List.of();
		job.progress().phase(Lang.get("cytrabackups.phase.prune_delete"));
		job.progress().addTotal(deletions.size(), 0);
		for (Pruner.Decision d : deletions) {
			job.cancel().check();
			s.repo.delete(d.backup().id);
			if (config.offsite.enabled && config.offsite.mirrorDeletes) offsite.enqueueDeleteBackup(d.backup().id);
			job.progress().addDone(1, 0);
			CytraBackups.LOGGER.info("CytraBackups: pruned backup #{} ({})", d.backup().id, String.join(", ", d.reasons()));
		}
		long freed = 0;
		if (collect) {
			if (config.offsite.enabled && config.offsite.mirrorDeletes) dropBlobs(remoteDead);
			GarbageCollector.Result gc = GarbageCollector.sweep(s.repo, live, markedAt, job.progress(), job.cancel());
			freed = gc.freedBytes();
			if (config.offsite.enabled && config.offsite.mirrorDeletes) offsite.enqueueDeleteBlobs(gc.deleted());
			if (remoteCleanup()) offsite.sweepOrphans(gc.live());
		}
		s.repo.state().lastPrune = System.currentTimeMillis();
		saveState();
		if (!deletions.isEmpty()) {
			fb.success("cytrabackups.prune.done", deletions.size(), Formatting.bytes(freed));
			notifier.prune(deletions.size(), freed);
			if (config.offsite.enabled) kickOffsite();
		} else {
			fb.info("cytrabackups.prune.none");
		}
	}

	public boolean garbageCollect(String who, Feedback fb) {
		return runJob("cytrabackups.job.gc", who, fb, false, job -> {
			GarbageCollector.Result gc = GarbageCollector.collect(services.repo, job.progress(), job.cancel());
			if (config.offsite.enabled && config.offsite.mirrorDeletes) {
				offsite.enqueueDeleteBlobs(gc.deleted());
				if (remoteCleanup()) offsite.sweepOrphans(gc.live());
				kickOffsite();
			}
			fb.success("cytrabackups.gc.done", Formatting.bytes(gc.freedBytes()), Formatting.bytes(gc.liveBytes()));
		});
	}

	public boolean verify(int id, String who, Feedback fb) {
		return runJob("cytrabackups.job.verify", who, fb, false, job -> {
			if (services.offsiteOnly) fb.info("cytrabackups.verify.offsite_note");
			Verifier.Result r = Verifier.verify(services.repo, id, services.workers, job.progress(), job.cancel());
			if (r.ok()) {
				fb.success("cytrabackups.verify.ok", id, Formatting.bytes(r.bytesChecked()));
			} else {
				fb.error("cytrabackups.verify.failed", id, r.problems().size());
				r.problems().stream().limit(10).forEach(p -> fb.send(Msg.detail("cytrabackups.verify.problem", p), true));
				notifier.backupFailure(Lang.get("cytrabackups.verify.discord", id), String.join("\n", r.problems().subList(0, Math.min(10, r.problems().size()))));
			}
		});
	}

	public boolean export(int id, String who, Feedback fb) {
		return runJob("cytrabackups.job.export", who, fb, false, job -> {
			Path out = ZipExporter.export(services.repo, id, services.storage.resolve("exports"), job.progress(), job.cancel());
			Path game = ModEnv.gameDir();
			String shown = out.startsWith(game) ? FileUtil.relative(game, out) : out.toString();
			fb.success("cytrabackups.export.done", id, shown, Formatting.bytes(Files.size(out)));
		});
	}

	public boolean importWorld(String path, String comment, String who, Feedback fb) {
		return runJob("cytrabackups.job.import", who, fb, false, job -> {
			Path game = ModEnv.gameDir();
			Path source = game.resolve(path).toAbsolutePath().normalize();
			if (!source.startsWith(game)) throw new IOException(Lang.get("cytrabackups.error.import_outside"));
			if (source.startsWith(worldDir)) throw new IOException(Lang.get("cytrabackups.error.import_live"));
			BackupService.Outcome out = WorldImporter.importWorld(services.backups, source, services.storage.resolve("tmp"), levelName, who, comment,
				services.filter(), job.progress(), job.cancel());
			fb.success("cytrabackups.import.done", path, out.meta().id, Formatting.bytes(out.meta().totalSize));
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
					fb.info("cytrabackups.diff.same", older, newer);
					return;
				}
				fb.info("cytrabackups.diff.title", older, newer, d.added().size(), d.removed().size(), d.changed().size(), d.changedChunks());
				d.added().stream().limit(8).forEach(p -> fb.send(Msg.detail("cytrabackups.diff.added", p), false));
				d.removed().stream().limit(8).forEach(p -> fb.send(Msg.detail("cytrabackups.diff.removed", p), false));
				d.changed().stream().limit(12).forEach(c -> fb.send(c.region() == null
					? Msg.detail("cytrabackups.diff.changed_file", c.path(), Formatting.bytes(c.oldSize()), Formatting.bytes(c.newSize()))
					: Msg.detail("cytrabackups.diff.changed_region", c.path(), c.region().modified(), c.region().added(), c.region().removed()), false));
				int more = Math.max(0, d.added().size() - 8) + Math.max(0, d.removed().size() - 8) + Math.max(0, d.changed().size() - 12);
				if (more > 0) fb.send(Msg.detail("cytrabackups.more", more), false);
			} catch (IOException e) {
				fb.error("cytrabackups.error.plain", e.getMessage());
			}
		});
	}

	public boolean cancel(CommandSourceStack src) {
		if (countdown != null) {
			String label = countdown.label();
			countdown = null;
			server.getPlayerList().broadcastSystemMessage(Msg.info("cytrabackups.countdown.cancelled", label, src.getTextName()), false);
			return true;
		}
		Job job = currentJob.get();
		if (job == null) return false;
		job.cancel().cancel("cancelled by " + src.getTextName());
		src.sendSuccess(() -> Msg.info("cytrabackups.job.cancelling", job.displayName()), true);
		return true;
	}

	public void reload(Feedback fb) {
		if (currentJob.get() != null) {
			fb.error("cytrabackups.error.reload_busy");
			return;
		}
		if (offsiteProgress.get() != null) {
			fb.error("cytrabackups.error.reload_upload");
			return;
		}
		async(() -> reloadNow(fb));
	}

	/** Writes settings edited in the GUI to the config file and reloads; {@code after} then runs on the server thread. */
	public void saveConfigAndReload(CytraConfig edited, Feedback fb, Runnable after) {
		if (currentJob.get() != null) {
			fb.error("cytrabackups.error.save_busy");
			server.execute(after);
			return;
		}
		if (offsiteProgress.get() != null) {
			fb.error("cytrabackups.error.save_upload");
			server.execute(after);
			return;
		}
		async(() -> {
			try {
				ConfigIO.save(ModEnv.configFile(), edited);
				reloadNow(fb);
			} catch (IOException e) {
				fb.error("cytrabackups.error.config_write", rootMessage(e));
			} finally {
				server.execute(after);
			}
		});
	}

	private void reloadNow(Feedback fb) {
		try {
			CytraConfig cfg = ConfigIO.load(ModEnv.configFile());
			Services old = services;
			Services fresh = Services.open(cfg, ModEnv.storageFor(cfg, levelName));
			OffsiteSync freshOffsite = fresh.offsite;
			config = cfg;
			services = fresh;
			offsite = freshOffsite;
			notifier.configure(cfg);
			old.close();
			fb.success(Lang.plural("cytrabackups.reload.done", fresh.repo.list().size()), fresh.repo.list().size());
		} catch (Exception e) {
			fb.error("cytrabackups.error.reload", rootMessage(e));
		}
	}

	OffsiteTarget createTarget(CytraConfig cfg) {
		return OffsiteTargets.create(cfg, services.storage);
	}

	public void kickOffsite() {
		CytraConfig cfg = config;
		if (!cfg.offsite.enabled) return;
		Services s = services;
		OffsiteSync sync = s.offsite;
		// off-site only: every backup goes off-site (also pre-restore ones), then its data is removed here
		if (!sync.hasWork() && !s.offsiteOnly) return;
		Progress p = new Progress();
		if (!offsiteProgress.compareAndSet(null, p)) return;
		offsiteExecutor.execute(() -> {
			try {
				if (s.offsiteOnly) sync.enqueueMissing();
				if (sync.hasWork()) {
					try (OffsiteTarget target = createTarget(cfg)) {
						OffsiteSync.Result r = sync.process(target, offsiteWorkers, OffsiteTargets.threads(cfg), p, CancelToken.NONE);
						CytraBackups.LOGGER.info("CytraBackups: off-site sync to {} done: {} backup(s), {} blobs ({}), {} deletions",
							target.describe(), r.backupsUploaded(), r.blobsUploaded(), Formatting.bytes(r.bytesUploaded()), r.deleted());
					}
				}
				if (s.offsiteOnly) {
					long freed = sync.evictUploaded(s.blobs);
					if (freed > 0) CytraBackups.LOGGER.info("CytraBackups: removed {} of uploaded backup data from this server (off-site only)", Formatting.bytes(freed));
				}
			} catch (Exception e) {
				CytraBackups.LOGGER.warn("CytraBackups: off-site sync failed (will retry later): {}", rootMessage(e));
				notifier.backupFailure(Lang.get("cytrabackups.offsite.copy"), rootMessage(e));
			} finally {
				offsiteProgress.compareAndSet(p, null);
			}
		});
	}

	public void offsiteSyncAll(Feedback fb) {
		if (!config.offsite.enabled) {
			fb.error("cytrabackups.error.offsite_off");
			return;
		}
		async(() -> {
			try (OffsiteTarget target = createTarget(config)) {
				offsite.bindTarget(target.id());
				int n = offsite.enqueueMissing();
				fb.success(Lang.plural("cytrabackups.offsite.queued", n), n, target.describe());
				kickOffsite();
			} catch (Exception e) {
				fb.error("cytrabackups.error.offsite", rootMessage(e));
			}
		});
	}

	/** Lists the backups stored off-site, with a [Fetch] button for the ones not present here. */
	public void offsiteList(Feedback fb) {
		if (!config.offsite.enabled) {
			fb.error("cytrabackups.error.offsite_off");
			return;
		}
		async(() -> {
			try (OffsiteTarget target = createTarget(config)) {
				List<OffsiteSync.RemoteBackup> remote = offsite.listRemote(target);
				if (remote.isEmpty()) {
					fb.info("cytrabackups.offsite.list_none", target.describe());
					return;
				}
				fb.info(Lang.plural("cytrabackups.offsite.list_title", remote.size()), remote.size(), target.describe());
				for (int i = remote.size() - 1; i >= 0; i--) {
					OffsiteSync.RemoteBackup r = remote.get(i);
					BackupMeta meta = r.meta();
					MutableComponent line = Msg.detail("cytrabackups.offsite.list_line", r.id(), Formatting.dateTimeShort(meta.createdAt, zone()),
						Formatting.bytes(meta.totalSize), meta.trigger.displayName() + (meta.comment.isBlank() ? "" : " " + meta.comment)).append(" ");
					line.append(services.repo.get(r.id()).isPresent() ? Msg.detail("cytrabackups.offsite.list_local")
						: Msg.button("cytrabackups.button.fetch", Msg.command("offsite", "fetch", r.id()), "cytrabackups.hover.fetch", r.id()));
					fb.send(line, false);
				}
			} catch (Exception e) {
				fb.error("cytrabackups.error.offsite", rootMessage(e));
			}
		});
	}

	/** Downloads a backup from the off-site copy into the local repository, so it can be restored like any other. */
	public boolean offsiteFetch(int id, String who, Feedback fb) {
		if (!config.offsite.enabled) {
			fb.error("cytrabackups.error.offsite_off");
			return false;
		}
		return runJob("cytrabackups.job.fetch", who, fb, false, job -> {
			CytraConfig cfg = config;
			try (OffsiteTarget target = createTarget(cfg)) {
				int blobs = offsite.fetch(target, id, offsiteWorkers, OffsiteTargets.threads(cfg), !services.offsiteOnly, job.progress(), job.cancel());
				BackupMeta m = services.repo.get(id).orElseThrow();
				fb.send(Msg.success("cytrabackups.offsite.fetched", id, target.describe(), blobs, Formatting.bytes(m.totalSize)).append(" ")
					.append(Msg.button("cytrabackups.button.info", Msg.command("info", id), "cytrabackups.hover.info", id)), false);
			}
		});
	}

	/** First line is the summary; further lines are details. */
	public List<Component> offsiteStatus() {
		List<Component> out = new ArrayList<>();
		if (!config.offsite.enabled) {
			out.add(Msg.tr("cytrabackups.offsite.off"));
			return out;
		}
		OffsiteSync.Queue q = offsite.queue();
		out.add(Msg.tr("cytrabackups.offsite.summary", config.offsite.type, q.uploaded.size(), q.uploads.size()));
		if (services.offsiteOnly) {
			out.add(Msg.detail("cytrabackups.offsite.only", config.offsite.localBufferGiB));
		} else if (OffsiteTargets.offsiteOnly(config)) {
			out.add(Msg.tr("cytrabackups.offsite.only_paused").withStyle(ChatFormatting.RED));
		}
		if (q.lastSuccess > 0) out.add(Msg.detail("cytrabackups.offsite.last", Msg.ago(q.lastSuccess, zone())));
		if (!q.lastError.isBlank()) out.add(Msg.tr("cytrabackups.offsite.last_error", q.lastError).withStyle(ChatFormatting.RED));
		Progress p = offsiteProgress.get();
		if (p != null) out.add(Msg.detail("cytrabackups.offsite.progress", p.phase(), Formatting.percent(p.fraction())));
		return out;
	}

	/** Server thread: snapshots in-memory state, then does the disk reads (free space, queue) off-thread. */
	public void sendStatus(CommandSourceStack src) {
		List<Component> lines = new ArrayList<>();
		long now = System.currentTimeMillis();
		lines.add(Msg.info("cytrabackups.status.title"));
		Job job = currentJob.get();
		if (countdown != null) lines.add(Msg.tr("cytrabackups.status.countdown", countdown.label(), countdown.secondsLeft()).withStyle(ChatFormatting.RED));
		if (job != null) {
			Progress p = job.progress();
			float f = p.fraction();
			long elapsed = now - job.startedAt();
			MutableComponent line = Msg.tr("cytrabackups.status.job", ProgressDisplay.line(job), job.requestedBy(), Msg.ago(job.startedAt(), zone()));
			if (f > 0.02f) line.append(" ").append(Msg.tr("cytrabackups.status.eta", Formatting.duration((long) (elapsed / f - elapsed))));
			lines.add(line);
		} else {
			lines.add(Msg.detail("cytrabackups.status.idle"));
		}
		RepositoryState st = services.repo.state();
		Component last = st.lastBackup > 0 ? Msg.ago(st.lastBackup, zone()) : Msg.tr("cytrabackups.status.never");
		lines.add(Msg.tr(Lang.plural("cytrabackups.status.backups", services.repo.list().size()), services.repo.list().size(), last, nextBackupDescription(now)));
		List<Component> offsiteLines = offsiteStatus();
		StreamingUpload stream = activeStream;
		if (stream != null) {
			lines.add(Msg.detail("cytrabackups.status.uploading", stream.uploadedBlobs(), Formatting.bytes(stream.uploadedBytes()), Formatting.bytes(stream.pendingBytes())));
		}
		async(() -> {
			long free = FileUtil.usableSpace(services.storage);
			Path game = ModEnv.gameDir();
			String where = services.storage.startsWith(game) ? FileUtil.relative(game, services.storage) : services.storage.toString();
			lines.add(Msg.tr("cytrabackups.status.storage", where, Formatting.bytes(free)));
			try {
				PendingOperation op = PendingOperationRunner.readPending(services.storage);
				if (op != null) lines.add(Msg.tr("cytrabackups.status.queued", op.describe()));
			} catch (IOException ignored) {
			}
			MutableComponent off = Msg.tr("cytrabackups.status.offsite", offsiteLines.getFirst());
			lines.add(off);
			for (Component c : offsiteLines.subList(1, offsiteLines.size())) lines.add(c);
			server.execute(() -> {
				for (Component line : lines) src.sendSuccess(() -> line, false);
			});
		});
	}

	private Component nextBackupDescription(long now) {
		CytraConfig.ScheduleSettings s = config.schedule;
		if (!s.enabled) return Msg.tr("cytrabackups.status.next_off");
		long next = nextScheduledTime(now);
		if (next == Long.MAX_VALUE) return Msg.tr("cytrabackups.status.next_none");
		return next <= now ? Msg.tr("cytrabackups.status.next_due") : Msg.tr("cytrabackups.status.next_in", Formatting.duration(next - now));
	}

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
			createBackup(Trigger.PLAYER_LEAVE, Lang.get("cytrabackups.backup.comment_leave"), "scheduler", Feedback.console());
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
		if (cfg.offsite.enabled && offsiteProgress.get() == null && tickCounter % (20 * 300) == 0) kickOffsite();
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
			player.sendSystemMessage(r.success ? Msg.success("cytrabackups.restore.last_ok", r.message) : Msg.error("cytrabackups.restore.last_failed", r.message));
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
				req.comment = Lang.get("cytrabackups.backup.comment_stop");
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
			notifier.backupFailure(Lang.get("cytrabackups.job.shutdown"), rootMessage(e));
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

	public String levelName() {
		return levelName;
	}

	public static Predicate<CommandSourceStack> isRunning() {
		return src -> instance != null;
	}
}
