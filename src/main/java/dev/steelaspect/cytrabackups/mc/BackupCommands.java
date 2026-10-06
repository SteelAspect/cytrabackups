package dev.steelaspect.cytrabackups.mc;

import static com.mojang.brigadier.arguments.IntegerArgumentType.getInteger;
import static com.mojang.brigadier.arguments.IntegerArgumentType.integer;
import static com.mojang.brigadier.arguments.StringArgumentType.getString;
import static com.mojang.brigadier.arguments.StringArgumentType.greedyString;
import static com.mojang.brigadier.arguments.StringArgumentType.word;
import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.tree.LiteralCommandNode;
import dev.steelaspect.cytrabackups.core.Formatting;
import dev.steelaspect.cytrabackups.core.Lang;
import dev.steelaspect.cytrabackups.core.backup.BackupMeta;
import dev.steelaspect.cytrabackups.core.backup.ChunkSelection;
import dev.steelaspect.cytrabackups.core.backup.Trigger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.DimensionArgument;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;

/** The /cbackup command tree (alias /cb). Every feature is usable from a vanilla client via commands and clickable chat. */
public final class BackupCommands {
	public static final String ROOT = "cbackup";
	public static final String ALIAS = "cb";
	private static final int PAGE_SIZE = 8;
	private static final String[][] HELP = {
		{"create [comment]", "create"}, {"list [page]", "list"}, {"info <id>", "info"}, {"diff <id1> <id2>", "diff"},
		{"restore <id>", "restore"}, {"restore <id> radius <r>", "radius"}, {"restore <id> chunks <dim> <x1> <z1> <x2> <z2>", "chunks"},
		{"restore <id> region <dim> <rx> <rz>", "region"}, {"preview <id> radius <r>", "preview"}, {"preview clear", "preview_clear"}, {"rollback", "rollback"}, {"pending [cancel|apply]", "pending"},
		{"comment <id> [text]", "comment"}, {"pin <id>", "pin"}, {"unpin <id>", "unpin"}, {"delete <id>", "delete"},
		{"verify <id>", "verify"}, {"export <id>", "export"}, {"import <path> [comment]", "import"}, {"prune [dryrun]", "prune"},
		{"gc", "gc"}, {"status", "status"}, {"cancel", "cancel"}, {"reload", "reload"}, {"offsite [status|sync|list]", "offsite"}, {"offsite fetch <id>", "fetch"},
	};

	private BackupCommands() {
	}

	private static final SuggestionProvider<CommandSourceStack> BACKUP_IDS = (ctx, builder) -> {
		BackupManager m = BackupManager.getOrNull();
		if (m != null) {
			List<BackupMeta> list = m.services().repo.list();
			for (int i = list.size() - 1; i >= 0 && list.size() - i <= 50; i--) {
				BackupMeta b = list.get(i);
				builder.suggest(b.id, Component.literal(Formatting.dateTimeShort(b.createdAt, m.zone()) + " " + b.trigger.displayName()
					+ (b.comment.isBlank() ? "" : " " + b.comment)));
			}
		}
		return builder.buildFuture();
	};

	private static RequiredArgumentBuilder<CommandSourceStack, Integer> id(String name) {
		return argument(name, integer(1)).suggests(BACKUP_IDS);
	}

