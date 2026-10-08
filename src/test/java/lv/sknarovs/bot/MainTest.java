package lv.sknarovs.bot;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.telegram.telegrambots.meta.api.methods.GetMe;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

class MainTest {

    @Test
    void waitsUntilApiServerAnswers() throws Exception {
        TelegramClient client = mock(TelegramClient.class);
        when(client.execute(any(GetMe.class)))
                .thenThrow(new TelegramApiException("connection refused"))
                .thenThrow(new TelegramApiException("connection refused"))
                .thenReturn(new User(1L, "bot", true));

        Main.waitUntilApiReachable(client, Duration.ofMillis(10));

        verify(client, times(3)).execute(any(GetMe.class));
    }

    @Test
    @Timeout(5)
    void apiErrorResponseIsNotRetried() throws Exception {
        TelegramClient client = mock(TelegramClient.class);
        when(client.execute(any(GetMe.class))).thenThrow(new TelegramApiRequestException("[401] Unauthorized"));

        assertThrows(TelegramApiRequestException.class, () -> Main.waitUntilApiReachable(client, Duration.ofMillis(10)));

        verify(client, times(1)).execute(any(GetMe.class));
    }

    @Test
    void uploadClientWaitsLongEnoughForSlowUplinks() {
        // 2000 MB at ~1.5 Mbit/s takes about 3 hours
        assertTrue(Main.createHttpClient().readTimeoutMillis() >= Duration.ofHours(3).toMillis());
    }
}
