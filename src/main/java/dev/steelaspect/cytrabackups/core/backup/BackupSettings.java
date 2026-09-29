package dev.steelaspect.cytrabackups.core.backup;

/**
 * Tunables for scanning a world.
 *
 * @param chunkDedup       split .mca files per chunk so unchanged chunks are shared between backups
 * @param trustMtime       reuse the previous entry when size and modification time are unchanged
 * @param pieceSize        max bytes per blob for regular files
 * @param maxReadBytesPerSecond throttle (0 = unlimited)
 * @param maxAttempts      re-reads of a file that changed while being read
 */
public record BackupSettings(boolean chunkDedup, boolean trustMtime, int pieceSize, long maxReadBytesPerSecond, int maxAttempts) {
	public static final int DEFAULT_PIECE_SIZE = 4 * 1024 * 1024;

	public static BackupSettings defaults() {
		return new BackupSettings(true, true, DEFAULT_PIECE_SIZE, 0, 3);
	}
}