	private static BackupManager manager() {
		return BackupManager.get();
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext ctx, Commands.CommandSelection selection) {
		var root = literal(ROOT).requires(src -> BackupManager.getOrNull() != null && Perms.any(src));

		root.then(literal("create").requires(Perms.require(Perms.CREATE))
			.executes(c -> create(c, ""))
			.then(argument("comment", greedyString()).executes(c -> create(c, getString(c, "comment")))));

		root.then(literal("list").requires(Perms.require(Perms.LIST))
			.executes(c -> list(c.getSource(), 1))
			.then(argument("page", integer(1)).executes(c -> list(c.getSource(), getInteger(c, "page")))));

		root.then(literal("info").requires(Perms.require(Perms.LIST))
			.then(id("id").executes(c -> info(c.getSource(), getInteger(c, "id")))));

		root.then(literal("diff").requires(Perms.require(Perms.LIST))
			.then(id("from").then(id("to").executes(c -> {
				manager().diff(getInteger(c, "from"), getInteger(c, "to"), Feedback.of(c.getSource()));
				return 1;
			}))));

		root.then(literal("comment").requires(Perms.require(Perms.COMMENT))
			.then(id("id")
				.executes(c -> {
					manager().setComment(getInteger(c, "id"), "", Feedback.of(c.getSource()));
					return 1;
				})
				.then(argument("text", greedyString()).executes(c -> {
					manager().setComment(getInteger(c, "id"), getString(c, "text"), Feedback.of(c.getSource()));
					return 1;
				}))));

		root.then(literal("pin").requires(Perms.require(Perms.PIN))
			.then(id("id").executes(c -> {
				manager().setPinned(getInteger(c, "id"), true, Feedback.of(c.getSource()));
				return 1;
			})));
		root.then(literal("unpin").requires(Perms.require(Perms.PIN))
			.then(id("id").executes(c -> {
				manager().setPinned(getInteger(c, "id"), false, Feedback.of(c.getSource()));
				return 1;
			})));

		root.then(literal("delete").requires(Perms.require(Perms.DELETE))
			.then(id("id").executes(c -> {
				manager().requestDelete(getInteger(c, "id"), c.getSource());
				return 1;
			})));

		var chunksArg = literal("chunks").then(argument("dimension", DimensionArgument.dimension())
			.then(argument("x1", integer()).then(argument("z1", integer()).then(argument("x2", integer()).then(argument("z2", integer())
				.executes(c -> chunks(c, ChunkSelection.box(getInteger(c, "x1"), getInteger(c, "z1"), getInteger(c, "x2"), getInteger(c, "z2")),
					DimensionArgument.getDimension(c, "dimension"))))))));
		var regionArg = literal("region").then(argument("dimension", DimensionArgument.dimension())
			.then(argument("rx", integer()).then(argument("rz", integer())
				.executes(c -> chunks(c, ChunkSelection.region(getInteger(c, "rx"), getInteger(c, "rz")), DimensionArgument.getDimension(c, "dimension"))))));
		var radiusArg = literal("radius").then(argument("radius", integer(0, 32)).executes(c -> {
			ServerPlayer p = c.getSource().getPlayerOrException();
			ChunkPos cp = p.chunkPosition();
			return chunks(c, ChunkSelection.radius(cp.x, cp.z, getInteger(c, "radius")), p.level());
		}));
		root.then(literal("restore").requires(Perms.require(Perms.RESTORE))
			.then(id("id")
				.executes(c -> {
					manager().requestFullRestore(getInteger(c, "id"), c.getSource());
					return 1;
				})
				.then(chunksArg)
				.then(regionArg)
				.then(radiusArg)));

		root.then(literal("preview").requires(Perms.require(Perms.RESTORE))
			.then(literal("clear").executes(c -> {
				manager().clearPreviews(c.getSource());
				return 1;
			}))
			.then(id("id")
				.then(literal("radius").then(argument("radius", integer(0, 7)).executes(c -> {
					ServerPlayer p = c.getSource().getPlayerOrException();
					ChunkPos cp = p.chunkPosition();
					manager().requestPreview(getInteger(c, "id"), p.level(), ChunkSelection.radius(cp.x, cp.z, getInteger(c, "radius")), c.getSource());
					return 1;
				})))
				.then(literal("chunks").then(argument("dimension", DimensionArgument.dimension())
					.then(argument("x1", integer()).then(argument("z1", integer()).then(argument("x2", integer()).then(argument("z2", integer())
						.executes(c -> {
							manager().requestPreview(getInteger(c, "id"), DimensionArgument.getDimension(c, "dimension"),
								ChunkSelection.box(getInteger(c, "x1"), getInteger(c, "z1"), getInteger(c, "x2"), getInteger(c, "z2")), c.getSource());
							return 1;
						})))))))));

		root.then(literal("confirm").then(argument("token", word()).executes(c -> {
			manager().confirm(getString(c, "token"), c.getSource(), true);
			return 1;
		})));
		root.then(literal("deny").then(argument("token", word()).executes(c -> {
			manager().confirm(getString(c, "token"), c.getSource(), false);
			return 1;
		})));

		root.then(literal("rollback").requires(Perms.require(Perms.RESTORE)).executes(c -> {
			manager().requestRollback(c.getSource());
			return 1;
		}));

		root.then(literal("pending").requires(Perms.require(Perms.RESTORE))
			.executes(c -> {
				manager().pendingInfo(c.getSource());
				return 1;
			})
			.then(literal("cancel").executes(c -> {
				manager().pendingCancel(c.getSource());
				return 1;
			}))
			.then(literal("apply").executes(c -> {
				manager().pendingApply(c.getSource());
				return 1;
			})));

		root.then(literal("verify").requires(Perms.require(Perms.VERIFY))
			.then(id("id").executes(c -> started(manager().verify(getInteger(c, "id"), c.getSource().getTextName(), Feedback.of(c.getSource())), c))));

		root.then(literal("export").requires(Perms.require(Perms.EXPORT))
			.then(id("id").executes(c -> started(manager().export(getInteger(c, "id"), c.getSource().getTextName(), Feedback.of(c.getSource())), c))));

		// "<path> [comment]": the first word is the path (quote it if it contains spaces), the rest is the comment.
		root.then(literal("import").requires(Perms.require(Perms.ADMIN))
			.then(argument("path_and_comment", greedyString()).executes(c -> {
				String[] pc = splitPathAndComment(getString(c, "path_and_comment"));
				return started(manager().importWorld(pc[0], pc[1], c.getSource().getTextName(), Feedback.of(c.getSource())), c);
			})));

		root.then(literal("prune").requires(Perms.require(Perms.PRUNE))
			.executes(c -> started(manager().prune(false, c.getSource().getTextName(), Feedback.of(c.getSource())), c))
			.then(literal("dryrun").executes(c -> started(manager().prune(true, c.getSource().getTextName(), Feedback.of(c.getSource())), c))));

		root.then(literal("gc").requires(Perms.require(Perms.PRUNE))
			.executes(c -> started(manager().garbageCollect(c.getSource().getTextName(), Feedback.of(c.getSource())), c)));

		root.then(literal("status").requires(Perms.require(Perms.LIST)).executes(c -> {
			manager().sendStatus(c.getSource());
			return 1;
		}));

		root.then(literal("cancel").requires(Perms.require(Perms.CANCEL)).executes(c -> {
			if (!manager().cancel(c.getSource())) {
				c.getSource().sendFailure(Msg.error("cytrabackups.error.nothing_to_cancel"));
				return 0;
			}
			return 1;
		}));

		root.then(literal("reload").requires(Perms.require(Perms.ADMIN)).executes(c -> {
			manager().reload(Feedback.of(c.getSource()));
			return 1;
		}));

		root.then(literal("offsite").requires(Perms.require(Perms.ADMIN))
			.executes(c -> offsiteStatus(c.getSource()))
			.then(literal("status").executes(c -> offsiteStatus(c.getSource())))
			.then(literal("sync").executes(c -> {
				manager().offsiteSyncAll(Feedback.of(c.getSource()));
				return 1;
			}))
			.then(literal("list").executes(c -> {
				manager().offsiteList(Feedback.of(c.getSource()));
				return 1;
			}))
			.then(literal("fetch").then(argument("id", integer(1))
				.executes(c -> started(manager().offsiteFetch(getInteger(c, "id"), c.getSource().getTextName(), Feedback.of(c.getSource())), c)))));

		root.then(literal("help").executes(c -> help(c.getSource())));
		root.executes(c -> help(c.getSource()));

		LiteralCommandNode<CommandSourceStack> node = dispatcher.register(root);
		dispatcher.register(literal(ALIAS).requires(node.getRequirement()).executes(c -> help(c.getSource())).redirect(node));
	}

