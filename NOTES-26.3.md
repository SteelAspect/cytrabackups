# Alloy Backups on Minecraft 26.3

Branch `mc26.3`, a one-off port of `main` at 1.9.0 (5e6d784). Version `1.9.0+26.3`, jar `alloy-backups-1.9.0+26.3.jar`.
New features and fixes go to 1.21.11 (`dev`) only.

## Stack

Minecraft 26.3 (unobfuscated, no remapping), Fabric Loader 0.20.1, Fabric API 0.165.0+26.3, Loom 1.19.3, Java 25,
Alloy UI 1.4.0+26.3 and fabric-permissions-api 0.7.0 (both jar-in-jar). The access widener uses the `official` namespace.

## What changed

- **World layout:** Minecraft 26 keeps every dimension, the overworld included, in `dimensions/<namespace>/<path>/`.
  The mod asks Minecraft for each dimension's folder, so backups and restores follow it. Only `DimensionPaths.folder()`
  (queued restores by dimension id) and the tests had the old layout hard-coded.
- **Names:** `ChunkPos` is a record (`x()`, `z()`, `pack()`, `unpack()`); payload registries are
  `serverboundPlay()`/`clientboundPlay()`; `ServerBossEvent` takes a UUID; client commands are `ClientCommands`, key
  mappings `KeyMappingHelper.registerKeyMapping`; screens use `GuiGraphicsExtractor`, `mc.gui.screen()`/`setScreen`,
  SDL mouse buttons and `InputConstants` keys (see `alloy-ui/NOTES-26.3.md`).
- **Block states in NBT** are written as `{id:...}` instead of `{Name:...}`. Restore previews (`.litematic`) use
  vanilla's writer, so they carry the new form; Litematica's 26.3 build reads them through the same code.

## Left out

- **Mod Menu button:** there is no Mod Menu build for 26.3 (20.x stops before 26.3, 22.x needs 26.4).
  The menu still opens with `/cbackupgui`, the key or the pause-menu button.
- **Alloy Syncmatica sharing of previews** is optional as before. Without it previews are saved as `.litematic` files.
  Not tested against the 26.3 Syncmatica port (still in progress).

## Tested (headless)

- Server game tests, 7/7: backup, full restore and rollback on disk, live area restore and its undo,
  preview, chunk holder range, permissions, GUI channel, migrate export.
- A real dedicated 26.3 server (`runServer`): backup, block changed, `/cbackup restore` + confirm, server stops,
  the restore is applied on the next start before the world loads, block back.
- Client game tests: GUI preview at five screen sizes, the full GUI flow (create, pin, area preview, area restore,
  full restore countdown) and a singleplayer restore applied on world open.
- Unit tests pass. The off-site integration tests (S3/SFTP/WebDAV) were not rerun: that code is pure Java and unchanged.
