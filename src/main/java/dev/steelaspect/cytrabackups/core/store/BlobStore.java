package dev.steelaspect.cytrabackups.core.store;

import dev.steelaspect.cytrabackups.core.FileUtil;
import dev.steelaspect.cytrabackups.core.Hash;
import dev.steelaspect.cytrabackups.core.compress.Codec;
import dev.steelaspect.cytrabackups.core.compress.Compression;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * Content-addressed store. Every unique piece of content is stored exactly once at
 * {@code blobs/ab/cd/<sha256>}, keyed by the SHA-256 of its <em>uncompressed</em> bytes.
 *
 * <p>Blob file layout: {@code "CYB1"} magic, 1 byte codec id, 4 byte raw length, payload.
 */
public final class BlobStore {
	private static final byte[] MAGIC = {'C', 'Y', 'B', '1'};
	public static final int HEADER_SIZE = 9;

	private final Path root;
	private final Path tmp;
	private final Compression compression;
	private final boolean fsync;

	public BlobStore(Path root, Path tmp, Compression compression, boolean fsync) throws IOException {
		this.root = root;
		this.tmp = tmp;
		this.compression = compression;
		this.fsync = fsync;
		Files.createDirectories(root);
		Files.createDirectories(tmp);
	}

	public Path root() {
		return root;
	}

	public Path pathFor(Hash hash) {
		String hex = hash.hex();
		return root.resolve(hex.substring(0, 2)).resolve(hex.substring(2, 4)).resolve(hex);
	}

	/** Result of a put: the reference plus whether this call wrote a new blob. */
	public record PutResult(BlobRef ref, boolean isNew) {
	}

	public PutResult put(byte[] data, int off, int len, boolean knownCompressed) throws IOException {
		return put(Hash.compute(data, off, len), data, off, len, knownCompressed);
	}

	public PutResult put(Hash hash, byte[] data, int off, int len, boolean knownCompressed) throws IOException {
		Path target = pathFor(hash);
		long existing = storedSize(target);
		if (existing >= 0) return new PutResult(new BlobRef(hash, len, existing), false);
		return put(hash, data, off, len, compression.encode(data, off, len, knownCompressed));
	}

	public Compression compression() {
		return compression;
	}

	/** Stores chunk NBT with the chunk encoder (dictionary zstd when available). */
	public PutResult putChunk(byte[] nbt, Compression.ChunkEncoder encoder) throws IOException {
		Hash hash = Hash.compute(nbt);
		Path target = pathFor(hash);
		long existing = storedSize(target);
		if (existing >= 0) return new PutResult(new BlobRef(hash, nbt.length, existing), false);
		return put(hash, nbt, 0, nbt.length, encoder.encode(nbt, 0, nbt.length));
	}

