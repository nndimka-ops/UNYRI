# Build pinned upstream sources; no external MinIO runtime image is required.
# These are explicit source versions; review upstream advisories before publication.
FROM golang:1.27.3-alpine3.24 AS build
ENV CGO_ENABLED=0 GOTOOLCHAIN=local
RUN apk add --no-cache git ca-certificates
WORKDIR /src
RUN git clone --depth 1 --branch RELEASE.2025-10-15T17-29-55Z https://github.com/minio/minio.git minio
RUN git clone --depth 1 --branch RELEASE.2025-08-13T08-35-41Z https://github.com/minio/mc.git mc
WORKDIR /src/minio
RUN go build -mod=readonly -p 2 -trimpath -o /out/minio .
WORKDIR /src/mc
RUN go build -mod=readonly -p 2 -trimpath -o /out/mc .

FROM alpine:3.24.3 AS minio
RUN apk add --no-cache ca-certificates
COPY --from=build /out/minio /usr/local/bin/minio
COPY --from=build /src/minio/LICENSE /licenses/minio-LICENSE
COPY --from=build /src/minio/CREDITS /licenses/minio-CREDITS
ENTRYPOINT ["/usr/local/bin/minio"]

FROM alpine:3.24.3 AS mc
RUN apk add --no-cache ca-certificates
COPY --from=build /out/mc /usr/local/bin/mc
COPY --from=build /src/mc/LICENSE /licenses/mc-LICENSE
COPY --from=build /src/mc/CREDITS /licenses/mc-CREDITS
ENTRYPOINT ["/usr/local/bin/mc"]
