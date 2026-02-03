import asyncio
import logging
import os
import time

from telegram import ReplyParameters, Update
from telegram.ext import (
    ApplicationBuilder,
    ContextTypes,
    MessageHandler,
    filters,
)
from yt_dlp import YoutubeDL
from yt_dlp.utils import DownloadError

# Config
MAX_SIZE = 45 * 1024 * 1024  # 45 MB
MAX_RETRIES = 3
DOWNLOAD_FOLDER = "downloads"
os.makedirs(DOWNLOAD_FOLDER, exist_ok=True)

# Logging
logging.basicConfig(
    format="%(asctime)s - %(name)s - %(levelname)s - %(message)s", level=logging.INFO
)
logger = logging.getLogger(__name__)

token = os.environ["BOT_TOKEN"]


def download_video(url: str) -> str:
    base = str(int(time.time() * 1000))
    outtmpl = f"{DOWNLOAD_FOLDER}/{base}.%(ext)s"

    ydl_opts = {
        "format": "bestvideo[height<=720]+bestaudio/bestvideo+bestaudio/best",
        "outtmpl": outtmpl,
        "merge_output_format": "mp4",
        "max_filesize": MAX_SIZE,
        "quiet": True,
        "noplaylist": True,
    }

    if os.path.exists("cookies.txt"):
        ydl_opts["cookiefile"] = "cookies.txt"

    with YoutubeDL(ydl_opts) as ydl:
        info = ydl.extract_info(url, download=True)
        file_path = ydl.prepare_filename(info)

    if not os.path.exists(file_path):
        raise DownloadError(f"No file was downloaded for URL {url}")

    return file_path


async def send_chat_action_periodically(
    context: ContextTypes.DEFAULT_TYPE,
    chat_id: int,
    action: str,
    stop_event: asyncio.Event,
):
    """Sends a chat action periodically until the stop_event is set."""
    while not stop_event.is_set():
        try:
            await context.bot.send_chat_action(chat_id=chat_id, action=action)
            await asyncio.sleep(4.5)  # Telegram actions timeout is 5 seconds
        except Exception as e:
            logger.warning("Could not send chat action: %s", e)
            break


async def handle_url(update: Update, context: ContextTypes.DEFAULT_TYPE):
    """
    Handles messages with URLs.
    Downloads video and sends it to the chat.
    """
    text = update.message.text
    urls = [
        text[e.offset : e.offset + e.length]
        for e in update.message.entities
        if e.type == "url"
    ]

    if not urls:
        return

    for url in urls:
        stop_sending_action = asyncio.Event()
        send_action_task = context.application.create_task(
            send_chat_action_periodically(
                context,
                update.effective_chat.id,
                "upload_video",
                stop_sending_action,
            )
        )

        file_path = None
        try:
            # Download with retries (run in thread to avoid blocking)
            for attempt in range(1, MAX_RETRIES + 1):
                try:
                    file_path = await asyncio.to_thread(download_video, url)
                    break
                except DownloadError as e:
                    logger.warning(
                        "No downloadable video for URL %s (error: %s)", url, e
                    )
                    file_path = None
                    break  # non-retryable
                except Exception as e:
                    if attempt < MAX_RETRIES:
                        logger.warning(
                            "Download failed (attempt %d/%d) for URL %s: %s",
                            attempt,
                            MAX_RETRIES,
                            url,
                            e,
                        )
                    else:
                        logger.error(
                            "Download failed after %d attempts for URL %s: %s",
                            MAX_RETRIES,
                            url,
                            e,
                        )
                        file_path = None

            if not file_path:
                continue  # skip to next URL

            # Check file size before sending
            if os.path.getsize(file_path) > MAX_SIZE:
                logger.warning("File %s exceeds max size, skipping", file_path)
                os.remove(file_path)
                continue

            # Send video with retries
            for attempt in range(1, MAX_RETRIES + 1):
                try:
                    await context.bot.send_video(
                        chat_id=update.effective_chat.id,
                        video=file_path,
                        reply_parameters=ReplyParameters(
                            message_id=update.message.message_id
                        ),
                    )
                    break
                except Exception as e:
                    if attempt < MAX_RETRIES:
                        logger.warning(
                            "Upload failed (attempt %d/%d) for file %s: %s",
                            attempt,
                            MAX_RETRIES,
                            file_path,
                            e,
                        )
                    else:
                        logger.error(
                            "Upload failed after %d attempts for file %s: %s",
                            MAX_RETRIES,
                            file_path,
                            e,
                        )
        finally:
            # Stop the chat action task
            stop_sending_action.set()
            await send_action_task

        # Cleanup file
        if file_path and os.path.exists(file_path):
            os.remove(file_path)


# --- Bot startup ---
if __name__ == "__main__":
    application = ApplicationBuilder().token(token).build()

    url_handler = MessageHandler(filters.Entity("url"), handle_url)
    application.add_handler(url_handler)

    application.run_polling()
