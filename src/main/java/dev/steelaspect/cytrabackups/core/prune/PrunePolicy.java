package dev.steelaspect.cytrabackups.core.prune;

/**
 * Composable retention rules. A backup survives if it is pinned, is the latest (when enabled), or is
 * selected by any "keep" rule (keepLast / hourly / daily / weekly / monthly). If no keep rule is set, every
 * backup is kept by default. {@code maxAgeDays} and {@code maxTotalBytes} are hard caps applied afterwards to
 * everything that is not pinned or the latest backup.
 */
public record PrunePolicy(
	int keepLast,
	int keepHourly,
	int keepDaily,
	int keepWeekly,
	int keepMonthly,
	double maxAgeDays,
	long maxTotalBytes,
	boolean alwaysKeepLatest,
	boolean keepPreRestore,
	double preRestoreMaxAgeDays
) {
	public boolean hasKeepRules() {
		return keepLast > 0 || keepHourly > 0 || keepDaily > 0 || keepWeekly > 0 || keepMonthly > 0;
	}

	public static PrunePolicy keepAll() {
		return new PrunePolicy(0, 0, 0, 0, 0, 0, 0, true, true, 0);
	}
}
