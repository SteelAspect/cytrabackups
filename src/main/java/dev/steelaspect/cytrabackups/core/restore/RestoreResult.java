package dev.steelaspect.cytrabackups.core.restore;

/** Outcome of the last applied pending operation, reported to admins once the server is up. */
public final class RestoreResult {
	public boolean success;
	public String operation = "";
	public String message = "";
	public long finishedAt;
	public Integer preRestoreBackupId;
	public String restoreId;
	public boolean reported;
	/** Mods or their settings changed: the server must start once more before it runs the new set. */
	public boolean restartNeeded;
}
