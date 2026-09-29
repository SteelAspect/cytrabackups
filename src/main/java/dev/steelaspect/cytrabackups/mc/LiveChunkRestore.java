package dev.steelaspect.cytrabackups.mc;

import dev.steelaspect.cytrabackups.core.FileUtil;
import dev.steelaspect.cytrabackups.core.backup.ChunkSelection;
import dev.steelaspect.cytrabackups.core.manifest.ChunkRef;
import dev.steelaspect.cytrabackups.core.manifest.FileEntry;
import dev.steelaspect.cytrabackups.core.manifest.Manifest;
import dev.steelaspect.cytrabackups.core.manifest.ManifestEntry;
import dev.steelaspect.cytrabackups.core.manifest.RegionEntry;
import dev.steelaspect.cytrabackups.core.region.RegionFiles;
import dev.steelaspect.cytrabackups.core.store.BlobRef;
import dev.steelaspect.cytrabackups.core.store.BlobStore;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.util.Mth;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.EntityStorage;
import net.minecraft.world.level.chunk.storage.RegionFileVersion;
import net.minecraft.world.level.chunk.storage.SectionStorage;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;
import net.minecraft.world.level.levelgen.structure.StructureCheck;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;

/**
 * Live chunk restore. Chunks are only rewritten when Minecraft has fully unloaded them (no chunk holder, no
 * pending unload, no loaded entity sections), and the writes go through Minecraft's own region IO workers so
 * its in-memory region file state stays consistent. Cached entity, POI and structure-start data for those chunks
 * is evicted so the next load reads the restored data.
 */
public final class LiveChunkRestore {
	private LiveChunkRestore() {
	}

	public record Safety(boolean safe, String reason) {
		static Safety ok() {
			return new Safety(true, "");
		}

		static Safety no(String reason) {
			return new Safety(false, reason);
		}
	}

	public enum Kind {
		REGION("region"), ENTITIES("entities"), POI("poi");

		public final String folder;

		Kind(String folder) {
			this.folder = folder;
		}
	}

	/** One chunk write; a null tag deletes the chunk from that storage. */
	public record Write(Kind kind, ChunkPos pos, CompoundTag tag) {
	}

	static int distanceToSelection(ChunkSelection sel, int x, int z) {
		int best = Integer.MAX_VALUE;
		for (ChunkSelection.Box b : sel.boxes()) {
			int dx = Math.max(Math.max(b.minX() - x, 0), x - b.maxX());
			int dz = Math.max(Math.max(b.minZ() - z, 0), z - b.maxZ());
			best = Math.min(best, Math.max(dx, dz));
		}
		return best;
	}

	/**
	 * Chebyshev distance in chunks from a player within which Minecraft keeps chunk holders for the area: the player
	 * ticket covers the (clamped) view distance and its level then spreads one level per chunk up to
	 * {@link ChunkLevel#MAX_LEVEL}. A holder may cache the chunk, so those chunks never count as unloaded.
	 */
	public static int holderRange(int viewDistance) {
		return Mth.clamp(viewDistance, 2, 32) + ChunkLevel.MAX_LEVEL - ChunkLevel.byStatus(FullChunkStatus.ENTITY_TICKING);
	}

	/** Server thread. Fast rejection before waiting for unloads. */
	public static Safety quickCheck(ServerLevel level, ChunkSelection sel, int viewDistance) {
		if (level.noSave) return Safety.no("autosave is disabled in this dimension (/save-off), so Minecraft will not unload the chunks");
		int range = holderRange(viewDistance);
		for (ServerPlayer p : level.players()) {
			ChunkPos cp = p.chunkPosition();
			int d = distanceToSelection(sel, cp.x, cp.z);
			if (d <= range) {
				return Safety.no("player " + p.getGameProfile().name() + " is " + d + " chunks from the area; Minecraft keeps chunks within " + range
					+ " chunks of a player in memory (view distance " + viewDistance + " plus loading margin), so players must be at least "
					+ (range + 1) + " chunks (" + (range + 1) * 16 + " blocks) away for a live restore");
			}
		}
		LongSet forced = level.getForceLoadedChunks();
		for (long f : forced) {
			ChunkPos cp = new ChunkPos(f);
			if (distanceToSelection(sel, cp.x, cp.z) <= 2) return Safety.no("chunk " + cp.x + "," + cp.z + " is force-loaded (/forceload)");
		}
		return Safety.ok();
	}

	/** Server thread. True when no selected chunk has any loaded or pending state in Minecraft. */
	public static int countLoaded(ServerLevel level, ChunkSelection sel) {
		ChunkMap cm = level.getChunkSource().chunkMap;
		PersistentEntitySectionManager<Entity> em = level.entityManager;
		int loaded = 0;
		for (ChunkSelection.Box b : sel.boxes()) {
			for (int x = b.minX(); x <= b.maxX(); x++) {
				for (int z = b.minZ(); z <= b.maxZ(); z++) {
					long k = ChunkPos.asLong(x, z);
					if (cm.getUpdatingChunkIfPresent(k) != null || cm.getVisibleChunkIfPresent(k) != null || cm.pendingUnloads.containsKey(k)
						|| em.chunkLoadStatuses.containsKey(k) || em.chunksToUnload.contains(k)
						|| em.sectionStorage.getExistingSectionsInChunk(k).findAny().isPresent()) {
						loaded++;
					}
				}
			}
		}
		return loaded;
	}

	public static SimpleRegionStorage storage(ServerLevel level, Kind kind) {
		return switch (kind) {
			case REGION -> level.getChunkSource().chunkMap;
			case ENTITIES -> {
				if (level.entityManager.permanentStorage instanceof EntityStorage es) yield es.simpleRegionStorage;
				throw new IllegalStateException("Entity storage was replaced by another mod; live entity restore unsupported");
			}
			case POI -> level.getPoiManager().simpleRegionStorage;
		};
	}

