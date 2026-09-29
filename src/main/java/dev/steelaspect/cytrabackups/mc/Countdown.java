package dev.steelaspect.cytrabackups.mc;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** Broadcast countdown before a restore stops the server. Server thread only. */
final class Countdown {
	private final int totalSeconds;
	private final String label;
	private final Runnable onFinish;
	private int ticks;

	Countdown(int seconds, String label, Runnable onFinish) {
		this.totalSeconds = Math.max(0, seconds);
		this.label = label;
		this.onFinish = onFinish;
		this.ticks = totalSeconds * 20;
	}

	String label() {
		return label;
	}

	int secondsLeft() {
		return (ticks + 19) / 20;
	}

	/** Returns true once finished (the finish action has run). */
	boolean tick(MinecraftServer server) {
		if (ticks <= 0) {
			onFinish.run();
			return true;
		}
		if (ticks % 20 == 0) {
			int s = ticks / 20;
			Component bar = Component.literal(label + " — server stops in " + s + "s").withStyle(ChatFormatting.RED, ChatFormatting.BOLD);
			for (ServerPlayer p : server.getPlayerList().getPlayers()) p.sendSystemMessage(bar, true);
			if (s == totalSeconds || s == 60 || s == 30 || s == 20 || s == 10 || s <= 5) {
				server.getPlayerList().broadcastSystemMessage(Msg.warn(label + ": the server stops in " + s + " second" + (s == 1 ? "" : "s") + "."), false);
			}
		}
		ticks--;
		return false;
	}
}
