# Java Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace `TelegramBot.py` with a Java 25 / Gradle bot that behaves the same, but uploads videos up to 2000 MB via a self-hosted Telegram Bot API server, packaged as an Alpine multi-arch image runnable under Docker and Podman.

**Architecture:** Plain Java app (no framework), package `lv.sknarovs.bot`: `BotConfig` (env), `YtDlpDownloader` (runs the `yt-dlp` CLI), `ChatActionHeartbeat` (periodic "sending video…"), `VideoBot` (update handling), `Main` (wiring). TelegramBots long-polling against a `telegram-bot-api` container started by docker-compose.

**Tech Stack:** Java 25, Gradle 9.8.1 (Kotlin DSL, `application` plugin), TelegramBots 10.3.0 (`telegrambots-longpolling`, `telegrambots-client`), Logback 1.6.5, JUnit 6.1.3, Mockito 5.24.0, yt-dlp + yt-dlp-ejs (pip), Deno, ffmpeg, `eclipse-temurin:25-*-alpine`, `aiogram/telegram-bot-api`.

**Spec:** `docs/superpowers/specs/2026-10-08-java-migration-design.md`

## Global Constraints

- Java 25; Gradle Kotlin DSL; Gradle wrapper committed; no Spring.
- Package `lv.sknarovs.bot`, sources in `src/main/java/lv/sknarovs/bot/`, tests in `src/test/java/lv/sknarovs/bot/`.
- yt-dlp format string exactly: `bestvideo[height<=720]+bestaudio/bestvideo+bestaudio/best`; merge to `mp4`; no playlists.
- Env vars: `BOT_TOKEN` (required), `TELEGRAM_API_URL` (default `http://telegram-bot-api:8081`), `MAX_FILE_SIZE_MB` (default `2000`).
- Download dir `downloads`, cookies file `cookies.txt` (both relative to working dir).
- Chat action `upload_video` every 4.5 s (4500 ms).
- yt-dlp process timeout: 30 minutes.
- No retries; failures logged, URL skipped, nothing sent to user; file always deleted.
- URLs within one message sequential; each update handled on its own virtual thread.
- All container image references fully qualified (`docker.io/...`, `ghcr.io/...`).
- Base images `docker.io/eclipse-temurin:25-jdk-alpine` (build) and `docker.io/eclipse-temurin:25-jre-alpine` (runtime). Platforms `linux/amd64`, `linux/arm64/v8`.
- Log format equivalent to Python's: `%d - %logger - %level - %msg%n`, level INFO.

## Review Focus

1. **Updates without a text message / without entities** (edited messages, photos, stickers, plain text) — must be ignored, never NPE (`Message.getEntities()` returns `null` when absent). Test in Task 3.
2. **File over `--max-filesize`** — yt-dlp exits 0 but prints no path and creates no file; must be a `DownloadException`, not a crash or a send of a bogus path. Test in Task 2.
3. **First URL of several fails** — later URLs in the same message still processed. Test in Task 3.
4. **Upload fails** (`TelegramApiException` from `sendVideo`) — downloaded file still deleted. Test in Task 3.
5. **yt-dlp hangs** — killed after the timeout and reported as `DownloadException`. Test in Task 2 (with a short injected timeout).

---

## File Structure

| File | Responsibility |
|---|---|
| `settings.gradle.kts`, `build.gradle.kts`, `gradlew`, `gradlew.bat`, `gradle/wrapper/*` | Build |
| `src/main/resources/logback.xml` | Console logging format |
| `src/main/java/lv/sknarovs/bot/BotConfig.java` | Env → config record, URL parsing |
| `src/main/java/lv/sknarovs/bot/DownloadException.java` | Checked exception for download failures |
| `src/main/java/lv/sknarovs/bot/YtDlpDownloader.java` | Build and run yt-dlp command |
| `src/main/java/lv/sknarovs/bot/ChatActionHeartbeat.java` | Periodic chat action, `AutoCloseable` |
| `src/main/java/lv/sknarovs/bot/VideoBot.java` | Update consumer, per-URL flow |
| `src/main/java/lv/sknarovs/bot/Main.java` | Wiring and startup |
| `Dockerfile`, `.dockerignore`, `docker-compose.yml`, `.env.example` | Containers |
| `.github/workflows/docker-build.yml` | Action version bumps |
| `README.md`, `AGENTS.md`, `.gitignore` | Docs / housekeeping |
| delete `TelegramBot.py`, `requirements.txt` | Python removal |

