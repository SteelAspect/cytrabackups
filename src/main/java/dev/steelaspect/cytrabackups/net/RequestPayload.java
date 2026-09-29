package dev.steelaspect.cytrabackups.net;

import dev.steelaspect.cytrabackups.CytraBackups;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Client -> server: asks for GUI data. Actions themselves go through {@link CommandPayload}. */
public record RequestPayload(String action) implements CustomPacketPayload {
	public static final Type<RequestPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(CytraBackups.MOD_ID, "v2/request"));
	public static final StreamCodec<RegistryFriendlyByteBuf, RequestPayload> CODEC = CustomPacketPayload.codec(
		(p, buf) -> buf.writeUtf(p.action, 32), buf -> new RequestPayload(buf.readUtf(32)));

	/** Backup list, job status and permissions ({@link BackupListPayload}). */
	public static final String LIST = "list";
	/** All settings for the editor ({@link ConfigPayload}); needs cytrabackups.admin. */
	public static final String CONFIG = "config";

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
