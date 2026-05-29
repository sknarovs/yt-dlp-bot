# AGENTS.md

## Project Overview

Single-file Telegram bot (`TelegramBot.py`) that downloads videos via yt-dlp and sends them to chat. No packages, no modules — the entire app lives in one file.

## Running

```bash
# Required env var (missing token crashes at startup)
export BOT_TOKEN="..."

# Run directly
python TelegramBot.py

# Or via Docker
docker build -t yt-dlp-bot .
docker run -e BOT_TOKEN="..." yt-dlp-bot
```

Python 3.11+ required. `ffmpeg` must be installed locally.

## Dependencies

```bash
pip install -r requirements.txt
```

Key dependencies: `python-telegram-bot`, `yt-dlp`, `yt-dlp-ejs`. The `yt-dlp-ejs` package requires Deno at runtime (Docker image installs it).

## Configuration

- `BOT_TOKEN` env var — **required**, no default, hard crash if missing
- `cookies.txt` — optional, place at project root for authenticated site downloads
- `MAX_SIZE` — 45 MB hardcoded in `TelegramBot.py`, not configurable via env

## Architecture

- `TelegramBot.py` — entire application: config, download logic, bot handlers, entrypoint
- `downloads/` — created at runtime for temp files, gitignored
- No tests, linter, formatter, or type checker configured

## CI

Two workflows in `.github/workflows/`:

- **docker-build.yml** — builds and pushes multi-arch Docker image on push to `main`
- **yt-dlp-release.yml** — daily check for new yt-dlp releases; rebuilds image if new version detected, also tags with yt-dlp version

`.yt-dlp-version` tracks last-built yt-dlp release. The release workflow commits updates to this file back to `main`.