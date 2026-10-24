# Alloy Backups 1.9.0: move a whole server through the off-site store

Status: **built** in 1.9.0 (2026-10-24). This plan was requested by the user through the Minecraft hosts chat.

## Why

The user wants to move a 45 GB Kinetic server to another Kinetic server. The panel can't transfer it and there's no shell, so SFTP copies go through their home internet and take hours. Alloy Backups already uploads straight from the server to S3 (datacenter to datacenter). Moving the server through the bucket takes minutes, and only data the bucket doesn't already hold gets uploaded.

## Commands

| Where | Command | What it does |
|---|---|---|
| old server | `/cbackup migrate export [comment]` | Makes one **server backup** of the whole server folder, pins it, and uploads it to the off-site copy. It waits for the upload, then prints the exact import command for the new server. Needs the off-site copy on. Works in off-site only mode. |
| new server | `/cbackup migrate list "<location>"` | Lists the server backups stored at `<location>`, newest first. |
| new server | `/cbackup migrate import "<location>" <id> [keepBackupSettings\|replaceBackupSettings]` | Downloads the backup's file list, checks versions and disk space, and asks for confirmation. On confirm it queues the import and stops the server like a full restore does. On the next start, before the world loads, it applies the import and then stops the server once more, so the moved mods load on the start after that. |

**`<location>`** is where the old server's backups are, inside this server's own off-site destination: the S3 prefix, the SFTP folder or the WebDAV URL. Credentials, bucket and host come from this server's settings, so both servers use the same bucket with different prefixes. The default is this server's own location.

## Server backups

- A server backup is a normal backup with `scope = "server"`. The engine already scans any folder with a filter, so it scans the game directory (the server folder) instead of the world.
- Region files are still deduplicated by chunk, so the world shares its blobs with the world backups that are already off-site. Only new data uploads.
- What's included comes from the new settings `migrate.include` and `migrate.exclude`, with the same glob rules as the world filter.
  - Default exclude: `libraries/**`, `versions/**`, `.fabric/**`, `.cache/**`, `cache/**`, `logs/**`, `crash-reports/**`, `debug/**`, `**/session.lock`, `**/*.lock`, `**/*.tmp`.
  - The backup storage folder is always skipped.
- Each backup records its Minecraft version, its Fabric Loader version and its world folder name.
- Server backups are pinned, so pruning keeps them; delete them after the move.
- They are never the baseline for the next world backup, so the world isn't re-read in full.
- World-only actions (restore, area restore, preview, zip export) refuse them with a pointer to `migrate import`.

## Import, applied at the next start

1. **On confirm:** `pending-restore.json` gets type `MIGRATE_IMPORT` with the location, the id and the backup settings choice. The meta and manifest are kept in `<storage>/migrate/`.
2. **Next start, at world open:** `StartupRestore` runs the import instead of a world restore. `RestoreEngine.planFull` and `execute` run over the server folder, reading blobs straight from the source location:
   - Blobs come through a scratch `BlobStore` whose remote reads from that location. Nothing is kept locally beyond each file being written.
   - The staging folder lives in the storage folder, because the server folder's parent isn't writable on panel hosts.
   - Replaced and removed files go to the recycle bin with a restore record, so `/cbackup rollback` undoes the move.
   - Never touched:
     - the storage folder;
     - `config/cytrabackups.json`, unless `replaceBackupSettings` was given;
     - `session.lock`;
     - anything matching `migrate.exclude`.
3. **After a successful import:** the server stops (`halt`), because the new mod set only loads on a fresh start. With auto-restart the panel brings it straight back. If the import fails, the server folder is rolled back and the server keeps starting unchanged, as failed restores do now.

## Safety

- **Confirmation details:** the size, the file count, both servers' Minecraft and Fabric Loader versions (with a warning when they differ), the world folder name, the free space and what is protected.
- **Disk space:** the import is refused when free space is below the backup's size plus `minFreeSpaceMiB`. Staging needs room for one copy, and replaced files move into the recycle bin without a copy.
- **One attempt:** like restores, the import isn't retried in a loop after a failed attempt.

## Tests

- **Integration test (s3proxy):** server A exports a server backup to prefix `a/`. Server B, with its own storage and prefix `b/`, imports it. Then check:
  - B's files equal A's, apart from the protected and excluded ones.
  - Files B had are in the recycle bin.
  - A rollback restores B.
- **Unit tests:** the server filter defaults, and server backups not being used as the baseline.
- **Server game test:** `migrate export` without the off-site copy gives a clear error, and `migrate import` with a bad location fails cleanly.
