package dev.steelaspect.cytrabackups.net;

import dev.steelaspect.cytrabackups.CytraBackups;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Server -> client: a result message shown inside the GUI (the same text is also sent to chat). */
public record MessagePayload(String text, boolean error) implements CustomPacketPayload {
	public static final Type<MessagePayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(CytraBackups.MOD_ID, "message"));
	public static final StreamCodec<RegistryFriendlyByteBuf, MessagePayload> CODEC = CustomPacketPayload.codec(MessagePayload::write,
		buf -> new MessagePayload(buf.readUtf(2048), buf.readBoolean()));

	private void write(RegistryFriendlyByteBuf buf) {
		buf.writeUtf(text.length() > 2048 ? text.substring(0, 2048) : text, 2048);
		buf.writeBoolean(error);
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
