package dev.steelaspect.cytrabackups.core.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** All settings, stored in config/cytrabackups.json. The comments written above each setting come from the lang file. */
public final class CytraConfig {
	public String storagePath = "backups/cytrabackups";
	public int workerThreads = 0;
	public int maxReadMiBPerSecond = 0;
	public CompressionSettings compression = new CompressionSettings();
	public boolean chunkDedup = true;
	public boolean trustModificationTime = true;
	public List<String> include = new ArrayList<>();
	public List<String> exclude = new ArrayList<>(List.of("session.lock", "logs/**", "dynmap/**", "bluemap/**", "squaremap/**", "**/*.tmp", "**/*.lock"));
	public long minFreeSpaceMiB = 1024;
	public boolean flushOnSave = true;
	public String timeZone = "system";
	public ScheduleSettings schedule = new ScheduleSettings();
	public PruneSettings prune = new PruneSettings();
	public RestoreSettings restore = new RestoreSettings();
	public ProgressSettings progress = new ProgressSettings();
	public DiscordSettings discord = new DiscordSettings();
	public OffsiteSettings offsite = new OffsiteSettings();
	public PermissionSettings permissions = new PermissionSettings();

	public static final class CompressionSettings {
		@Choices({"zstd", "deflate", "none"})
		public String algorithm = "zstd";
		public int level = 3;
		public int minSavingsPercent = 3;
		public boolean recompressChunks = false;
		public int chunkLevel = 15;
	}

	public static final class ScheduleSettings {
		public boolean enabled = false;
		public int intervalMinutes = 30;
		public List<String> timesOfDay = new ArrayList<>();
		public int startupDelayMinutes = 2;
		public boolean backupOnStop = false;
		public boolean backupWhenLastPlayerLeaves = false;
		public boolean onlyIfPlayersWereOnline = false;
		public boolean skipIfUnchanged = true;
		public List<String> unchangedIgnore = new ArrayList<>(List.of("level.dat", "level.dat_old"));
	}

	public static final class PruneSettings {
		public boolean enabled = true;
		public int intervalMinutes = 60;
		public int keepLast = 12;
		public int keepHourly = 24;
		public int keepDaily = 7;
		public int keepWeekly = 4;
		public int keepMonthly = 6;
		public double maxAgeDays = 0;
		public double maxTotalSizeGiB = 0;
		public boolean alwaysKeepLatest = true;
		public boolean keepPreRestore = true;
		public double preRestoreMaxAgeDays = 14;
		public boolean garbageCollect = true;
	}

	public static final class RestoreSettings {
		public int confirmTimeoutSeconds = 30;
		public int countdownSeconds = 10;
		@Choices({"startup", "shutdown"})
		public String applyMode = "startup";
		public String kickMessage = "Restoring a world backup. The server will be back shortly.";
		public boolean livePartialRestore = true;
		public int liveUnloadTimeoutSeconds = 15;
		public int recycleBinKeep = 3;
		public int maxChunks = 65536;
	}

	public static final class ProgressSettings {
		public boolean bossBar = true;
		public boolean actionBar = false;
		@Choices({"permitted", "everyone", "nobody"})
		public String showTo = "permitted";
		public boolean announceScheduled = false;
	}

	public static final class DiscordSettings {
		public boolean enabled = false;
		@Secret
		public String webhookUrl = "";
		public String username = "CytraBackups";
		public boolean onBackupSuccess = true;
		public boolean onBackupFailure = true;
		public boolean onRestore = true;
		public boolean onLowDisk = true;
		public boolean onPrune = false;
		public long lowDiskWarningMiB = 4096;
		public String failureMention = "";
	}

	public static final class OffsiteSettings {
		public boolean enabled = false;
		@Choices({"s3", "sftp", "webdav"})
		public String type = "s3";
		public boolean mirrorDeletes = true;
		public S3 s3 = new S3();
		public Sftp sftp = new Sftp();
		public WebDav webdav = new WebDav();

		public static final class S3 {
			public String endpoint = "";
			public String region = "us-east-1";
			public String bucket = "";
			public String prefix = "cytrabackups/";
			public String accessKey = "";
			@Secret
			public String secretKey = "";
			public boolean pathStyle = true;
		}

		public static final class Sftp {
			public String host = "";
			public int port = 22;
			public String username = "";
			@Secret
			public String password = "";
			public String privateKey = "";
			@Secret
			public String privateKeyPassphrase = "";
			public String remoteDir = "cytrabackups";
			@Choices({"tofu", "yes", "no"})
			public String hostKeyChecking = "tofu";
		}

		public static final class WebDav {
			public String url = "";
			public String username = "";
			@Secret
			public String password = "";
		}
	}

	public static final class PermissionSettings {
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
