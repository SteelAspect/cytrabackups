package dev.steelaspect.cytrabackups.core.manifest;

import dev.steelaspect.cytrabackups.core.Hash;
import dev.steelaspect.cytrabackups.core.store.BlobRef;
import java.util.List;
import java.util.function.Consumer;

/** A regular file split into fixed-size pieces, each stored as its own blob. */
public record FileEntry(String path, long size, long mtime, Hash contentHash, List<BlobRef> pieces) implements ManifestEntry {
	public FileEntry {
		pieces = List.copyOf(pieces);
	}

	@Override
	public void forEachBlob(Consumer<BlobRef> consumer) {
		pieces.forEach(consumer);
	}
}
