package dev.steelaspect.cytrabackups.mc;

import dev.steelaspect.cytrabackups.core.config.CytraConfig;
import dev.steelaspect.cytrabackups.core.offsite.OffsiteTarget;
import dev.steelaspect.cytrabackups.core.offsite.S3Target;
import dev.steelaspect.cytrabackups.core.offsite.SftpTarget;
import dev.steelaspect.cytrabackups.core.offsite.WebDavTarget;
import java.nio.file.Path;

/** Builds the off-site destination client from the settings. Throws IllegalArgumentException if it is not configured. */
public final class OffsiteTargets {
	private OffsiteTargets() {
	}

	public static OffsiteTarget create(CytraConfig cfg, Path storage) {
		CytraConfig.OffsiteSettings o = cfg.offsite;
		return switch (o.type) {
			case "sftp" -> new SftpTarget(o.sftp.host, o.sftp.port, o.sftp.username, o.sftp.password,
				o.sftp.privateKey.isBlank() ? null : ModEnv.gameDir().resolve(o.sftp.privateKey), o.sftp.privateKeyPassphrase,
				o.sftp.remoteDir, o.sftp.hostKeyChecking, storage.resolve("offsite").resolve("known_hosts"));
			case "webdav" -> new WebDavTarget(o.webdav.url, o.webdav.username, o.webdav.password);
			default -> new S3Target(o.s3.endpoint, o.s3.region, o.s3.bucket, o.s3.prefix, o.s3.accessKey, o.s3.secretKey, o.s3.pathStyle);
		};
	}

	/** Uploads/downloads at a time: SFTP has a single channel. */
	public static int threads(CytraConfig cfg) {
		return cfg.offsite.type.equals("sftp") ? 1 : Math.max(1, cfg.offsite.uploadThreads);
	}

	/** Off-site only mode: backup data is uploaded and then deleted here. */
	public static boolean offsiteOnly(CytraConfig cfg) {
		return cfg.offsite.enabled && !cfg.offsite.keepLocalCopy;
	}
}
