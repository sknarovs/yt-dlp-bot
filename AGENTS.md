# AGENTS.md

## Running

```bash
export BOT_TOKEN="..."  # required — hard crash (KeyError) if missing
python TelegramBot.py
```

Python 3.11+ and `ffmpeg` must be installed. For Docker: `docker build -t yt-dlp-bot . && docker run -e BOT_TOKEN="..." yt-dlp-bot`.

## Dependencies

```bash
pip install -r requirements.txt
```

`yt-dlp-ejs` requires **Deno** at runtime (the Dockerfile installs it; local dev needs Deno on PATH).

## Architecture

Single-file app: `TelegramBot.py` contains all config, download logic, bot handlers, and entrypoint. No packages, no modules.

- `downloads/` — created at **import time** via `os.makedirs`, gitignored
- `cookies.txt` — optional, place at project root for authenticated downloads

## Non-obvious behavior

- **No playlist downloads** — `noplaylist: True` is hardcoded in yt-dlp opts
- **Download format** — capped at 720p (`bestvideo[height<=720]+bestaudio`), merged to mp4
- **MAX_SIZE = 45 MB** — hardcoded, not configurable via env. Applied both as yt-dlp `max_filesize` and as a post-download check that deletes the file
- **Retry logic** — `DownloadError` is **not retried** (breaks immediately); other exceptions retry up to `MAX_RETRIES=3`
- **Downloads run in a thread pool** (`asyncio.to_thread`) — blocking yt-dlp calls don't block the event loop

## CI

- **docker-build.yml** — builds and pushes multi-arch (amd64 + arm64) image to `ghcr.io` on push to `main`
- **yt-dlp-release.yml** — daily cron checks for new yt-dlp release; rebuilds image if new version detected, also tags with yt-dlp version. Commits updated `.yt-dlp-version` back to `main`

## Quality

No tests, linter, formatter, or type checker configured.