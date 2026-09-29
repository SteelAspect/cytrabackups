package dev.steelaspect.cytrabackups.core.manifest;

import dev.steelaspect.cytrabackups.core.Hash;
import dev.steelaspect.cytrabackups.core.store.BlobRef;
import java.util.function.Consumer;

/** A file captured in a backup. Paths are relative to the world folder and use '/' separators. */
public sealed interface ManifestEntry permits FileEntry, RegionEntry {
	String path();

	/** Size of the original file on disk. */
	long size();

	/** Last-modified time of the original file (used for fast "unchanged" detection). */
	long mtime();

	/** SHA-256 of the whole file for plain files, or of the chunk table for region entries. */
	Hash contentHash();

	void forEachBlob(Consumer<BlobRef> consumer);

	default long storedBytes() {
		long[] sum = {0};
		forEachBlob(b -> sum[0] += b.storedLength());
		return sum[0];
	}

	default String fileName() {
		String p = path();
		int i = p.lastIndexOf('/');
		return i < 0 ? p : p.substring(i + 1);
	}

	default String parentPath() {
		String p = path();
		int i = p.lastIndexOf('/');
		return i < 0 ? "" : p.substring(0, i);
	}
}
