# CytraBackups (Fabric 1.21.11)

Deduplicating world backups for technical Fabric servers, for **Minecraft Java 1.21.11**.

- **Server-side only.** Vanilla clients can use every feature through `/backup` commands and clickable chat.
- **Built for panel hosts** (Pterodactyl and similar). Everything runs in pure Java inside the server process: no root, no shell scripts, no external binaries, no cron.
- **Optional client GUI** in the same jar (`environment: "*"`). Players without the mod lose nothing, and the server never requires it.

Author: steelaspect · Mod ID: `cytrabackups` · Package: `dev.steelaspect.cytrabackups` · License: MIT

## Features

- **Content-addressed storage.** Every file piece is hashed with SHA-256 and stored once in a blob store. Each backup is a manifest that points at blobs, so unchanged files cost nothing.
- **Chunk-level dedup for `.mca` files.** Region, entity and POI files are split per chunk. A region with one changed chunk stores only that chunk again. This can be disabled; region files are then stored whole.
- **Per-blob compression.** zstd (pure-Java [aircompressor](https://github.com/airlift/aircompressor), levels 1–19) or deflate. Data that is already compressed (chunk payloads, gzip `.dat` files, images, zips) is stored as-is.
- **Safe copies.** `save-all flush` runs first, then autosave is off for the copy and back on afterwards, even on failure. Files that change while being read are re-read. Hashing, compression and I/O run on low-priority worker threads, never on the server thread.
- **Include/exclude globs.** Defaults exclude `session.lock`, logs, map-renderer tiles and temp files. Backups cover all dimensions (including modded), `entities/`, `poi/`, `level.dat`, `playerdata`, `advancements`, `stats` and `data/`.
- **Scheduling.** Fixed interval, daily times, on server stop, when the last player leaves, only if players were online, and skip-if-unchanged. Last-run times survive restarts.
- **Pruning.** Keep-last-N, hourly/daily/weekly/monthly buckets (grandfather-father-son), max age and max total size. Pinned backups are never pruned. Supports a dry run, followed by mark-and-sweep garbage collection of unreferenced blobs.
- **Safe restore:**
  - Clickable `[Confirm]` that expires, then a broadcast countdown, then everyone is kicked.
  - The restore is applied **before the world loads** on the next start, through a mixin into world opening. A "shutdown" mode serves hosts that do not auto-restart.
  - An automatic pre-restore backup is always taken first.
  - Everything is staged and verified before any world file is touched. Replaced files go to a **recycle bin** (`/backup rollback`).
  - Every restored file is re-hashed in place. On any mismatch the restore rolls itself back.
  - The swap is journaled, so a crash mid-restore is rolled back on the next start.
- **Partial restore** of chunks, a region, or a radius around you. Terrain, entities and POI are restored.
  - It runs **live** when the area can be fully unloaded: written through Minecraft's own region IO, with entity and POI caches refreshed.
  - Otherwise it is **queued for the next start**, and the reason is reported.
  - A backup of just the affected area is taken first.
- **More commands:**
  - `list` (paged and clickable), `info`, `diff` (files and chunks), `comment`, `pin`/`unpin`, `delete`
  - `export` (standalone world `.zip` you can download from the host's file manager) and `import` (a world folder or `.zip`)
  - `verify`, `status`, `cancel`, `reload`
- **Discord webhook** notifications for success, failure, restore and low disk.
- **Off-site copies** to S3-compatible storage (AWS, R2, B2, MinIO…), SFTP or WebDAV. All pure Java; only new blobs are uploaded.
  - Existing backups are queued automatically when off-site is first enabled or the destination changes (type, endpoint, bucket, prefix or folder).
  - The S3 bucket must already exist. A missing WebDAV base folder is created if its parent exists.
  - Remote copies follow local deletes and pruning (`mirrorDeletes`).
- **Progress** shown in a boss bar or the action bar.
- **Permissions** via [fabric-permissions-api](https://github.com/lucko/fabric-permissions-api) (LuckPerms and others), with op-level fallback. Restore needs op 4 by default.
- **Singleplayer** works too, with one repository per world.

## Install

1. Install Fabric Loader 0.19.5+ and Fabric API for 1.21.11.
2. Put `cytrabackups-1.1.0.jar` into `mods/` on the server. Clients may also install it to get the GUI; it is optional.
3. Start the server once. `config/cytrabackups.json` is created with comments, and backups go to `backups/cytrabackups/` next to the world.

The jar bundles its libraries through Fabric jar-in-jar: aircompressor 2.0.3 (pure-Java zstd), fabric-permissions-api 0.6.1 and JSch 2.28.7 (pure-Java SFTP).

## Build

```bash
./gradlew build
```

Output: **`build/libs/cytrabackups-1.1.0.jar`**, the mod jar with bundled libraries. `build/libs/cytrabackups-1.1.0-sources.jar` holds the sources.

- Requires JDK 21. The build uses Gradle 9.7.1 (wrapper) and Fabric Loom 1.17.21 with official Mojang mappings.
- Loom 1.18 needs Java 25 to run Gradle, so 1.17.21 is used to keep the whole toolchain on Java 21.
- Unit tests: `./gradlew test`
- Off-site integration tests: `./gradlew integrationTest`. These run against embedded S3, SFTP and WebDAV servers and are kept out of `build` because of their large test-only dependencies.
- Headless server GameTests: `./gradlew runGameTest`. This starts a dedicated test server, runs the tests and exits non-zero on failure.
- Client GameTest (real client, GUI and singleplayer): `./gradlew runClientGameTest` on a desktop. Screenshots land in `build/run/clientGameTest/screenshots/`. It has not yet passed in a headless environment (see [Tests](#tests)).

## Commands

All commands are under `/backup` (`/backup help` lists them). `<id>` suggests existing backups.

| Command | Permission | What it does |
|---|---|---|
| `create [comment]` | `create` | Back up now (save-all flush → save-off → copy → save-on) |
| `list [page]` | `list` | Paged list, newest first; click an id for details, `[Restore]`, `[Pin]` |
| `info <id>` | `list` | Size, dedup savings, trigger, creator, versions, action buttons |
| `diff <id1> <id2>` | `list` | Added/removed/changed files and changed chunk counts/coordinates |
| `comment <id> [text]` | `comment` | Set a backup's comment; without text it clears it |
| `pin <id>` / `unpin <id>` | `pin` | Pinned backups are never pruned (and cannot be deleted) |
| `delete <id>` | `delete` | Delete a backup (asks to confirm) |
| `restore <id>` | `restore` | Full restore: confirm → countdown → kick → pre-restore backup → apply before the world loads |
| `restore <id> chunks <dim> <x1> <z1> <x2> <z2>` | `restore` | Restore a chunk rectangle (chunk coordinates) |
| `restore <id> region <dim> <rx> <rz>` | `restore` | Restore the 32×32 chunks of one region file |
| `restore <id> radius <r>` | `restore` | Restore chunks within `r` chunks of you (usually queued, since you are standing in it) |
| `confirm <token>` / `deny <token>` | – | Answer a confirmation (the chat buttons run these) |
| `rollback` | `restore` | Undo the last restore from its recycle bin (restart required) |
| `pending` / `pending cancel` / `pending apply` | `restore` | Show, drop, or apply now (countdown + stop) a queued restore |
| `verify <id>` | `verify` | Re-hash every blob of a backup without restoring |
| `export <id>` | `export` | Build `backups/cytrabackups/exports/<world>-backup-<id>-<time>.zip` |
| `import <path> [comment]` | `admin` | Import a world folder or `.zip` (path relative to the server folder; quote paths containing spaces) |
| `prune [dryrun]` | `prune` | Apply retention rules; `dryrun` lists what would be deleted and why |
| `gc` | `prune` | Delete blobs no backup references any more |
| `status` | `list` | Running job (phase, %, ETA), next automatic backup, free space, queue, off-site |
| `cancel` | `cancel` | Cancel the running job or a restore countdown |
| `reload` | `admin` | Reload `config/cytrabackups.json` |
| `offsite [status\|sync]` | `admin` | Off-site status; `sync` queues every backup not uploaded yet |

The console gets the confirmation token printed (`type: backup confirm <token>`), because it cannot click.

### Client GUI (optional)

With the mod installed on the client, everything the mod can do is also in the GUI. Open it with `/backupgui`, the **Backups** button in the pause menu, or a key binding (Controls → CytraBackups; unbound by default).

- **Backups tab:** the list (date, trigger, size, comment, pin). Click to select, double-click for details. Buttons for the selected backup:
  - Info, Compare… (with any other backup) and Diff prev
  - Restore… (whole world) and Area… (map selector)
  - Verify, Export, Pin/Unpin, Comment… (empty clears it) and Delete…
  - Create backup, with an optional comment
- **Area… (map selector):**
  - Loaded chunks are painted once into a small texture with map colours and height shading, then drawn with a single blit, so it stays smooth at any zoom.
  - Drag to select. **Around me** selects a radius around you and **Whole region** selects a 32×32 region file. You can also type chunk coordinates.
  - Pick any dimension; the map is only shown for the one you are in.
  - Selection, hover text and the confirmation show chunk and block coordinates.
- **Restore tab:** show, cancel or apply the queued restore; roll back the last restore; cancel a countdown or running job.
- **Tools tab:**
  - status, cancel job and all commands
  - prune preview (dry run), prune now and garbage collection
  - import a world folder or `.zip`
  - off-site status and sync now
  - settings and config reload
- **Settings…:** an editor for **every** option in `config/cytrabackups.json`, grouped by section, with the config comment as each setting's tooltip.
  - On/off toggles, choice buttons, number, text and list fields.
  - Passwords, keys and the Discord webhook are never sent to the client. You only see whether they are set, and can replace or clear them.
  - Save sends only the changed values. The server validates them, writes the file and reloads. Invalid values are shown in red and nothing is saved.
  - Needs `cytrabackups.admin`.
- **Output panel:** the server's replies, with chat colours. Links such as `[Confirm]`, `[Info]` or `[Diff prev]` can be clicked.
- **How it works:**
  - Every button runs the matching `/backup` command on the server as you, so permissions, checks and messages are identical to typing it. Each one is written to the server log (`<player> ran /backup ... from the CytraBackups GUI`).
  - Confirmations (restore, delete, rollback, apply pending) open a dialog instead of chat links.
  - Buttons you have no permission for are greyed out.
  - On servers without CytraBackups (or with a different version of the GUI protocol), the GUI says so and sends nothing.

## Permissions

Nodes are `cytrabackups.<name>`. Without a permissions mod, the op levels from `permissions.defaultLevels` apply:

| Node | Default op level |
|---|---|
| `cytrabackups.list`, `.create`, `.comment`, `.verify`, `.progress` (sees the boss bar) | 2 |
| `cytrabackups.pin`, `.cancel`, `.prune`, `.export`, `.delete` | 3 |
| `cytrabackups.restore`, `.admin` (reload, import, off-site) | 4 |

In singleplayer, commands need cheats enabled (or "Allow Commands" when opening to LAN), just like vanilla op commands.

## How restores work

**Full restore / rollback**

1. You confirm (within 30 s). A countdown is broadcast (10 s by default), then all players are kicked.
2. The operation is written to `backups/cytrabackups/pending-restore.json`.
3. The server stops, and the restore is applied depending on `restore.applyMode`:
   - `startup` (default, for hosts that auto-restart): on the next start, a mixin in `LevelStorageSource.LevelStorageAccess` applies it right after the world folder is locked, before `level.dat` or any chunk is read.
   - `shutdown`: it is applied right after the server has saved and stopped. The server stays stopped (or is restarted by the host, already restored).
4. Applying an operation always:
   1. rolls back any interrupted earlier restore (journal),
   2. takes a **pre-restore backup** of the current world,
   3. **stages** every file next to the world and verifies each against its SHA-256 (the world is still untouched),
   4. moves replaced or removed files into the **recycle bin** and moves the staged files into place (journaled),
   5. **re-verifies every placed file**; any mismatch rolls the whole swap back automatically.
5. The outcome is logged, sent to Discord, and shown to players with restore permission when they join.

`/backup rollback` swaps the recycle-bin contents back the same way. The files it replaces go to a new recycle bin, so a rollback can itself be rolled back. The recycle bins of the last `restore.recycleBinKeep` restores are kept.

**Partial restore (chunks, region, radius)**

1. A restore is attempted **live** when all of these hold:
   - no player is close enough to keep the area in memory. Minecraft keeps chunk holders for view distance + 13 chunks around each player (the player ticket spreads outwards until `ChunkLevel.MAX_LEVEL`; the `holderRangeMatchesTickets` gametest checks this against the real ticket system), so in singleplayer with render distance 12 you need to be at least 26 chunks (416 blocks) away. The exact distance is in the message when a restore is queued,
   - nothing in or next to it is force-loaded,
   - autosave is on,
   - every selected chunk fully unloads within `liveUnloadTimeoutSeconds`.

   Unloaded means no chunk holder, no pending unload and no loaded entity sections.
2. Pending chunk IO is flushed, then a backup of just the affected region/entity/POI files is taken.
3. In one server tick, safety is re-checked and the backup's chunk NBT is written through Minecraft's own IO workers. `ChunkMap`, `EntityStorage` and `PoiManager` caches for those chunks are evicted, so the next load reads the restored data. Chunks that did not exist in the backup are deleted, so they regenerate.
4. If it is not safe (for example a player is nearby, the chunks are force-loaded, or they stayed loaded), the restore is **queued for the next start** and the reason is reported. In singleplayer "next start" means the next time the world is opened.

   `/backup pending apply` restarts right away. At startup the region files are merged: only the selected chunks are replaced, and everything else in those region files is kept.

## Scheduling and pruning

- Backups run every `schedule.intervalMinutes` and/or at `schedule.timesOfDay`, with times missed while the server was down caught up within 6 h. Timestamps live in `backups/cytrabackups/state.json`, so the schedule survives restarts.
- `skipIfUnchanged` compares content hashes with the previous backup, ignoring `level.dat`/`level.dat_old`, which are rewritten on every save.
- `onlyIfPlayersWereOnline` skips automatic backups on an empty server.
- A failed automatic backup is retried after 5 minutes.
- Pruning runs every `prune.intervalMinutes`. Keep rules combine as a union: a backup survives if **any** rule keeps it.
  - `keepLast` keeps the newest N.
  - `keepHourly`/`keepDaily`/`keepWeekly`/`keepMonthly` keep the newest backup of each of the last N hours, days, ISO weeks and months, in `timeZone`.
  - `maxAgeDays` and `maxTotalSizeGiB` are hard caps applied afterwards. The size cap counts deduplicated storage, so shared blobs are only freed when their last backup goes.
  - Pinned backups and the latest backup are never pruned. Pre-restore backups are kept for `preRestoreMaxAgeDays`.
  - `/backup prune dryrun` shows every decision with its reason.

## Storage layout

```
backups/cytrabackups/
  blobs/ab/cd/<sha256>          content-addressed blobs ("CYB1" header, codec, raw length, data)
  backups/000042/meta.json      id, time, comment, creator, trigger, sizes, dedup savings, versions
  backups/000042/manifest.bin   deflated binary manifest: files -> blob pieces, regions -> per-chunk blobs
  backups/000042/new-blobs.bin  blobs first written by this backup
  recycle/<restore-id>/         files replaced by a restore (for /backup rollback)
  restores/<restore-id>.json    restore records
  exports/                      /backup export output
  offsite/                      off-site queue, uploaded-blob index, pinned SFTP host keys
  state.json, pending-restore.json, last-restore-result.json
```

Blob hashes are over the *uncompressed* content, so changing the compression setting never breaks dedup. Region chunks are stored as their exact on-disk payload (compression byte + data). Restored region files are rebuilt compactly and verified against a hash of their chunk table.

## Configuration

`config/cytrabackups.json` is JSON with `//` comments. Edit it in game with **Settings…** in the GUI, or by hand followed by `/backup reload`.

Invalid values are rejected and the old config is kept:
- unknown choices;
- negative numbers;
- compression levels outside 1-19 (zstd) or 1-9 (deflate);
- ports outside 1-65535;
- permission levels outside 0-4;
- bad `HH:mm` times or time zones.

Choice values are case-insensitive (`"Shutdown"` becomes `"shutdown"`). The full default file:

```jsonc
// CytraBackups configuration. Reload with /backup reload. Comments are regenerated on save.
{
  // Where backups are stored. Relative paths are resolved against the server folder (the game folder in singleplayer,
  // where a per-world subfolder is added). Keep it outside the world folder.
  "storagePath": "backups/cytrabackups",
  // Worker threads for hashing/compression/IO. 0 = auto (half the CPU cores, max 4). Threads run at low priority.
  "workerThreads": 0,
  // Limit how fast backups read the world, in MiB/s, to protect server disk I/O. 0 = unlimited.
  "maxReadMiBPerSecond": 0,
  "compression": {
    // zstd (pure Java, recommended), deflate, or none. Already-compressed data is always stored as-is.
    "algorithm": "zstd",
    // zstd: 1-19 (3 = fast default); deflate: 1-9.
    "level": 3,
    // Keep the compressed form only if it saves at least this many percent.
    "minSavingsPercent": 3
  },
  // Store .mca region files chunk-by-chunk so a region with one changed chunk only stores that chunk again.
  // false = store region files whole (still deduplicated when completely unchanged).
  "chunkDedup": true,
  // Reuse the previous backup's data for files whose size and modification time did not change (much faster).
  "trustModificationTime": true,
  // Glob patterns (relative to the world folder) to include. Empty = everything. '**' spans folders.
  "include": [],
  // Glob patterns to exclude. Patterns without '/' match a file name at any depth. Excludes win over includes.
  "exclude": ["session.lock", "logs/**", "dynmap/**", "bluemap/**", "squaremap/**", "**/*.tmp", "**/*.lock"],
  // Abort a backup cleanly when less than this much free space (MiB) would remain on the storage disk.
  "minFreeSpaceMiB": 1024,
  // Run 'save-all flush' before each backup (recommended; briefly blocks the server thread while chunks are written).
  "flushOnSave": true,
  // Time zone for daily times and daily/weekly/monthly pruning buckets, e.g. "Europe/Berlin". "system" = server default.
  "timeZone": "system",
  "schedule": {
    // Enable automatic backups.
    "enabled": true,
    // Minutes between automatic backups. 0 = disabled (use timesOfDay only).
    "intervalMinutes": 30,
    // Additional fixed daily times in 24h "HH:mm" format, e.g. ["04:00", "16:00"].
    "timesOfDay": [],
    // Minimum minutes after server start before the first automatic backup.
    "startupDelayMinutes": 2,
    // Back up while the server is stopping (after the final save).
    "backupOnStop": false,
    // Back up when the last player leaves.
    "backupWhenLastPlayerLeaves": false,
    // Skip automatic backups unless at least one player was online since the previous backup.
    "onlyIfPlayersWereOnline": false,
    // Skip automatic backups when nothing changed since the previous one.
    "skipIfUnchanged": true,
    // Files ignored by the 'nothing changed' check (they are rewritten on every save).
    "unchangedIgnore": ["level.dat", "level.dat_old"]
  },
  "prune": {
    // Run pruning automatically.
    "enabled": true,
    // Minutes between automatic prune runs (also runs after each automatic backup when due).
    "intervalMinutes": 60,
    // Keep rules are combined: a backup survives if ANY rule keeps it. Set all to 0 to keep everything
    // (then only maxAgeDays / maxTotalSizeGiB delete). Pinned backups are never pruned.
    "keepLast": 12,
    // Keep the newest backup of each of the last N hours.
    "keepHourly": 24,
    // Keep the newest backup of each of the last N days.
    "keepDaily": 7,
    // Keep the newest backup of each of the last N weeks.
    "keepWeekly": 4,
    // Keep the newest backup of each of the last N months.
    "keepMonthly": 6,
    // Delete unpinned backups older than this many days, even if a keep rule matches. 0 = off.
    "maxAgeDays": 0.0,
    // Delete the oldest unpinned backups until the store is below this size (GiB, deduplicated). 0 = off.
    "maxTotalSizeGiB": 0.0,
    // Never prune the newest backup.
    "alwaysKeepLatest": true,
    // Keep automatic pre-restore backups for preRestoreMaxAgeDays regardless of other keep rules (0 = forever).
    "keepPreRestore": true,
    // Days to keep automatic pre-restore backups when keepPreRestore is on (0 = forever).
    "preRestoreMaxAgeDays": 14.0,
    // Delete blobs no longer referenced by any backup after pruning.
    "garbageCollect": true
  },
  "restore": {
    // Seconds a restore confirmation link stays valid.
    "confirmTimeoutSeconds": 30,
    // Broadcast countdown before players are kicked and the server stops for a restore.
    "countdownSeconds": 10,
    // When to apply a restore that needs the world closed:
    //   "startup"  = stop the server; the restore is applied on the next start before the world loads (hosts that auto-restart).
    //   "shutdown" = apply right after the server has saved and stopped, then stay stopped (hosts that do not auto-restart).
    "applyMode": "startup",
    // Message shown to kicked players.
    "kickMessage": "Restoring a world backup. The server will be back shortly.",
    // Try to restore chunk selections live (unload, write, reload) when no player is near. Otherwise queue for restart.
    "livePartialRestore": true,
    // Seconds to wait for the selected chunks to unload before giving up on a live restore.
    "liveUnloadTimeoutSeconds": 15,
    // How many previous restores keep their recycle bin (for /backup rollback).
    "recycleBinKeep": 3,
    // Maximum chunks in one partial restore.
    "maxChunks": 65536
  },
  "progress": {
    // Show a boss bar while a job runs.
    "bossBar": true,
    // Show progress in the action bar.
    "actionBar": false,
    // Who sees progress: "permitted" (cytrabackups.progress), "everyone" or "nobody".
    "showTo": "permitted",
    // Broadcast a chat message when an automatic backup finishes.
    "announceScheduled": false
  },
  "discord": {
    // Post notifications to a Discord webhook.
    "enabled": false,
    // Discord channel webhook URL (Channel settings > Integrations > Webhooks).
    "webhookUrl": "",
    // Name the webhook posts as.
    "username": "CytraBackups",
    // Post when a backup finishes.
    "onBackupSuccess": true,
    // Post when a backup fails.
    "onBackupFailure": true,
    // Post when a restore or rollback is scheduled or applied.
    "onRestore": true,
    // Post when free disk space drops below lowDiskWarningMiB.
    "onLowDisk": true,
    // Post when pruning deletes backups.
    "onPrune": false,
    // Warn when free space on the storage disk drops below this many MiB.
    "lowDiskWarningMiB": 4096,
    // Optional text prepended to failure messages, e.g. "<@&123456789>" to ping a role.
    "failureMention": ""
  },
  "offsite": {
    // Copy backups to off-site storage after they are created.
    "enabled": false,
    // "s3" (any S3-compatible service), "sftp" or "webdav".
    "type": "s3",
    // Also delete remote copies when backups are pruned/deleted locally.
    "mirrorDeletes": true,
    "s3": {
      // e.g. https://s3.eu-central-1.amazonaws.com, https://<account>.r2.cloudflarestorage.com, https://s3.us-west-004.backblazeb2.com
      "endpoint": "",
      // Region name used for request signing, e.g. us-east-1 ("auto" for Cloudflare R2).
      "region": "us-east-1",
      // Bucket name.
      "bucket": "",
      // Key prefix (folder) inside the bucket.
      "prefix": "cytrabackups/",
      // Access key ID.
      "accessKey": "",
      // Secret access key.
      "secretKey": "",
      // Path-style URLs (endpoint/bucket/key). Needed for MinIO and most self-hosted services.
      "pathStyle": true
    },
    "sftp": {
      // SFTP server host name or IP.
      "host": "",
      // SSH port.
      "port": 22,
      // Login user name.
      "username": "",
      // Login password (not needed with a private key).
      "password": "",
      // Path to a private key (relative to the server folder). Used instead of the password when set.
      "privateKey": "",
      // Passphrase of the private key, if it has one.
      "privateKeyPassphrase": "",
      // Remote folder for the backups (created if missing).
      "remoteDir": "cytrabackups",
      // "tofu" = trust the host key on first connect and pin it (stored next to the backups), "yes" = known_hosts only, "no" = never check.
      "hostKeyChecking": "tofu"
    },
    "webdav": {
      // Base URL of the target folder, e.g. https://cloud.example.com/remote.php/dav/files/me/backups/
      "url": "",
      // WebDAV user name.
      "username": "",
      // WebDAV password or app password.
      "password": ""
    }
  },
  "permissions": {
    // Op level required for each permission when no permissions mod (e.g. LuckPerms) decides.
    // Permission nodes are cytrabackups.<name>.
    "defaultLevels": {
      "list": 2,
      "create": 2,
      "comment": 2,
      "verify": 2,
      "progress": 2,
      "pin": 3,
      "cancel": 3,
      "prune": 3,
      "export": 3,
      "delete": 3,
      "restore": 4,
      "admin": 4
    }
  }
}
```

## Tests

- **Unit tests** (`./gradlew test`, 62 tests) cover the pure-Java core:
  - dedup (unchanged world adds no blobs; one changed chunk stores exactly one blob; duplicate files; large-file pieces; skip-if-unchanged)
  - manifest round-trip
  - every pruning strategy (keep-last, hourly/daily/weekly/monthly, ISO weeks, max age, max size with shared blobs, pins, pre-restore window)
  - compression round-trip at several zstd levels, deflate, incompressible and already-compressed data
  - hash verification and corruption detection
  - full restore, chunk restore, recycle-bin rollback, journal crash recovery, aborting a restore on a corrupt blob
  - garbage collection, diff, config parsing and validation, off-site sync and destination switching, S3 SigV4 key derivation
  - the GUI settings editor: every setting has a type and tooltip, secrets are never sent, edits apply to a copy, bad values give readable problems, saved edits survive a reload
  - the Discord client against a local webhook endpoint (payload format, 429 `Retry-After` retries, HTTP errors, Discord's length limits)
- **Off-site integration tests** (`./gradlew integrationTest`, 12 tests) use real servers embedded in the test:
  - **S3:** [S3Proxy](https://github.com/gaul/s3proxy), which verifies every AWS SigV4 signature. Covers keys with special characters, a wrong secret (403), and a full mirror including pruned deletes.
  - **SFTP:** Apache MINA SSHD. Covers password and private-key login, trust-on-first-use pinning, refusing a changed host key, strict and disabled checking, and a full sync.
  - **WebDAV:** Tomcat's WebdavServlet with basic auth. Covers folder creation (including the base folder), bad credentials (401), and a full sync.
- **Server GameTests** (`./gradlew runGameTest`), on a real headless 1.21.11 server:
  1. back up
  2. modify the world and back up again
  3. full restore and rollback through the startup-restore code path (checking the block in the restored region NBT)
  4. modify again, unload the chunk, **live chunk restore**, then reload it and assert the original block is back
  5. the live-restore distance check matches Minecraft's real ticket system (chunk holders reach exactly view distance + 13 chunks)
  6. the GUI command channel with real (mock-connection) players:
     - an op's GUI action runs and its output reaches the GUI;
     - "delete" asks through a dialog request, and denying it keeps the backup;
     - a non-op gets exactly the refusal typing the command gives;
     - non-`/backup` commands are refused
  7. fabric-permissions-api decisions (what LuckPerms and similar mods plug into) override the op fallback in both directions, including command-tree visibility
- **Client GameTest** (`runClientGameTest`, not yet verified): a real client with a singleplayer world, driven with mouse and keyboard. It is written to check that:
  - `/backupgui` opens the list;
  - "Create backup" and "Pin" work;
  - a drag-selected chunk restore in the map selector is queued, because the player is standing in the area;
  - "Restore..." starts the countdown;
  - a queued full restore is applied by the world-open mixin when the save is reopened.

  It has not passed in the headless build sandbox. There, under Xvfb with software OpenGL, the integrated server stalls at "Preparing spawn area: 16%" before the test code runs. Run it on a desktop, or check these steps by hand in game.
- **Vanilla client** (no mod installed), checked with a [mineflayer](https://github.com/PrismarineJS/mineflayer) bot on protocol 1.21.11:
  - Non-ops cannot see `/backup`.
  - List rows carry `run_command`/`suggest_command` click events, and confirmations work by running the clicked command.
  - The boss bar shows during backups.
  - A chunk restore next to the player is queued, with the reason stated.
  - Leaving triggers the last-player-left backup.
  - The restore countdown appears in chat and the action bar, followed by a kick with the configured message.
  - An op is told the restore result on the next join.

## Design notes

- The design was informed by [x-backup](https://github.com/zly2006/x-backup): a blob store with per-backup file lists, region and chunk restores, and keep policies. **x-backup is licensed AGPL-3.0, so no code was copied.** CytraBackups is an independent Java implementation with its own formats and is MIT-licensed.
- Loose blob files keep existence checks and garbage collection free of large in-memory indexes. The cost is many small files in `blobs/` on large worlds, spread over a two-level (256×256) fan-out.
- Region files are read by position with a size and modification-time check before and after each read. Files that change mid-read are re-read, and chunk payloads are validated. During the copy, autosave is off; in 1.21.11 that also stops chunk unloading, so region files stay still.
