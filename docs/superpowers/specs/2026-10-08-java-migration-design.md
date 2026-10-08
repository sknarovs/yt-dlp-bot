# Java Migration — Design

Date: 2026-10-08
Status: Approved in conversation, pending written-spec review

## Goal

Rewrite the yt-dlp Telegram bot from Python (`TelegramBot.py`) to Java so the
maintainer (a Java developer) can support it comfortably, and lift the 50 MB
upload ceiling by running a self-hosted Telegram Bot API server.

## Success criteria

- The bot behaves like today from a user's point of view: send a message with
  one or more URLs → receive each video as a reply to that message.
- Videos up to 2000 MB can be delivered (previously 45 MB).
- Image builds for `linux/amd64` and `linux/arm64` and runs on a 64-bit
  Raspberry Pi under Docker, and on a laptop under Podman.
- The existing CI (push-to-main image build, daily yt-dlp release rebuild)
  keeps working.

## Decisions

| Topic | Decision |
|---|---|
| Language / runtime | Java 25 (LTS) |
| Build tool | Gradle, Kotlin DSL (`build.gradle.kts`), Gradle wrapper committed |
| Telegram library | TelegramBots (`org.telegram:telegrambots-longpolling` + `org.telegram:telegrambots-client`), latest stable version pinned during planning |
| Framework | None (no Spring) |
| yt-dlp integration | Invoke the `yt-dlp` CLI as a subprocess (no Java port of yt-dlp exists) |
| Large files | Self-hosted `telegram-bot-api` server in `--local` mode (2000 MB upload limit) |
| Video quality | Unchanged: capped at 720p |
| Max file size | `MAX_FILE_SIZE_MB` env var, default `2000` |
| Logging | SLF4J + Logback (console) |
| Base images | Alpine (`eclipse-temurin:25-*-alpine`) |
| Container engines | Docker (prod, Raspberry Pi, arm64) and Podman (dev laptop) |

## Behaviour to preserve

- Only messages containing `url` entities are handled.
- URLs within one message are processed **sequentially**.
- yt-dlp format: `bestvideo[height<=720]+bestaudio/bestvideo+bestaudio/best`,
  merged to mp4, no playlists.
- `cookies.txt` in the working directory is used if present.
- Max file size applied both as yt-dlp `--max-filesize` and as a post-download
  size check (merged output can exceed the per-format limit); oversized files
  are deleted and skipped.
- Video is sent as a reply to the original message.
- "Sending video…" chat action (`upload_video`) is sent every 4.5 s during
  download and upload.
- No retries. Download or upload failures are logged and the URL is skipped;
  nothing is sent to the user.
- Downloaded file is always deleted afterwards.

## Intentional behaviour changes

1. **Concurrency across messages.** The Python bot processes updates strictly
   one at a time. The Java bot handles each incoming update on its own virtual
   thread, so one long download does not block other chats. URLs within a
   single message remain sequential.
2. **Upload limit** raised to 2000 MB via the local Bot API server.
3. **`supportsStreaming(true)`** is set on `sendVideo` so large videos can start
   playing before fully downloaded on the client.
4. **Unique filenames** use a UUID instead of a millisecond timestamp (needed
   now that downloads can run concurrently).
5. **yt-dlp process timeout** of 30 minutes (hardcoded constant). A hung
   process is destroyed and treated as a download failure, instead of holding
   a thread forever.

## Code structure

Single Gradle module. Package `lv.sknarovs.bot` in `src/main/java/lv/sknarovs/bot/`.

### `BotConfig` (record)

Built once at startup from environment variables via a static
`fromEnv(Map<String,String>)` factory (map parameter makes it testable).

| Field | Env var | Default |
|---|---|---|
| `botToken` | `BOT_TOKEN` | required — startup fails with a clear message if missing/blank |
| `telegramApiUrl` | `TELEGRAM_API_URL` | `http://telegram-bot-api:8081` |
| `maxFileSizeBytes` | `MAX_FILE_SIZE_MB` | `2000` (MB → bytes) |
| `downloadDir` | — | `downloads` (created at startup) |
| `cookiesFile` | — | `cookies.txt` |

### `Main`

- Loads `BotConfig`, creates the download directory.
- Parses `telegramApiUrl` into a TelegramBots `TelegramUrl`.
- Builds an `OkHttpClient` with long timeouts suitable for multi-GB uploads
  (no call/write timeout cap that would abort a 2 GB upload; read timeout
  generous) and passes it to `OkHttpTelegramClient`.
- Registers `VideoBot` with `TelegramBotsLongPollingApplication`, pointing the
  poller at the same `TelegramUrl`.
- Registers a shutdown hook that closes the long-polling application, so
  SIGTERM from `docker stop` / `podman stop` shuts down cleanly.

### `VideoBot`

Implements the TelegramBots long-polling update consumer.

- For each update with a message containing `url` entities, submits handling
  to a virtual-thread executor.
- `extractUrls(Message)` — returns the substrings for entities of type `url`.
  Telegram entity offsets are UTF-16 code units, which matches Java `String`
  indexing, so `substring(offset, offset + length)` is correct even with emoji.
- For each URL, sequentially:
  1. Open a `ChatActionHeartbeat` (try-with-resources).
  2. `YtDlpDownloader.download(url)` → `Path`; on exception log and continue.
  3. If file size > `maxFileSizeBytes`, log, delete, continue.
  4. `sendVideo` with `InputFile(file)`, `replyToMessageId`, `supportsStreaming`;
     on exception log.
  5. `finally` delete the file if it exists.

### `YtDlpDownloader`

