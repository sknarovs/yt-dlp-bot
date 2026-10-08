package lv.sknarovs.bot;

import java.net.URI;
import java.nio.file.Path;
import java.util.Map;
import org.telegram.telegrambots.meta.TelegramUrl;

public record BotConfig(
        String botToken,
        TelegramUrl telegramApiUrl,
        long maxFileSizeBytes,
        Path downloadDir,
        Path cookiesFile) {

    public static final String DEFAULT_TELEGRAM_API_URL = "http://telegram-bot-api:8081";
    public static final int DEFAULT_MAX_FILE_SIZE_MB = 2000;

    public static BotConfig fromEnv(Map<String, String> env) {
        String token = env.get("BOT_TOKEN");
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("BOT_TOKEN environment variable is required");
        }

        String apiUrl = env.getOrDefault("TELEGRAM_API_URL", DEFAULT_TELEGRAM_API_URL);
        long maxFileSizeMb = Long.parseLong(
                env.getOrDefault("MAX_FILE_SIZE_MB", String.valueOf(DEFAULT_MAX_FILE_SIZE_MB)));

        return new BotConfig(
                token,
                parseTelegramUrl(apiUrl),
                maxFileSizeMb * 1024 * 1024,
                Path.of("downloads"),
                Path.of("cookies.txt"));
    }

    public static TelegramUrl parseTelegramUrl(String url) {
        URI uri = URI.create(url);
        String scheme = uri.getScheme();
        int port = uri.getPort();
        if (port == -1) {
            port = "https".equals(scheme) ? 443 : 80;
        }
        return new TelegramUrl(scheme, uri.getHost(), port, false);
    }
}
