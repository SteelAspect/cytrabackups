package dev.steelaspect.cytrabackups.mc;

import dev.steelaspect.cytrabackups.CytraBackups;
import dev.steelaspect.cytrabackups.net.CytraNetworking;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** Where job messages go. Always marshals to the server thread; safe to call from worker threads. */
public interface Feedback {
	void send(Component message, boolean error);

	default void info(String text) {
		send(Msg.info(text), false);
	}

	default void success(String text) {
		send(Msg.success(text), false);
	}

	default void warn(String text) {
		send(Msg.warn(text), false);
	}

	default void error(String text) {
		send(Msg.error(text), true);
	}

	static Feedback of(CommandSourceStack source) {
		MinecraftServer server = source.getServer();
		ServerPlayer player = source.getPlayer();
		return (message, error) -> server.execute(() -> {
			if (player != null) {
				if (!player.hasDisconnected()) {
					player.sendSystemMessage(message);
					CytraNetworking.sendMessage(player, message, error);
				}
			} else if (error) {
				source.sendFailure(message);
			} else {
				source.sendSuccess(() -> message, false);
			}
		});
	}

	static Feedback console() {
		return (message, error) -> {
			if (error) CytraBackups.LOGGER.warn(message.getString());
			else CytraBackups.LOGGER.info(message.getString());
		};
	}

	/** Sends to both: e.g. the requesting player and the console log. */
	default Feedback and(Feedback other) {
		Feedback self = this;
		return (message, error) -> {
			self.send(message, error);
			other.send(message, error);
		};
	}
}
