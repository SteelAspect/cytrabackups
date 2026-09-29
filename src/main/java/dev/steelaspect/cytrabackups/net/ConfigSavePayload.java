package dev.steelaspect.cytrabackups.net;

import dev.steelaspect.cytrabackups.CytraBackups;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Client -> server: changed settings ({@code path -> text}); the server validates, saves the config file and reloads. */
public record ConfigSavePayload(Map<String, String> changes) implements CustomPacketPayload {
	public static final Type<ConfigSavePayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(CytraBackups.MOD_ID, "v2/config_save"));
	public static final StreamCodec<RegistryFriendlyByteBuf, ConfigSavePayload> CODEC = CustomPacketPayload.codec(ConfigSavePayload::write, ConfigSavePayload::read);

	private void write(RegistryFriendlyByteBuf buf) {
		buf.writeVarInt(changes.size());
		changes.forEach((k, v) -> {
			buf.writeUtf(k, 256);
			buf.writeUtf(v, 8192);
		});
	}

	private static ConfigSavePayload read(RegistryFriendlyByteBuf buf) {
		int n = buf.readVarInt();
		if (n > 1024) throw new IllegalArgumentException("too many settings");
		Map<String, String> m = new LinkedHashMap<>();
		for (int i = 0; i < n; i++) m.put(buf.readUtf(256), buf.readUtf(8192));
		return new ConfigSavePayload(m);
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