---

### Task 1: Gradle project skeleton + `BotConfig`

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, Gradle wrapper files, `src/main/resources/logback.xml`, `src/main/java/lv/sknarovs/bot/BotConfig.java`
- Modify: `.gitignore`
- Test: `src/test/java/lv/sknarovs/bot/BotConfigTest.java`

**Interfaces:**
- Produces:
  - `record BotConfig(String botToken, TelegramUrl telegramApiUrl, long maxFileSizeBytes, Path downloadDir, Path cookiesFile)`
  - `static BotConfig BotConfig.fromEnv(Map<String, String> env)` — throws `IllegalStateException("BOT_TOKEN environment variable is required")` if `BOT_TOKEN` missing/blank.
  - `static TelegramUrl BotConfig.parseTelegramUrl(String url)` — scheme/host/port from `URI`; port defaults to 443 for `https`, 80 for `http` when absent; `testServer=false`.
  - Constants: `DEFAULT_TELEGRAM_API_URL = "http://telegram-bot-api:8081"`, `DEFAULT_MAX_FILE_SIZE_MB = 2000`.

- [ ] **Step 1: Generate Gradle wrapper**

Gradle 9.8.1 is installed via SDKMAN. Non-interactive shells don't load SDKMAN, so source it first:
```bash
source ~/.sdkman/bin/sdkman-init.sh
gradle wrapper --gradle-version 9.8.1 --distribution-type bin
```
(create an empty `settings.gradle.kts` first with `rootProject.name = "yt-dlp-bot"`.)
Expected: `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.{jar,properties}` exist.

- [ ] **Step 2: Write `build.gradle.kts`**

```kotlin
plugins { application }

java { toolchain { languageVersion = JavaLanguageVersion.of(25) } }

repositories { mavenCentral() }

val mockitoAgent = configurations.create("mockitoAgent")

dependencies {
    implementation("org.telegram:telegrambots-longpolling:10.3.0")
    implementation("org.telegram:telegrambots-client:10.3.0")
    implementation("ch.qos.logback:logback-classic:1.6.5")

    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.mockito:mockito-core:5.24.0")
    mockitoAgent("org.mockito:mockito-core:5.24.0") { isTransitive = false }
}

application {
    mainClass = "lv.sknarovs.bot.Main"
    applicationName = "yt-dlp-bot"
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("-javaagent:${mockitoAgent.asPath}")
}
```

- [ ] **Step 3: Write `logback.xml`** — console appender, pattern `%d{yyyy-MM-dd HH:mm:ss,SSS} - %logger - %level - %msg%n`, root INFO.

- [ ] **Step 4: Write failing `BotConfigTest`**

```java
@Test void appliesDefaults() {
    var c = BotConfig.fromEnv(Map.of("BOT_TOKEN", "t"));
    assertEquals("t", c.botToken());
    assertEquals(new TelegramUrl("http", "telegram-bot-api", 8081, false), c.telegramApiUrl());
    assertEquals(2000L * 1024 * 1024, c.maxFileSizeBytes());
    assertEquals(Path.of("downloads"), c.downloadDir());
    assertEquals(Path.of("cookies.txt"), c.cookiesFile());
}
@Test void missingTokenFails()  { assertThrows(IllegalStateException.class, () -> BotConfig.fromEnv(Map.of())); }
@Test void blankTokenFails()    { assertThrows(IllegalStateException.class, () -> BotConfig.fromEnv(Map.of("BOT_TOKEN", " "))); }
@Test void readsMaxFileSize()   { assertEquals(50L * 1024 * 1024, BotConfig.fromEnv(Map.of("BOT_TOKEN", "t", "MAX_FILE_SIZE_MB", "50")).maxFileSizeBytes()); }
@Test void httpsUrlDefaultsTo443() { assertEquals(new TelegramUrl("https", "api.telegram.org", 443, false), BotConfig.parseTelegramUrl("https://api.telegram.org")); }
@Test void customApiUrl() { assertEquals(new TelegramUrl("http", "localhost", 9000, false),
        BotConfig.fromEnv(Map.of("BOT_TOKEN", "t", "TELEGRAM_API_URL", "http://localhost:9000")).telegramApiUrl()); }
```

