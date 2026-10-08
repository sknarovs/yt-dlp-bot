package lv.sknarovs.bot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class YtDlpDownloaderTest {

    @TempDir
    Path tmp;

    @Test
    void buildsCommandWithoutCookies() {
        var downloader = new YtDlpDownloader(
                "yt-dlp", Path.of("downloads"), tmp.resolve("cookies.txt"), 100, Duration.ofSeconds(5));

        assertEquals(List.of("yt-dlp",
                "-f", "bestvideo[height<=720]+bestaudio/bestvideo+bestaudio/best",
                "--merge-output-format", "mp4",
                "--max-filesize", "100",
                "--no-playlist", "--no-progress",
                "--print", "after_move:filepath",
                "-o", "downloads/x.%(ext)s",
                "https://e.com/v"),
                downloader.buildCommand("https://e.com/v", "downloads/x.%(ext)s"));
    }

    @Test
    void addsCookiesWhenFileExists() throws IOException {
        Path cookies = Files.createFile(tmp.resolve("cookies.txt"));
        var downloader = new YtDlpDownloader("yt-dlp", Path.of("downloads"), cookies, 100, Duration.ofSeconds(5));

        List<String> command = downloader.buildCommand("https://e.com/v", "downloads/x.%(ext)s");

        int last = command.size() - 1;
        assertEquals("https://e.com/v", command.get(last));
        assertEquals(cookies.toString(), command.get(last - 1));
        assertEquals("--cookies", command.get(last - 2));
    }

    @Test
    void downloadReturnsPrintedPath() throws Exception {
        String script = """
                #!/bin/sh
                out=""
                while [ $# -gt 0 ]; do
                  if [ "$1" = "-o" ]; then out="$2"; fi
                  shift
                done
                file=$(echo "$out" | sed 's/%(ext)s/mp4/')
                touch "$file"
                echo "WARNING: something"
                echo "$file"
                """;
        var downloader = downloader(stub(script), Duration.ofSeconds(10));

        Path result = downloader.download("https://e.com/v");

        assertTrue(Files.exists(result));
        assertEquals(tmp, result.getParent());
        assertTrue(result.getFileName().toString().endsWith(".mp4"));
    }

    @Test
    void exitZeroWithoutOutputFails() throws Exception {
        var downloader = downloader(stub("#!/bin/sh\nexit 0\n"), Duration.ofSeconds(10));

        assertThrows(DownloadException.class, () -> downloader.download("https://e.com/v"));
    }

    @Test
    void nonZeroExitFailsWithStderr() throws Exception {
        var downloader = downloader(
                stub("#!/bin/sh\necho \"ERROR: Unsupported URL\" >&2\nexit 1\n"), Duration.ofSeconds(10));

        var e = assertThrows(DownloadException.class, () -> downloader.download("https://e.com/v"));

        assertTrue(e.getMessage().contains("exit code 1"), e.getMessage());
        assertTrue(e.getMessage().contains("Unsupported URL"), e.getMessage());
    }

    @Test
    void hangingProcessTimesOut() throws Exception {
        var downloader = downloader(stub("#!/bin/sh\nsleep 30\n"), Duration.ofMillis(500));

        long start = System.nanoTime();
        assertThrows(DownloadException.class, () -> downloader.download("https://e.com/v"));

        assertTrue(Duration.ofNanos(System.nanoTime() - start).compareTo(Duration.ofSeconds(5)) < 0);
    }

    private YtDlpDownloader downloader(Path executable, Duration timeout) {
        return new YtDlpDownloader(executable.toString(), tmp, tmp.resolve("cookies.txt"), 100, timeout);
    }

    private Path stub(String script) throws IOException {
        Path file = tmp.resolve("yt-dlp-stub.sh");
        Files.writeString(file, script);
        assertTrue(file.toFile().setExecutable(true));
        return file;
    }
}
