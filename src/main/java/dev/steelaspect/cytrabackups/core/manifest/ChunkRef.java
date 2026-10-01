package dev.steelaspect.cytrabackups.core.manifest;

import dev.steelaspect.cytrabackups.core.store.BlobRef;

/**
 * One chunk of a region file: slot index (x + z*32), region timestamp and the blob holding it. {@code format} 0 means
 * the blob is the chunk's payload exactly as in the region file (compression byte included); 1, 2 or 3 mean the blob is
 * the chunk's NBT, unpacked from that compression type (gzip, zlib, none) and to be packed with it again on restore.
 */
public record ChunkRef(int index, int timestamp, BlobRef blob, int format) {
	public static final int RAW = 0;

	public ChunkRef(int index, int timestamp, BlobRef blob) {
		this(index, timestamp, blob, RAW);
	}

	/** True when the blob holds unpacked NBT. */
	public boolean unpacked() {
		return format != RAW;
	}
}
