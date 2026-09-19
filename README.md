# TextNote

A lightweight Android text editor · byte-exact

**English** | [简体中文](README.zh-CN.md)

[![Android CI](https://github.com/groundgrounder/TextNote/actions/workflows/android.yml/badge.svg?branch=main)](https://github.com/groundgrounder/TextNote/actions/workflows/android.yml)

<p>
  <img src="docs/screenshots/home.png" width="30%" alt="Recent files">
  <img src="docs/screenshots/editor.png" width="30%" alt="Editor">
  <img src="docs/screenshots/settings.png" width="30%" alt="Settings">
</p>

Open a file, change two words, save — and nothing about the file should have changed. That's what
TextNote is for: UTF-8, GB18030 and BOM'd UTF-16, LF, CRLF and CR line endings, read and written
back exactly as they came, byte for byte.

## Features

**Writing**

- Undo, redo, find and replace (regex, match case, whole word, `$1` templates)
- On a hardware keyboard: `Ctrl+Z` to undo, `Ctrl+Shift+Z` or `Ctrl+Y` to redo
- The bar along the bottom always tells you what you're looking at: encoding, line endings, syntax,
  line and column, characters and lines

**Reading**

- Syntax highlighting for 34 languages, picked by file name — it knows `Makefile`, `Dockerfile`,
  `CMakeLists.txt` and `.gitignore`; you can also choose a language yourself, and it remembers, so
  the next time you open that file it's still the one you picked
- A line-number gutter; `.diff` / `.patch` / `.rej` are coloured by their leading `+` and `-`, so
  you can see what changed at a glance

**Keeping your file safe**

- If a file mixes several kinds of line ending, it says so — saving with any one of them would
  rewrite part of the file
- A draft is saved the moment you leave the app; you're only asked "restore or discard" when the
  draft really is different from the file, and it never decides for you. Drafts are kept for 30
  days, and the home screen warns you before one expires
- If another app changes the file, you're told — what you're looking at isn't touched
- A file that isn't text (null bytes, control characters) is refused with a reason instead of a
  screen of gibberish

**Large files**

- Past 64k characters: no more highlighting, plus a warning that typing may feel slow
- Past 200k characters: read-only, but find and jump still work
- Past 4 MB: it won't open at all — reading that much into memory would bring the app down, and
  that's a memory problem rather than a comfort one

**JSON**

- Checked as you type: the first error is marked in the text, the status bar shows how many there
  are, and tapping it jumps there and says what's wrong
- `.jsonc` and `.json5` are checked leniently: comments and trailing commas are fine

**Appearance**

- Follow the system, light or dark, with dynamic colour (Android 12 and up)
- Font, size and line spacing are adjustable, and soft wrap can be turned off (off means sideways
  scrolling)
- UI language: follow system / English / 简体中文 / 繁體中文 / Latina
- Two documents side by side in split screen or a desktop window, each with its own edits;
  long-press a recent file for "Open in new window"

## Download

Get `TextNote-vX.Y.Z.apk` from
[Releases](https://github.com/groundgrounder/TextNote/releases/latest) and install it (the first
install needs "install unknown apps" allowed). Android 8.0 and up.

## Usage

- "Open file" goes through the system picker; "New file" starts you off empty
- Pick TextNote from a file manager, or share a text file to it from another app
- No storage permission is requested — reading and writing go through the one-off grant the system
  picker gives you

## Known limits

- No Markdown preview: this is a plain-text editor
- Highlighting guesses, line by line — it isn't a compiler: nested block comments, Rust's
  `r#"…"#` and Ruby / Shell heredocs aren't parsed, and a multi-line construct is understood as
  just "one opening token plus one closing token"
- UTF-16 without a BOM is treated as binary (convert it to UTF-8 in another tool and it opens
  fine); a null byte past the first 8,000 characters goes unnoticed
- Checks are local and single-file (JSON for now): no completion, no hover, no go-to-definition,
  and no project-wide analysis
- No bundled fonts — the three system families only; columns count UTF-16 units, so an emoji
  takes two
- Soft wrap applies to editable documents only: read-only browsing loads line by line, and
  wrapping would mean re-measuring every one of them, so it doesn't wrap
- Big5 isn't detected separately: GB18030 already covers its encoding space

## Building it yourself

You'll need JDK 17 and the Android SDK (`sdk.dir` in `local.properties`):

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

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