	/** Server thread: returns futures that complete when pending IO for all three storages has hit the disk. */
	public static CompletableFuture<Void> flush(ServerLevel level) {
		List<CompletableFuture<Void>> fs = new ArrayList<>();
		for (Kind k : Kind.values()) fs.add(storage(level, k).synchronize(true));
		return CompletableFuture.allOf(fs.toArray(CompletableFuture[]::new));
	}

	/**
	 * Off-thread: decodes the backup's chunk NBT for every selected chunk. Chunks absent from the backup but present in
	 * the world become deletions; chunks absent from both are skipped (so no empty region files are created).
	 */
	public static List<Write> prepare(Manifest manifest, BlobStore blobs, Path world, String dimFolder, ChunkSelection sel) throws IOException {
		List<Write> out = new ArrayList<>();
		String prefix = dimFolder.isEmpty() ? "" : dimFolder + "/";
		for (ChunkSelection.RegionPos rp : sel.regions()) {
			List<Integer> indices = sel.indicesIn(rp.x(), rp.z());
			for (Kind kind : Kind.values()) {
				String path = prefix + kind.folder + "/" + RegionFiles.regionFileName(rp.x(), rp.z());
				ManifestEntry entry = manifest.get(path);
				Map<Integer, byte[]> backupPayloads = new HashMap<>();
				if (entry instanceof RegionEntry r) {
					for (ChunkRef c : r.chunks()) if (indices.contains(c.index())) backupPayloads.put(c.index(), blobs.read(c.blob().hash()));
				} else if (entry instanceof FileEntry f) {
					Path tmp = java.nio.file.Files.createTempFile("cytrabackups-region", ".mca");
					try {
						ByteArrayOutputStream bos = new ByteArrayOutputStream();
						for (BlobRef piece : f.pieces()) bos.write(blobs.read(piece.hash()));
						java.nio.file.Files.write(tmp, bos.toByteArray());
						for (RegionFiles.ChunkSlot s : RegionFiles.readAll(tmp)) if (indices.contains(s.index())) backupPayloads.put(s.index(), s.payload());
					} finally {
						java.nio.file.Files.deleteIfExists(tmp);
					}
				}
				BitSet present = RegionFiles.presentIndices(FileUtil.resolveSafe(world, path));
				for (int idx : indices) {
					ChunkPos pos = new ChunkPos(RegionFiles.chunkX(rp.x(), idx), RegionFiles.chunkZ(rp.z(), idx));
					byte[] payload = backupPayloads.get(idx);
					if (payload != null) {
						out.add(new Write(kind, pos, decode(payload, manifest, blobs, prefix + kind.folder, pos)));
					} else if (present.get(idx)) {
						out.add(new Write(kind, pos, null));
					}
				}
			}
		}
		return out;
	}

	static CompoundTag decode(byte[] payload, Manifest manifest, BlobStore blobs, String dir, ChunkPos pos) throws IOException {
		int type = payload[0] & 0xFF;
		boolean external = (type & RegionFiles.EXTERNAL_FLAG) != 0;
		RegionFileVersion version = RegionFileVersion.fromId(type & 0x7F);
		if (version == null) throw new IOException("Chunk " + pos + " uses unknown compression " + (type & 0x7F));
		InputStream raw;
		if (external) {
			ManifestEntry mcc = manifest.get(dir + "/" + RegionFiles.externalChunkFileName(pos.x, pos.z));
			if (!(mcc instanceof FileEntry f)) throw new IOException("External chunk file for " + pos + " is missing from the backup");
			ByteArrayOutputStream bos = new ByteArrayOutputStream();
			for (BlobRef piece : f.pieces()) bos.write(blobs.read(piece.hash()));
			raw = new ByteArrayInputStream(bos.toByteArray());
		} else {
			raw = new ByteArrayInputStream(payload, 1, payload.length - 1);
		}
		try (DataInputStream in = new DataInputStream(version.wrap(raw))) {
			return NbtIo.read(in, NbtAccounter.unlimitedHeap());
		}
	}

	/** Server thread: writes through Minecraft's IO workers and evicts cached entity/POI state. */
	public static void apply(ServerLevel level, List<Write> writes) {
		ChunkMap cm = level.getChunkSource().chunkMap;
		PoiManager poi = level.getPoiManager();
		EntityStorage entityStorage = level.entityManager.permanentStorage instanceof EntityStorage es ? es : null;
		java.util.Set<ChunkPos> touched = new java.util.HashSet<>();
		for (Write w : writes) {
			storage(level, w.kind()).write(w.pos(), w.tag());
			touched.add(w.pos());
		}
		SectionStorage<?, ?> poiSections = poi;
		StructureCheck structures = level.structureCheck;
		for (ChunkPos pos : touched) {
			long k = pos.toLong();
			cm.chunkTypeCache.remove(k);
			// structure-start lookups for these chunks are re-read from the (restored) chunk data on next use
			structures.loadedChunks.remove(k);
			structures.featureChecks.values().forEach(checks -> checks.remove(k));
			if (entityStorage != null) entityStorage.emptyChunks.remove(k);
			synchronized (poiSections.loadLock) {
				poiSections.loadedChunks.remove(k);
				poiSections.pendingLoads.remove(k);
			}
			poiSections.dirtyChunks.remove(k);
			poi.loadedChunks.remove(k);
			for (int y = level.getMinSectionY(); y <= level.getMaxSectionY(); y++) {
				long sk = SectionPos.asLong(pos.x, y, pos.z);
				poiSections.storage.remove(sk);
				poiSections.onSectionLoad(sk);
			}
		}
	}
}