	/** Reply for commands that start a background job; the job reports its own result. */
	private static int started(boolean started, CommandContext<CommandSourceStack> c) {
		if (started) {
			c.getSource().sendSuccess(() -> Msg.info("cytrabackups.job.started")
				.append(" ").append(Msg.button("cytrabackups.button.status", Msg.command("status"), "cytrabackups.hover.status")), false);
		}
		return started ? 1 : 0;
	}

	/** Splits {@code path rest...} or {@code "quoted path" rest...} into {path, comment}. */
	static String[] splitPathAndComment(String input) {
		String in = input.trim();
		if (in.startsWith("\"")) {
			int end = in.indexOf('"', 1);
			if (end > 0) return new String[]{in.substring(1, end), in.substring(end + 1).trim()};
			return new String[]{in.substring(1), ""};
		}
		int space = in.indexOf(' ');
		return space < 0 ? new String[]{in, ""} : new String[]{in.substring(0, space), in.substring(space + 1).trim()};
	}

	private static int create(CommandContext<CommandSourceStack> c, String comment) {
		CommandSourceStack src = c.getSource();
		boolean ok = manager().createBackup(Trigger.MANUAL, comment, src.getTextName(), Feedback.of(src));
		if (ok) src.sendSuccess(() -> Msg.info("cytrabackups.backup.started"), true);
		return ok ? 1 : 0;
	}

