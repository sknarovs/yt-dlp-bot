package lv.sknarovs.bot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.longpolling.interfaces.LongPollingUpdateConsumer;
import org.telegram.telegrambots.meta.api.methods.send.SendVideo;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.MessageEntity;
import org.telegram.telegrambots.meta.api.objects.ReplyParameters;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

/**
 * Downloads every URL in an incoming message and sends it back as a video reply.
 * Each message is handled on its own virtual thread; URLs within a message are processed in order.
 */
public class VideoBot implements LongPollingUpdateConsumer {

    private static final Logger log = LoggerFactory.getLogger(VideoBot.class);

    private final TelegramClient client;
    private final YtDlpDownloader downloader;
    private final long maxFileSizeBytes;
    private final ExecutorService handlers = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService heartbeats = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "chat-action-heartbeat");
        thread.setDaemon(true);
        return thread;
    });

    public VideoBot(TelegramClient client, YtDlpDownloader downloader, long maxFileSizeBytes) {
        this.client = client;
        this.downloader = downloader;
        this.maxFileSizeBytes = maxFileSizeBytes;
    }

    @Override
    public void consume(List<Update> updates) {
        for (Update update : updates) {
            // An exception escaping here would silently stop the library's polling task
            try {
                if (update.hasMessage() && !extractUrls(update.getMessage()).isEmpty()) {
                    handlers.submit(() -> handleMessage(update.getMessage()));
                }
            } catch (RuntimeException e) {
                log.error("Could not handle update {}", update.getUpdateId(), e);
            }
        }
    }

    static List<String> extractUrls(Message message) {
        List<MessageEntity> entities = message.getEntities();
        if (entities == null) {
            return List.of();
        }
        return entities.stream()
                .filter(entity -> "url".equals(entity.getType()))
                .map(MessageEntity::getText)
                .toList();
    }

    void handleMessage(Message message) {
        for (String url : extractUrls(message)) {
            try {
                processUrl(message, url);
            } catch (RuntimeException e) {
                log.error("Unexpected error for URL {}", url, e);
            }
        }
    }

    private void processUrl(Message message, String url) {
        Path file = null;
        try (var heartbeat = new ChatActionHeartbeat(client, heartbeats, message.getChatId())) {
            file = downloader.download(url);

            if (Files.size(file) > maxFileSizeBytes) {
                log.warn("File {} exceeds max size, skipping", file);
                return;
            }

            client.execute(SendVideo.builder()
                    .chatId(message.getChatId())
                    .video(new InputFile(file.toFile()))
                    .supportsStreaming(true)
                    .replyParameters(ReplyParameters.builder().messageId(message.getMessageId()).build())
                    .build());
        } catch (DownloadException e) {
            log.error("Download failed for URL {}: {}", url, e.getMessage());
        } catch (TelegramApiException e) {
            log.error("Upload failed for file {}: {}", file, e.getMessage());
        } catch (IOException e) {
            log.error("Could not read file {}: {}", file, e.getMessage());
        } finally {
            deleteQuietly(file);
        }
    }

    private static void deleteQuietly(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("Could not delete file {}: {}", file, e.getMessage());
        }
    }

    @Override
    public void close() {
        handlers.shutdown();
        heartbeats.shutdownNow();
    }
}
