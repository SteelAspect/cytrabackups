package dev.steelaspect.cytrabackups.core.transfer;

import dev.steelaspect.cytrabackups.core.Lang;
import dev.steelaspect.cytrabackups.core.CancelToken;
import dev.steelaspect.cytrabackups.core.FileUtil;
import dev.steelaspect.cytrabackups.core.Progress;
import dev.steelaspect.cytrabackups.core.backup.BackupMeta;
import dev.steelaspect.cytrabackups.core.backup.BackupRepository;
import dev.steelaspect.cytrabackups.core.manifest.ChunkRef;
import dev.steelaspect.cytrabackups.core.manifest.FileEntry;
import dev.steelaspect.cytrabackups.core.manifest.Manifest;
import dev.steelaspect.cytrabackups.core.manifest.ManifestEntry;
import dev.steelaspect.cytrabackups.core.manifest.RegionEntry;
import dev.steelaspect.cytrabackups.core.region.RegionFiles;
import dev.steelaspect.cytrabackups.core.store.BlobRef;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Rebuilds a backup into a standalone world .zip (openable by any Minecraft install). */
public final class ZipExporter {
	private ZipExporter() {
	}

	public static Path export(BackupRepository repo, int id, Path outDir, Progress progress, CancelToken cancel) throws IOException {
		BackupMeta meta = repo.get(id).orElseThrow(() -> new IOException("No backup #" + id));
		Manifest m = repo.loadManifest(id);
		String root = sanitize(meta.levelName.isBlank() ? "world" : meta.levelName);
		String name = root + "-backup-" + id + "-" + DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now()) + ".zip";
		Files.createDirectories(outDir);
		Path target = outDir.resolve(name);
		Path partial = outDir.resolve(name + ".part");
		progress.phase(Lang.get("cytrabackups.phase.export", id));
		progress.addTotal(m.size(), m.totalSize());
		try (ZipOutputStream zip = new ZipOutputStream(new java.io.BufferedOutputStream(Files.newOutputStream(partial), 1 << 16))) {
			for (ManifestEntry e : m.entries()) {
				cancel.check();
				ZipEntry ze = new ZipEntry(root + "/" + e.path());
				ze.setLastModifiedTime(FileTime.fromMillis(e.mtime()));
				if (e instanceof RegionEntry r) {
					zip.setLevel(Deflater.NO_COMPRESSION); // chunk payloads are already compressed
					zip.putNextEntry(ze);
					writeRegion(repo, r, zip);
				} else {
					zip.setLevel(Deflater.DEFAULT_COMPRESSION);
					zip.putNextEntry(ze);
					for (BlobRef piece : ((FileEntry) e).pieces()) zip.write(repo.blobs().read(piece.hash()));
				}
				zip.closeEntry();
				progress.addDone(1, e.size());
			}
		} catch (IOException | RuntimeException ex) {
			Files.deleteIfExists(partial);
			throw ex;
		}
		FileUtil.move(partial, target, true);
		return target;
	}

	private static void writeRegion(BackupRepository repo, RegionEntry r, OutputStream out) throws IOException {
		List<RegionFiles.SlotInfo> slots = new ArrayList<>();
		Map<Integer, BlobRef> refs = new HashMap<>();
		for (ChunkRef c : r.chunks()) {
			slots.add(new RegionFiles.SlotInfo(c.index(), c.timestamp(), c.blob().rawLength()));
			refs.put(c.index(), c.blob());
		}
		OutputStream shield = new java.io.FilterOutputStream(out) {
			@Override
			public void write(byte[] b, int off, int len) throws IOException {
				out.write(b, off, len);
			}

			@Override
			public void close() {
				// the zip entry is closed by the caller
			}
		};
		RegionFiles.write(shield, slots, idx -> repo.blobs().read(refs.get(idx).hash()));
	}

	static String sanitize(String s) {
		return s.replaceAll("[^A-Za-z0-9._-]", "_");
	}
}