	private static int chunks(CommandContext<CommandSourceStack> c, ChunkSelection sel, ServerLevel level) {
		manager().requestChunkRestore(getInteger(c, "id"), level, sel, c.getSource());
		return 1;
	}

	private static int offsiteStatus(CommandSourceStack src) {
		List<Component> lines = manager().offsiteStatus();
		for (int i = 0; i < lines.size(); i++) {
			Component line = i == 0 ? Msg.info("cytrabackups.offsite.title").append(" ").append(lines.get(i)) : lines.get(i);
			src.sendSuccess(() -> line, false);
		}
		return 1;
	}

	static int list(CommandSourceStack src, int page) {
		BackupManager m = manager();
		List<BackupMeta> all = new ArrayList<>(m.services().repo.list());
		all.sort(Comparator.comparingInt((BackupMeta b) -> b.id).reversed());
		if (all.isEmpty()) {
			src.sendSuccess(() -> Msg.info("cytrabackups.list.empty").append(" ")
				.append(Msg.button("cytrabackups.button.create", Msg.command("create"), "cytrabackups.hover.create")), false);
			return 0;
		}
		int pages = (all.size() + PAGE_SIZE - 1) / PAGE_SIZE;
		int p = Math.max(1, Math.min(page, pages));
		List<BackupMeta> shown = all.subList((p - 1) * PAGE_SIZE, Math.min(all.size(), p * PAGE_SIZE));
		int from = (p - 1) * PAGE_SIZE + 1, to = from + shown.size() - 1;
		src.sendSuccess(() -> Msg.info("cytrabackups.list.header", from, to, all.size()), false);

		boolean canRestore = Perms.check(src, Perms.RESTORE);
		int idW = 0, sizeW = 0, trigW = 0;
		for (BackupMeta b : shown) {
			idW = Math.max(idW, ChatColumns.width("#" + b.id + "*"));
			sizeW = Math.max(sizeW, ChatColumns.width(Formatting.bytes(b.totalSize)));
			trigW = Math.max(trigW, ChatColumns.width(trigger(b)));
		}
		for (BackupMeta b : shown) {
			MutableComponent id = Msg.hover(Component.literal("#" + b.id).withStyle(s -> s.withColor(Msg.ACCENT)
				.withClickEvent(new ClickEvent.RunCommand(Msg.command("info", b.id)))), idHover(b));
			String idText = "#" + b.id;
			if (b.pinned) {
				id.append(Msg.hover(Component.literal("*").withStyle(Msg.ACCENT), Msg.tr("cytrabackups.hover.pinned")));
				idText += "*";
			}
			MutableComponent line = ChatColumns.cell(id, idText, idW, 8);
			String date = Formatting.dateTimeShort(b.createdAt, m.zone());
			line.append(ChatColumns.cell(Msg.hover(Component.literal(date), Component.literal(Formatting.ago(b.createdAt, System.currentTimeMillis())
				+ ", " + Formatting.dateTime(b.createdAt, m.zone()))), date, ChatColumns.width(date), 8));
			String size = Formatting.bytes(b.totalSize);
			line.append(ChatColumns.cell(Component.literal(size), size, sizeW, 8));
			line.append(ChatColumns.cell(Component.literal(trigger(b)).withStyle(ChatFormatting.GRAY), trigger(b), trigW, 8));
			if (canRestore) line.append(restoreButton(b)).append(" ");
			line.append(Msg.button("cytrabackups.button.info", Msg.command("info", b.id), "cytrabackups.hover.info", b.id));
			src.sendSuccess(() -> line, false);
		}
		if (pages > 1) {
			MutableComponent nav = p > 1 ? Msg.button("cytrabackups.button.prev", Msg.command("list", p - 1), "cytrabackups.hover.page", p - 1)
				: Msg.inactiveButton("cytrabackups.button.prev");
			nav.append(" ").append(Msg.detail("cytrabackups.list.page", p, pages)).append(" ");
			nav.append(p < pages ? Msg.button("cytrabackups.button.next", Msg.command("list", p + 1), "cytrabackups.hover.page", p + 1)
				: Msg.inactiveButton("cytrabackups.button.next"));
			src.sendSuccess(() -> nav, false);
		}
		return all.size();
	}

	/** Whole-world restore, or for an area backup a restore of just that area. */
	private static MutableComponent restoreButton(BackupMeta b) {
		return Msg.dangerButton("cytrabackups.button.restore", Msg.command("restore", b.id), b.partial ? "cytrabackups.hover.restore_area" : "cytrabackups.hover.restore", b.id);
	}

