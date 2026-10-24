package dev.steelaspect.cytrabackups.net;

import dev.steelaspect.cytrabackups.CytraBackups;
import net.fabricmc.fabric.api.networking.v1.FabricPacket;
import net.fabricmc.fabric.api.networking.v1.PacketType;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

/**
 * Server -> client: a command started from the GUI needs confirmation. The GUI shows a dialog and answers with
 * {@code /cbackup confirm <token>} or {@code /cbackup deny <token>}, exactly like clicking the chat links.
 */
public record PromptPayload(String token, String title, String details, int timeoutSeconds) implements FabricPacket {
	public static final PacketType<PromptPayload> TYPE = PacketType.create(new ResourceLocation(CytraBackups.MOD_ID, "v2/prompt"),
		buf -> new PromptPayload(buf.readUtf(64), buf.readUtf(1024), buf.readUtf(2048), buf.readVarInt()));

	@Override
	public void write(FriendlyByteBuf buf) {
		buf.writeUtf(token, 64);
		buf.writeUtf(clip(title, 1024), 1024);
		buf.writeUtf(clip(details, 2048), 2048);
		buf.writeVarInt(timeoutSeconds);
	}

	private static String clip(String s, int max) {
		return s.length() <= max ? s : s.substring(0, max);
	}

	@Override
	public PacketType<?> getType() {
		return TYPE;
	}
}