	private PutResult put(Hash hash, byte[] data, int off, int len, Compression.Encoded enc) throws IOException {
		Path target = pathFor(hash);
		long existing = storedSize(target);
		if (existing >= 0) return new PutResult(new BlobRef(hash, len, existing), false);

		Files.createDirectories(target.getParent());
		Path temp = tmp.resolve(UUID.randomUUID() + ".blob");
		try (FileChannel ch = FileChannel.open(temp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
			ByteBuffer header = ByteBuffer.allocate(HEADER_SIZE);
			header.put(MAGIC).put((byte) enc.codec().id).putInt(len).flip();
			while (header.hasRemaining()) ch.write(header);
			int dataOff = enc.data() == data ? off : 0;
			ByteBuffer body = ByteBuffer.wrap(enc.data(), dataOff, enc.length());
			while (body.hasRemaining()) ch.write(body);
			if (fsync) ch.force(true);
		} catch (IOException e) {
			Files.deleteIfExists(temp);
			throw e;
		}
		try {
			FileUtil.move(temp, target, false);
		} catch (FileAlreadyExistsException e) {
			// Another worker stored identical content concurrently.
			Files.deleteIfExists(temp);
			return new PutResult(new BlobRef(hash, len, Math.max(0, storedSize(target))), false);
		}
		return new PutResult(new BlobRef(hash, len, HEADER_SIZE + (long) enc.length()), true);
	}

	public boolean exists(Hash hash) {
		return Files.isRegularFile(pathFor(hash));
	}

	/** Stored size of the blob or -1 if absent. */
	public long storedSize(Hash hash) {
		return storedSize(pathFor(hash));
	}

	private static long storedSize(Path p) {
		try {
			return Files.readAttributes(p, BasicFileAttributes.class).size();
		} catch (IOException e) {
			return -1;
		}
	}

	/** Reads and decompresses a blob, verifying its length and SHA-256. */
	public byte[] read(Hash hash) throws IOException {
		Path p = pathFor(hash);
		byte[] file;
		try {
			file = Files.readAllBytes(p);
		} catch (NoSuchFileException e) {
			throw new CorruptBlobException(hash, "missing blob " + hash.shortHex());
		}
		return decode(file, hash);
	}

	/** Moves a blob file obtained elsewhere (an off-site copy) into the store after checking it decodes to {@code hash}. */
	public void adopt(Path file, Hash hash) throws IOException {
		decode(Files.readAllBytes(file), hash);
		Path target = pathFor(hash);
		Files.createDirectories(target.getParent());
		try {
			FileUtil.move(file, target, false);
		} catch (FileAlreadyExistsException e) {
			Files.deleteIfExists(file);
		}
	}

	private static byte[] decode(byte[] file, Hash hash) throws IOException {
		if (file.length < HEADER_SIZE || file[0] != MAGIC[0] || file[1] != MAGIC[1] || file[2] != MAGIC[2] || file[3] != MAGIC[3]) {
			throw new CorruptBlobException(hash, "bad blob header " + hash.shortHex());
		}
		Codec codec;
		try {
			codec = Codec.byId(file[4]);
		} catch (IllegalArgumentException e) {
			throw new CorruptBlobException(hash, "unknown codec in blob " + hash.shortHex());
		}
		int rawLen = ByteBuffer.wrap(file, 5, 4).getInt();
		byte[] raw;
		try {
			raw = Compression.decode(codec, file, HEADER_SIZE, file.length - HEADER_SIZE, rawLen);
		} catch (IOException e) {
			throw new CorruptBlobException(hash, "cannot decode blob " + hash.shortHex() + ": " + e.getMessage());
		}
		if (!Hash.compute(raw).equals(hash)) throw new CorruptBlobException(hash, "hash mismatch for blob " + hash.shortHex());
		return raw;
	}

	/** Verifies a blob without returning its content. */
	public boolean verify(Hash hash) {
		try {
			read(hash);
			return true;
		} catch (IOException e) {
			return false;
		}
	}

	public boolean delete(Hash hash) throws IOException {
		Path p = pathFor(hash);
		boolean deleted = Files.deleteIfExists(p);
		if (deleted) FileUtil.pruneEmptyParents(p, root);
		return deleted;
	}

	/** Visits every blob file: (hash, attributes). Unknown files are skipped. */
	public void forEach(BiConsumer<Hash, BasicFileAttributes> visitor) throws IOException {
		if (!Files.isDirectory(root)) return;
		Files.walkFileTree(root, new SimpleFileVisitor<>() {
			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
				String name = file.getFileName().toString();
				if (Hash.isHex(name)) visitor.accept(Hash.fromHex(name), attrs);
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFileFailed(Path file, IOException exc) {
				return FileVisitResult.CONTINUE;
			}
		});
	}

	/** Deletes leftover temp files older than the given age (from crashed jobs). */
	public void cleanTemp(long olderThanMillis) {
		long cutoff = System.currentTimeMillis() - olderThanMillis;
		try (var stream = Files.list(tmp)) {
			stream.forEach(p -> {
				try {
					if (Files.getLastModifiedTime(p).toMillis() < cutoff) FileUtil.deleteRecursively(p);
				} catch (IOException ignored) {
				}
			});
		} catch (IOException ignored) {
		}
	}

	public static final class CorruptBlobException extends IOException {
		public final Hash hash;

		public CorruptBlobException(Hash hash, String message) {
			super(message);
			this.hash = hash;
		}
	}
}
