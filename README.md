# TextNote

A lightweight Android text editor · edits the files already on your device · no storage permission, no network

**English** | [简体中文](README.zh-CN.md)

[![Android CI](https://github.com/groundgrounder/TextNote/actions/workflows/android.yml/badge.svg?branch=main)](https://github.com/groundgrounder/TextNote/actions/workflows/android.yml)

<p>
  <img src="docs/screenshots/home.png" width="30%" alt="Recent files">
  <img src="docs/screenshots/editor.png" width="30%" alt="Editor">
  <img src="docs/screenshots/settings.png" width="30%" alt="Settings">
</p>

## What it is

A plain-text editor for your phone — for config files, logs, scripts, or a couple of lines you want
to jot down. Open a file, change two lines, save it, and nothing about the file should have changed.

- **Unchanged when you save**: a GBK config stays GBK, a CRLF script stays CRLF — you don't have to
  reformat a file just to change two lines
- **No computer needed**: server configs, `.env` files, startup scripts and logs — the files you'd
  normally open a laptop to change
- **Big files stay readable**: a log running to hundreds of thousands of characters switches to
  read-only browsing, where paging, searching and jumping all still work
- **Free and open source, no account, no network, no storage permission**: reading and writing go
  through the one-off grant the system file picker gives you

## Download

Grab the latest APK (`TextNote-vX.Y.Z.apk`) from
[Releases](https://github.com/groundgrounder/TextNote/releases).

- Requires Android 8.0 (API 26) or newer
- The APK does not come from an app store, so on first install the system will ask you to allow
  installs from unknown sources

## Features

**Files**

- Open: tap a text file in any file manager and pick "Open with TextNote" — or share it to TextNote
  from another app
- Create: tap "New file", pick a location and a name, and start writing
- Encoding and line endings untouched: UTF-8, GB18030, GBK and BOM'd UTF-16 all read correctly, and
  a file is saved back the way it came; if a file mixes several kinds of line ending, it says so
- Recent files: everything you open stays on the home screen and survives a reboot. Remove an entry
  you no longer need without touching the file; anything that can no longer be read is flagged, and
  tapping it walks you through granting access again
- Your changes are kept: it never writes to your file on its own — **it saves when you tell it to** —
  but the moment you leave the app a draft is stored inside the app, so a backgrounded process
  can't lose your work; next time it asks whether to restore it. Drafts are kept for 30 days, and
  the home screen warns you before one expires
- If another app changes the file, you're told — what you're looking at isn't touched
- How big is too big: up to 64k characters everything is editable; past that highlighting stops and
  a warning appears; past 200k the file becomes read-only; past 4 MB it won't open at all — reading
  that much into memory would bring the app down
- A file that isn't text (null bytes, control characters) is refused with a reason instead of a
  screen of gibberish; UTF-16 without a BOM reads as binary, for instance — convert it to UTF-8 in
  another tool and it opens fine

**Editing**

- Undo and redo: consecutive typing counts as one step, and the buttons grey out when there's
  nothing left
- Find and replace: shows "match 3 of 12", wraps around, replaces just this one or all of them;
  supports regex, match case and whole word, and `$1` in the replacement for captured groups
- On a hardware keyboard: `Ctrl+Z` to undo, `Ctrl+Shift+Z` or `Ctrl+Y` to redo
- The bar along the bottom always tells you what you're looking at: encoding, line endings, syntax,
  problem count, line and column, characters and lines

**Highlighting**

- Syntax highlighting for 34 languages, picked by file name — it knows `Makefile`, `Dockerfile`,
  `CMakeLists.txt` and `.gitignore`; you can also choose a language yourself, and it remembers, so
  the next time you open that file it's still the one you picked
- `.diff` / `.patch` / `.rej` are coloured by their leading `+` and `-`, so you can see what changed
  at a glance
- A line-number gutter

**JSON**

- Checked as you type, entirely on the device with no network involved: the first error is marked
  in the text, the status bar shows how many there are, and tapping it jumps there and says what's
  wrong
- `.jsonc` and `.json5` are checked leniently: comments and trailing commas are fine

**Multi-window**

- One window, one document: open several at once in split screen or in desktop windows on a tablet,
  each with its own edits
- Open another: long-press a recent file and pick "Open in new window". The same file never opens a
  second editor, so two windows can't overwrite each other's work

**Appearance and language**

- Follow the system, light or dark, with wallpaper-based colour (Android 12 and up)
- Font, size and line spacing are adjustable, and soft wrap can be turned off (off means sideways
  scrolling)
- Four UI languages: English, 简体中文, 繁體中文 and Latina; follows the system by default, and you
  can switch inside the app

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
