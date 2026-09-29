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
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import dev.steelaspect.cytrabackups.core.Formatting;
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
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;

/** The /backup command tree. Every feature is usable from a vanilla client via commands and clickable chat. */
public final class BackupCommands {
	private static final int PAGE_SIZE = 8;

	private BackupCommands() {
	}

	private static final SuggestionProvider<CommandSourceStack> BACKUP_IDS = (ctx, builder) -> {
		BackupManager m = BackupManager.getOrNull();
		if (m != null) {
			List<BackupMeta> list = m.services().repo.list();
			for (int i = list.size() - 1; i >= 0 && list.size() - i <= 50; i--) {
				BackupMeta b = list.get(i);
				builder.suggest(b.id, Component.literal(Formatting.dateTime(b.createdAt, m.zone()) + " " + b.trigger.displayName()
					+ (b.comment.isBlank() ? "" : " - " + b.comment)));
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
		var root = literal("backup").requires(src -> BackupManager.getOrNull() != null && Perms.any(src));

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
			.then(id("id").then(argument("text", greedyString()).executes(c -> {
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
			.then(id("id").executes(c -> job(manager().verify(getInteger(c, "id"), c.getSource().getTextName(), Feedback.of(c.getSource())), c, "Verifying"))));

		root.then(literal("export").requires(Perms.require(Perms.EXPORT))
			.then(id("id").executes(c -> job(manager().export(getInteger(c, "id"), c.getSource().getTextName(), Feedback.of(c.getSource())), c, "Exporting"))));

		// The path is greedy so folders like "backups/old world.zip" work unquoted; add a comment later with /backup comment.
		root.then(literal("import").requires(Perms.require(Perms.ADMIN))
			.then(argument("path", greedyString())
				.executes(c -> job(manager().importWorld(getString(c, "path").trim(), "", c.getSource().getTextName(), Feedback.of(c.getSource())), c, "Importing"))));

		root.then(literal("prune").requires(Perms.require(Perms.PRUNE))
			.executes(c -> job(manager().prune(false, c.getSource().getTextName(), Feedback.of(c.getSource())), c, "Pruning"))
			.then(literal("dryrun").executes(c -> job(manager().prune(true, c.getSource().getTextName(), Feedback.of(c.getSource())), c, "Planning prune"))));

		root.then(literal("gc").requires(Perms.require(Perms.PRUNE))
			.executes(c -> job(manager().garbageCollect(c.getSource().getTextName(), Feedback.of(c.getSource())), c, "Collecting garbage")));

		root.then(literal("status").requires(Perms.require(Perms.LIST)).executes(c -> {
			for (Component line : manager().status()) c.getSource().sendSuccess(() -> line, false);
			return 1;
		}));

		root.then(literal("cancel").requires(Perms.require(Perms.CANCEL)).executes(c -> {
			if (!manager().cancel(c.getSource())) {
				c.getSource().sendFailure(Msg.error("Nothing to cancel."));
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
			})));

		root.then(literal("help").executes(c -> help(c.getSource())));
		root.executes(c -> help(c.getSource()));

		dispatcher.register(root);
	}

	private static int job(boolean started, CommandContext<CommandSourceStack> c, String what) {
		if (started) c.getSource().sendSuccess(() -> Msg.info(what + "... progress: /backup status"), false);
		return started ? 1 : 0;
	}

	private static int create(CommandContext<CommandSourceStack> c, String comment) {
		CommandSourceStack src = c.getSource();
		boolean ok = manager().createBackup(Trigger.MANUAL, comment, src.getTextName(), Feedback.of(src));
		if (ok) src.sendSuccess(() -> Msg.info("Backup started..."), true);
		return ok ? 1 : 0;
	}

	private static int chunks(CommandContext<CommandSourceStack> c, ChunkSelection sel, ServerLevel level) {
		manager().requestChunkRestore(getInteger(c, "id"), level, sel, c.getSource());
		return 1;
	}

	private static int offsiteStatus(CommandSourceStack src) {
		for (String s : manager().offsiteStatus()) src.sendSuccess(() -> Msg.info(s), false);
		return 1;
	}

	static int list(CommandSourceStack src, int page) {
		BackupManager m = manager();
		List<BackupMeta> all = new ArrayList<>(m.services().repo.list());
		all.sort(Comparator.comparingInt((BackupMeta b) -> b.id).reversed());
		if (all.isEmpty()) {
			src.sendSuccess(() -> Msg.info("No backups yet. ").append(Msg.run("Create one", "/backup create", "Run /backup create", ChatFormatting.GREEN)), false);
			return 0;
		}
		int pages = (all.size() + PAGE_SIZE - 1) / PAGE_SIZE;
		int p = Math.max(1, Math.min(page, pages));
		long now = System.currentTimeMillis();
		src.sendSuccess(() -> Msg.info("Backups (page " + p + "/" + pages + ", " + all.size() + " total, newest first):"), false);
		boolean canRestore = Perms.check(src, Perms.RESTORE);
		boolean canPin = Perms.check(src, Perms.PIN);
		for (BackupMeta b : all.subList((p - 1) * PAGE_SIZE, Math.min(all.size(), p * PAGE_SIZE))) {
			MutableComponent line = Component.literal(" ");
			MutableComponent idPart = Msg.text("#" + b.id, ChatFormatting.AQUA, ChatFormatting.BOLD);
			line.append(Msg.click(idPart, "/backup info " + b.id, Component.literal("Show details of #" + b.id)));
			line.append(Msg.text(" " + Formatting.dateTime(b.createdAt, m.zone()) + " (" + Formatting.ago(b.createdAt, now) + ") ", ChatFormatting.WHITE));
			line.append(Msg.text(b.trigger.displayName() + (b.partial ? " (area)" : ""), ChatFormatting.GRAY));
			line.append(Msg.text(" " + Formatting.bytes(b.totalSize), ChatFormatting.WHITE));
			line.append(Msg.text(" +" + Formatting.bytes(b.newStoredBytes), ChatFormatting.DARK_GRAY));
			if (b.pinned) line.append(Msg.text(" ★pinned", ChatFormatting.GOLD));
			if (!b.comment.isBlank()) line.append(Msg.text(" \"" + b.comment + "\"", ChatFormatting.ITALIC, ChatFormatting.GRAY));
			line.append(" ");
			if (canRestore) line.append(Msg.suggest("Restore", "/backup restore " + b.id, "Put /backup restore " + b.id + " into chat", ChatFormatting.RED)).append(" ");
			if (canPin) {
				line.append(b.pinned ? Msg.run("Unpin", "/backup unpin " + b.id, "Allow pruning", ChatFormatting.YELLOW)
					: Msg.run("Pin", "/backup pin " + b.id, "Never prune this backup", ChatFormatting.YELLOW));
			}
			src.sendSuccess(() -> line, false);
		}
		if (pages > 1) {
			MutableComponent nav = Component.literal(" ");
			nav.append(p > 1 ? Msg.run("« Prev", "/backup list " + (p - 1), "Page " + (p - 1), ChatFormatting.AQUA) : Msg.text("[« Prev]", ChatFormatting.DARK_GRAY));
			nav.append(Msg.text("  Page " + p + "/" + pages + "  ", ChatFormatting.GRAY));
			nav.append(p < pages ? Msg.run("Next »", "/backup list " + (p + 1), "Page " + (p + 1), ChatFormatting.AQUA) : Msg.text("[Next »]", ChatFormatting.DARK_GRAY));
			src.sendSuccess(() -> nav, false);
		}
		return all.size();
	}

	static int info(CommandSourceStack src, int id) {
		BackupManager m = manager();
		Optional<BackupMeta> opt = m.services().repo.get(id);
		if (opt.isEmpty()) {
			src.sendFailure(Msg.error("No backup #" + id + "."));
			return 0;
		}
		BackupMeta b = opt.get();
		long now = System.currentTimeMillis();
		List<Component> lines = new ArrayList<>();
		lines.add(Msg.info("Backup #" + b.id + " — " + Formatting.dateTime(b.createdAt, m.zone()) + " (" + Formatting.ago(b.createdAt, now) + ")"));
		lines.add(Msg.text(" Trigger: " + b.trigger.displayName() + (b.partial ? " (area backup: " + b.scope + ")" : "") + " · Creator: "
			+ (b.creator.isBlank() ? "-" : b.creator) + " · Pinned: " + (b.pinned ? "yes" : "no"), ChatFormatting.GRAY));
		if (!b.comment.isBlank()) lines.add(Msg.text(" Comment: " + b.comment, ChatFormatting.GRAY));
		if (b.restoreTarget != null) lines.add(Msg.text(" Taken automatically before restoring #" + b.restoreTarget, ChatFormatting.GRAY));
		lines.add(Msg.text(" Size: " + Formatting.bytes(b.totalSize) + " in " + b.fileCount + " files, " + b.chunkCount + " chunks", ChatFormatting.GRAY));
		lines.add(Msg.text(" New data stored: " + Formatting.bytes(b.newStoredBytes) + " (" + b.newBlobs + " new blobs, " + b.reusedFiles + "/" + b.fileCount
			+ " files unchanged) · Dedup saved: " + Formatting.bytes(b.dedupSavedBytes()), ChatFormatting.GRAY));
		lines.add(Msg.text(" Stored size if alone: " + Formatting.bytes(b.referencedStoredBytes) + " · Minecraft " + b.minecraftVersion + " · CytraBackups "
			+ b.modVersion + " · took " + Formatting.duration(b.durationMillis), ChatFormatting.GRAY));
		MutableComponent actions = Component.literal(" ");
		if (Perms.check(src, Perms.RESTORE)) actions.append(Msg.run("Restore", "/backup restore " + id, "Restore the whole world (asks to confirm)", ChatFormatting.RED)).append(" ");
		if (Perms.check(src, Perms.VERIFY)) actions.append(Msg.run("Verify", "/backup verify " + id, "Check every blob hash", ChatFormatting.GREEN)).append(" ");
		if (Perms.check(src, Perms.EXPORT)) actions.append(Msg.run("Export", "/backup export " + id, "Build a standalone .zip", ChatFormatting.AQUA)).append(" ");
		if (Perms.check(src, Perms.PIN)) {
			actions.append(b.pinned ? Msg.run("Unpin", "/backup unpin " + id, "Allow pruning", ChatFormatting.YELLOW)
				: Msg.run("Pin", "/backup pin " + id, "Never prune", ChatFormatting.YELLOW)).append(" ");
		}
		if (Perms.check(src, Perms.COMMENT)) actions.append(Msg.suggest("Comment", "/backup comment " + id + " ", "Edit the comment", ChatFormatting.WHITE)).append(" ");
		List<BackupMeta> all = m.services().repo.list();
		int idx = all.indexOf(b);
		if (idx > 0) actions.append(Msg.run("Diff prev", "/backup diff " + all.get(idx - 1).id + " " + id, "Compare with #" + all.get(idx - 1).id, ChatFormatting.WHITE)).append(" ");
		if (Perms.check(src, Perms.DELETE) && !b.pinned) actions.append(Msg.run("Delete", "/backup delete " + id, "Delete (asks to confirm)", ChatFormatting.DARK_RED));
		lines.add(actions);
		for (Component line : lines) src.sendSuccess(() -> line, false);
		return 1;
	}

	private static int help(CommandSourceStack src) {
		String[][] cmds = {
			{"create [comment]", "Create a backup now"},
			{"list [page]", "List backups (clickable)"},
			{"info <id>", "Details of a backup"},
			{"diff <id1> <id2>", "Changed files and chunks"},
			{"restore <id>", "Restore the whole world (restart)"},
			{"restore <id> chunks <dim> <x1> <z1> <x2> <z2>", "Restore chunks (live when safe)"},
			{"restore <id> region <dim> <rx> <rz>", "Restore one region file area"},
			{"restore <id> radius <r>", "Restore chunks around you"},
			{"rollback", "Undo the last restore"},
			{"pending [cancel|apply]", "Queued restore operation"},
			{"verify <id>", "Check integrity without restoring"},
			{"export <id>", "Standalone world .zip"},
			{"import <path>", "Import a world folder or .zip (path relative to the server folder)"},
			{"comment|pin|unpin|delete <id>", "Manage backups"},
			{"prune [dryrun]", "Apply retention rules"},
			{"gc", "Free unreferenced data"},
			{"status | cancel | reload", "Jobs and config"},
			{"offsite [status|sync]", "Off-site copies"},
		};
		src.sendSuccess(() -> Msg.info("Commands:"), false);
		for (String[] c : cmds) {
			String first = "/backup " + c[0].split(" ")[0] + " ";
			src.sendSuccess(() -> Msg.suggest("/backup " + c[0], first, c[1], ChatFormatting.AQUA).append(Msg.text(" " + c[1], ChatFormatting.GRAY)), false);
		}
		return 1;
	}

	@SuppressWarnings("unused")
	private static ServerPlayer player(CommandSourceStack src) throws CommandSyntaxException {
		return src.getPlayerOrException();
	}
}
