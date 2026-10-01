package dev.steelaspect.cytrabackups.core.prune;

import dev.steelaspect.cytrabackups.core.Formatting;
import dev.steelaspect.cytrabackups.core.Lang;
import dev.steelaspect.cytrabackups.core.Hash;
import dev.steelaspect.cytrabackups.core.backup.BackupMeta;
import dev.steelaspect.cytrabackups.core.backup.Trigger;
import java.io.IOException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.IsoFields;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/** Pure planning of which backups to delete; performs no I/O except through the supplied blob lister. */
public final class Pruner {
	public enum Bucket {
		HOURLY, DAILY, WEEKLY, MONTHLY
	}

	/** Decision for one backup, with human-readable reasons. */
	public record Decision(BackupMeta backup, boolean keep, List<String> reasons) {
	}

	/** Supplies (blob hash -> stored size) for a backup; only needed when a size cap is configured. */
	public interface BlobLister {
		Map<Hash, Long> blobs(BackupMeta backup) throws IOException;
	}

	private final PrunePolicy policy;
	private final ZoneId zone;

	public Pruner(PrunePolicy policy, ZoneId zone) {
		this.policy = policy;
		this.zone = zone;
	}

	public List<Decision> plan(List<BackupMeta> backups, long now, BlobLister lister) throws IOException {
		return plan(backups, now, lister, Set.of());
	}

	/** {@code needed}: ids that must survive whatever the rules say, e.g. the backup a queued restore is waiting for. */
	public List<Decision> plan(List<BackupMeta> backups, long now, BlobLister lister, Set<Integer> needed) throws IOException {
		List<BackupMeta> newestFirst = new ArrayList<>(backups);
		newestFirst.sort(Comparator.comparingLong((BackupMeta b) -> b.createdAt).thenComparingInt(b -> b.id).reversed());

		Map<Integer, List<String>> keepReasons = new LinkedHashMap<>();
		Map<Integer, List<String>> deleteReasons = new HashMap<>();
		Set<Integer> protectedIds = new HashSet<>();

		for (BackupMeta b : newestFirst) keepReasons.put(b.id, new ArrayList<>());

		for (BackupMeta b : newestFirst) {
			if (b.pinned) {
				keepReasons.get(b.id).add(Lang.get("cytrabackups.prune.reason.pinned"));
				protectedIds.add(b.id);
			}
			if (needed.contains(b.id)) {
				keepReasons.get(b.id).add(Lang.get("cytrabackups.prune.reason.queued"));
				protectedIds.add(b.id);
			}
		}
		BackupMeta latest = newestFirst.stream().filter(b -> !b.partial).findFirst().orElse(null);
		if (policy.alwaysKeepLatest() && latest != null) {
			keepReasons.get(latest.id).add(Lang.get("cytrabackups.prune.reason.latest"));
			protectedIds.add(latest.id);
		}

		// Keep rules only consider complete backups; partial (area) backups follow the pre-restore rule.
		List<BackupMeta> full = newestFirst.stream().filter(b -> !b.partial).toList();
		if (policy.keepLast() > 0) {
			for (int i = 0; i < Math.min(policy.keepLast(), full.size()); i++) {
				keepReasons.get(full.get(i).id).add(Lang.get("cytrabackups.prune.reason.last", policy.keepLast()));
			}
		}
		applyBuckets(full, Bucket.HOURLY, policy.keepHourly(), keepReasons);
		applyBuckets(full, Bucket.DAILY, policy.keepDaily(), keepReasons);
		applyBuckets(full, Bucket.WEEKLY, policy.keepWeekly(), keepReasons);
		applyBuckets(full, Bucket.MONTHLY, policy.keepMonthly(), keepReasons);

		for (BackupMeta b : newestFirst) {
			if (b.trigger == Trigger.PRE_RESTORE && policy.keepPreRestore()) {
				double ageDays = (now - b.createdAt) / 86_400_000.0;
				if (policy.preRestoreMaxAgeDays() <= 0 || ageDays <= policy.preRestoreMaxAgeDays()) {
					keepReasons.get(b.id).add(Lang.get("cytrabackups.prune.reason.pre_restore"));
				}
			}
		}

		Set<Integer> kept = new HashSet<>();
		for (BackupMeta b : newestFirst) {
			List<String> reasons = keepReasons.get(b.id);
			boolean keep = protectedIds.contains(b.id) || !reasons.isEmpty();
			boolean expiredPreRestore = b.trigger == Trigger.PRE_RESTORE && policy.keepPreRestore() && policy.preRestoreMaxAgeDays() > 0;
			if (!keep && !policy.hasKeepRules() && !expiredPreRestore) {
				keep = true;
				reasons.add(Lang.get("cytrabackups.prune.reason.no_rules"));
			}
			if (keep) {
				kept.add(b.id);
			} else {
				deleteReasons.computeIfAbsent(b.id, k -> new ArrayList<>()).add(expiredPreRestore
					? Lang.get("cytrabackups.prune.reason.pre_restore_old", Formatting.number(policy.preRestoreMaxAgeDays()))
					: Lang.get(b.partial ? "cytrabackups.prune.reason.area" : "cytrabackups.prune.reason.no_rule"));
			}
		}

		if (policy.maxAgeDays() > 0) {
			for (BackupMeta b : newestFirst) {
				if (protectedIds.contains(b.id) || !kept.contains(b.id)) continue;
				double ageDays = (now - b.createdAt) / 86_400_000.0;
				if (ageDays > policy.maxAgeDays()) {
					kept.remove(b.id);
					deleteReasons.computeIfAbsent(b.id, k -> new ArrayList<>()).add(Lang.get("cytrabackups.prune.reason.max_age", Formatting.number(policy.maxAgeDays()), Formatting.number(ageDays)));
				}
			}
		}

		if (policy.maxTotalBytes() > 0) {
			applySizeCap(newestFirst, kept, protectedIds, deleteReasons, lister);
		}

		List<Decision> out = new ArrayList<>();
		for (BackupMeta b : newestFirst) {
			boolean keep = kept.contains(b.id);
			out.add(new Decision(b, keep, keep ? keepReasons.get(b.id) : deleteReasons.getOrDefault(b.id, List.of())));
		}
		return out;
	}

