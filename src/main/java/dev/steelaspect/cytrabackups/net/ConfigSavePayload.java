package dev.steelaspect.cytrabackups.net;

import dev.steelaspect.cytrabackups.CytraBackups;
import java.util.LinkedHashMap;
import java.util.Map;
import net.fabricmc.fabric.api.networking.v1.FabricPacket;
import net.fabricmc.fabric.api.networking.v1.PacketType;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

/** Client -> server: changed settings ({@code path -> text}); the server validates, saves the config file and reloads. */
public record ConfigSavePayload(Map<String, String> changes) implements FabricPacket {
	public static final PacketType<ConfigSavePayload> TYPE = PacketType.create(new ResourceLocation(CytraBackups.MOD_ID, "v2/config_save"), ConfigSavePayload::read);

	@Override
	public void write(FriendlyByteBuf buf) {
		buf.writeVarInt(changes.size());
		changes.forEach((k, v) -> {
			buf.writeUtf(k, 256);
			buf.writeUtf(v, 8192);
		});
	}

	private static ConfigSavePayload read(FriendlyByteBuf buf) {
		int n = buf.readVarInt();
		if (n > 1024) throw new IllegalArgumentException("too many settings");
		Map<String, String> m = new LinkedHashMap<>();
		for (int i = 0; i < n; i++) m.put(buf.readUtf(256), buf.readUtf(8192));
		return new ConfigSavePayload(m);
	}

	@Override
	public PacketType<?> getType() {
		return TYPE;
	}
}
