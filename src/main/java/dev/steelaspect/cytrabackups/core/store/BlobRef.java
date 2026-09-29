package dev.steelaspect.cytrabackups.core.store;

import dev.steelaspect.cytrabackups.core.Hash;

/**
 * Reference from a manifest to a stored blob.
 *
 * @param rawLength    uncompressed length of the content
 * @param storedLength on-disk size of the blob file (used for size accounting and pruning by size)
 */
public record BlobRef(Hash hash, int rawLength, long storedLength) {
}
