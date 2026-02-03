# yt-dlp-bot

A simple Telegram bot that downloads videos from URLs using `yt-dlp` and sends them to the chat.

## Features

- Downloads videos from any URL supported by `yt-dlp`.
- Sends the downloaded video back to the Telegram chat.
- Automatically retries downloads and uploads on failure.
- Configurable maximum file size to avoid exceeding Telegram's limits.
- Supports using a `cookies.txt` file for downloading from sites that require authentication.
- Dockerized for easy deployment.

## Getting Started

These instructions will get you a copy of the project up and running on your local machine for development and testing purposes.

### Prerequisites

- Python 3.11 or later
- pip
- ffmpeg

### Installation

1.  **Clone the repository:**
    ```bash
    git clone https://github.com/sknarovs/yt-dlp-bot.git
    cd yt-dlp-bot
    ```

2.  **Create and activate a virtual environment:**
    ```bash
    python -m venv venv
    source venv/bin/activate
    ```

3.  **Install the dependencies:**
    ```bash
    pip install -r requirements.txt
    ```

### Configuration

The bot is configured using environment variables.

-   `BOT_TOKEN`: Your Telegram bot token.

You can set the environment variable in your shell:

```bash
export BOT_TOKEN="YOUR_TELEGRAM_BOT_TOKEN"
```

Optionally, you can place a `cookies.txt` file in the root of the project to download videos from websites that require a login.

### Running the bot

Once you have configured the bot, you can run it with the following command:

```bash
python TelegramBot.py
```

## Deployment

The recommended way to deploy the bot is using Docker.

### Using the pre-built Docker image

A pre-built Docker image is available on the GitHub Container Registry.

1.  **Pull the image:**
    ```bash
    docker pull ghcr.io/sknarovs/yt-dlp-bot:latest
    ```

2.  **Run the container:**
    ```bash
    docker run -d --name yt-dlp-bot -e BOT_TOKEN="YOUR_TELEGRAM_BOT_TOKEN" ghcr.io/sknarovs/yt-dlp-bot:latest
    ```

    If you need to use a `cookies.txt` file, you can mount it as a volume:

    ```bash
    docker run -d --name yt-dlp-bot -e BOT_TOKEN="YOUR_TELEGRAM_BOT_TOKEN" -v /path/to/cookies.txt:/app/cookies.txt ghcr.io/sknarovs/yt-dlp-bot:latest
    ```

### Building the Docker image manually

1.  **Clone the repository:**
    ```bash
    git clone https://github.com/sknarovs/yt-dlp-bot.git
    cd yt-dlp-bot
    ```

2.  **Build the image:**
    ```bash
    docker build -t yt-dlp-bot .
    ```

3.  **Run the container:**
    ```bash
    docker run -d --name yt-dlp-bot -e BOT_TOKEN="YOUR_TELEGRAM_BOT_TOKEN" yt-dlp-bot
    ```

## Development

Follow the "Getting Started" instructions to set up a local development environment.

## Contributing

1.  Fork the repository.
2.  Create a new branch: `git checkout -b feature-name`
3.  Make your changes and commit them: `git commit -m 'Add some feature'`
4.  Push to the branch: `git push origin feature-name`
5.  Submit a pull request.

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.
