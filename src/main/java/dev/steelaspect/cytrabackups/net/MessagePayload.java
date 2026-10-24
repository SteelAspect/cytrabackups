package dev.steelaspect.cytrabackups.net;

import dev.steelaspect.cytrabackups.CytraBackups;
import net.fabricmc.fabric.api.networking.v1.FabricPacket;
import net.fabricmc.fabric.api.networking.v1.PacketType;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/** Server -> client: a message for the GUI's output panel, with colours and click/hover events intact. */
public record MessagePayload(Component message, boolean error) implements FabricPacket {
	public static final PacketType<MessagePayload> TYPE = PacketType.create(new ResourceLocation(CytraBackups.MOD_ID, "v2/message"),
		buf -> new MessagePayload(buf.readComponentTrusted(), buf.readBoolean()));

	@Override
	public void write(FriendlyByteBuf buf) {
		buf.writeComponent(message);
		buf.writeBoolean(error);
	}

	@Override
	public PacketType<?> getType() {
		return TYPE;
	}
}