- [ ] **Step 5: Run — expect compile failure**

Run: `./gradlew test` → FAIL (`cannot find symbol BotConfig`).

- [ ] **Step 6: Implement `BotConfig`** per Interfaces.

- [ ] **Step 7: Run — expect pass**

Run: `./gradlew test` → `BUILD SUCCESSFUL`, 6 tests.

- [ ] **Step 8: Update `.gitignore`** — replace Python entries with: `build/`, `.gradle/`, `.idea/`, `*.iml`, `.env`, `downloads/`, `cookies.txt`, `*.log`, `.DS_Store`, `GEMINI.md`.

- [ ] **Step 9: Commit**

```bash
git add settings.gradle.kts build.gradle.kts gradlew gradlew.bat gradle src .gitignore
git commit -m "Add Gradle project and BotConfig"
```

---

### Task 2: `YtDlpDownloader`

**Files:**
- Create: `src/main/java/lv/sknarovs/bot/DownloadException.java`, `src/main/java/lv/sknarovs/bot/YtDlpDownloader.java`
- Test: `src/test/java/lv/sknarovs/bot/YtDlpDownloaderTest.java`

**Interfaces:**
- Consumes: `BotConfig` (Task 1) — only in the convenience constructor.
- Produces:
  - `class DownloadException extends Exception` — `(String message)`, `(String message, Throwable cause)`.
  - `YtDlpDownloader(String executable, Path downloadDir, Path cookiesFile, long maxFileSizeBytes, Duration timeout)`
  - `YtDlpDownloader(BotConfig config)` → `("yt-dlp", config.downloadDir(), config.cookiesFile(), config.maxFileSizeBytes(), Duration.ofMinutes(30))`
  - `List<String> buildCommand(String url, String outputTemplate)`
  - `Path download(String url) throws DownloadException`

- [ ] **Step 1: Write failing tests**

`buildCommand` tests (use `@TempDir` for cookies path):
```java
@Test void buildsCommandWithoutCookies() {
    var d = new YtDlpDownloader("yt-dlp", Path.of("downloads"), tmp.resolve("cookies.txt"), 100, Duration.ofSeconds(5));
    assertEquals(List.of("yt-dlp",
        "-f", "bestvideo[height<=720]+bestaudio/bestvideo+bestaudio/best",
        "--merge-output-format", "mp4",
        "--max-filesize", "100",
        "--no-playlist", "--no-progress",
        "--print", "after_move:filepath",
        "-o", "downloads/x.%(ext)s",
        "https://e.com/v"), d.buildCommand("https://e.com/v", "downloads/x.%(ext)s"));
}
@Test void addsCookiesWhenFileExists() // create tmp/cookies.txt; assert command contains "--cookies", <path> immediately before the URL, URL still last
```

