package dev.steelaspect.cytrabackups.net;

import dev.steelaspect.cytrabackups.CytraBackups;
import dev.steelaspect.cytrabackups.core.config.ConfigSchema;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.networking.v1.FabricPacket;
import net.fabricmc.fabric.api.networking.v1.PacketType;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

/** Server -> client: every setting for the editor (secrets masked), plus problems from the last save attempt. */
public record ConfigPayload(List<ConfigSchema.Entry> entries, List<String> problems) implements FabricPacket {
	public static final PacketType<ConfigPayload> TYPE = PacketType.create(new ResourceLocation(CytraBackups.MOD_ID, "v2/config"), ConfigPayload::read);

	@Override
	public void write(FriendlyByteBuf buf) {
		buf.writeVarInt(entries.size());
		for (ConfigSchema.Entry e : entries) {
			buf.writeUtf(e.path(), 256);
			buf.writeEnum(e.type());
			buf.writeUtf(e.value(), 8192);
			buf.writeVarInt(e.choices().size());
			for (String c : e.choices()) buf.writeUtf(c, 64);
			buf.writeBoolean(e.set());
		}
		buf.writeVarInt(problems.size());
		for (String p : problems) buf.writeUtf(p.length() > 1024 ? p.substring(0, 1024) : p, 1024);
	}

	private static ConfigPayload read(FriendlyByteBuf buf) {
		int n = buf.readVarInt();
		List<ConfigSchema.Entry> entries = new ArrayList<>(Math.min(n, 1024));
		for (int i = 0; i < n; i++) {
			String path = buf.readUtf(256);
			ConfigSchema.Type type = buf.readEnum(ConfigSchema.Type.class);
			String value = buf.readUtf(8192);
			int c = buf.readVarInt();
			List<String> choices = new ArrayList<>(Math.min(c, 64));
			for (int j = 0; j < c; j++) choices.add(buf.readUtf(64));
			entries.add(new ConfigSchema.Entry(path, type, value, choices, buf.readBoolean()));
		}
		int p = buf.readVarInt();
		List<String> problems = new ArrayList<>(Math.min(p, 256));
		for (int i = 0; i < p; i++) problems.add(buf.readUtf(1024));
		return new ConfigPayload(entries, problems);
	}

	@Override
	public PacketType<?> getType() {
		return TYPE;
	}
}
