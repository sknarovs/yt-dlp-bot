package lv.sknarovs.bot;

import java.nio.file.Files;
import java.time.Duration;
import okhttp3.OkHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication;
import org.telegram.telegrambots.longpolling.util.DefaultGetUpdatesGenerator;
import org.telegram.telegrambots.meta.api.methods.GetMe;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);
    private static final Duration API_RETRY_INTERVAL = Duration.ofSeconds(5);

    static void main(String[] args) throws Exception {
        BotConfig config;
        try {
            config = BotConfig.fromEnv(System.getenv());
        } catch (IllegalStateException e) {
            log.error(e.getMessage());
            System.exit(1);
            return;
        }

        Files.createDirectories(config.downloadDir());

        OkHttpClient httpClient = createHttpClient();
        var client = new OkHttpTelegramClient(httpClient, config.botToken(), config.telegramApiUrl());
        var downloader = new YtDlpDownloader(config);
        downloader.clearDownloadDir(); // leftovers from a run that was killed mid-download
        var bot = new VideoBot(client, downloader, config.maxFileSizeBytes());

        // Registering starts polling immediately and fails hard if the API server is down,
        // which is common right after `compose up` while telegram-bot-api is still starting.
        try {
            waitUntilApiReachable(client, API_RETRY_INTERVAL);
        } catch (TelegramApiRequestException e) {
            log.error("Telegram API rejected the bot: {}", e.getMessage());
            System.exit(1);
        }

        var app = new TelegramBotsLongPollingApplication();
        app.registerBot(config.botToken(), config::telegramApiUrl, new DefaultGetUpdatesGenerator(), bot);
        log.info("Bot started, using Telegram API at {}", config.telegramApiUrl());

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                app.close();
            } catch (Exception e) {
                log.warn("Error while stopping long polling: {}", e.getMessage());
            }
            bot.close();
        }));

        Thread.currentThread().join();
    }

    /**
     * Blocks until the API server answers. Connection failures are retried; an error response from the
     * server (e.g. 401 for a wrong token) is thrown, since retrying will not fix it.
     */
    /**
     * The local Bot API server answers sendVideo only after it has re-uploaded the file to Telegram,
     * so the read timeout has to cover a 2000 MB transfer on a slow uplink (~1.5 Mbit/s → ~3 h).
     */
    static OkHttpClient createHttpClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(75))
                .writeTimeout(Duration.ofMinutes(5))
                .readTimeout(Duration.ofHours(4))
                .build();
    }

    static void waitUntilApiReachable(TelegramClient client, Duration retryInterval)
            throws InterruptedException, TelegramApiRequestException {
        while (true) {
            try {
                client.execute(new GetMe());
                return;
            } catch (TelegramApiRequestException e) {
                throw e;
            } catch (TelegramApiException e) {
                log.warn("Telegram API not reachable yet ({}), retrying in {}s",
                        e.getMessage(), retryInterval.toSeconds());
                Thread.sleep(retryInterval);
            }
        }
    }
}
