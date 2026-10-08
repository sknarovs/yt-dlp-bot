package lv.sknarovs.bot;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class YtDlpDownloader {

    private static final String FORMAT = "bestvideo[height<=720]+bestaudio/bestvideo+bestaudio/best";
    private static final int STDERR_TAIL_LINES = 20;
    private static final ExecutorService READERS = Executors.newVirtualThreadPerTaskExecutor();
    private static final Logger log = LoggerFactory.getLogger(YtDlpDownloader.class);

    private final String executable;
    private final Path downloadDir;
    private final Path cookiesFile;
    private final long maxFileSizeBytes;
    private final Duration timeout;

    public YtDlpDownloader(String executable, Path downloadDir, Path cookiesFile, long maxFileSizeBytes,
                           Duration timeout) {
        this.executable = executable;
        this.downloadDir = downloadDir;
        this.cookiesFile = cookiesFile;
        this.maxFileSizeBytes = maxFileSizeBytes;
        this.timeout = timeout;
    }

    public YtDlpDownloader(BotConfig config) {
        this("yt-dlp", config.downloadDir(), config.cookiesFile(), config.maxFileSizeBytes(), Duration.ofMinutes(30));
    }

    List<String> buildCommand(String url, String outputTemplate) {
        List<String> command = new ArrayList<>(List.of(executable,
                "-f", FORMAT,
                "--merge-output-format", "mp4",
                "--max-filesize", String.valueOf(maxFileSizeBytes),
                "--no-playlist", "--no-progress",
                "--print", "after_move:filepath",
                "-o", outputTemplate));
        if (Files.exists(cookiesFile)) {
            command.add("--cookies");
            command.add(cookiesFile.toString());
        }
        command.add("--"); // a URL starting with '-' must never be parsed as an option
        command.add(url);
        return command;
    }

    /** Removes everything in the download directory, e.g. files left behind by a previous run. */
    public void clearDownloadDir() {
        try (var files = Files.list(downloadDir)) {
            files.forEach(YtDlpDownloader::deleteQuietly);
        } catch (IOException e) {
            log.warn("Could not clear {}: {}", downloadDir, e.getMessage());
        }
    }

    public Path download(String url) throws DownloadException {
        String id = UUID.randomUUID().toString();
        Path result = null;
        try {
            result = run(url, downloadDir.resolve(id + ".%(ext)s").toString());
            return result;
        } finally {
            // Partial downloads, unmerged format files or extra videos yt-dlp produced for this URL
            deleteOtherFiles(id, result);
        }
    }

    private Path run(String url, String outputTemplate) throws DownloadException {
        Process process;
        try {
            process = new ProcessBuilder(buildCommand(url, outputTemplate)).start();
        } catch (IOException e) {
            throw new DownloadException("Could not start yt-dlp", e);
        }

        // Readers are not awaited on timeout: an orphaned child (ffmpeg, deno) may keep the pipes open.
        Future<String> stdout = READERS.submit(() -> readAll(process.getInputStream()));
        Future<String> stderr = READERS.submit(() -> readAll(process.getErrorStream()));
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                killTree(process);
                throw new DownloadException("yt-dlp timed out after " + timeout + " for " + url);
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                throw new DownloadException(
                        "yt-dlp failed with exit code " + exitCode + ": " + tail(stderr.get()));
            }

            Path file = lastNonBlankLine(stdout.get());
            if (file == null || !Files.exists(file)) {
                throw new DownloadException("yt-dlp produced no file for " + url);
            }
            return file;
        } catch (InterruptedException e) {
            killTree(process);
            Thread.currentThread().interrupt();
            throw new DownloadException("Interrupted while downloading " + url, e);
        } catch (ExecutionException e) {
            throw new DownloadException("Could not read yt-dlp output", e.getCause());
        }
    }

    private static void killTree(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    private void deleteOtherFiles(String id, Path keep) {
        // Compare names only: yt-dlp prints an absolute path while downloadDir may be relative
        Path keepName = keep == null ? null : keep.getFileName();
        try (var files = Files.newDirectoryStream(downloadDir, id + "*")) {
            for (Path file : files) {
                if (!file.getFileName().equals(keepName)) {
                    deleteQuietly(file);
                }
            }
        } catch (IOException e) {
            log.warn("Could not clean up files for {}: {}", id, e.getMessage());
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("Could not delete {}: {}", file, e.getMessage());
        }
    }

    private static String readAll(InputStream stream) throws IOException {
        return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }

    private static Path lastNonBlankLine(String output) {
        List<String> lines = output.lines().filter(line -> !line.isBlank()).toList();
        return lines.isEmpty() ? null : Path.of(lines.getLast().strip());
    }

    private static String tail(String output) {
        List<String> lines = output.lines().toList();
        return String.join("\n", lines.subList(Math.max(0, lines.size() - STDERR_TAIL_LINES), lines.size()));
    }
}
