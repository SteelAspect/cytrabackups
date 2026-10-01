package dev.steelaspect.cytrabackups.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.steelaspect.cytrabackups.core.backup.BackupMeta;
import dev.steelaspect.cytrabackups.core.backup.Trigger;
import dev.steelaspect.cytrabackups.core.prune.PrunePolicy;
import dev.steelaspect.cytrabackups.core.prune.Pruner;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

class PrunerTest {
	static final long HOUR = 3_600_000L;
	static final long DAY = 24 * HOUR;
	// Wednesday 2026-01-14 12:00 UTC
	static final long NOW = 1_768_392_000_000L;

	static BackupMeta b(int id, long ageMillis) {
		BackupMeta m = new BackupMeta();
		m.id = id;
		m.createdAt = NOW - ageMillis;
		m.trigger = Trigger.SCHEDULED;
		return m;
	}

	static PrunePolicy policy(int last, int hourly, int daily, int weekly, int monthly, double maxAgeDays, long maxBytes) {
		return new PrunePolicy(last, hourly, daily, weekly, monthly, maxAgeDays, maxBytes, true, true, 0);
	}

	static Set<Integer> kept(List<Pruner.Decision> ds) {
		Set<Integer> s = new TreeSet<>();
		for (Pruner.Decision d : ds) if (d.keep()) s.add(d.backup().id);
		return s;
	}

	/** One backup every 30 minutes for 60 days (ids ascending with time). */
	static List<BackupMeta> halfHourly(int days) {
		List<BackupMeta> list = new ArrayList<>();
		int n = days * 48;
		for (int i = 0; i < n; i++) list.add(b(i + 1, (long) (n - 1 - i) * 30 * 60_000L));
		return list;
	}

	private static List<Pruner.Decision> plan(PrunePolicy p, List<BackupMeta> list) throws Exception {
		return new Pruner(p, ZoneOffset.UTC).plan(list, NOW, x -> Map.of());
	}

	@Test
	void backupNeededByAQueuedRestoreIsKept() throws Exception {
		List<BackupMeta> list = new ArrayList<>();
		for (int i = 1; i <= 10; i++) list.add(b(i, (10 - i) * HOUR));
		Set<Integer> k = kept(new Pruner(policy(2, 0, 0, 0, 0, 0, 0), ZoneOffset.UTC).plan(list, NOW, x -> Map.of(), Set.of(3)));
		assertEquals(Set.of(3, 9, 10), k, "the two newest plus the one a queued restore is waiting for");
	}

	@Test
	void keepLastN() throws Exception {
		List<BackupMeta> list = halfHourly(1);
		Set<Integer> k = kept(plan(policy(5, 0, 0, 0, 0, 0, 0), list));
		assertEquals(Set.of(44, 45, 46, 47, 48), k);
	}

	@Test
	void noRulesKeepsEverything() throws Exception {
		List<BackupMeta> list = halfHourly(1);
		assertEquals(48, kept(plan(PrunePolicy.keepAll(), list)).size());
	}

	@Test
	void hourlyBucketsKeepNewestPerHour() throws Exception {
		List<BackupMeta> list = halfHourly(1);
		Set<Integer> k = kept(plan(policy(0, 3, 0, 0, 0, 0, 0), list));
		// backups at :00 and :30; newest per hour = the :30 one... NOW is 12:00 so id 48 is 12:00 alone in its hour
		assertEquals(3, k.size());
		assertTrue(k.contains(48));
		assertTrue(k.contains(47) && k.contains(45), "newest of each of the next two hours: " + k);
	}

	@Test
	void dailyWeeklyMonthly() throws Exception {
		List<BackupMeta> list = halfHourly(70);
		assertEquals(7, kept(plan(policy(0, 0, 7, 0, 0, 0, 0), list)).size());
		Set<Integer> weekly = kept(plan(policy(0, 0, 0, 4, 0, 0, 0), list));
		assertEquals(4, weekly.size());
		Set<Integer> monthly = kept(plan(policy(0, 0, 0, 0, 3, 0, 0), list));
		assertEquals(3, monthly.size());
		// GFS composition is a union
		Set<Integer> gfs = kept(plan(policy(0, 24, 7, 4, 3, 0, 0), list));
		Set<Integer> union = new TreeSet<>();
		union.addAll(kept(plan(policy(0, 24, 0, 0, 0, 0, 0), list)));
		union.addAll(kept(plan(policy(0, 0, 7, 0, 0, 0, 0), list)));
		union.addAll(weekly);
		union.addAll(monthly);
		assertEquals(union, gfs);
	}