	private static String trigger(BackupMeta b) {
		return b.trigger.displayName() + (b.partial ? " " + Lang.get("cytrabackups.list.area") : "");
	}

	private static Component idHover(BackupMeta b) {
		MutableComponent hover = Msg.tr("cytrabackups.hover.info", b.id);
		if (!b.comment.isBlank()) hover = Component.literal(b.comment).append("\n").append(hover.withStyle(ChatFormatting.GRAY));
		return hover;
	}

	static int info(CommandSourceStack src, int id) {
		BackupManager m = manager();
		Optional<BackupMeta> opt = m.services().repo.get(id);
		if (opt.isEmpty()) {
			src.sendFailure(Msg.error("cytrabackups.error.no_backup", id));
			return 0;
		}
		BackupMeta b = opt.get();
		List<Component> lines = new ArrayList<>();
		lines.add(Msg.info("cytrabackups.info.title", b.id, Msg.ago(b.createdAt, m.zone()), b.creator.isBlank() ? "-" : b.creator, b.trigger.displayName()));
		if (!b.comment.isBlank()) lines.add(Msg.detail("cytrabackups.info.comment", Component.literal(b.comment).withStyle(ChatFormatting.WHITE)));
		lines.add(Msg.detail("cytrabackups.info.size", Formatting.bytes(b.totalSize), Formatting.bytes(b.newStoredBytes), b.fileCount, b.chunkCount,
			Formatting.duration(b.durationMillis)));
		if (b.pinned) lines.add(Msg.detail("cytrabackups.info.pinned"));
		if (b.partial) lines.add(Msg.detail("cytrabackups.info.area", b.scope));
		if (b.restoreTarget != null) lines.add(Msg.detail("cytrabackups.info.pre_restore", b.restoreTarget));
		lines.add(actions(src, m, b));
		for (Component line : lines) src.sendSuccess(() -> line, false);
		return 1;
	}

	private static MutableComponent actions(CommandSourceStack src, BackupManager m, BackupMeta b) {
		List<MutableComponent> buttons = new ArrayList<>();
		if (Perms.check(src, Perms.RESTORE)) buttons.add(restoreButton(b));
		if (Perms.check(src, Perms.VERIFY)) buttons.add(Msg.button("cytrabackups.button.verify", Msg.command("verify", b.id), "cytrabackups.hover.verify"));
		if (Perms.check(src, Perms.EXPORT) && !b.partial) buttons.add(Msg.button("cytrabackups.button.export", Msg.command("export", b.id), "cytrabackups.hover.export"));
		if (Perms.check(src, Perms.PIN)) {
			buttons.add(b.pinned ? Msg.button("cytrabackups.button.unpin", Msg.command("unpin", b.id), "cytrabackups.hover.unpin")
				: Msg.button("cytrabackups.button.pin", Msg.command("pin", b.id), "cytrabackups.hover.pin"));
		}
		if (Perms.check(src, Perms.COMMENT)) buttons.add(Msg.suggestButton("cytrabackups.button.comment", Msg.command("comment", b.id) + " ", "cytrabackups.hover.comment"));
		List<BackupMeta> all = m.services().repo.list();
		int idx = all.indexOf(b);
		if (idx > 0) buttons.add(Msg.button("cytrabackups.button.diff", Msg.command("diff", all.get(idx - 1).id, b.id), "cytrabackups.hover.diff", all.get(idx - 1).id));
		if (Perms.check(src, Perms.DELETE) && !b.pinned) buttons.add(Msg.dangerButton("cytrabackups.button.delete", Msg.command("delete", b.id), "cytrabackups.hover.delete"));
		MutableComponent line = Component.empty();
		for (int i = 0; i < buttons.size(); i++) line.append(i == 0 ? Component.empty() : Component.literal(" ")).append(buttons.get(i));
		return line;
	}

	private static int help(CommandSourceStack src) {
		src.sendSuccess(() -> Msg.info("cytrabackups.help.title"), false);
		for (String[] h : HELP) {
			String usage = "/" + ROOT + " " + h[0];
			String first = "/" + ROOT + " " + h[0].split(" ")[0] + " ";
			MutableComponent line = Msg.hover(Component.literal(usage).withStyle(s -> s.withColor(Msg.ACCENT)
				.withClickEvent(new ClickEvent.SuggestCommand(first))), Msg.tr("cytrabackups.hover.help"));
			line.append(" ").append(Msg.detail("cytrabackups.help." + h[1]));
			src.sendSuccess(() -> line, false);
		}
		return 1;
	}
}
