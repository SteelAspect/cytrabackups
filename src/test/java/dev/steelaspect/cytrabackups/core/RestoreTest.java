package dev.steelaspect.cytrabackups.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.steelaspect.cytrabackups.core.backup.BackupMeta;
import dev.steelaspect.cytrabackups.core.backup.ChunkSelection;
import dev.steelaspect.cytrabackups.core.backup.Trigger;
import dev.steelaspect.cytrabackups.core.manifest.Manifest;
import dev.steelaspect.cytrabackups.core.manifest.RegionEntry;
import dev.steelaspect.cytrabackups.core.region.RegionFiles;
import dev.steelaspect.cytrabackups.core.restore.PendingOperation;
import dev.steelaspect.cytrabackups.core.restore.PendingOperationRunner;
import dev.steelaspect.cytrabackups.core.restore.RestorePlan;
import dev.steelaspect.cytrabackups.core.restore.RestoreRecord;
import dev.steelaspect.cytrabackups.core.restore.RestoreResult;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RestoreTest {
	@TempDir
	Path dir;

	private static RestorePlan planFull(TestWorlds w, int id) throws Exception {
		Manifest m = w.repo.loadManifest(id);
		return w.restore.planFull(m, id, false, w.world, TestWorlds.defaultFilter(), List.of(), new Progress(), CancelToken.NONE);
	}

	private static void mutate(TestWorlds w) throws Exception {
		w.write("level.dat", "level-data-CHANGED");
		w.write("playerdata/new-player.dat", "griefer");
		Files.delete(w.world.resolve("data/raids.dat"));
		TestWorlds.writeRegion(w.world.resolve("region/r.0.0.mca"), Map.of(0, 1L, 1, 2L));
	}

	@Test
	void fullRestoreRecreatesBackupExactly() throws Exception {
		try (TestWorlds w = new TestWorlds(dir)) {
			w.populate();
			Map<String, String> original = w.snapshot();
			BackupMeta b = w.backup("good").meta();
			mutate(w);
			Map<String, String> mutated = w.snapshot();
			assertNotEquals(original, mutated);

			RestorePlan plan = planFull(w, b.id);
			assertEquals(1, plan.removeCount(), "new-player.dat is not in the backup");
			RestoreRecord rec = w.restore.execute(plan, w.world, new Progress(), CancelToken.NONE);
			assertEquals(original, w.snapshot(), "world content equals backup after restore");
			assertTrue(Files.exists(w.world.resolve("session.lock")), "excluded files are untouched");
			assertTrue(Files.exists(Path.of(rec.recycleDir).resolve("playerdata/new-player.dat")), "removed file went to recycle bin");
			assertTrue(Files.exists(Path.of(rec.recycleDir).resolve("level.dat")), "replaced file went to recycle bin");
			assertFalse(Files.exists(w.storage.resolve("restore-journal.log")), "journal cleared on success");
			// restored region is a valid region file
			assertEquals(40, RegionFiles.readAll(w.world.resolve("region/r.0.0.mca")).size());
		}
	}

	@Test
	void rollbackRestoresRecycledFiles() throws Exception {
		try (TestWorlds w = new TestWorlds(dir)) {
			w.populate();
			BackupMeta b = w.backup("good").meta();
			mutate(w);
			Map<String, String> mutated = w.snapshot();
			RestoreRecord rec = w.restore.execute(planFull(w, b.id), w.world, new Progress(), CancelToken.NONE);
			assertNotEquals(mutated, w.snapshot());

			RestorePlan undo = w.restore.planRollback(w.restore.latestUndoable().orElseThrow(), w.world, new Progress(), CancelToken.NONE);
			RestoreRecord undone = w.restore.execute(undo, w.world, new Progress(), CancelToken.NONE);
			w.restore.markRolledBack(rec, undone.restoreId);
			assertEquals(mutated, w.snapshot(), "rollback returns the world to its pre-restore state");
			// and the rollback itself can be undone
			assertEquals(undone.restoreId, w.restore.latestUndoable().orElseThrow().restoreId);
		}
	}

	@Test
	void corruptBlobAbortsRestoreWithoutTouchingWorld() throws Exception {
		try (TestWorlds w = new TestWorlds(dir)) {
			w.populate();
			BackupMeta b = w.backup("good").meta();
			mutate(w);
			Map<String, String> mutated = w.snapshot();
			RegionEntry r = (RegionEntry) w.repo.loadManifest(b.id).get("region/r.0.0.mca");
			Path blob = w.blobs.pathFor(r.chunks().get(3).blob().hash());
			byte[] bytes = Files.readAllBytes(blob);
			bytes[bytes.length - 1] ^= 0x7F;
			Files.write(blob, bytes);
			assertThrows(java.io.IOException.class, () -> w.restore.execute(planFull(w, b.id), w.world, new Progress(), CancelToken.NONE));
			assertEquals(mutated, w.snapshot(), "world untouched when verification fails");
			try (var s = Files.list(dir)) {
				assertFalse(s.anyMatch(p -> p.getFileName().toString().contains("staging")), "staging cleaned up");
			}
		}
	}

	@Test
	void interruptedSwapIsRolledBackOnRecovery() throws Exception {
		try (TestWorlds w = new TestWorlds(dir)) {
			w.populate();
			Map<String, String> before = w.snapshot();
			Path recycle = w.storage.resolve("recycle/crash");
			Path staging = dir.resolve(".world-staging-crash");
			Files.createDirectories(recycle.resolve("playerdata"));
			Files.createDirectories(staging);
			// simulate: level.dat recycled + replacement placed, playerdata recycled, then crash
			Files.move(w.world.resolve("level.dat"), recycle.resolve("level.dat"));
			Files.writeString(w.world.resolve("level.dat"), "half-restored");
			Files.move(w.world.resolve("playerdata/abc.dat"), recycle.resolve("playerdata/abc.dat"));
			Files.writeString(w.storage.resolve("restore-journal.log"), String.join("\n",
				"BEGIN\tcrash\t" + w.world.toAbsolutePath() + "\t" + recycle.toAbsolutePath() + "\t" + staging.toAbsolutePath(),
				"RECYCLE\tlevel.dat", "PLACE\tlevel.dat", "RECYCLE\tplayerdata/abc.dat", ""), StandardCharsets.UTF_8);
			List<String> log = new ArrayList<>();
			assertTrue(w.restore.recoverInterrupted(log::add));
			assertEquals(before, w.snapshot());
			assertFalse(Files.exists(w.storage.resolve("restore-journal.log")));
		}
	}

	@Test
	void interruptedRecycleCopyKeepsTheOriginalFile() throws Exception {
		try (TestWorlds w = new TestWorlds(dir)) {
			w.populate();
			Map<String, String> before = w.snapshot();
			Path recycle = w.storage.resolve("recycle/crash");
			Path staging = dir.resolve(".world-staging-crash");
			Files.createDirectories(recycle);
			Files.createDirectories(staging);
			// simulate: the copy of level.dat to a recycle bin on another disk was cut off; the world file is intact
			Files.writeString(recycle.resolve("level.dat"), "half");
			Files.writeString(w.storage.resolve("restore-journal.log"), String.join("\n",
				"BEGIN\tcrash\t" + w.world.toAbsolutePath() + "\t" + recycle.toAbsolutePath() + "\t" + staging.toAbsolutePath(),
				"RECYCLE\tlevel.dat", ""), StandardCharsets.UTF_8);
			assertTrue(w.restore.recoverInterrupted(msg -> {
			}));
			assertEquals(before, w.snapshot(), "the intact world file wins over the partial recycle copy");
			assertFalse(Files.exists(recycle));
		}
	}

	@Test
	void chunkRestoreOnlyTouchesSelectedChunks() throws Exception {
		try (TestWorlds w = new TestWorlds(dir)) {
			w.populate();
			BackupMeta b = w.backup("good").meta();
			// damage chunks 0..5 (slots) and also chunk 30; add a brand-new chunk 100
			Map<Integer, Long> damaged = new TreeMap<>();
			for (int i = 0; i < 40; i++) damaged.put(i, (i <= 5 || i == 30) ? 5000L + i : 1000L + i);
			damaged.put(100, 7777L);
			TestWorlds.writeRegion(w.world.resolve("region/r.0.0.mca"), damaged);

			// restore chunks (0,0)..(2,0) and (100 = x4,z3)
			ChunkSelection sel = new ChunkSelection(List.of(new ChunkSelection.Box(0, 0, 2, 0), new ChunkSelection.Box(4, 3, 4, 3)));
			RestorePlan plan = w.restore.planChunks(w.repo.loadManifest(b.id), b.id, w.world, "", sel, new Progress(), CancelToken.NONE);
			w.restore.execute(plan, w.world, new Progress(), CancelToken.NONE);

			Map<Integer, byte[]> after = new TreeMap<>();
			for (RegionFiles.ChunkSlot s : RegionFiles.readAll(w.world.resolve("region/r.0.0.mca"))) after.put(s.index(), s.payload());
			for (int i = 0; i <= 2; i++) assertTrue(java.util.Arrays.equals(TestWorlds.chunkPayload(1000L + i, 3000), after.get(i)), "slot " + i + " restored");
			for (int i = 3; i <= 5; i++) assertTrue(java.util.Arrays.equals(TestWorlds.chunkPayload(5000L + i, 3000), after.get(i)), "slot " + i + " untouched");
			assertTrue(java.util.Arrays.equals(TestWorlds.chunkPayload(5030L, 3000), after.get(30)), "slot 30 outside selection untouched");
			assertFalse(after.containsKey(100), "chunk absent in backup is removed");
			// entities and poi for the area were restored too (identical here) and nothing else changed
			assertTrue(Files.exists(w.world.resolve("entities/r.0.0.mca")));
		}
	}

	@Test
	void pendingRunnerTakesPreRestoreBackupFirst() throws Exception {
		try (TestWorlds w = new TestWorlds(dir)) {
			w.populate();
			BackupMeta good = w.backup("good").meta();
			mutate(w);
			Map<String, String> mutated = w.snapshot();
			PendingOperation op = new PendingOperation();
			op.type = PendingOperation.Type.FULL_RESTORE;
			op.backupId = good.id;
			op.requestedBy = "tester";
			PendingOperationRunner runner = new PendingOperationRunner(w.service, w.restore, TestWorlds.defaultFilter(), List.of(), "world", 3, s -> {
			});
			RestoreResult res = runner.apply(op, w.world, new Progress(), CancelToken.NONE);
			assertTrue(res.success, res.message);
			BackupMeta pre = w.repo.get(res.preRestoreBackupId).orElseThrow();
			assertEquals(Trigger.PRE_RESTORE, pre.trigger);
			assertEquals(good.id, pre.restoreTarget);
			// the pre-restore backup captured the mutated world
			Manifest preManifest = w.repo.loadManifest(pre.id);
			assertTrue(preManifest.get("playerdata/new-player.dat") != null);

			PendingOperation undo = new PendingOperation();
			undo.type = PendingOperation.Type.ROLLBACK;
			RestoreResult undoRes = runner.apply(undo, w.world, new Progress(), CancelToken.NONE);
			assertTrue(undoRes.success, undoRes.message);
			assertEquals(mutated, w.snapshot());
		}
	}
}
