package dev.steelaspect.cytrabackups.core.transfer;

import dev.steelaspect.cytrabackups.core.CancelToken;
import dev.steelaspect.cytrabackups.core.FileUtil;
import dev.steelaspect.cytrabackups.core.Progress;
import dev.steelaspect.cytrabackups.core.backup.BackupService;
import dev.steelaspect.cytrabackups.core.backup.PathFilter;
import dev.steelaspect.cytrabackups.core.backup.Trigger;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Comparator;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Imports an existing world folder or world .zip as a new backup. */
public final class WorldImporter {
	private WorldImporter() {
	}

	public static BackupService.Outcome importWorld(BackupService service, Path source, Path tmpDir, String levelName, String creator,
													String comment, PathFilter filter, Progress progress, CancelToken cancel) throws IOException {
		if (!Files.exists(source)) throw new IOException("Not found: " + source);
		Path extracted = null;
		try {
			Path folder;
			if (Files.isDirectory(source)) {
				folder = source;
			} else if (source.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".zip")) {
				extracted = tmpDir.resolve("import-" + UUID.randomUUID());
				progress.phase("Extracting " + source.getFileName());
				unzip(source, extracted, cancel);
				folder = extracted;
			} else {
				throw new IOException("Import source must be a world folder or a .zip file");
			}
			Path worldRoot = findWorldRoot(folder).orElseThrow(() -> new IOException("No level.dat found in " + source.getFileName()));
			BackupService.Request req = new BackupService.Request();
			req.worldDir = worldRoot;
			req.levelName = levelName;
			req.trigger = Trigger.IMPORT;
			req.creator = creator;
			req.comment = comment == null || comment.isBlank() ? "Imported from " + source.getFileName() : comment;
			req.filter = filter;
			req.noReuse = true;
			req.progress = progress;
			req.cancel = cancel;
			return service.create(req);
		} finally {
			if (extracted != null) FileUtil.deleteRecursively(extracted);
		}
	}

	/** The shallowest directory (depth <= 3) containing level.dat. */
	static Optional<Path> findWorldRoot(Path folder) throws IOException {
		if (Files.isRegularFile(folder.resolve("level.dat"))) return Optional.of(folder);
		try (Stream<Path> s = Files.find(folder, 3, (p, a) -> a.isRegularFile() && p.getFileName().toString().equals("level.dat"))) {
			return s.map(Path::getParent).min(Comparator.comparingInt(Path::getNameCount));
		}
	}

	static void unzip(Path zipFile, Path target, CancelToken cancel) throws IOException {
		Files.createDirectories(target);
		try (ZipInputStream zin = new ZipInputStream(new java.io.BufferedInputStream(Files.newInputStream(zipFile), 1 << 16))) {
			ZipEntry e;
			while ((e = zin.getNextEntry()) != null) {
				cancel.check();
				String name = e.getName().replace('\\', '/');
				Path out = FileUtil.resolveSafe(target, name); // rejects zip-slip entries
				if (e.isDirectory()) {
					Files.createDirectories(out);
					continue;
				}
				Files.createDirectories(out.getParent());
				try (var os = Files.newOutputStream(out)) {
					copy(zin, os);
				}
				if (e.getLastModifiedTime() != null) Files.setLastModifiedTime(out, FileTime.fromMillis(e.getLastModifiedTime().toMillis()));
			}
		}
	}

	private static void copy(InputStream in, java.io.OutputStream out) throws IOException {
		byte[] buf = new byte[1 << 16];
		int n;
		while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
	}
}
