package lv.sknarovs.bot;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.telegram.telegrambots.meta.api.methods.send.SendVideo;
import org.telegram.telegrambots.meta.api.objects.MessageEntity;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.chat.Chat;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

class VideoBotTest {

    @TempDir
    Path tmp;

    private final TelegramClient client = mock(TelegramClient.class);
    private final YtDlpDownloader downloader = mock(YtDlpDownloader.class);
    private VideoBot bot = new VideoBot(client, downloader, 1000);

    @AfterEach
    void closeBot() {
        bot.close();
    }

    @Test
    void extractsOnlyUrlEntities() {
        var message = message("see https://a.com and #tag", url(4, 13), new MessageEntity("hashtag", 22, 4));

        assertEquals(List.of("https://a.com"), VideoBot.extractUrls(message));
    }

    @Test
    void extractsUrlAfterEmoji() {
        var message = message("🎬 https://a.com", url(3, 13));

        assertEquals(List.of("https://a.com"), VideoBot.extractUrls(message));
    }

    @Test
    void noEntitiesGivesEmptyList() {
        assertEquals(List.of(), VideoBot.extractUrls(message("just text")));
    }

    @Test
    void consumeIgnoresUpdateWithoutMessage() {
        bot.consume(List.of(new Update()));

        verifyNoInteractions(downloader);
    }

    @Test
    void consumeSurvivesClosedExecutor() {
        bot.close();

        assertDoesNotThrow(() -> bot.consume(List.of(update(message("https://a.com", url(0, 13))))));
    }

    @Test
    void consumeSurvivesEntityOutsideText() {
        assertDoesNotThrow(() -> bot.consume(List.of(update(message("short", url(0, 50))))));
    }

    @Test
    void sendsVideoAsReplyAndDeletesFile() throws Exception {
        Path file = file("a.mp4", 10);
        when(downloader.download("https://a.com")).thenReturn(file);

        bot.handleMessage(message("https://a.com", url(0, 13)));

        var captor = ArgumentCaptor.forClass(SendVideo.class);
        verify(client).execute(captor.capture());
        SendVideo sent = captor.getValue();
        assertEquals("42", sent.getChatId());
        assertEquals(7, sent.getReplyParameters().getMessageId());
        assertTrue(sent.getSupportsStreaming());
        assertFalse(Files.exists(file));
    }

    @Test
    void oversizedFileIsDeletedAndNotSent() throws Exception {
        bot.close();
        bot = new VideoBot(client, downloader, 5);
        Path file = file("a.mp4", 10);
        when(downloader.download("https://a.com")).thenReturn(file);

        bot.handleMessage(message("https://a.com", url(0, 13)));

        verify(client, never()).execute(any(SendVideo.class));
        assertFalse(Files.exists(file));
    }

    @Test
    void failedDownloadDoesNotStopNextUrl() throws Exception {
        Path file = file("b.mp4", 10);
        when(downloader.download("https://a.com")).thenThrow(new DownloadException("x"));
        when(downloader.download("https://b.com")).thenReturn(file);

        bot.handleMessage(message("https://a.com https://b.com", url(0, 13), url(14, 13)));

        verify(client, times(1)).execute(any(SendVideo.class));
    }

    @Test
    void failedUploadStillDeletesFile() throws Exception {
        Path file = file("a.mp4", 10);
        when(downloader.download("https://a.com")).thenReturn(file);
        when(client.execute(any(SendVideo.class))).thenThrow(new TelegramApiException("upload failed"));

        bot.handleMessage(message("https://a.com", url(0, 13)));

        assertFalse(Files.exists(file));
    }

    private static Message message(String text, MessageEntity... entities) {
        var message = new Message();
        message.setMessageId(7);
        message.setChat(new Chat(42L, "private"));
        message.setText(text);
        if (entities.length > 0) {
            message.setEntities(List.of(entities));
        }
        return message;
    }

    private static Update update(Message message) {
        var update = new Update();
        update.setMessage(message);
        return update;
    }

    private static MessageEntity url(int offset, int length) {
        return new MessageEntity("url", offset, length);
    }

    private Path file(String name, int size) throws IOException {
        return Files.write(tmp.resolve(name), new byte[size]);
    }
}
