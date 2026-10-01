package dev.steelaspect.cytrabackups.core.restore;

import java.util.ArrayList;
import java.util.List;

/** Persistent record of an applied restore, used by {@code /cbackup rollback}. */
public final class RestoreRecord {
	public enum Kind {
		FULL, CHUNKS, ROLLBACK
	}

	public static final class Item {
		public String path;
		/** A new file was put in place at this path. */
		public boolean placed;
		/** The previous file was moved into the recycle bin. */
		public boolean recycled;
		public String recycledHash;
		public boolean recycledIsRegion;
		/** The region hash is over chunk NBT (unpacked chunks) rather than raw payloads. */
		public boolean recycledUnpacked;
		public String placedHash;
		public boolean placedIsRegion;
		public boolean placedUnpacked;
	}

	public String restoreId;
	public Kind kind;
	public int backupId;
	public String description = "";
	public long appliedAt;
	public String worldDir;
	public String recycleDir;
	public boolean rolledBack;
	public String rolledBackBy;
	public List<Item> items = new ArrayList<>();
}
