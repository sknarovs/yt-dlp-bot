# Stage 1: Build environment
FROM python:3.11-slim-bookworm AS builder

# Install build dependencies
RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential \
    && rm -rf /var/lib/apt/lists/*

# Copy requirements and install dependencies
COPY requirements.txt .
RUN pip install --no-cache-dir --prefix=/install -r requirements.txt

# Stage 2: Final image
FROM python:3.11-slim-bookworm

# Set working directory
WORKDIR /app

# Install runtime dependencies
RUN apt-get update && apt-get install -y --no-install-recommends \
    ffmpeg \
    unzip \
    curl \
    && rm -rf /var/lib/apt/lists/*

RUN curl -fsSL https://deno.land/install.sh | sh \
    && apt-get remove -y curl unzip

ENV DENO_INSTALL="/root/.deno"
ENV PATH="$DENO_INSTALL/bin:$PATH"

COPY --from=builder /install /usr/local/

# Copy the bot script
COPY TelegramBot.py .
COPY cookies.txt .

# Create downloads directory
RUN mkdir downloads

# Set environment variables
ENV BOT_TOKEN=""

# Run the bot
CMD ["python", "TelegramBot.py"]
