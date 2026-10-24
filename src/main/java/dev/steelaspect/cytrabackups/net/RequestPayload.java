package dev.steelaspect.cytrabackups.net;

import dev.steelaspect.cytrabackups.CytraBackups;
import net.fabricmc.fabric.api.networking.v1.FabricPacket;
import net.fabricmc.fabric.api.networking.v1.PacketType;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

/** Client -> server: asks for GUI data. Actions themselves go through {@link CommandPayload}. */
public record RequestPayload(String action) implements FabricPacket {
	public static final PacketType<RequestPayload> TYPE = PacketType.create(new ResourceLocation(CytraBackups.MOD_ID, "v2/request"), buf -> new RequestPayload(buf.readUtf(32)));

	@Override
	public void write(FriendlyByteBuf buf) {
		buf.writeUtf(this.action, 32);
	}

	/** Backup list, job status and permissions ({@link BackupListPayload}). */
	public static final String LIST = "list";
	/** All settings for the editor ({@link ConfigPayload}); needs cytrabackups.admin. */
	public static final String CONFIG = "config";

	@Override
	public PacketType<?> getType() {
		return TYPE;
	}
}
