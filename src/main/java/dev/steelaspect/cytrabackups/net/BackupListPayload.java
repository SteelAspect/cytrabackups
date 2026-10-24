package dev.steelaspect.cytrabackups.net;

import dev.steelaspect.cytrabackups.CytraBackups;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.networking.v1.FabricPacket;
import net.fabricmc.fabric.api.networking.v1.PacketType;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

/** Server -> client: backup list, job status, dimensions and what the player may do. */
public record BackupListPayload(List<Entry> entries, String status, String jobPhase, float jobProgress, int permissions, int viewDistance,
								List<String> dimensions) implements FabricPacket {
	public static final PacketType<BackupListPayload> TYPE = PacketType.create(new ResourceLocation(CytraBackups.MOD_ID, "v2/list"), BackupListPayload::read);

	public static final int CAN_CREATE = 1;
	public static final int CAN_RESTORE = 2;
	public static final int CAN_DELETE = 4;
	public static final int CAN_PIN = 8;
	public static final int CAN_COMMENT = 16;
	public static final int CAN_VERIFY = 32;
	public static final int CAN_EXPORT = 64;
	public static final int CAN_PRUNE = 128;
	public static final int CAN_CANCEL = 256;
	public static final int CAN_ADMIN = 512;
	public static final int CAN_LIST = 1024;

	public record Entry(int id, long createdAt, String trigger, String comment, String creator, long totalSize, long newBytes,
						boolean pinned, boolean partial) {
	}

	public boolean can(int flag) {
		return (permissions & flag) != 0;
	}

	private static BackupListPayload read(FriendlyByteBuf buf) {
		int n = buf.readVarInt();
		List<Entry> list = new ArrayList<>(Math.min(n, 4096));
		for (int i = 0; i < n; i++) {
			list.add(new Entry(buf.readVarInt(), buf.readLong(), buf.readUtf(32), buf.readUtf(512), buf.readUtf(64), buf.readVarLong(),
				buf.readVarLong(), buf.readBoolean(), buf.readBoolean()));
		}
		String status = buf.readUtf(1024);
		String phase = buf.readUtf(256);
		float progress = buf.readFloat();
		int perms = buf.readVarInt();
		int view = buf.readVarInt();
		int d = buf.readVarInt();
		List<String> dims = new ArrayList<>(Math.min(d, 256));
		for (int i = 0; i < d; i++) dims.add(buf.readUtf(256));
		return new BackupListPayload(list, status, phase, progress, perms, view, dims);
	}

	@Override
	public void write(FriendlyByteBuf buf) {
		buf.writeVarInt(entries.size());
		for (Entry e : entries) {
			buf.writeVarInt(e.id());
			buf.writeLong(e.createdAt());
			buf.writeUtf(trim(e.trigger(), 32), 32);
			buf.writeUtf(trim(e.comment(), 512), 512);
			buf.writeUtf(trim(e.creator(), 64), 64);
			buf.writeVarLong(e.totalSize());
			buf.writeVarLong(e.newBytes());
			buf.writeBoolean(e.pinned());
			buf.writeBoolean(e.partial());
		}
		buf.writeUtf(trim(status, 1024), 1024);
		buf.writeUtf(trim(jobPhase, 256), 256);
		buf.writeFloat(jobProgress);
		buf.writeVarInt(permissions);
		buf.writeVarInt(viewDistance);
		buf.writeVarInt(dimensions.size());
		for (String d : dimensions) buf.writeUtf(trim(d, 256), 256);
	}

	private static String trim(String s, int max) {
		if (s == null) return "";
		return s.length() <= max ? s : s.substring(0, max);
	}

	@Override
	public PacketType<?> getType() {
		return TYPE;
	}
}
