# Changelog

All notable changes to CytraBackups. Minecraft Java **1.21.11**, Fabric Loader 0.19.5+, Fabric API, Java 21.

## 1.6.0

**Off-site only: keep backups only at the off-site copy.**

- New setting **Keep local copy** (`offsite.keepLocalCopy`, on by default). Turned off, every backup uploads its new data while it runs and deletes it on this server once uploaded. Only the backup list and at most **Upload buffer** (`offsite.localBufferGiB`, default 4) of data waiting for upload stay here, so a world bigger than the free disk space can be backed up.
- Unchanged data is referenced, not uploaded again. An interrupted backup (restart, lost connection) continues where it stopped on the next try.
- If uploading is slower than reading the world, world saving is turned back on for the rest of that backup instead of holding the world unsaved for hours.
- Restores, area restores, verify and export download the data they need from the off-site copy. Fetching a backup in this mode only fetches its file list.
- Cleanup also deletes off-site data that only the removed backups used, and leftovers of interrupted backups.
- New setting **Parallel transfers** (`offsite.uploadThreads`, default 8, was a fixed 3): uploads and downloads at the same time. Backups are many small pieces, so this matters more than bandwidth.
- Fix: data uploaded again shortly after being queued for off-site deletion (content that came back, e.g. after a restore) could be deleted off-site by that older queued deletion.

Install: replace any older `cytrabackups-*.jar` in `mods/`.

## 1.5.1

**Automatic backups start off.**

- On a fresh install automatic backups are now disabled (`schedule.enabled` defaults to `false`). Turn them on under Settings > Schedule > Enabled, or set `schedule.enabled` to `true` in `config/cytrabackups.json` and run `/cbackup reload`. Manual backups work as before.
- Existing configs are not changed: a server that already has `"enabled": true` keeps its automatic backups.
- `/cbackup status` says where to turn automatic backups on while they are off.

Install: replace any older `cytrabackups-*.jar` in `mods/`.

## 1.5.0

**Smaller backups: chunk recompression.**

- New setting **Recompress chunks** (`compression.recompressChunks`, off by default). Each chunk's data is unpacked from Minecraft's zlib and stored with native zstd plus a dictionary trained on real 1.21.11 chunks (shipped in the jar). Measured on 25,000 real chunks: about 24 percent smaller than the zlib payloads at the default level 15 (`compression.chunkLevel`, 1-22). Restores, exports and live chunk restores pack the chunks back into normal region files, so Minecraft never sees a difference.
- Native zstd (zstd-jni) is bundled for Windows, Linux and macOS (x64 and ARM64). With it, the zstd **Level** setting now works (1-22). On a platform without a native library the mod falls back to the pure-Java zstd as before; dictionary-compressed chunks can only be read where the native library loads.
- Turning the setting on or off makes the next backup re-read every region file once (it is a full read, not a full copy: unchanged chunks that already exist in the store are still shared).
- Backups written with recompression on need CytraBackups 1.5.0 or newer to restore. Older backups restore as before.

Install: replace any older `cytrabackups-*.jar` in `mods/`.

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
