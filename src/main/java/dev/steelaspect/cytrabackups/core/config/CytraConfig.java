package dev.steelaspect.cytrabackups.core.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** All settings, serialized to config/cytrabackups.json (JSON with // comments). */
public final class CytraConfig {
	@Comment({"Where backups are stored. Relative paths are resolved against the server folder (the game folder in singleplayer,",
		"where a per-world subfolder is added). Keep it outside the world folder."})
	public String storagePath = "backups/cytrabackups";

	@Comment("Worker threads for hashing/compression/IO. 0 = auto (half the CPU cores, max 4). Threads run at low priority.")
	public int workerThreads = 0;

	@Comment("Limit how fast backups read the world, in MiB/s, to protect server disk I/O. 0 = unlimited.")
	public int maxReadMiBPerSecond = 0;

	public CompressionSettings compression = new CompressionSettings();

	@Comment({"Store .mca region files chunk-by-chunk so a region with one changed chunk only stores that chunk again.",
		"false = store region files whole (still deduplicated when completely unchanged)."})
	public boolean chunkDedup = true;

	@Comment("Reuse the previous backup's data for files whose size and modification time did not change (much faster).")
	public boolean trustModificationTime = true;

	@Comment("Glob patterns (relative to the world folder) to include. Empty = everything. '**' spans folders.")
	public List<String> include = new ArrayList<>();

	@Comment("Glob patterns to exclude. Patterns without '/' match a file name at any depth. Excludes win over includes.")
	public List<String> exclude = new ArrayList<>(List.of("session.lock", "logs/**", "dynmap/**", "bluemap/**", "squaremap/**", "**/*.tmp", "**/*.lock"));

	@Comment("Abort a backup cleanly when less than this much free space (MiB) would remain on the storage disk.")
	public long minFreeSpaceMiB = 1024;

	@Comment("Run 'save-all flush' before each backup (recommended; briefly blocks the server thread while chunks are written).")
	public boolean flushOnSave = true;

	@Comment("Time zone for daily times and daily/weekly/monthly pruning buckets, e.g. \"Europe/Berlin\". \"system\" = server default.")
	public String timeZone = "system";

	public ScheduleSettings schedule = new ScheduleSettings();
	public PruneSettings prune = new PruneSettings();
	public RestoreSettings restore = new RestoreSettings();
	public ProgressSettings progress = new ProgressSettings();
	public DiscordSettings discord = new DiscordSettings();
	public OffsiteSettings offsite = new OffsiteSettings();
	public PermissionSettings permissions = new PermissionSettings();

	public static final class CompressionSettings {
		@Comment("zstd (pure Java, recommended), deflate, or none. Already-compressed data is always stored as-is.")
		@Choices({"zstd", "deflate", "none"})
		public String algorithm = "zstd";
		@Comment("zstd: 1-19 (3 = fast default); deflate: 1-9.")
		public int level = 3;
		@Comment("Keep the compressed form only if it saves at least this many percent.")
		public int minSavingsPercent = 3;
	}

	public static final class ScheduleSettings {
		@Comment("Enable automatic backups.")
		public boolean enabled = true;
		@Comment("Minutes between automatic backups. 0 = disabled (use timesOfDay only).")
		public int intervalMinutes = 30;
		@Comment("Additional fixed daily times in 24h \"HH:mm\" format, e.g. [\"04:00\", \"16:00\"].")
		public List<String> timesOfDay = new ArrayList<>();
		@Comment("Minimum minutes after server start before the first automatic backup.")
		public int startupDelayMinutes = 2;
		@Comment("Back up while the server is stopping (after the final save).")
		public boolean backupOnStop = false;
		@Comment("Back up when the last player leaves.")
		public boolean backupWhenLastPlayerLeaves = false;
		@Comment("Skip automatic backups unless at least one player was online since the previous backup.")
		public boolean onlyIfPlayersWereOnline = false;
		@Comment("Skip automatic backups when nothing changed since the previous one.")
		public boolean skipIfUnchanged = true;
		@Comment("Files ignored by the 'nothing changed' check (they are rewritten on every save).")
		public List<String> unchangedIgnore = new ArrayList<>(List.of("level.dat", "level.dat_old"));
	}

	public static final class PruneSettings {
		@Comment("Run pruning automatically.")
		public boolean enabled = true;
		@Comment("Minutes between automatic prune runs (also runs after each automatic backup when due).")
		public int intervalMinutes = 60;
		@Comment({"Keep rules are combined: a backup survives if ANY rule keeps it. Set all to 0 to keep everything",
			"(then only maxAgeDays / maxTotalSizeGiB delete). Pinned backups are never pruned."})
		public int keepLast = 12;
		@Comment("Keep the newest backup of each of the last N hours.")
		public int keepHourly = 24;
		@Comment("Keep the newest backup of each of the last N days.")
		public int keepDaily = 7;
		@Comment("Keep the newest backup of each of the last N weeks.")
		public int keepWeekly = 4;
		@Comment("Keep the newest backup of each of the last N months.")
		public int keepMonthly = 6;
		@Comment("Delete unpinned backups older than this many days, even if a keep rule matches. 0 = off.")
		public double maxAgeDays = 0;
		@Comment("Delete the oldest unpinned backups until the store is below this size (GiB, deduplicated). 0 = off.")
		public double maxTotalSizeGiB = 0;
		@Comment("Never prune the newest backup.")
		public boolean alwaysKeepLatest = true;
		@Comment("Keep automatic pre-restore backups for preRestoreMaxAgeDays regardless of other keep rules (0 = forever).")
		public boolean keepPreRestore = true;
		@Comment("Days to keep automatic pre-restore backups when keepPreRestore is on (0 = forever).")
		public double preRestoreMaxAgeDays = 14;
		@Comment("Delete blobs no longer referenced by any backup after pruning.")
		public boolean garbageCollect = true;
	}

	public static final class RestoreSettings {
		@Comment("Seconds a restore confirmation link stays valid.")
		public int confirmTimeoutSeconds = 30;
		@Comment("Broadcast countdown before players are kicked and the server stops for a restore.")
		public int countdownSeconds = 10;
		@Comment({"When to apply a restore that needs the world closed:",
			"  \"startup\"  = stop the server; the restore is applied on the next start before the world loads (hosts that auto-restart).",
			"  \"shutdown\" = apply right after the server has saved and stopped, then stay stopped (hosts that do not auto-restart)."})
		@Choices({"startup", "shutdown"})
		public String applyMode = "startup";
		@Comment("Message shown to kicked players.")
		public String kickMessage = "Restoring a world backup. The server will be back shortly.";
		@Comment("Try to restore chunk selections live (unload, write, reload) when no player is near. Otherwise queue for restart.")
		public boolean livePartialRestore = true;
		@Comment("Seconds to wait for the selected chunks to unload before giving up on a live restore.")
		public int liveUnloadTimeoutSeconds = 15;
		@Comment("How many previous restores keep their recycle bin (for /backup rollback).")
		public int recycleBinKeep = 3;
		@Comment("Maximum chunks in one partial restore.")
		public int maxChunks = 65536;
	}

	public static final class ProgressSettings {
		@Comment("Show a boss bar while a job runs.")
		public boolean bossBar = true;
		@Comment("Show progress in the action bar.")
		public boolean actionBar = false;
		@Comment("Who sees progress: \"permitted\" (cytrabackups.progress), \"everyone\" or \"nobody\".")
		@Choices({"permitted", "everyone", "nobody"})
		public String showTo = "permitted";
		@Comment("Broadcast a chat message when an automatic backup finishes.")
		public boolean announceScheduled = false;
	}

	public static final class DiscordSettings {
		@Comment("Post notifications to a Discord webhook.")
		public boolean enabled = false;
		@Comment("Discord channel webhook URL (Channel settings > Integrations > Webhooks).")
		@Secret
		public String webhookUrl = "";
		@Comment("Name the webhook posts as.")
		public String username = "CytraBackups";
		@Comment("Post when a backup finishes.")
		public boolean onBackupSuccess = true;
		@Comment("Post when a backup fails.")
		public boolean onBackupFailure = true;
		@Comment("Post when a restore or rollback is scheduled or applied.")
		public boolean onRestore = true;
		@Comment("Post when free disk space drops below lowDiskWarningMiB.")
		public boolean onLowDisk = true;
		@Comment("Post when pruning deletes backups.")
		public boolean onPrune = false;
		@Comment("Warn when free space on the storage disk drops below this many MiB.")
		public long lowDiskWarningMiB = 4096;
		@Comment("Optional text prepended to failure messages, e.g. \"<@&123456789>\" to ping a role.")
		public String failureMention = "";
	}

	public static final class OffsiteSettings {
		@Comment("Copy backups to off-site storage after they are created.")
		public boolean enabled = false;
		@Comment("\"s3\" (any S3-compatible service), \"sftp\" or \"webdav\".")
		@Choices({"s3", "sftp", "webdav"})
		public String type = "s3";
		@Comment("Also delete remote copies when backups are pruned/deleted locally.")
		public boolean mirrorDeletes = true;
		public S3 s3 = new S3();
		public Sftp sftp = new Sftp();
		public WebDav webdav = new WebDav();

		public static final class S3 {
			@Comment("e.g. https://s3.eu-central-1.amazonaws.com, https://<account>.r2.cloudflarestorage.com, https://s3.us-west-004.backblazeb2.com")
			public String endpoint = "";
			@Comment("Region name used for request signing, e.g. us-east-1 (\"auto\" for Cloudflare R2).")
			public String region = "us-east-1";
			@Comment("Bucket name.")
			public String bucket = "";
			@Comment("Key prefix (folder) inside the bucket.")
			public String prefix = "cytrabackups/";
			@Comment("Access key ID.")
			public String accessKey = "";
			@Comment("Secret access key.")
			@Secret
			public String secretKey = "";
			@Comment("Path-style URLs (endpoint/bucket/key). Needed for MinIO and most self-hosted services.")
			public boolean pathStyle = true;
		}

		public static final class Sftp {
			@Comment("SFTP server host name or IP.")
			public String host = "";
			@Comment("SSH port.")
			public int port = 22;
			@Comment("Login user name.")
			public String username = "";
			@Comment("Login password (not needed with a private key).")
			@Secret
			public String password = "";
			@Comment("Path to a private key (relative to the server folder). Used instead of the password when set.")
			public String privateKey = "";
			@Comment("Passphrase of the private key, if it has one.")
			@Secret
			public String privateKeyPassphrase = "";
			@Comment("Remote folder for the backups (created if missing).")
			public String remoteDir = "cytrabackups";
			@Comment("\"tofu\" = trust the host key on first connect and pin it (stored next to the backups), \"yes\" = known_hosts only, \"no\" = never check.")
			@Choices({"tofu", "yes", "no"})
			public String hostKeyChecking = "tofu";
		}

		public static final class WebDav {
			@Comment("Base URL of the target folder, e.g. https://cloud.example.com/remote.php/dav/files/me/backups/")
			public String url = "";
			@Comment("WebDAV user name.")
			public String username = "";
			@Comment("WebDAV password or app password.")
			@Secret
			public String password = "";
		}
	}

	public static final class PermissionSettings {
		@Comment({"Op level required for each permission when no permissions mod (e.g. LuckPerms) decides.",
			"Permission nodes are cytrabackups.<name>."})
		public Map<String, Integer> defaultLevels = defaults();

		private static Map<String, Integer> defaults() {
			Map<String, Integer> m = new LinkedHashMap<>();
			m.put("list", 2);
			m.put("create", 2);
			m.put("comment", 2);
			m.put("verify", 2);
			m.put("progress", 2);
			m.put("pin", 3);
			m.put("cancel", 3);
			m.put("prune", 3);
			m.put("export", 3);
			m.put("delete", 3);
			m.put("restore", 4);
			m.put("admin", 4);
			return m;
		}

		public int level(String node) {
			Integer v = defaultLevels.get(node);
			if (v != null) return v;
			return defaults().getOrDefault(node, 4);
		}
	}
}
