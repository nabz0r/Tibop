FROM rust:1.98-bookworm AS build
WORKDIR /src
ENV CARGO_NET_GIT_FETCH_WITH_CLI=true
COPY Cargo.toml Cargo.lock ./
COPY src/ src/
RUN --mount=type=cache,target=/usr/local/cargo/registry \
    --mount=type=cache,target=/usr/local/cargo/git \
    --mount=type=cache,target=/src/target \
    cargo build --locked --release && cp /src/target/release/lettrine /usr/local/bin/lettrine

FROM debian:bookworm-slim AS api
WORKDIR /app
COPY --from=build /usr/local/bin/lettrine /usr/local/bin/lettrine
COPY --from=build /usr/share/ca-certificates/ /usr/share/ca-certificates/
COPY --from=build /etc/ssl/certs/ /etc/ssl/certs/
COPY web/ web/
COPY data/ data/
COPY docs/ docs/
ENV LETTRINE_BIND=0.0.0.0:8787
EXPOSE 8787
ENTRYPOINT ["lettrine"]
CMD ["serve"]

FROM nginx:alpine AS dashboard
RUN sed -i 's/^worker_processes .*;/worker_processes 1;/' /etc/nginx/nginx.conf
COPY infra/dashboard.conf /etc/nginx/conf.d/default.conf
COPY web/ /usr/share/nginx/html/
EXPOSE 8080
