# Changelog

All notable changes to CytraBackups. Minecraft Java **1.21.11**, Fabric Loader 0.19.5+, Fabric API, Java 21.

## 1.4.0

**Get backups back from the off-site copy.**

- `/cbackup offsite list` shows the backups stored at your S3, SFTP or WebDAV destination, with a [Fetch] button for the ones not on this server. `/cbackup offsite fetch <id>` downloads one into the local store (every piece verified), after which it can be restored like any other backup. Also under Tools in the menu.
- Works for disaster recovery: on a fresh server with the same off-site settings, list, fetch, restore.
- OVHcloud Object Storage documented as an S3 destination (endpoint `https://s3.<region>.io.cloud.ovh.net`, region e.g. `gra`, path-style on).
- The zstd "Level" setting is documented as having no effect: the bundled pure-Java zstd has a single level (about zstd 3). Measured on 25,000 real 1.21.11 chunks, recompressing with higher zstd levels would save at most 10-24%, so that is planned separately with a native zstd.

Install: replace any older `cytrabackups-*.jar` in `mods/`.

## 1.3.1

**Fixes from a full code review.**

- A crash during a restore whose recycle bin is on another disk could put a half-copied file back over an intact world file. The recovery now keeps the intact file.
- Reloading or saving settings while an off-site upload runs is refused instead of corrupting the upload queue.
- Area backups (the automatic "before restoring" backups of a live area restore) can be restored again: [Restore] on one restores just that area, live when nobody is near. Live area restores now end with an [Undo] button. Older area backups without area info restore their files at the next restart.
- Prune no longer deletes the backup a queued restore is waiting for.
- The Discord failure mention is sent with a username even when `discord.username` is empty.
- Deletes are logged to the server console like the other destructive actions.

## 1.3.0

**New command name, plainer chat, vanilla-style menu.**

- The command is now `/cbackup`, with `/cb` as a short alias. `/backup` is no longer registered, so CytraBackups doesn't clash with other backup mods. The menu command is `/cbackupgui`.
- Chat output uses one accent colour, grey for details, green for success and red for errors. Messages are shorter.
- `/cbackup list` shows compact aligned rows (id, date, size, trigger) with [Restore] and [Info] buttons, and page navigation. Hover a date for the exact time.
- Sizes use KB/MB/GB, and times are shown as "2h ago" with the exact time on hover.
- The menu uses vanilla Minecraft widgets and layout throughout, and fits small windows at every GUI scale.
- All text is in `assets/cytrabackups/lang/en_us.json`, so the mod can be translated. Players without the mod still see English.

Install: replace any older `cytrabackups-*.jar` in `mods/` (server, and client if you use the menu). Update command blocks, scripts or macros that use `/backup` to `/cbackup`.

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
