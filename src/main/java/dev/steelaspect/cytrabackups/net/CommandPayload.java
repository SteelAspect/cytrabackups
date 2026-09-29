package dev.steelaspect.cytrabackups.net;

import dev.steelaspect.cytrabackups.CytraBackups;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client -> server: a GUI button ran a {@code /backup ...} command. The server executes it as the player through the
 * normal command tree, so permissions and behaviour are identical to typing it, and sends the output back to the GUI.
 */
public record CommandPayload(String command) implements CustomPacketPayload {
	public static final int MAX_LENGTH = 1024;
	public static final Type<CommandPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(CytraBackups.MOD_ID, "v2/command"));
	public static final StreamCodec<RegistryFriendlyByteBuf, CommandPayload> CODEC = CustomPacketPayload.codec(
		(p, buf) -> buf.writeUtf(p.command, MAX_LENGTH), buf -> new CommandPayload(buf.readUtf(MAX_LENGTH)));

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
