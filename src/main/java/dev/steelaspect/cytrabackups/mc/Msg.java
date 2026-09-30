package dev.steelaspect.cytrabackups.mc;

import dev.steelaspect.cytrabackups.core.Formatting;
import dev.steelaspect.cytrabackups.core.Lang;
import java.time.ZoneId;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentUtils;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

/**
 * Chat messages in one colour scheme: gold accent, grey for details, green for success, red for errors and
 * destructive buttons. Texts are translatable with the English text as fallback, because vanilla clients do not have
 * the mod's language file.
 */
public final class Msg {
	public static final ChatFormatting ACCENT = ChatFormatting.GOLD;

	private Msg() {
	}

	public static MutableComponent tr(String key, Object... args) {
		Object[] safe = new Object[args.length];
		for (int i = 0; i < args.length; i++) safe[i] = args[i] instanceof Component c ? c : String.valueOf(args[i]);
		return Component.translatableWithFallback(key, Lang.raw(key), safe);
	}

	private static MutableComponent prefixed(MutableComponent body) {
		return tr("cytrabackups.chat.prefix").withStyle(ACCENT).append(" ").append(body);
	}

	public static MutableComponent info(String key, Object... args) {
		return prefixed(tr(key, args).withStyle(ChatFormatting.WHITE));
	}

	public static MutableComponent success(String key, Object... args) {
		return prefixed(tr(key, args).withStyle(ChatFormatting.GREEN));
	}

	public static MutableComponent error(String key, Object... args) {
		return prefixed(tr(key, args).withStyle(ChatFormatting.RED));
	}

	/** A secondary line under a message, without the prefix. */
	public static MutableComponent detail(String key, Object... args) {
		return tr(key, args).withStyle(ChatFormatting.GRAY);
	}

	public static MutableComponent button(String labelKey, String command, String hoverKey, Object... hoverArgs) {
		return button(labelKey, new ClickEvent.RunCommand(command), ACCENT, hoverKey, hoverArgs);
	}

	/** Red button for restore, delete and other actions that change or remove data (they still ask to confirm). */
	public static MutableComponent dangerButton(String labelKey, String command, String hoverKey, Object... hoverArgs) {
		return button(labelKey, new ClickEvent.RunCommand(command), ChatFormatting.RED, hoverKey, hoverArgs);
	}

	/** Button that puts a command into the chat box to be completed. */
	public static MutableComponent suggestButton(String labelKey, String command, String hoverKey, Object... hoverArgs) {
		return button(labelKey, new ClickEvent.SuggestCommand(command), ACCENT, hoverKey, hoverArgs);
	}

	private static MutableComponent button(String labelKey, ClickEvent click, ChatFormatting color, String hoverKey, Object... hoverArgs) {
		Component hover = tr(hoverKey, hoverArgs);
		return ComponentUtils.wrapInSquareBrackets(tr(labelKey)).withStyle(s -> s.withColor(color).withClickEvent(click)
			.withHoverEvent(new HoverEvent.ShowText(hover)));
	}

	/** A disabled button: grey, not clickable. */
	public static MutableComponent inactiveButton(String labelKey) {
		return ComponentUtils.wrapInSquareBrackets(tr(labelKey)).withStyle(ChatFormatting.DARK_GRAY);
	}

	/** "2h ago", with the exact time on hover. */
	public static MutableComponent ago(long millis, ZoneId zone) {
		return Component.literal(Formatting.ago(millis, System.currentTimeMillis()))
			.withStyle(s -> s.withHoverEvent(new HoverEvent.ShowText(Component.literal(Formatting.dateTime(millis, zone)))));
	}

	public static MutableComponent hover(MutableComponent c, Component text) {
		return c.withStyle(s -> s.withHoverEvent(new HoverEvent.ShowText(text)));
	}

	public static String command(Object... parts) {
		StringBuilder sb = new StringBuilder("/").append(BackupCommands.ROOT);
		for (Object p : parts) sb.append(' ').append(p);
		return sb.toString();
	}
}