	private void applyBuckets(List<BackupMeta> newestFirst, Bucket bucket, int count, Map<Integer, List<String>> reasons) {
		if (count <= 0) return;
		Set<Long> seen = new HashSet<>();
		String reasonKey = "cytrabackups.prune.reason." + bucket.name().toLowerCase(java.util.Locale.ROOT);
		for (BackupMeta b : newestFirst) {
			long key = bucketKey(b.createdAt, bucket);
			if (seen.contains(key)) continue;
			if (seen.size() >= count) break;
			seen.add(key);
			reasons.get(b.id).add(Lang.get(reasonKey, seen.size()));
		}
	}

	/** Start of the bucket (hour/day/ISO week/month) containing the timestamp, in the configured zone. */
	public long bucketKey(long epochMillis, Bucket bucket) {
		ZonedDateTime t = Instant.ofEpochMilli(epochMillis).atZone(zone);
		ZonedDateTime start = switch (bucket) {
			case HOURLY -> t.truncatedTo(ChronoUnit.HOURS);
			case DAILY -> t.truncatedTo(ChronoUnit.DAYS);
			case WEEKLY -> t.truncatedTo(ChronoUnit.DAYS).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
			case MONTHLY -> t.truncatedTo(ChronoUnit.DAYS).withDayOfMonth(1);
		};
		if (bucket == Bucket.WEEKLY) {
			return start.get(IsoFields.WEEK_BASED_YEAR) * 100L + start.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
		}
		return start.toInstant().toEpochMilli();
	}

	/**
	 * Deletes the oldest unprotected kept backups until the union of referenced blob sizes fits the cap.
	 * Uses reference counting, so shared (deduplicated) blobs are only freed when their last user goes.
	 */
	private void applySizeCap(List<BackupMeta> newestFirst, Set<Integer> kept, Set<Integer> protectedIds,
							  Map<Integer, List<String>> deleteReasons, BlobLister lister) throws IOException {
		Map<Integer, Map<Hash, Long>> perBackup = new HashMap<>();
		Map<Hash, int[]> refs = new HashMap<>();
		Map<Hash, Long> sizes = new HashMap<>();
		long total = 0;
		for (BackupMeta b : newestFirst) {
			if (!kept.contains(b.id)) continue;
			Map<Hash, Long> blobs = lister.blobs(b);
			perBackup.put(b.id, blobs);
			for (Map.Entry<Hash, Long> e : blobs.entrySet()) {
				int[] c = refs.computeIfAbsent(e.getKey(), k -> new int[1]);
				if (c[0]++ == 0) {
					sizes.put(e.getKey(), e.getValue());
					total += e.getValue();
				}
			}
		}
		List<BackupMeta> oldestFirst = new ArrayList<>(newestFirst);
		java.util.Collections.reverse(oldestFirst);
		for (BackupMeta b : oldestFirst) {
			if (total <= policy.maxTotalBytes()) break;
			if (!kept.contains(b.id) || protectedIds.contains(b.id)) continue;
			long freed = 0;
			for (Hash h : perBackup.get(b.id).keySet()) {
				int[] c = refs.get(h);
				if (--c[0] == 0) freed += sizes.get(h);
			}
			total -= freed;
			kept.remove(b.id);
			deleteReasons.computeIfAbsent(b.id, k -> new ArrayList<>()).add(Lang.get("cytrabackups.prune.reason.max_size", Formatting.bytes(freed)));
		}
	}

	/** Convenience for building a blob lister from any function. */
	public static BlobLister lister(Function<BackupMeta, Map<Hash, Long>> f) {
		return f::apply;
	}
}
