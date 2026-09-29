package dev.steelaspect.cytrabackups.core;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.UUID;

public final class FileUtil {
	private FileUtil() {
	}

	/** Moves a file, preferring an atomic rename; falls back to copy+delete across file systems. */
	public static void move(Path from, Path to, boolean replace) throws IOException {
		Files.createDirectories(to.toAbsolutePath().getParent());
		try {
			if (replace) {
				Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} else {
				Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
			}
		} catch (AtomicMoveNotSupportedException e) {
			if (replace) {
				Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
			} else {
				Files.move(from, to);
			}
		}
	}

	/** Writes bytes to a temp sibling, fsyncs, then atomically renames over the target. */
	public static void writeAtomic(Path target, byte[] data) throws IOException {
		Files.createDirectories(target.toAbsolutePath().getParent());
		Path tmp = target.resolveSibling(target.getFileName() + "." + UUID.randomUUID() + ".tmp");
		try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
			java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(data);
			while (buf.hasRemaining()) ch.write(buf);
			ch.force(true);
		} catch (IOException e) {
			Files.deleteIfExists(tmp);
			throw e;
		}
		move(tmp, target, true);
	}

	public static void writeAtomic(Path target, String text) throws IOException {
		writeAtomic(target, text.getBytes(StandardCharsets.UTF_8));
	}

	public interface StreamWriter {
		void write(OutputStream out) throws IOException;
	}

	public static void writeAtomic(Path target, StreamWriter writer) throws IOException {
		Files.createDirectories(target.toAbsolutePath().getParent());
		Path tmp = target.resolveSibling(target.getFileName() + "." + UUID.randomUUID() + ".tmp");
		try {
			try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
				 OutputStream out = java.nio.channels.Channels.newOutputStream(ch)) {
				OutputStream buffered = new java.io.BufferedOutputStream(out, 1 << 16);
				writer.write(buffered);
				buffered.flush();
				ch.force(true);
			}
			move(tmp, target, true);
		} catch (IOException | RuntimeException e) {
			Files.deleteIfExists(tmp);
			throw e;
		}
	}

	public static void deleteRecursively(Path root) throws IOException {
		if (!Files.exists(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return;
		Files.walkFileTree(root, new SimpleFileVisitor<>() {
			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
				Files.deleteIfExists(file);
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
				if (exc instanceof NoSuchFileException) return FileVisitResult.CONTINUE;
				throw exc;
			}

			@Override
			public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
				if (exc != null && !(exc instanceof NoSuchFileException)) throw exc;
				try {
					Files.deleteIfExists(dir);
				} catch (DirectoryNotEmptyException ignored) {
					// something was written concurrently; leave it
				}
				return FileVisitResult.CONTINUE;
			}
		});
	}

	/** Removes empty parent directories up to (but excluding) {@code stop}. */
	public static void pruneEmptyParents(Path file, Path stop) {
		Path dir = file.getParent();
		while (dir != null && !dir.equals(stop) && dir.startsWith(stop)) {
			try {
				Files.delete(dir);
			} catch (IOException e) {
				return;
			}
			dir = dir.getParent();
		}
	}

	/** Converts a path relative to {@code root} into a portable forward-slash string. */
	public static String relative(Path root, Path file) {
		Path rel = root.relativize(file);
		StringBuilder sb = new StringBuilder();
		for (Path p : rel) {
			if (!sb.isEmpty()) sb.append('/');
			sb.append(p.toString());
		}
		return sb.toString();
	}

	/** Resolves a portable relative path under root, rejecting traversal outside of it. */
	public static Path resolveSafe(Path root, String relative) throws IOException {
		Path resolved = root;
		for (String part : relative.split("/")) {
			if (part.isEmpty() || part.equals(".")) continue;
			if (part.equals("..") || part.contains("\\") || part.contains(":")) throw new IOException("Unsafe path in backup: " + relative);
			resolved = resolved.resolve(part);
		}
		Path norm = resolved.normalize();
		if (!norm.startsWith(root.normalize())) throw new IOException("Path escapes root: " + relative);
		return norm;
	}

	public static long usableSpace(Path path) {
		try {
			Path p = path.toAbsolutePath();
			while (p != null && !Files.exists(p)) p = p.getParent();
			if (p == null) return Long.MAX_VALUE;
			return Files.getFileStore(p).getUsableSpace();
		} catch (IOException e) {
			return Long.MAX_VALUE;
		}
	}
}
