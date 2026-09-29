package dev.steelaspect.cytrabackups.net;

import dev.steelaspect.cytrabackups.CytraBackups;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Server -> client: a command started from the GUI needs confirmation. The GUI shows a dialog and answers with
 * {@code /backup confirm <token>} or {@code /backup deny <token>}, exactly like clicking the chat links.
 */
public record PromptPayload(String token, String title, String details, int timeoutSeconds) implements CustomPacketPayload {
	public static final Type<PromptPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(CytraBackups.MOD_ID, "v2/prompt"));
	public static final StreamCodec<RegistryFriendlyByteBuf, PromptPayload> CODEC = CustomPacketPayload.codec(
		(p, buf) -> {
			buf.writeUtf(p.token, 64);
			buf.writeUtf(clip(p.title, 1024), 1024);
			buf.writeUtf(clip(p.details, 2048), 2048);
			buf.writeVarInt(p.timeoutSeconds);
		},
		buf -> new PromptPayload(buf.readUtf(64), buf.readUtf(1024), buf.readUtf(2048), buf.readVarInt()));

	private static String clip(String s, int max) {
		return s.length() <= max ? s : s.substring(0, max);
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
