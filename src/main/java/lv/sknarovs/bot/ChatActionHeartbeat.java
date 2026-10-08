package lv.sknarovs.bot;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.meta.api.methods.ActionType;
import org.telegram.telegrambots.meta.api.methods.send.SendChatAction;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

/**
 * Sends the "sending video…" chat action every 4.5 seconds until closed.
 * Telegram clears a chat action after 5 seconds.
 */
public class ChatActionHeartbeat implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ChatActionHeartbeat.class);
    private static final long INTERVAL_MS = 4500;

    private final ScheduledFuture<?> future;

    public ChatActionHeartbeat(TelegramClient client, ScheduledExecutorService scheduler, long chatId) {
        SendChatAction action = SendChatAction.builder()
                .chatId(chatId)
                .action(ActionType.UPLOAD_VIDEO.toString())
                .build();
        future = scheduler.scheduleAtFixedRate(() -> {
            try {
                client.execute(action);
            } catch (TelegramApiException e) {
                log.warn("Could not send chat action: {}", e.getMessage());
                throw new IllegalStateException(e); // cancels further executions
            }
        }, 0, INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    @Override
    public void close() {
        future.cancel(false);
    }
}
