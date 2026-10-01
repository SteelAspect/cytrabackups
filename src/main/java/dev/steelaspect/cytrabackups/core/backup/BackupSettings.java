package dev.steelaspect.cytrabackups.core.backup;

/**
 * Tunables for scanning a world.
 *
 * @param chunkDedup       split .mca files per chunk so unchanged chunks are shared between backups
 * @param trustMtime       reuse the previous entry when size and modification time are unchanged
 * @param pieceSize        max bytes per blob for regular files
 * @param maxReadBytesPerSecond throttle (0 = unlimited)
 * @param maxAttempts      re-reads of a file that changed while being read
 * @param unpackChunks     store chunks as NBT compressed with the chunk dictionary instead of Minecraft's zlib payloads
 * @param chunkLevel       zstd level for unpacked chunks (1-22)
 */
public record BackupSettings(boolean chunkDedup, boolean trustMtime, int pieceSize, long maxReadBytesPerSecond, int maxAttempts,
							 boolean unpackChunks, int chunkLevel) {
	public static final int DEFAULT_PIECE_SIZE = 4 * 1024 * 1024;

	public BackupSettings(boolean chunkDedup, boolean trustMtime, int pieceSize, long maxReadBytesPerSecond, int maxAttempts) {
		this(chunkDedup, trustMtime, pieceSize, maxReadBytesPerSecond, maxAttempts, false, 15);
	}

	public static BackupSettings defaults() {
		return new BackupSettings(true, true, DEFAULT_PIECE_SIZE, 0, 3);
	}

	public BackupSettings withUnpackChunks(boolean unpack, int level) {
		return new BackupSettings(chunkDedup, trustMtime, pieceSize, maxReadBytesPerSecond, maxAttempts, unpack, level);
	}
}
