# Changelog

All notable changes to CytraBackups. Minecraft Java **1.21.11**, Fabric Loader 0.19.5+, Fabric API, Java 21.

## 1.2.0

**A cleaner, coloured GUI.**

- New look for every screen: header bar, dark panels, coloured flat buttons (blue actions, green create/save, red for anything destructive, amber for caution), tabs with an accent underline.
- Backups tab: striped list with coloured triggers, and a "Selected backup" card showing date, size, new data and comment next to its actions.
- Restore and Tools tabs are laid out as titled cards with a short explanation each.
- Settings: a compact panel with every box right next to its label, striped rows, green/red ON/OFF toggles, choice buttons, and a gold marker on unsaved changes. "Save" shows how many changes are pending.
- Confirmations (restore, delete, rollback, prune, apply queued restore) use a matching themed dialog.
- Area selector: themed controls and a labelled radius box.

Install: replace any older `cytrabackups-*.jar` in `mods/` (server, and client if you use the GUI).

## 1.1.0

**Everything the mod does is available in the GUI.**

- Tabs for Backups, Restore and Tools; open with `/backupgui`, the Backups button in the pause menu, or a key binding.
- Settings editor for every option in `config/cytrabackups.json`, with the config comments as tooltips. Passwords, keys and the Discord webhook are never sent to clients.
- Every GUI button runs the matching `/backup` command as you, so permissions and messages are the same as typing it; confirmations open a dialog; GUI actions are logged on the server.
- Area selector: any dimension, "Around me" radius and "Whole region".
- `/backup comment <id>` without text clears a comment.
- Stricter config validation (negative numbers, ports, permission levels, compression levels).
- The live chunk restore now knows how far away players must be (view distance + 13 chunks) and says so instead of waiting and queueing.
- Chunk selector map is drawn from a texture (no more lag).

The GUI network protocol changed, so 1.0.0 and 1.1.0+ clients and servers do not talk to each other's GUI; commands are unaffected.

## 1.0.0

First release: deduplicating, chunk-level backups with zstd/deflate, scheduling, pruning with garbage collection, safe full and partial restores with recycle bin and rollback, export/import, Discord notifications, S3/SFTP/WebDAV off-site copies, permissions via fabric-permissions-api, and an optional client GUI.
