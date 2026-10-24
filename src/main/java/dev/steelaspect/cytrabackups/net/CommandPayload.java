package dev.steelaspect.cytrabackups.net;

import dev.steelaspect.cytrabackups.CytraBackups;
import net.fabricmc.fabric.api.networking.v1.FabricPacket;
import net.fabricmc.fabric.api.networking.v1.PacketType;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

/**
 * Client -> server: a GUI button ran a {@code /cbackup ...} command. The server executes it as the player through the
 * normal command tree, so permissions and behaviour are identical to typing it, and sends the output back to the GUI.
 */
public record CommandPayload(String command) implements FabricPacket {
	public static final int MAX_LENGTH = 1024;
	public static final PacketType<CommandPayload> TYPE = PacketType.create(new ResourceLocation(CytraBackups.MOD_ID, "v2/command"), buf -> new CommandPayload(buf.readUtf(MAX_LENGTH)));

	@Override
	public void write(FriendlyByteBuf buf) {
		buf.writeUtf(this.command, MAX_LENGTH);
	}

	@Override
	public PacketType<?> getType() {
		return TYPE;
	}
}
