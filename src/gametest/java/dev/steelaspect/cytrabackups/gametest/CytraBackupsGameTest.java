package dev.steelaspect.cytrabackups.gametest;

import dev.steelaspect.cytrabackups.core.backup.BackupMeta;
import dev.steelaspect.cytrabackups.core.backup.ChunkSelection;
import dev.steelaspect.cytrabackups.core.backup.Trigger;
import dev.steelaspect.cytrabackups.core.region.RegionFiles;
import dev.steelaspect.cytrabackups.core.restore.PendingOperation;
import dev.steelaspect.cytrabackups.core.restore.PendingOperationRunner;
import dev.steelaspect.cytrabackups.core.restore.RestoreResult;
import dev.steelaspect.cytrabackups.mc.BackupManager;
import dev.steelaspect.cytrabackups.mc.Feedback;
import dev.steelaspect.cytrabackups.mc.LiveChunkRestore;
import dev.steelaspect.cytrabackups.mc.StartupRestore;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.storage.RegionFileVersion;

/**
 * End-to-end test on a real (headless) server: backup, modify, full restore + rollback through the startup-restore
 * code path into a scratch world folder, then a live chunk restore in the running world.
 */
public class CytraBackupsGameTest {
	private static final int CX = 1000, CZ = 1000; // far away from the test structures, never loaded by them
	private static final BlockPos P = new BlockPos(CX * 16 + 5, 100, CZ * 16 + 7);

	private static final class Messages implements Feedback {
		final List<String> lines = new CopyOnWriteArrayList<>();

		@Override
		public void send(net.minecraft.network.chat.Component message, boolean error) {
			lines.add((error ? "ERROR " : "") + message.getString());
		}

		String last() {
			return lines.isEmpty() ? "" : lines.get(lines.size() - 1);
		}
	}

	private static void forceLoad(ServerLevel level, boolean on) {
		level.setChunkForced(CX, CZ, on);
		if (on) level.getChunk(CX, CZ); // load synchronously
	}

	private static BackupMeta latestWithComment(BackupManager m, String comment) {
		List<BackupMeta> list = m.services().repo.list();
		for (int i = list.size() - 1; i >= 0; i--) if (list.get(i).comment.equals(comment)) return list.get(i);
		return null;
	}

