package dev.steelaspect.cytrabackups.core.backup;

/** Small persistent state for the repository and scheduler ({@code state.json}). */
public final class RepositoryState {
	public int nextId = 1;
	public long lastScheduledBackup;
	public long lastBackup;
	public long lastPrune;
	/** Set whenever a player is online; cleared after each successful backup. */
	public boolean playersSeenSinceBackup = true;
}
