# Alloy Backups 1.8.0: Nocturne menus

Status: **built** on 2026-10-20 (1.8.0). Small differences from the plan: the settings rows use Alloy UI's `Rows` helpers directly (same widgets as `Options`), and the Mod Menu notice is a one-button notice instead of a `Dialog`.

## Goal

Make the backup menu look like the rest of Alloy. It should use the Nocturne theme through the shared Alloy UI library, so whatever theme the player picks in the Hub applies here too.

Nothing else changes:
- The server side, commands, config and backup data stay the same.
- The network messages stay the same. A 1.8.0 client still works with a 1.6.2 or newer server, and a server needs no update.

Out of scope:
- **No Alloy Hub plugin page.** The user decided on 2026-10-19 that settings stay in the mod's own menu.
- The Hub has its own small built-in Backups entry (the menu key plus an "Open backup browser" button). It lives in the cytra-hub repo and is not touched here.

## Dependency

- Add `dev.steelaspect:alloy-ui:1.3.0` as a jar-in-jar dependency (from mavenLocal), with `"alloy-ui": ">=1.3.0 <2.0.0"` in `depends`.
- Only client code uses it. A server-only install carries the bundled jar but never opens a screen.

## Screens

| Now (vanilla) | 1.8.0 (Alloy UI) |
|---|---|
| `BackupScreen`: 3 tabs (Backups, Restore, Tools), a vanilla list and grey buttons | An `AlloyScreen` frame with a header (title, then server status on the right) and `Tabs` with the same 3 tabs. The backup list goes in a `ScrollPanel` with two-line rows (name and comment, then date, size and pin). The actions are Alloy `Button`s with one primary button per tab ("Create backup" on Backups). |
| `LogPanel` (server messages under the list) | Stays as a panel under the list, in Nocturne colours. Errors and finished jobs also show a toast. |
| `SettingsScreen`: a section list and a vanilla setting list | `Options` built from the `ConfigSchema` entries the server sends. Sections fold. Rows get a ↺ reset button, and pill toggles replace the ON/OFF buttons. Save and Revert sit in the footer, with Save as the primary button. On a narrow window the sections stack. |
| `FormScreen` (comment, export path…) | An Alloy `Dialog` with `TextField`s. |
| Server confirm prompts (vanilla `ConfirmScreen`) | An Alloy `Dialog`. The confirm button is red for destructive actions (restore, delete, prune). |
| `ChunkSelectorScreen` (draw a chunk area) | An `AlloyScreen` with the map on the canvas hooks (`renderCanvas`, `canvasDragged`…) and the Restore, Copy command and Back buttons as Alloy widgets. The grid colours come from theme tokens. |
| Pause menu "Backups" button | Stays vanilla, because it sits in the vanilla pause menu. |
| Mod Menu entry (added in 1.7.2 on dev) | Opens the new screen. The "join a world" notice becomes an Alloy `Dialog`. |

All hard-coded greys (`0xFFA0A0A0` and the like) go. All text stays translatable. Existing keys are reused, and new keys are added to `en_us.json`.

## Rules followed

These come from the `minecraft-ui` skill:
- The frame is at most 640 × 340 and is sized from the window on every resize.
- No more than 4 tabs, all on one row.
- One primary button per view.
- Long names are cut with "…", with the full text in a tooltip.
- It is checked at GUI scale 1 to 4 and in a 640 × 400 window.

## Tests

- Update `CytraBackupsClientGameTest` and `GuiPreviewClientGameTest` for the new button labels and layout. They drive the real menu, so they cover create, pin, area restore, full restore and confirm.
- The new `ModMenuClientGameTest` keeps checking the Mod Menu path.
- Retake the README screenshots from the preview test.

## Version

**1.8.0.** The menus change visibly, and Alloy UI becomes a new bundled dependency. The work happens on `dev` and is merged to `main` after an in-game test.

## Open questions for the user

1. Is the Hub's existing small Backups entry (menu key plus "Open backup browser") fine as it is?
2. Should the pause menu button stay where it is now (bottom-left)?
