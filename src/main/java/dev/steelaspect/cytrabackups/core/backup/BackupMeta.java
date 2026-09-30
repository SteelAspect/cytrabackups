package dev.steelaspect.cytrabackups.core.backup;

/** Metadata stored as {@code meta.json} next to every manifest. Plain fields for Gson. */
public final class BackupMeta {
	public int id;
	public long createdAt;
	public String comment = "";
	public String creator = "";
	public Trigger trigger = Trigger.MANUAL;
	public boolean pinned;
	/** Partial backups (pre-restore of an area) only contain some files; restoring them never deletes others. */
	public boolean partial;
	public String scope = "";
	public String levelName = "";

	/** Logical size of the world captured by this backup. */
	public long totalSize;
	public long fileCount;
	public long chunkCount;
	/** Bytes of new blobs this backup added to the store (after compression). */
	public long newStoredBytes;
	public long newBlobs;
	public long reusedFiles;
	/** Sum of stored sizes of every blob this backup references (its size if it were the only backup). */
	public long referencedStoredBytes;

	public String minecraftVersion = "";
	public String modVersion = "";
	public long durationMillis;
	public String manifestSha256 = "";
	/** For pre-restore backups: the backup that was about to be restored. */
	public Integer restoreTarget;

	public BackupMeta copy() {
		BackupMeta m = new BackupMeta();
		m.id = id;
		m.createdAt = createdAt;
		m.comment = comment;
		m.creator = creator;
		m.trigger = trigger;
		m.pinned = pinned;
		m.partial = partial;
		m.scope = scope;
		m.levelName = levelName;
		m.totalSize = totalSize;
		m.fileCount = fileCount;
		m.chunkCount = chunkCount;
		m.newStoredBytes = newStoredBytes;
		m.newBlobs = newBlobs;
		m.reusedFiles = reusedFiles;
		m.referencedStoredBytes = referencedStoredBytes;
		m.minecraftVersion = minecraftVersion;
		m.modVersion = modVersion;
		m.durationMillis = durationMillis;
		m.manifestSha256 = manifestSha256;
		m.restoreTarget = restoreTarget;
		return m;
	}
}
