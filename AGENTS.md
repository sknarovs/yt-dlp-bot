# AGENTS.md

## Running

```bash
export BOT_TOKEN="..."                              # required — exits with code 1 if missing
export TELEGRAM_API_URL="https://api.telegram.org"  # default is http://telegram-bot-api:8081 (compose)
./gradlew run
```

JDK 25, `yt-dlp` (+ `yt-dlp-ejs`), `ffmpeg` and Deno must be on `PATH`. In production: `docker compose up -d` (or `podman compose up -d`) with a `.env` file — see `.env.example` and README.

## Build

```bash
./gradlew test          # unit tests
./gradlew installDist   # build/install/yt-dlp-bot/bin/yt-dlp-bot
```

Gradle Kotlin DSL, wrapper committed. Dependencies: TelegramBots 10.x (`telegrambots-longpolling`, `telegrambots-client`), Logback; tests use JUnit and Mockito.

## Architecture

Package `lv.sknarovs.bot`:

- `Main` — wiring; waits for the Bot API server, then registers the long-polling bot
- `BotConfig` — env vars → record
- `YtDlpDownloader` — runs the `yt-dlp` CLI as a subprocess
- `VideoBot` — update consumer; per-URL download → size check → `sendVideo` → delete
- `ChatActionHeartbeat` — "sending video…" chat action while working

`downloads/` is created at startup and gitignored. `cookies.txt` (optional) in the working dir is passed to yt-dlp.

## Non-obvious behavior

- **Local Bot API server required for >50 MB** — compose runs `aiogram/telegram-bot-api` in local mode (2000 MB uploads). The bot must be logged out of `api.telegram.org` once (`/logOut`) before using it.
- **Startup waits for the API server** — `GetMe` every 5 s until reachable; TelegramBots crashes if `registerBot` runs while the server is down. An error *response* (e.g. 401 wrong token) exits with code 1 instead of retrying.
- **No playlist downloads** — `--no-playlist`
- **Download format** — capped at 720p (`bestvideo[height<=720]+bestaudio/bestvideo+bestaudio/best`), merged to mp4
- **MAX_FILE_SIZE_MB** (default 2000) — passed as yt-dlp `--max-filesize` and checked again after download (merged output can exceed it)
- **yt-dlp exits 0 with no output when a file is over `--max-filesize`** — treated as a download failure
- **File path comes from yt-dlp** — `--print after_move:filepath`, last non-blank stdout line
- **yt-dlp timeout** — 30 minutes, then the process tree is killed
- **No retry on download/upload failure** — logged, URL skipped, nothing sent to the user
- **Concurrency** — each message on its own virtual thread; URLs within a message processed sequentially
- **Chat action heartbeat** — `upload_video` every 4.5 s during download/upload
- **Upload HTTP read timeout is 30 minutes** — the local server replies only after re-uploading to Telegram

## CI

- **docker-build.yml** — builds and pushes multi-arch (amd64 + arm64) image to `ghcr.io` on push to `main`. The Gradle build (incl. tests) runs inside the Dockerfile on the build host's architecture.
- **yt-dlp-release.yml** — daily cron checks for new yt-dlp release; rebuilds image if new version detected, also tags with yt-dlp version. Commits updated `.yt-dlp-version` back to `main`

## Quality

JUnit + Mockito unit tests (`./gradlew test`), also run during the image build. No linter or formatter configured.
