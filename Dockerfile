# Stage 1: Build environment
FROM python:3.11-alpine AS builder

RUN apk add --no-cache gcc musl-dev

COPY requirements.txt .
RUN pip install --no-cache-dir --prefix=/install -r requirements.txt

# Stage 2: Final image
FROM python:3.11-alpine

WORKDIR /app

RUN apk add --no-cache ffmpeg curl unzip \
    && curl -fsSL https://deno.land/install.sh | sh \
    && apk del curl unzip

ENV DENO_INSTALL="/root/.deno"
ENV PATH="$DENO_INSTALL/bin:$PATH"

COPY --from=builder /install /usr/local/

COPY TelegramBot.py .

RUN mkdir downloads

CMD ["python", "TelegramBot.py"]
