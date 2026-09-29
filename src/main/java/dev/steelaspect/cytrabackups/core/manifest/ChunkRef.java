package dev.steelaspect.cytrabackups.core.manifest;

import dev.steelaspect.cytrabackups.core.store.BlobRef;

/** One chunk of a region file: slot index (x + z*32), region timestamp and the blob holding its payload. */
public record ChunkRef(int index, int timestamp, BlobRef blob) {
}
