package dev.steelaspect.cytrabackups.net;

import dev.steelaspect.cytrabackups.CytraBackups;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client -> server request from the optional GUI. The server re-checks permissions for every action, exactly like
 * the equivalent command.
 */
public record RequestPayload(String action, int id, String text, String dimension, int x1, int z1, int x2, int z2) implements CustomPacketPayload {
	public static final Type<RequestPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(CytraBackups.MOD_ID, "request"));
	public static final StreamCodec<RegistryFriendlyByteBuf, RequestPayload> CODEC = CustomPacketPayload.codec(RequestPayload::write, RequestPayload::new);

	public static final String LIST = "list";
	public static final String CREATE = "create";
	public static final String RESTORE = "restore";
	public static final String RESTORE_CHUNKS = "restore_chunks";
	public static final String DELETE = "delete";
	public static final String PIN = "pin";
	public static final String UNPIN = "unpin";
	public static final String COMMENT = "comment";
	public static final String VERIFY = "verify";

	private RequestPayload(RegistryFriendlyByteBuf buf) {
		this(buf.readUtf(32), buf.readVarInt(), buf.readUtf(512), buf.readUtf(256), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
	}

	public static RequestPayload simple(String action, int id) {
		return new RequestPayload(action, id, "", "", 0, 0, 0, 0);
	}

	private void write(RegistryFriendlyByteBuf buf) {
		buf.writeUtf(action, 32);
		buf.writeVarInt(id);
		buf.writeUtf(text, 512);
		buf.writeUtf(dimension, 256);
		buf.writeVarInt(x1);
		buf.writeVarInt(z1);
		buf.writeVarInt(x2);
		buf.writeVarInt(z2);
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
