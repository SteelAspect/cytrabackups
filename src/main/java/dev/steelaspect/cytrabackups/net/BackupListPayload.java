package dev.steelaspect.cytrabackups.net;

import dev.steelaspect.cytrabackups.CytraBackups;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Server -> client: backup list, job status and what the player may do. */
public record BackupListPayload(List<Entry> entries, String status, String jobPhase, float jobProgress, int permissions, int viewDistance)
	implements CustomPacketPayload {
	public static final Type<BackupListPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(CytraBackups.MOD_ID, "list"));
	public static final StreamCodec<RegistryFriendlyByteBuf, BackupListPayload> CODEC = CustomPacketPayload.codec(BackupListPayload::write, BackupListPayload::read);

	public static final int CAN_CREATE = 1;
	public static final int CAN_RESTORE = 2;
	public static final int CAN_DELETE = 4;
	public static final int CAN_PIN = 8;
	public static final int CAN_COMMENT = 16;

	public record Entry(int id, long createdAt, String trigger, String comment, String creator, long totalSize, long newBytes,
						boolean pinned, boolean partial) {
	}

	public boolean can(int flag) {
		return (permissions & flag) != 0;
	}

	private static BackupListPayload read(RegistryFriendlyByteBuf buf) {
		int n = buf.readVarInt();
		List<Entry> list = new ArrayList<>(Math.min(n, 4096));
		for (int i = 0; i < n; i++) {
			list.add(new Entry(buf.readVarInt(), buf.readLong(), buf.readUtf(32), buf.readUtf(512), buf.readUtf(64), buf.readVarLong(),
				buf.readVarLong(), buf.readBoolean(), buf.readBoolean()));
		}
		return new BackupListPayload(list, buf.readUtf(1024), buf.readUtf(256), buf.readFloat(), buf.readVarInt(), buf.readVarInt());
	}

	private void write(RegistryFriendlyByteBuf buf) {
		buf.writeVarInt(entries.size());
		for (Entry e : entries) {
			buf.writeVarInt(e.id());
			buf.writeLong(e.createdAt());
			buf.writeUtf(e.trigger(), 32);
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
	}

	private static String trim(String s, int max) {
		if (s == null) return "";
		return s.length() <= max ? s : s.substring(0, max);
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
