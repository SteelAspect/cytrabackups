package dev.steelaspect.cytrabackups.mc;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

/** Chat component helpers (vanilla-client friendly: plain text + click/hover events). */
public final class Msg {
	private Msg() {
	}

	public static MutableComponent prefix() {
		return Component.literal("[CytraBackups] ").withStyle(ChatFormatting.GOLD);
	}

	public static MutableComponent info(String text) {
		return prefix().append(Component.literal(text).withStyle(ChatFormatting.GRAY));
	}

	public static MutableComponent success(String text) {
		return prefix().append(Component.literal(text).withStyle(ChatFormatting.GREEN));
	}

	public static MutableComponent warn(String text) {
		return prefix().append(Component.literal(text).withStyle(ChatFormatting.YELLOW));
	}

	public static MutableComponent error(String text) {
		return prefix().append(Component.literal(text).withStyle(ChatFormatting.RED));
	}

	public static MutableComponent text(String text, ChatFormatting... fmt) {
		return Component.literal(text).withStyle(fmt);
	}

	/** Clickable [label] that runs a command immediately. */
	public static MutableComponent run(String label, String command, String hover, ChatFormatting color) {
		return Component.literal("[" + label + "]").withStyle(s -> s.withColor(color).withBold(true)
			.withClickEvent(new ClickEvent.RunCommand(command))
			.withHoverEvent(new HoverEvent.ShowText(Component.literal(hover))));
	}

	/** Clickable [label] that puts a command into the chat box for editing. */
	public static MutableComponent suggest(String label, String command, String hover, ChatFormatting color) {
		return Component.literal("[" + label + "]").withStyle(s -> s.withColor(color)
			.withClickEvent(new ClickEvent.SuggestCommand(command))
			.withHoverEvent(new HoverEvent.ShowText(Component.literal(hover))));
	}

	public static MutableComponent hover(MutableComponent c, Component hover) {
		return c.withStyle(s -> s.withHoverEvent(new HoverEvent.ShowText(hover)));
	}

	public static MutableComponent click(MutableComponent c, String command, Component hover) {
		return c.withStyle(s -> s.withClickEvent(new ClickEvent.RunCommand(command)).withHoverEvent(new HoverEvent.ShowText(hover)));
	}
}
