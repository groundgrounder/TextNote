# TextNote

A lightweight Android text editor · byte-exact · Kotlin + Jetpack Compose + Material 3

**English** | [简体中文](README.zh-CN.md)

[![Android CI](https://github.com/groundgrounder/TextNote/actions/workflows/android.yml/badge.svg?branch=main)](https://github.com/groundgrounder/TextNote/actions/workflows/android.yml)

<p>
  <img src="docs/screenshots/home.png" width="30%" alt="Recent files">
  <img src="docs/screenshots/editor.png" width="30%" alt="Editor">
  <img src="docs/screenshots/settings.png" width="30%" alt="Settings">
</p>

For people who open a file, change two lines and put it back. **Fidelity comes first**: open a file,
save it without touching anything, and not one byte should have changed — encoding, line endings and
BOM all go back exactly as they came.

## Features

**Writing**

- Editing, undo/redo (consecutive typing coalesces into one step), find and replace
  (regex / case / whole word / `$1` templates)
- Hardware keyboards: `Ctrl+Z` to undo, `Ctrl+Shift+Z` / `Ctrl+Y` to redo
- A status bar that always shows the document facts: encoding, line endings, syntax, problem count,
  line and column, characters and lines

**Reading**

- Syntax highlighting for 34 languages, picked by file **name**: the whole name first
  (`Makefile`, `Dockerfile`, `CMakeLists.txt`, `.gitignore`), then the extension — overridable per
  file and **remembered per file**
- Patches (`.diff` / `.patch` / `.rej`) are coloured by **line prefix**, so added and removed
  blocks stand out at a glance
- A line-number gutter; once a large file switches to read-only browsing, find and jump still work

**Not breaking your file**

- Byte-exact round trip: encoding (UTF-8 / GB18030 / BOM) and line endings (LF / CRLF / CR) are
  detected on read and preserved on write; a file mixing several endings says so
- Drafts are flushed the moment the app goes to the background; it only asks "restore / discard"
  when the draft **actually differs** from the file, and never applies it on its own; drafts are
  kept for 30 days, and the home screen warns before one expires
- If another app changes the file, you get told — it is not applied behind your back
- A file that isn't text (null bytes, mostly unprintable characters) is refused with a reason
  instead of being shown as a screen of garbage

**Limits and safeguards**

- Large files come in three tiers: past 64k characters highlighting stops and a warning appears,
  past 200k characters the file becomes **read-only**, past 4 MB it refuses to open at all
  (rather than gambling with memory)
- JSON is validated as you type: the first syntax error is marked in place, the status bar shows the
  problem count, and tapping it jumps there and says what's wrong; `.jsonc` / `.json5` are checked
  leniently (comments and trailing commas allowed)

**Appearance**

- Theme: follow system / light / dark, with dynamic color (Android 12+)
- Font family, size and line spacing; soft wrap can be turned off (off means horizontal scrolling)
- UI language: follow system / English / 简体中文 / 繁體中文 / Latina
- Multi-window: two documents side by side in split screen or on the desktop, each with its own
  edits; long-press a recent entry for "Open in new window"

## Download

Grab `TextNote-vX.Y.Z.apk` from
[Releases](https://github.com/groundgrounder/TextNote/releases/latest) and install it (you'll need
to allow "install unknown apps" the first time). Requires Android 8.0 (API 26) or newer.

## Usage

- **Open file** goes through the system file picker; **New file** starts from scratch
- Pick TextNote straight from a file manager: matching is by MIME (`text/*` plus a set of
  `application/*` types), with a long list of source and config extensions as a fallback
- **Share a text file** to TextNote from another app and it opens just the same
- No storage permission is requested: every read and write goes through the picker's grant

## Known limits (deliberate trade-offs)

- **No Markdown preview**: this is a plain-text editor, not a Markdown app
- **Highlighting is a line-by-line approximation**, not a compiler front end: nested block comments,
  Rust raw strings `r#"…"#` and Ruby / Shell heredocs are not parsed — a multi-line construct is
  recognised as one opening delimiter plus one closing delimiter
- **"Is this text?" is judged on the decoded content**: UTF-16 with a BOM opens fine; UTF-16 without
  a BOM, and a null byte past the first 8,000 characters, are the known blind spots
- **Checks are local and single-file** (JSON only so far): there is no language server, so no
  completion, hover or go-to-definition, and no project-wide analysis
- **No bundled fonts** — only the three system families; columns are counted in UTF-16 code units,
  so an emoji takes two
- **Soft wrap only applies to editable documents**: read-only browsing of large files loads line by
  line, and wrapping would mean re-running line breaking for every one of them, so it never wraps
- **Big5 is not detected separately**: GB18030 covers its encoding space, and fidelity holds as long
  as the same encoding is written back

## Building from source

JDK 17 and the Android SDK (`sdk.dir` in `local.properties`).

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Architecture

`core/` is pure Kotlin — no `androidx.compose.*`, no `android.*`, so its assertions run under a
plain JVM. `data/` holds SAF I/O and drafts; `ui/` is the Compose layer. The swappable kernel is
enforced by package boundaries rather than a fat interface: `ui/` only talks to
`EditorViewModel`'s state. Those assertions need no emulator, and CI runs them on every push.

## License

Copyright (C) 2026 groundgrounder

TextNote is free software: you can redistribute it and/or modify it under the terms of the
**GNU General Public License** as published by the Free Software Foundation, either version 3
of the License, or (at your option) any later version.

TextNote is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
[GNU General Public License](https://www.gnu.org/licenses/gpl-3.0.html) for more details.

You should have received a copy of the GNU General Public License along with this program.
If not, see <https://www.gnu.org/licenses/>.
