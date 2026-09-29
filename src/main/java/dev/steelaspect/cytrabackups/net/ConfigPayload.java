package dev.steelaspect.cytrabackups.net;

import dev.steelaspect.cytrabackups.CytraBackups;
import dev.steelaspect.cytrabackups.core.config.ConfigSchema;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Server -> client: every setting for the editor (secrets masked), plus problems from the last save attempt. */
public record ConfigPayload(List<ConfigSchema.Entry> entries, List<String> problems) implements CustomPacketPayload {
	public static final Type<ConfigPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(CytraBackups.MOD_ID, "v2/config"));
	public static final StreamCodec<RegistryFriendlyByteBuf, ConfigPayload> CODEC = CustomPacketPayload.codec(ConfigPayload::write, ConfigPayload::read);

	private void write(RegistryFriendlyByteBuf buf) {
		buf.writeVarInt(entries.size());
		for (ConfigSchema.Entry e : entries) {
			buf.writeUtf(e.path(), 256);
			buf.writeEnum(e.type());
			buf.writeUtf(e.value(), 8192);
			buf.writeUtf(e.comment(), 2048);
			buf.writeVarInt(e.choices().size());
			for (String c : e.choices()) buf.writeUtf(c, 64);
			buf.writeBoolean(e.set());
		}
		buf.writeVarInt(problems.size());
		for (String p : problems) buf.writeUtf(p.length() > 1024 ? p.substring(0, 1024) : p, 1024);
	}

	private static ConfigPayload read(RegistryFriendlyByteBuf buf) {
		int n = buf.readVarInt();
		List<ConfigSchema.Entry> entries = new ArrayList<>(Math.min(n, 1024));
		for (int i = 0; i < n; i++) {
			String path = buf.readUtf(256);
			ConfigSchema.Type type = buf.readEnum(ConfigSchema.Type.class);
			String value = buf.readUtf(8192);
			String comment = buf.readUtf(2048);
			int c = buf.readVarInt();
			List<String> choices = new ArrayList<>(Math.min(c, 64));
			for (int j = 0; j < c; j++) choices.add(buf.readUtf(64));
			entries.add(new ConfigSchema.Entry(path, type, value, comment, choices, buf.readBoolean()));
		}
		int p = buf.readVarInt();
		List<String> problems = new ArrayList<>(Math.min(p, 256));
		for (int i = 0; i < p; i++) problems.add(buf.readUtf(1024));
		return new ConfigPayload(entries, problems);
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
