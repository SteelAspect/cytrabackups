package dev.steelaspect.cytrabackups.core.restore;

import dev.steelaspect.cytrabackups.core.Hash;
import dev.steelaspect.cytrabackups.core.manifest.ManifestEntry;
import dev.steelaspect.cytrabackups.core.manifest.RegionEntry;
import java.nio.file.Path;
import java.util.List;

/** The list of file operations a restore will perform. */
public record RestorePlan(RestoreRecord.Kind kind, int backupId, String description, List<Op> ops, int unchanged, List<String> notes) {
	public enum Action {
		PLACE, REMOVE
	}

	/** Where the new content of a PLACE op comes from. */
	public sealed interface Source permits FromEntry, MergeRegion, FromFile {
	}

	/** A whole file from the backup. */
	public record FromEntry(ManifestEntry entry) implements Source {
	}

	/**
	 * The current region file with the selected slots replaced by the backup's chunks (or removed when the
	 * backup has none). {@code backupRegion} may be null when the region did not exist in the backup;
	 * {@code backupWholeFile} is set when the backup stored the region as a plain file.
	 */
	public record MergeRegion(RegionEntry backupRegion, ManifestEntry backupWholeFile, List<Integer> indices) implements Source {
	}

	/** A file from disk (the recycle bin) with a known hash ({@code unpacked}: region hash over chunk NBT). */
	public record FromFile(Path file, Hash expected, boolean region, boolean unpacked) implements Source {
	}

	/**
	 * @param currentHash hash of the file currently in the world (null if absent)
	 * @param currentUnpacked the region hash was taken over chunk NBT rather than raw payloads
	 */
	public record Op(String path, Action action, Source source, Hash currentHash, boolean currentIsRegion, boolean currentUnpacked) {
		public Op(String path, Action action, Source source, Hash currentHash, boolean currentIsRegion) {
			this(path, action, source, currentHash, currentIsRegion, false);
		}
	}

	public long placeCount() {
		return ops.stream().filter(o -> o.action == Action.PLACE).count();
	}

	public long removeCount() {
		return ops.stream().filter(o -> o.action == Action.REMOVE).count();
	}
}
