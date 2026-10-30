# Alloy Backups

Chunk-level deduplicated world backups for Fabric servers and singleplayer, with off-site copies, safe restores and server migration. Workspace rules: `../CLAUDE.md`. Backups settings live **only** in this mod's own menu: never build a Hub plugin page for it (user decision).

## What it is
- id `cytrabackups` (was CytraBackups), display name "Alloy Backups", package `dev.steelaspect.cytrabackups`, jar `alloy-backups-<mod_version>.jar` (1.9.0 on dev/main at last check, last release 1.8.1). `environment: *`: server/singleplayer core, optional client menu.
- Repo `SteelAspect/cytrabackups`, `dev` -> `main`. Docs: `README.md`, `CHANGELOG.md`, `DESIGN.md` (1.8.0 Nocturne menus), `docs/DESIGN-migrate.md` (1.9.0 server move), `PROGRESS.txt`. 26.3 port on branch `mc26.3` (`../26.3`).

## Stack
- MC 1.21.11, Loader 0.19.5, Fabric API 0.141.6+1.21.11, Loom 1.17.21, **Mojang mappings**, Java 21 (`~/.local/share/PrismLauncher/java/java-runtime-delta`). Gradle configuration cache is off.
- jar-in-jar: Alloy UI `alloyui_version=1.3.1`, fabric-permissions-api 0.6.1, aircompressor 2.0.3, zstd-jni 1.5.7-6, JSch (SFTP) 2.28.7. Mod Menu 17.0.1 (compile + dev runtime). JUnit 5.11.4. Optional dev: `-PwithSyncmatica` loads a local private Syncmatica jar.
- Source sets: `main`, `client`, `test`, `gametest`, `integrationTest`.

## Code map
- Entrypoints `CytraBackups` (main), `client.CytraBackupsClient` (command `/cbackupgui`), `client.ModMenuIntegration`. Server command `/cbackup ...` (`mc/BackupCommands`, permissions in `mc/Perms`).
- `core/` (no Minecraft deps where possible): `backup/` (`BackupEngine`, `BackupService`, `BackupRepository`, `Verifier`, filters/triggers), `store/BlobStore`, `manifest/`, `region/` (chunk payloads), `compress/` (zstd/aircompressor), `restore/` (`RestoreEngine`, `RestorePlan`, `PendingOperationRunner`), `prune/` (`Pruner`, `GarbageCollector`), `offsite/` (`S3Target`, `SftpTarget`, `WebDavTarget`, `OffsiteSync`), `migrate/ServerMigration`, `transfer/` (zip export, world import), `notify/DiscordWebhook`, `config/` (`CytraConfig`, `ConfigSchema`, `Secret`; file `config/cytrabackups.json`).
- `mc/`: `BackupManager`, `LiveChunkRestore`, `StartupRestore`, `SaveControl`, `Notifier`, `BackupPreview`, `SyncmaticaShare`, `MigrationImport`, feedback/chat helpers. `net/` payloads for the GUI (`BackupListPayload`, `ConfigPayload`, `ConfigSavePayload`, `CommandPayload`...). Client screens: `BackupScreen`, `SettingsScreen`, `ChunkSelectorScreen`, `Dialogs`, `BackupFrame`.
- Mixin: `LevelStorageAccessMixin` (`LevelStorageSource.LevelStorageAccess.<init>` TAIL). No Hub page.

## Build and test
```bash
export JAVA_HOME=~/.local/share/PrismLauncher/java/java-runtime-delta
./gradlew build                                   # JUnit (31 test classes incl. core + mc)
./gradlew runGameTest                             # server game tests (CytraBackups, Permissions, GuiChannel, Migrate)
./gradlew runGameTest -Pgt=<full test id>         # one test
./gradlew runClientGameTest                       # GuiPreview, full GUI flow, Mod Menu
./gradlew integrationTest                         # off-site copy against embedded S3, SFTP, WebDAV (S3Proxy); also MigrateIntegrationTest
```
Headless and muted. Test the built jar for restore/migrate changes; never restore onto the user's real worlds.

## Cross-mod links
- `mc/SyncmaticaShare` optionally shares a backup preview with Alloy Syncmatica (soft). No Hub page by decision. Discord notifications via webhook only (no bot).
- Alloy UI: **alloy-ui** chat.

## Decisions and gotchas
- Restores are safe and undoable (pending operation + restore record); big files restore/import in parallel batches of 8.
- Migration (`/cbackup migrate export|list|import`, import at next start with rollback): refuses import from its own location, keeps its own off-site destination, rollback needs admin. Panel hosts may reset the level name after a move (README).
- Secrets (S3/SFTP/WebDAV keys) go through `config/Secret`; never log them.
- Server needs no client classes; the GUI talks over `net/` payloads that the server validates.

## Release
- Bump `mod_version`, CHANGELOG, `python ../cytra-releases/release.py build/libs/alloy-backups-<v>.jar "<changelog>"` (tags in this repo are `vX.Y.Z`). Server jar swaps are server changes: ask first. Never release without asking.

## Gaps and improvement ideas
- 1.9.0 (migrate) is on `main` but unreleased; PROGRESS's last line (2026-10-24) says the first real move (#73) was running with the old build: no result recorded.
- Alloy UI 1.3.1 vs 1.4.0.
- README/CHANGELOG still say "formerly CytraBackups" in places; check the Download link and badges after the 1.9.0 release.
- `core/config/ConfigSchema` drives the GUI settings; there is no test that every config key has a lang string.
- Last git tag is `v1.7.0` while 1.8.0/1.8.1 were released: tags are not kept in step with `cytra-releases`.
