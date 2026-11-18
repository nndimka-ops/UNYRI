# Build from the repository root:
# docker build -f server/Dockerfile -t coco-server:local .
FROM rust:1.98-bookworm AS build
WORKDIR /src/server
COPY server/Cargo.toml server/Cargo.lock ./
COPY server/src ./src
COPY server/migrations ./migrations
COPY server/console ./console
COPY server/legal ./legal
COPY shared/chat-appearance.json /src/shared/chat-appearance.json
COPY shared/iana-root-tlds.txt /src/shared/iana-root-tlds.txt
RUN cargo build --locked --release --bin messenger-server --bin coco-admin

FROM debian:bookworm-slim
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --uid 10001 --create-home --shell /usr/sbin/nologin coco \
    && install -d -m 0700 -o coco -g coco /app/data
WORKDIR /app
COPY --from=build /src/server/target/release/messenger-server /usr/local/bin/messenger-server
COPY --from=build /src/server/target/release/coco-admin /usr/local/bin/coco-admin
RUN mkdir -p /app/data && chown 10001:10001 /app/data && chmod 700 /app/data
USER coco
ENV COCO_ENV=production SERVER_HOST=0.0.0.0 SERVER_PORT=8080 COCO_DATA_DIR=/app/data
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
    CMD curl --fail --silent http://127.0.0.1:8080/health || exit 1
ENTRYPOINT ["/usr/local/bin/messenger-server"]
