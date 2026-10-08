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
                "--", "https://e.com/v"),
                downloader.buildCommand("https://e.com/v", "downloads/x.%(ext)s"));
    }

    @Test
    void addsCookiesWhenFileExists() throws IOException {
        Path cookies = Files.createFile(tmp.resolve("cookies.txt"));
        var downloader = new YtDlpDownloader("yt-dlp", Path.of("downloads"), cookies, 100, Duration.ofSeconds(5));

        List<String> command = downloader.buildCommand("https://e.com/v", "downloads/x.%(ext)s");

        int last = command.size() - 1;
        assertEquals("https://e.com/v", command.get(last));
        assertEquals("--", command.get(last - 1));
        assertEquals(cookies.toString(), command.get(last - 2));
        assertEquals("--cookies", command.get(last - 3));
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
        assertEquals(downloads(), result.getParent());
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

    @Test
    void failedDownloadRemovesPartialFiles() throws Exception {
        String script = OUTPUT_ARG + """
                touch "$(echo "$out" | sed 's/%(ext)s/f137.mp4.part/')"
                exit 1
                """;
        var downloader = downloader(stub(script), Duration.ofSeconds(10));

        assertThrows(DownloadException.class, () -> downloader.download("https://e.com/v"));

        assertEquals(List.of(), listDownloads());
    }

    @Test
    void extraOutputFilesAreRemoved() throws Exception {
        String script = OUTPUT_ARG + """
                first=$(echo "$out" | sed 's/%(ext)s/1.mp4/')
                second=$(echo "$out" | sed 's/%(ext)s/2.mp4/')
                touch "$first" "$second"
                echo "$first"
                echo "$second"
                """;
        var downloader = downloader(stub(script), Duration.ofSeconds(10));

        Path result = downloader.download("https://e.com/v");

        assertEquals(List.of(result), listDownloads());
    }

    @Test
    void keepsResultWhenPrintedPathDiffersInForm() throws Exception {
        // In production the download dir is relative ("downloads") but yt-dlp prints an absolute path.
        String script = OUTPUT_ARG + """
                file=$(echo "$out" | sed 's/%(ext)s/mp4/')
                touch "$file"
                realpath "$file"
                """;
        Path unnormalizedDir = downloads().resolve("..").resolve("downloads");
        var downloader = new YtDlpDownloader(
                stub(script).toString(), unnormalizedDir, tmp.resolve("cookies.txt"), 100, Duration.ofSeconds(10));

        Path result = downloader.download("https://e.com/v");

        assertTrue(Files.exists(result));
    }

    @Test
    void timeoutDoesNotWaitForOrphanedChildren() throws Exception {
        // The background sleep is reparented away from the script but keeps stdout open.
        var downloader = downloader(stub("#!/bin/sh\n(sleep 30 &)\nsleep 30\n"), Duration.ofMillis(500));

        long start = System.nanoTime();
        assertThrows(DownloadException.class, () -> downloader.download("https://e.com/v"));

        assertTrue(Duration.ofNanos(System.nanoTime() - start).compareTo(Duration.ofSeconds(5)) < 0);
    }

    @Test
    void clearDownloadDirRemovesEverything() throws Exception {
        Files.createFile(downloads().resolve("old.mp4"));
        Files.createFile(downloads().resolve("old.f137.mp4.part"));

        downloader(Path.of("yt-dlp"), Duration.ofSeconds(1)).clearDownloadDir();

        assertEquals(List.of(), listDownloads());
    }

    private static final String OUTPUT_ARG = """
            #!/bin/sh
            out=""
            while [ $# -gt 0 ]; do
              if [ "$1" = "-o" ]; then out="$2"; fi
              shift
            done
            """;

    private YtDlpDownloader downloader(Path executable, Duration timeout) {
        return new YtDlpDownloader(executable.toString(), downloads(), tmp.resolve("cookies.txt"), 100, timeout);
    }

    private Path downloads() {
        try {
            return Files.createDirectories(tmp.resolve("downloads"));
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private List<Path> listDownloads() throws IOException {
        try (var files = Files.list(downloads())) {
            return files.toList();
        }
    }

    private Path stub(String script) throws IOException {
        Path file = tmp.resolve("yt-dlp-stub.sh");
        Files.writeString(file, script);
        assertTrue(file.toFile().setExecutable(true));
        return file;
    }
}
