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

# Config
MAX_SIZE = 45 * 1024 * 1024  # 45 MB
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
            try:
                file_path = await asyncio.to_thread(download_video, url)
            except Exception as e:
                logger.error("Download failed for URL %s: %s", url, e)
                file_path = None

            if not file_path:
                continue

            if os.path.getsize(file_path) > MAX_SIZE:
                logger.warning("File %s exceeds max size, skipping", file_path)
                os.remove(file_path)
                continue

            try:
                await context.bot.send_video(
                    chat_id=update.effective_chat.id,
                    video=file_path,
                    reply_parameters=ReplyParameters(
                        message_id=update.message.message_id
                    ),
                )
            except Exception as e:
                logger.error("Upload failed for file %s: %s", file_path, e)
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