- `buildCommand(String url, Path outputTemplate)` — pure function returning the
  argument list (unit-tested):
  ```
  yt-dlp
    -f "bestvideo[height<=720]+bestaudio/bestvideo+bestaudio/best"
    --merge-output-format mp4
    --max-filesize <bytes>
    --no-playlist
    --no-progress
    --print after_move:filepath
    -o downloads/<uuid>.%(ext)s
    [--cookies cookies.txt]   # only if the file exists
    <url>
  ```
  `--print after_move:filepath` makes yt-dlp output the final file path on
  stdout after merging, instead of the code guessing the extension.
- `download(String url)` — runs the command via `ProcessBuilder`, reads stdout
  and stderr concurrently (avoids pipe-buffer deadlock), waits up to 30 min.
  Success = exit code 0 and the printed path exists. Otherwise throws
  `DownloadException` carrying the exit code and the tail of stderr.

### `ChatActionHeartbeat` (AutoCloseable)

- On construction schedules `SendChatAction(chatId, upload_video)` every 4.5 s
  starting immediately.
- A failed send is logged at WARN and the heartbeat stops (matches Python).
- `close()` cancels the scheduled task. Uses one shared
  `ScheduledExecutorService` owned by `VideoBot`.

## Tests

JUnit 5, run by `./gradlew test` (and therefore during the image build).
No network or real yt-dlp required.

- `BotConfigTest` — defaults applied; missing `BOT_TOKEN` fails; MB → bytes.
- `VideoBotTest` — `extractUrls` returns only `url` entities, handles multiple
  URLs and emoji before a URL.
- `YtDlpDownloaderTest` — `buildCommand` includes the expected flags; adds
  `--cookies` only when the cookies file exists.

## Containers

### `Dockerfile`

All image references fully qualified (Podman would otherwise prompt for
short-name resolution).

- **Build stage**: `FROM --platform=$BUILDPLATFORM docker.io/eclipse-temurin:25-jdk-alpine`.
  Copies the Gradle wrapper and sources, runs `./gradlew --no-daemon test installDist`.
  Runs natively on the CI runner even for the arm64 target, since the output
  (jars) is architecture-independent.
- **Runtime stage**: `FROM docker.io/eclipse-temurin:25-jre-alpine`.
  - `apk add --no-cache ffmpeg python3 deno`
    (Alpine `deno` package replaces the `deno.land` install script, whose
    glibc binary does not run on musl).
  - Python venv at `/opt/venv`, `pip install --no-cache-dir yt-dlp yt-dlp-ejs`,
    venv `bin` prepended to `PATH`.
  - `WORKDIR /app`, copy `build/install/yt-dlp-bot/` from the build stage,
    create `/app/downloads`.
  - `CMD ["/app/bin/yt-dlp-bot"]`.
- yt-dlp is installed fresh on every image build, so the daily
  `yt-dlp-release.yml` rebuild continues to pick up new versions.

### `docker-compose.yml` (new)

Compatible with both `docker compose` and `podman compose`.

```yaml
services:
  telegram-bot-api:
    image: docker.io/aiogram/telegram-bot-api:latest
    environment:
      TELEGRAM_API_ID: ${TELEGRAM_API_ID}
      TELEGRAM_API_HASH: ${TELEGRAM_API_HASH}
      TELEGRAM_LOCAL: "1"
    volumes:
      - telegram-bot-api-data:/var/lib/telegram-bot-api
    restart: unless-stopped

  bot:
    image: ghcr.io/sknarovs/yt-dlp-bot:latest
    environment:
      BOT_TOKEN: ${BOT_TOKEN}
      TELEGRAM_API_URL: http://telegram-bot-api:8081
    depends_on:
      - telegram-bot-api
    restart: unless-stopped
    # optional: - ./cookies.txt:/app/cookies.txt:ro

volumes:
  telegram-bot-api-data:
```

The API server port is not published to the host; only the bot reaches it
over the compose network. Files are uploaded via normal multipart HTTP, so no
shared volume between the containers is needed.

### `.env.example` (new)

Documents `BOT_TOKEN`, `TELEGRAM_API_ID`, `TELEGRAM_API_HASH` (the latter two
from https://my.telegram.org).

### One-time setup (README)

Before the bot is first used against the local server it must be logged out of
the public Bot API:

```bash
curl "https://api.telegram.org/bot$BOT_TOKEN/logOut"
```

Kept manual — automating it on every start would be fragile.

### Platform notes

- Raspberry Pi must run a **64-bit** OS (Temurin has no 32-bit ARM build).
- All three images (`eclipse-temurin` alpine JDK/JRE, `aiogram/telegram-bot-api`)
  publish `arm64`.

## CI

- `docker-build.yml`: bump `actions/checkout@v3` → `v4` and
  `docker/login-action@v2` → `v3`. Otherwise unchanged.
- `yt-dlp-release.yml`: unchanged.
- No separate Gradle CI job; tests run inside the Docker build.

## Repository cleanup

- Delete `TelegramBot.py`, `requirements.txt`.
- `.gitignore`: add `build/`, `.gradle/`, `.idea/`, `.env`, `*.iml`; drop Python
  entries.
- `.dockerignore`: add `build/`, `.gradle/`, `.idea/`, `.env`, `downloads/`,
  `docs/`; drop Python entries.
- Rewrite `README.md` (local run with Gradle, compose setup for Docker and
  Podman, `logOut` step, Raspberry Pi note) and `AGENTS.md` (Java layout,
  commands, non-obvious behaviour).

## Out of scope

- Raising video quality above 720p.
- Retries, user-facing error messages, progress reporting.
- Playlist support.
- Publishing the Bot API server port or using its `file://` local-path upload.