`download` tests use stub executables: write a `#!/bin/sh` script into `@TempDir`, `setExecutable(true)`, pass its absolute path as `executable`; `downloadDir` = temp dir. Stubs locate the `-o` value by looping over `"$@"`.
- `downloadReturnsPrintedPath` — stub: finds `-o` arg, replaces `%(ext)s` with `mp4`, `touch`es it, prints a warning line to stdout first (`echo "WARNING: something"`), then prints the path. Assert returned path equals that file and it exists. (Use the **last non-blank stdout line** as the path.)
- `exitZeroWithoutOutputFails` — stub `exit 0` with no output (yt-dlp's behaviour when a file exceeds `--max-filesize`). `assertThrows(DownloadException.class, ...)`.
- `nonZeroExitFailsWithStderr` — stub `echo "ERROR: Unsupported URL" >&2; exit 1`. Assert exception message contains `exit code 1` and `Unsupported URL`.
- `hangingProcessTimesOut` — stub `sleep 30`; timeout `Duration.ofMillis(500)`. `assertThrows(DownloadException.class)`, and assert the call returns in < 5 s.

- [ ] **Step 2: Run — expect compile failure**

Run: `./gradlew test --tests lv.sknarovs.bot.YtDlpDownloaderTest` → FAIL.

- [ ] **Step 3: Implement**

`buildCommand`: argument order exactly as in the test; `--cookies <cookiesFile>` inserted before the URL only if `Files.exists(cookiesFile)`.

`download`: output template `downloadDir.resolve(UUID.randomUUID() + ".%(ext)s").toString()`; `ProcessBuilder(buildCommand(...))`; read stdout and stderr each on its own virtual thread into strings (avoids pipe deadlock); `waitFor(timeout)` — on timeout `destroyForcibly()` and throw `DownloadException("yt-dlp timed out after " + timeout)`. Non-zero exit → `DownloadException("yt-dlp failed with exit code N: " + last ~20 lines of stderr)`. Path = last non-blank stdout line; missing or `!Files.exists` → `DownloadException("yt-dlp produced no file for " + url)`. `IOException`/`InterruptedException` (restore interrupt flag) wrapped in `DownloadException`.

- [ ] **Step 4: Run — expect pass**

Run: `./gradlew test` → `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add src
git commit -m "Add YtDlpDownloader"
```

---

### Task 3: `ChatActionHeartbeat` + `VideoBot`

**Files:**
- Create: `src/main/java/lv/sknarovs/bot/ChatActionHeartbeat.java`, `src/main/java/lv/sknarovs/bot/VideoBot.java`
- Test: `src/test/java/lv/sknarovs/bot/VideoBotTest.java`

**Interfaces:**
- Consumes: `YtDlpDownloader.download(String) : Path throws DownloadException` (Task 2).
- Produces:
  - `ChatActionHeartbeat(TelegramClient client, ScheduledExecutorService scheduler, long chatId)` — on construction `scheduleAtFixedRate` of `SendChatAction(chatId, ActionType.UPLOAD_VIDEO)` at 0 / 4500 ms. On `TelegramApiException`: log WARN `"Could not send chat action: {}"` and cancel itself. `close()` cancels the future (no checked exception).
  - `class VideoBot implements LongPollingUpdateConsumer, AutoCloseable`
    - `VideoBot(TelegramClient client, YtDlpDownloader downloader, long maxFileSizeBytes)` — owns a `Executors.newVirtualThreadPerTaskExecutor()` and a 1-thread daemon `ScheduledExecutorService` for heartbeats.
    - `void consume(List<Update> updates)` — for each update with `hasMessage()` and non-empty URLs, submit `handleMessage(message)` to the virtual-thread executor.
    - `static List<String> extractUrls(Message message)` — `null`-safe; entities with `type == "url"`, using `MessageEntity.getText()` (the library computes it from the message text in UTF-16 units). Empty list when none.
    - `void handleMessage(Message message)` — package-private, synchronous; per-URL flow from the spec.
    - `close()` shuts down both executors.

- [ ] **Step 1: Write failing tests** (Mockito `mock(TelegramClient.class)`, `mock(YtDlpDownloader.class)`, `@TempDir` files)

Helper: `message(String text, MessageEntity... entities)` building `Message` via setters with `Chat` id `42L` and `messageId` `7`; `url(int offset, int length)` → `new MessageEntity("url", offset, length)`.

- `extractsOnlyUrlEntities` — text `"see https://a.com and #tag"` with a `url` and a `hashtag` entity → `["https://a.com"]`.
- `extractsUrlAfterEmoji` — text `"🎬 https://a.com"`, url entity offset `3` length `13` → `["https://a.com"]`.
- `noEntitiesGivesEmptyList` — message with `entities == null` → `[]`.
- `consumeIgnoresUpdateWithoutMessage` — `new Update()` (no message) passed to `consume(List.of(...))` → no exception; `verifyNoInteractions(downloader)`.
- `sendsVideoAsReplyAndDeletesFile` — downloader returns a temp file of 10 bytes; capture `SendVideo` from `client.execute(any(SendVideo.class))`; assert `chatId == "42"`, `replyParameters.messageId == 7`, `supportsStreaming == true`; file deleted afterwards.
- `oversizedFileIsDeletedAndNotSent` — `maxFileSizeBytes = 5`, file of 10 bytes → `verify(client, never()).execute(any(SendVideo.class))`; file deleted.
- `failedDownloadDoesNotStopNextUrl` — two URLs; first `thenThrow(new DownloadException("x"))`, second returns a file → exactly one `SendVideo` sent.
- `failedUploadStillDeletesFile` — `execute(any(SendVideo.class))` throws `TelegramApiException` → no exception escapes `handleMessage`; file deleted.

- [ ] **Step 2: Run — expect compile failure**

Run: `./gradlew test --tests lv.sknarovs.bot.VideoBotTest` → FAIL.

- [ ] **Step 3: Implement `ChatActionHeartbeat`** per Interfaces.

- [ ] **Step 4: Implement `VideoBot`** — per URL: `try (var hb = new ChatActionHeartbeat(...))` around download + size check + send; reply via `ReplyParameters.builder().messageId(...)`; `InputFile(file.toFile())`; catch `DownloadException` → `log.error("Download failed for URL {}: {}", ...)`, `TelegramApiException` → `log.error("Upload failed for file {}: {}", ...)`, oversize → `log.warn("File {} exceeds max size, skipping", ...)`; `finally` `Files.deleteIfExists` (log, don't throw, on `IOException`). Catch `RuntimeException` per URL too so one bad URL can't kill the rest.

- [ ] **Step 5: Run — expect pass**

Run: `./gradlew test` → `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add src
git commit -m "Add VideoBot and ChatActionHeartbeat"
```

---

### Task 4: `Main` wiring

**Files:**
- Create: `src/main/java/lv/sknarovs/bot/Main.java`

**Interfaces:**
- Consumes: `BotConfig.fromEnv`, `YtDlpDownloader(BotConfig)`, `VideoBot(TelegramClient, YtDlpDownloader, long)`.
- Produces: `public static void main(String[] args)`.

- [ ] **Step 1: Implement `Main.main`**

1. `BotConfig.fromEnv(System.getenv())`; on `IllegalStateException` log the message at ERROR and `System.exit(1)`.
2. `Files.createDirectories(config.downloadDir())`.
3. Upload `OkHttpClient`: `new OkHttpClient.Builder().connectTimeout(75s).writeTimeout(5 min).readTimeout(30 min).build()` — the local API server only responds after it has re-uploaded the file to Telegram, so the read timeout must cover a 2 GB transfer.
4. `new OkHttpTelegramClient(httpClient, config.botToken(), config.telegramApiUrl())`.
5. `var app = new TelegramBotsLongPollingApplication(); app.registerBot(token, () -> config.telegramApiUrl(), new DefaultGetUpdatesGenerator(), bot); app.start();` — log `"Bot started, using Telegram API at {}"`.
6. Shutdown hook: `app.close()` then `bot.close()`, exceptions logged.
7. `Thread.currentThread().join()` to keep main alive.

- [ ] **Step 2: Verify missing-token behaviour**

Run: `./gradlew installDist && env -u BOT_TOKEN build/install/yt-dlp-bot/bin/yt-dlp-bot; echo "exit=$?"`
Expected: log line containing `BOT_TOKEN environment variable is required`, `exit=1`.

- [ ] **Step 3: Verify startup with a dummy token**

Run: `BOT_TOKEN=123:dummy TELEGRAM_API_URL=http://127.0.0.1:9 timeout 10 build/install/yt-dlp-bot/bin/yt-dlp-bot; echo "exit=$?"`
Expected: `Bot started, using Telegram API at ...`, then connection-refused errors from the poller (retrying), `exit=124` (killed by timeout, not crashed).

- [ ] **Step 4: Commit**

```bash
git add src
git commit -m "Add Main entrypoint"
```

---

### Task 5: Container image, compose, Python removal

**Files:**
- Modify: `Dockerfile` (rewrite), `.dockerignore` (rewrite)
- Create: `docker-compose.yml`, `.env.example`
- Delete: `TelegramBot.py`, `requirements.txt`

- [ ] **Step 1: Write `Dockerfile`**

```dockerfile
FROM --platform=$BUILDPLATFORM docker.io/eclipse-temurin:25-jdk-alpine AS builder
WORKDIR /build
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
RUN ./gradlew --no-daemon dependencies > /dev/null
COPY src src
RUN ./gradlew --no-daemon test installDist

FROM docker.io/eclipse-temurin:25-jre-alpine
RUN apk add --no-cache ffmpeg python3 deno \
    && python3 -m venv /opt/venv \
    && /opt/venv/bin/pip install --no-cache-dir yt-dlp yt-dlp-ejs
ENV PATH="/opt/venv/bin:$PATH"
WORKDIR /app
COPY --from=builder /build/build/install/yt-dlp-bot/ /app/
RUN mkdir downloads
CMD ["/app/bin/yt-dlp-bot"]
```

- [ ] **Step 2: Write `.dockerignore`** — `.git`, `.github`, `.gradle`, `build`, `.idea`, `*.iml`, `.env`, `downloads`, `docs`, `cookies.txt`, `*.log`, `.DS_Store`.

- [ ] **Step 3: Build with Podman**

Run: `podman build -t yt-dlp-bot .`
Expected: success; test task output shows tests passing.

- [ ] **Step 4: Smoke-test tools in the image**

Run: `podman run --rm --entrypoint sh yt-dlp-bot -c 'yt-dlp --version && deno --version && ffmpeg -version | head -1 && java -version'`
Expected: four version outputs, exit 0.

Run: `podman run --rm yt-dlp-bot; echo "exit=$?"` → `BOT_TOKEN environment variable is required`, `exit=1`.

- [ ] **Step 5: Write `docker-compose.yml`** — exactly the YAML in the spec's "docker-compose.yml" section (services `telegram-bot-api`, `bot`; volume `telegram-bot-api-data`; cookies mount left as a comment).

- [ ] **Step 6: Write `.env.example`**

```
# From @BotFather
BOT_TOKEN=
# From https://my.telegram.org -> API development tools
TELEGRAM_API_ID=
TELEGRAM_API_HASH=
```

- [ ] **Step 7: Validate compose file**

Run: `podman compose config` (with a throwaway `.env` copied from `.env.example`, deleted afterwards)
Expected: rendered config, no errors.

- [ ] **Step 8: Delete Python files and commit**

```bash
git rm TelegramBot.py requirements.txt
git add Dockerfile .dockerignore docker-compose.yml .env.example
git commit -m "Containerize Java bot with local Bot API server"
```

---

### Task 6: CI and docs

**Files:**
- Modify: `.github/workflows/docker-build.yml`, `README.md`, `AGENTS.md`

- [ ] **Step 1: Bump actions** in `docker-build.yml`: `actions/checkout@v3` → `@v4`, `docker/login-action@v2` → `@v3`. Nothing else changes; `yt-dlp-release.yml` untouched.

- [ ] **Step 2: Rewrite `README.md`** — sections: features (no "retries" claim; 720p; up to 2000 MB via local Bot API server); prerequisites for local dev (JDK 25, yt-dlp, ffmpeg, Deno on PATH); `./gradlew test`, `./gradlew run` with env vars; deployment with compose: get `api_id`/`api_hash`, copy `.env.example` → `.env`, one-time `curl "https://api.telegram.org/bot$BOT_TOKEN/logOut"`, `docker compose up -d` / `podman compose up -d`; cookies mount; Raspberry Pi needs 64-bit OS; config table of the three env vars + `TELEGRAM_API_ID`/`TELEGRAM_API_HASH`.

- [ ] **Step 3: Rewrite `AGENTS.md`** — keep the same headings (Running, Dependencies, Architecture, Non-obvious behavior, CI, Quality) updated for Java: `./gradlew test`/`run`/`installDist`; class list; non-obvious items: no playlists, 720p, `MAX_FILE_SIZE_MB` default 2000 checked both in yt-dlp and after download, yt-dlp exit 0 with no output = oversize, no retries, sequential per message / concurrent across messages, heartbeat 4.5 s, 30-min yt-dlp timeout, requires local Bot API server + one-time `logOut`, long OkHttp read timeout for uploads; Quality: JUnit + Mockito tests, run in Docker build.

- [ ] **Step 4: Commit**

```bash
git add .github README.md AGENTS.md
git commit -m "Update CI actions and docs for Java bot"
```

---

### Task 7: End-to-end check (manual, needs real credentials)

Needs the user's `BOT_TOKEN`, `TELEGRAM_API_ID`, `TELEGRAM_API_HASH` — the user runs this.

- [ ] **Step 1:** `podman build -t ghcr.io/sknarovs/yt-dlp-bot:latest .`
- [ ] **Step 2:** fill `.env`; run the one-time `logOut` curl; `podman compose up -d`.
- [ ] **Step 3:** send the bot a short YouTube link → video arrives as a reply; "sending video…" shown meanwhile.
- [ ] **Step 4:** send a link to a video known to be > 50 MB at 720p → arrives (proves the local server path).
- [ ] **Step 5:** send a message with two links → both arrive, in order.
- [ ] **Step 6:** `podman compose logs bot` shows no errors; `podman compose down`.
