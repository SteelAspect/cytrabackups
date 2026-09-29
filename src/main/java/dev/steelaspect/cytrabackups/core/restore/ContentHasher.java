package dev.steelaspect.cytrabackups.core.restore;

import dev.steelaspect.cytrabackups.core.Hash;
import dev.steelaspect.cytrabackups.core.manifest.ChunkRef;
import dev.steelaspect.cytrabackups.core.manifest.RegionEntry;
import dev.steelaspect.cytrabackups.core.region.RegionFiles;
import dev.steelaspect.cytrabackups.core.store.BlobRef;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/** Computes the same content hashes a manifest stores, directly from files on disk. */
public final class ContentHasher {
	private ContentHasher() {
	}

	public static Hash plain(Path file) throws IOException {
		MessageDigest md = Hash.newDigest();
		byte[] buf = new byte[1 << 16];
		try (InputStream in = Files.newInputStream(file)) {
			int n;
			while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
		}
		return Hash.finish(md);
	}

	/** Logical region hash (see {@link RegionEntry#logicalHash}); throws for malformed region files. */
	public static Hash region(Path file) throws IOException {
		List<ChunkRef> refs = new ArrayList<>();
		try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
			RegionFiles.read(ch, slot -> refs.add(new ChunkRef(slot.index(), slot.timestamp(),
				new BlobRef(Hash.compute(slot.payload()), slot.payload().length, 0))));
		}
		return RegionEntry.logicalHash(refs);
	}

	public static Hash of(Path file, boolean region) throws IOException {
		return region ? region(file) : plain(file);
	}
}
