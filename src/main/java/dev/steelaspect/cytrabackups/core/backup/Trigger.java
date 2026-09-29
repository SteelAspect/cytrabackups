package dev.steelaspect.cytrabackups.core.backup;

/** Why a backup was created. */
public enum Trigger {
	MANUAL,
	SCHEDULED,
	PRE_RESTORE,
	SHUTDOWN,
	PLAYER_LEAVE,
	IMPORT;

	public String displayName() {
		return name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
	}
}