	@Test
	void weeklyBucketsAreIsoWeeks() {
		Pruner p = new Pruner(PrunePolicy.keepAll(), ZoneOffset.UTC);
		long mondayMorning = NOW - 2 * DAY - 11 * HOUR; // Mon 01:00
		long sundayNight = mondayMorning - 2 * HOUR;     // Sun 23:00
		assertTrue(p.bucketKey(mondayMorning, Pruner.Bucket.WEEKLY) != p.bucketKey(sundayNight, Pruner.Bucket.WEEKLY));
		assertEquals(p.bucketKey(mondayMorning, Pruner.Bucket.WEEKLY), p.bucketKey(NOW, Pruner.Bucket.WEEKLY));
	}

	@Test
	void maxAgeDeletesEvenKeptBackupsButNeverPinnedOrLatest() throws Exception {
		List<BackupMeta> list = List.of(b(1, 40 * DAY), b(2, 20 * DAY), b(3, 10 * DAY), b(4, DAY));
		list.get(0).pinned = true;
		Set<Integer> k = kept(plan(policy(10, 0, 0, 0, 0, 15, 0), list));
		assertEquals(Set.of(1, 3, 4), k);
		// everything old but the latest is still kept
		Set<Integer> k2 = kept(plan(policy(0, 0, 0, 0, 0, 0.5, 0), List.of(b(1, 10 * DAY), b(2, 5 * DAY))));
		assertEquals(Set.of(2), k2);
	}

	@Test
	void maxTotalSizeAccountsForSharedBlobs() throws Exception {
		List<BackupMeta> list = List.of(b(1, 4 * DAY), b(2, 3 * DAY), b(3, 2 * DAY), b(4, DAY));
		Hash shared = Hash.compute("shared".getBytes());
		Map<Integer, Map<Hash, Long>> blobs = new HashMap<>();
		for (BackupMeta m : list) {
			Map<Hash, Long> x = new HashMap<>();
			x.put(shared, 1000L);
			x.put(Hash.compute(("own" + m.id).getBytes()), 100L);
			blobs.put(m.id, x);
		}
		// total = 1000 + 4*100 = 1400; cap 1250 -> must drop 2 oldest (each frees 100)
		List<Pruner.Decision> ds = new Pruner(policy(0, 0, 0, 0, 0, 0, 1250), ZoneOffset.UTC).plan(list, NOW, m -> blobs.get(m.id));
		assertEquals(Set.of(3, 4), kept(ds));
	}

	@Test
	void pinnedBackupsAreNeverPruned() throws Exception {
		List<BackupMeta> list = halfHourly(2);
		list.get(3).pinned = true;
		list.get(10).pinned = true;
		Set<Integer> k = kept(plan(policy(1, 0, 0, 0, 0, 0.01, 1), list));
		assertTrue(k.contains(4) && k.contains(11), "pinned survive every rule: " + k);
	}

	@Test
	void preRestoreWindow() throws Exception {
		BackupMeta old = b(1, 10 * DAY);
		old.trigger = Trigger.PRE_RESTORE;
		BackupMeta fresh = b(2, DAY);
		fresh.trigger = Trigger.PRE_RESTORE;
		BackupMeta latest = b(3, HOUR);
		PrunePolicy p = new PrunePolicy(0, 0, 0, 0, 0, 0, 0, true, true, 7);
		assertEquals(Set.of(2, 3), kept(new Pruner(p, ZoneOffset.UTC).plan(List.of(old, fresh, latest), NOW, x -> Map.of())));
	}

	@Test
	void everyDeletionHasAReason() throws Exception {
		for (Pruner.Decision d : plan(policy(3, 0, 1, 0, 0, 0, 0), halfHourly(3))) {
			assertTrue(!d.reasons().isEmpty(), "decision for #" + d.backup().id + " has no reason");
		}
	}
}
