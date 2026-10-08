# yt-dlp-bot

A Telegram bot that downloads videos from URLs using `yt-dlp` and sends them back to the chat.

## Features

- Downloads videos from any URL supported by `yt-dlp` (several URLs per message are handled one by one).
- Sends each video as a reply to the original message.
- Videos are capped at 720p and merged to mp4 — good enough for phone screens, and keeps files small.
- Uploads videos up to **2000 MB** by talking to a self-hosted [Telegram Bot API server](https://github.com/tdlib/telegram-bot-api) instead of `api.telegram.org` (which limits bots to 50 MB).
- Supports a `cookies.txt` file for sites that require a login.
- Multi-arch container image (amd64 and arm64), runs under Docker or Podman.

## Deployment

The bot runs as two containers: the bot itself and a local Telegram Bot API server.

### 1. Get credentials

- `BOT_TOKEN` — from [@BotFather](https://t.me/BotFather).
- `TELEGRAM_API_ID` and `TELEGRAM_API_HASH` — log in at <https://my.telegram.org>, open **API development tools** and create an application.

### 2. Configure

```bash
cp .env.example .env
# fill in BOT_TOKEN, TELEGRAM_API_ID, TELEGRAM_API_HASH
```

### 3. Log the bot out of the public Bot API (once)

A bot must be logged out of `api.telegram.org` before it can be used with a local server:

```bash
curl "https://api.telegram.org/bot<BOT_TOKEN>/logOut"
```

This only needs to be done once per bot.

### 4. Start

Docker:

```bash
docker compose up -d
docker compose logs -f bot
```

Podman (needs `podman-compose` or `docker-compose` installed as the compose provider):

```bash
podman compose up -d
podman compose logs -f bot
```

To use cookies, put `cookies.txt` next to `docker-compose.yml` and uncomment the `volumes` section of the `bot` service.

### Raspberry Pi

The image is published for `arm64`, so it runs on a Raspberry Pi 3, 4 or 5 with a **64-bit** OS. 32-bit Raspberry Pi OS is not supported (there is no 32-bit ARM Java 25 runtime).

## Configuration

| Variable | Used by | Default | Description |
|---|---|---|---|
| `BOT_TOKEN` | bot | — (required) | Telegram bot token |
| `TELEGRAM_API_URL` | bot | `http://telegram-bot-api:8081` | Bot API server the bot talks to |
| `MAX_FILE_SIZE_MB` | bot | `2000` | Larger videos are skipped |
| `TELEGRAM_API_ID` | telegram-bot-api | — (required) | From my.telegram.org |
| `TELEGRAM_API_HASH` | telegram-bot-api | — (required) | From my.telegram.org |

## Development

### Prerequisites

- JDK 25
- `yt-dlp` (with `yt-dlp-ejs`), `ffmpeg` and [Deno](https://deno.com) on `PATH`

Gradle is provided by the wrapper (`./gradlew`).

### Build and test

```bash
./gradlew test
```

### Run locally

Against the public Bot API (50 MB limit, the bot must not be logged out):

```bash
BOT_TOKEN="..." TELEGRAM_API_URL="https://api.telegram.org" MAX_FILE_SIZE_MB=50 ./gradlew run
```

### Build the image

```bash
docker build -t yt-dlp-bot .   # or: podman build -t yt-dlp-bot .
```

The image build also runs the tests.

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.