	@GameTest(maxTicks = 400_000) // the test server ticks unthrottled while we wait on real-time IO
	public void backupRestoreRoundTrip(GameTestHelper helper) {
		BackupManager m = BackupManager.get();
		ServerLevel level = helper.getLevel();
		Messages msgs = new Messages();
		int[] ids = new int[2];
		AtomicReference<String> diskResult = new AtomicReference<>();
		Path scratch = m.services().storage.resolve("gametest-world");

		helper.startSequence()
			// 1. gold block -> backup A
			.thenExecute(() -> {
				forceLoad(level, true);
				level.setBlockAndUpdate(P, Blocks.GOLD_BLOCK.defaultBlockState());
				helper.assertTrue(m.createBackup(Trigger.MANUAL, "gametest-A", "gametest", msgs), "backup A should start");
			})
			.thenWaitUntil(() -> {
				helper.assertTrue(m.currentJob() == null && latestWithComment(m, "gametest-A") != null, "waiting for backup A: " + msgs.last());
				ids[0] = latestWithComment(m, "gametest-A").id;
			})
			// 2. modify the world: diamond block -> backup B
			.thenExecute(() -> {
				level.setBlockAndUpdate(P, Blocks.DIAMOND_BLOCK.defaultBlockState());
				helper.assertTrue(m.createBackup(Trigger.MANUAL, "gametest-B", "gametest", msgs), "backup B should start");
			})
			.thenWaitUntil(() -> {
				helper.assertTrue(m.currentJob() == null && latestWithComment(m, "gametest-B") != null, "waiting for backup B: " + msgs.last());
				ids[1] = latestWithComment(m, "gametest-B").id;
			})
			// 3. full restore through the same code path used at startup, into a scratch world folder
			.thenExecute(() -> new Thread(() -> diskResult.set(fullRestoreOnDisk(m, scratch, ids[0], ids[1])), "gametest-full-restore").start())
			.thenWaitUntil(() -> helper.assertTrue(diskResult.get() != null, "waiting for the on-disk full restore"))
			.thenExecute(() -> {
				helper.assertTrue(diskResult.get().equals("ok"), "full restore on disk: " + diskResult.get());
				try {
					m.services().repo.reload(); // the startup path used its own repository handle
				} catch (java.io.IOException e) {
					throw new RuntimeException(e);
				}
				// 4. modify the live world again, unload the chunk and restore it live from backup A
				level.setBlockAndUpdate(P, Blocks.EMERALD_BLOCK.defaultBlockState());
				forceLoad(level, false);
				m.startChunkRestore(ids[0], level, ChunkSelection.box(CX, CZ, CX, CZ), "gametest", msgs);
			})
			.thenWaitUntil(() -> helper.assertTrue(m.currentJob() == null && (msgs.last().contains("LIVE") || msgs.last().contains("QUEUED")
				|| msgs.last().startsWith("ERROR")), "waiting for live chunk restore: " + msgs.last()))
			.thenExecute(() -> {
				helper.assertTrue(msgs.last().contains("LIVE"), "chunk restore should have been applied live: " + msgs.last());
				forceLoad(level, true);
				Block b = level.getBlockState(P).getBlock();
				helper.assertTrue(b == Blocks.GOLD_BLOCK, "live chunk restore should bring back the gold block, found " + b);
				forceLoad(level, false);
				msgs.lines.forEach(l -> org.slf4j.LoggerFactory.getLogger("CytraBackups-GameTest").info("[job message] {}", l));
				org.slf4j.LoggerFactory.getLogger("CytraBackups-GameTest").info("On-disk full restore / restore / rollback: {}", diskResult.get());
			})
			.thenSucceed();
	}

	/**
	 * The live-restore distance check must match what Minecraft really keeps in memory: a ticket at the player-ticket
	 * level (radius 2 = level 31) must leave chunk holders, as counted by the restore's own unload check, exactly
	 * {@code holderRange(vd) - vd} chunks beyond the ticketed area and none further out.
	 */
	@GameTest(maxTicks = 2_000)
	public void holderRangeMatchesTickets(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		int spread = LiveChunkRestore.holderRange(10) - 10;
		ChunkPos c = new ChunkPos(-2000, 2000); // untouched by other tests
		helper.assertTrue(spread > 2, "holder spread should exceed the old view distance + 2 guess, got " + spread);
		helper.startSequence()
			.thenExecute(() -> level.getChunkSource().addTicketWithRadius(TicketType.FORCED, c, 2))
			.thenWaitUntil(() -> helper.assertTrue(LiveChunkRestore.countLoaded(level, ChunkSelection.box(c.x + spread, c.z, c.x + spread, c.z)) == 1,
				"a holder should exist " + spread + " chunks from the ticket"))
			.thenIdle(20)
			.thenExecute(() -> {
				helper.assertTrue(LiveChunkRestore.countLoaded(level, ChunkSelection.box(c.x + spread, c.z - spread, c.x + spread, c.z + spread)) == 2 * spread + 1,
					"every chunk " + spread + " away should have a holder");
				helper.assertTrue(LiveChunkRestore.countLoaded(level, ChunkSelection.box(c.x + spread + 1, c.z - spread - 1, c.x + spread + 1, c.z + spread + 1)) == 0,
					"no chunk " + (spread + 1) + " away should have a holder");
				org.slf4j.LoggerFactory.getLogger("CytraBackups-GameTest").info("Chunk holders reach {} chunks beyond a player's view distance", spread);
				level.getChunkSource().removeTicketWithRadius(TicketType.FORCED, c, 2);
			})
			.thenSucceed();
	}

