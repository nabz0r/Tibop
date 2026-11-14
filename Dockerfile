FROM rust:1.98-bookworm AS build
WORKDIR /src
ENV CARGO_NET_GIT_FETCH_WITH_CLI=true
RUN apt-get update && apt-get install -y --no-install-recommends cmake \
    && rm -rf /var/lib/apt/lists/*
COPY Cargo.toml Cargo.lock ./
COPY src/ src/
COPY data/fr/classic-exclusions.txt data/fr/
COPY infra/languages.sql infra/participants.sql infra/move_analytics.sql infra/
RUN --mount=type=cache,target=/usr/local/cargo/registry \
    --mount=type=cache,target=/usr/local/cargo/git \
    --mount=type=cache,target=/src/target \
    cargo build --locked --release && cp /src/target/release/lettrine /usr/local/bin/lettrine

FROM debian:bookworm-slim AS api
WORKDIR /app
RUN install -d -o 10001 -g 10001 -m 0700 /var/lib/lettrine
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

FROM golang:1.26.1-bookworm AS fisf-reference-build
WORKDIR /src
ENV GOTOOLCHAIN=local CGO_ENABLED=0
COPY tools/fisf_reference/go.mod tools/fisf_reference/go.sum ./
RUN --mount=type=cache,target=/go/pkg/mod go mod download
COPY tools/fisf_reference/main.go ./
RUN --mount=type=cache,target=/go/pkg/mod \
    --mount=type=cache,target=/root/.cache/go-build \
    go build -mod=readonly -trimpath -o /out/macondo-reference .

FROM api AS fisf-benchmark
COPY --from=fisf-reference-build /out/macondo-reference /usr/local/bin/macondo-reference
ENV LETTRINE_REFERENCE=/usr/local/bin/macondo-reference
CMD ["help"]

FROM nginx:alpine AS dashboard
RUN sed -i 's/^worker_processes .*;/worker_processes 1;/' /etc/nginx/nginx.conf
COPY infra/dashboard.conf /etc/nginx/conf.d/default.conf
COPY infra/rooms.conf.template /etc/nginx/templates/rooms.conf.template
COPY infra/proxy.conf /etc/nginx/lettrine/proxy.conf
COPY web/ /usr/share/nginx/html/
EXPOSE 8080 8081
