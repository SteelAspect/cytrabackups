package dev.steelaspect.cytrabackups.net;

import dev.steelaspect.cytrabackups.CytraBackups;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Server -> client: a message for the GUI's output panel, with colours and click/hover events intact. */
public record MessagePayload(Component message, boolean error) implements CustomPacketPayload {
	public static final Type<MessagePayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(CytraBackups.MOD_ID, "v2/message"));
	public static final StreamCodec<RegistryFriendlyByteBuf, MessagePayload> CODEC = CustomPacketPayload.codec(
		(p, buf) -> {
			ComponentSerialization.TRUSTED_STREAM_CODEC.encode(buf, p.message);
			buf.writeBoolean(p.error);
		},
		buf -> new MessagePayload(ComponentSerialization.TRUSTED_STREAM_CODEC.decode(buf), buf.readBoolean()));

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