	/** Restores B into an empty folder, then A over it (reverting the change), then rolls back; checks the block each time. */
	private static String fullRestoreOnDisk(BackupManager m, Path scratch, int idA, int idB) {
		try {
			dev.steelaspect.cytrabackups.core.FileUtil.deleteRecursively(scratch);
			Files.createDirectories(scratch);
			String rel = "region/" + RegionFiles.regionFileName(Math.floorDiv(CX, 32), Math.floorDiv(CZ, 32));

			RestoreResult r = apply(m, scratch, PendingOperation.Type.FULL_RESTORE, idB);
			if (!r.success) return "restore of B failed: " + r.message;
			String block = blockAt(scratch.resolve(rel));
			if (!block.equals("minecraft:diamond_block")) return "after restoring B expected diamond_block, found " + block;

			r = apply(m, scratch, PendingOperation.Type.FULL_RESTORE, idA);
			if (!r.success) return "restore of A failed: " + r.message;
			block = blockAt(scratch.resolve(rel));
			if (!block.equals("minecraft:gold_block")) return "after restoring A expected gold_block, found " + block;
			if (r.preRestoreBackupId == null) return "no pre-restore backup was taken";

			r = apply(m, scratch, PendingOperation.Type.ROLLBACK, 0);
			if (!r.success) return "rollback failed: " + r.message;
			block = blockAt(scratch.resolve(rel));
			if (!block.equals("minecraft:diamond_block")) return "after rollback expected diamond_block, found " + block;
			return "ok";
		} catch (Throwable t) {
			return t.toString();
		} finally {
			try {
				dev.steelaspect.cytrabackups.core.FileUtil.deleteRecursively(scratch);
			} catch (java.io.IOException ignored) {
			}
		}
	}

	private static RestoreResult apply(BackupManager m, Path world, PendingOperation.Type type, int id) throws Exception {
		PendingOperation op = new PendingOperation();
		op.type = type;
		op.backupId = id;
		op.worldDir = world.toAbsolutePath().normalize().toString();
		op.requestedBy = "gametest";
		op.requestedAt = System.currentTimeMillis();
		PendingOperationRunner.writePending(m.services().storage, op);
		RestoreResult r = StartupRestore.apply(m.config(), m.services().storage, "gametest-world", world, "gametest");
		if (r == null) throw new IllegalStateException("pending operation was not applied");
		return r;
	}

	/** Reads the block name at P from a region file by decoding the chunk NBT and its paletted block storage. */
	static String blockAt(Path regionFile) throws Exception {
		int index = RegionFiles.index(CX, CZ);
		for (RegionFiles.ChunkSlot slot : RegionFiles.readAll(regionFile)) {
			if (slot.index() != index) continue;
			RegionFileVersion v = RegionFileVersion.fromId(slot.compressionType());
			CompoundTag chunk;
			try (DataInputStream in = new DataInputStream(v.wrap(new ByteArrayInputStream(slot.payload(), 1, slot.payload().length - 1)))) {
				chunk = NbtIo.read(in, NbtAccounter.unlimitedHeap());
			}
			ListTag sections = chunk.getListOrEmpty("sections");
			for (int i = 0; i < sections.size(); i++) {
				CompoundTag section = sections.getCompoundOrEmpty(i);
				if (section.getByteOr("Y", Byte.MIN_VALUE) != (byte) (P.getY() >> 4)) continue;
				CompoundTag states = section.getCompoundOrEmpty("block_states");
				ListTag palette = states.getListOrEmpty("palette");
				if (palette.size() == 1) return palette.getCompoundOrEmpty(0).getStringOr("Name", "?");
				long[] data = states.getLongArray("data").orElse(new long[0]);
				int bits = Math.max(4, 32 - Integer.numberOfLeadingZeros(palette.size() - 1));
				int perLong = 64 / bits;
				int idx = ((P.getY() & 15) << 8) | ((P.getZ() & 15) << 4) | (P.getX() & 15);
				long word = data[idx / perLong];
				int value = (int) ((word >>> ((idx % perLong) * bits)) & ((1L << bits) - 1));
				return palette.getCompoundOrEmpty(value).getStringOr("Name", "?");
			}
			return "minecraft:air (no section)";
		}
		return "no chunk";
	}
}
