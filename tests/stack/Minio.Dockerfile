FROM golang:1.24-bookworm AS server-build
RUN git clone --depth 1 --branch RELEASE.2025-04-22T22-12-26Z https://github.com/minio/minio.git /src
WORKDIR /src
RUN CGO_ENABLED=0 go build -o /minio .

FROM golang:1.24-bookworm AS client-build
RUN git clone --depth 1 --branch RELEASE.2025-04-16T18-13-26Z https://github.com/minio/mc.git /src
WORKDIR /src
RUN CGO_ENABLED=0 go build -o /mc .

FROM alpine:3.21 AS server
COPY --from=server-build /minio /usr/local/bin/minio
ENTRYPOINT ["minio"]

FROM alpine:3.21 AS client
COPY --from=client-build /mc /usr/local/bin/mc
ENTRYPOINT ["mc"]
