package lv.sknarovs.bot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.TelegramUrl;

class BotConfigTest {

    @Test
    void appliesDefaults() {
        var config = BotConfig.fromEnv(Map.of("BOT_TOKEN", "t"));

        assertEquals("t", config.botToken());
        assertEquals(new TelegramUrl("http", "telegram-bot-api", 8081, false), config.telegramApiUrl());
        assertEquals(2000L * 1024 * 1024, config.maxFileSizeBytes());
        assertEquals(Path.of("downloads"), config.downloadDir());
        assertEquals(Path.of("cookies.txt"), config.cookiesFile());
    }

    @Test
    void missingTokenFails() {
        assertThrows(IllegalStateException.class, () -> BotConfig.fromEnv(Map.of()));
    }

    @Test
    void blankTokenFails() {
        assertThrows(IllegalStateException.class, () -> BotConfig.fromEnv(Map.of("BOT_TOKEN", " ")));
    }

    @Test
    void readsMaxFileSize() {
        var config = BotConfig.fromEnv(Map.of("BOT_TOKEN", "t", "MAX_FILE_SIZE_MB", "50"));

        assertEquals(50L * 1024 * 1024, config.maxFileSizeBytes());
    }

    @Test
    void httpsUrlDefaultsTo443() {
        assertEquals(new TelegramUrl("https", "api.telegram.org", 443, false),
                BotConfig.parseTelegramUrl("https://api.telegram.org"));
    }

    @Test
    void customApiUrl() {
        var config = BotConfig.fromEnv(Map.of("BOT_TOKEN", "t", "TELEGRAM_API_URL", "http://localhost:9000"));

        assertEquals(new TelegramUrl("http", "localhost", 9000, false), config.telegramApiUrl());
    }
}
