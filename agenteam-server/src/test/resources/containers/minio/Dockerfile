# 固定官方签名版本对应的源码；此镜像仅用于 S3 对象存储测试。
FROM golang:1.24.7-bookworm AS build
WORKDIR /source
ADD https://codeload.github.com/minio/minio/tar.gz/07c3a429bfed433e49018cb0f78a52145d4bedeb /tmp/minio.tar.gz
RUN printf '%s  %s\n' '8819e3e7817e46b7b3798f8f200ead208562e571563c2e040352378031abe9f2' /tmp/minio.tar.gz | sha256sum --check --strict \
    && tar -xzf /tmp/minio.tar.gz --strip-components=1 -C /source
ENV CGO_ENABLED=0 GOMAXPROCS=2
RUN go build -mod=readonly -p=2 -trimpath -o /out/minio .

FROM debian:bookworm-slim
RUN mkdir -p /data /usr/share/licenses/minio && chown 10001:10001 /data
COPY --from=build /out/minio /usr/local/bin/minio
COPY --from=build /source/LICENSE /usr/share/licenses/minio/LICENSE
COPY --from=build /etc/ssl/certs/ca-certificates.crt /etc/ssl/certs/ca-certificates.crt
ENV HOME=/tmp MINIO_UPDATE=off
USER 10001:10001
EXPOSE 9000
ENTRYPOINT ["/usr/local/bin/minio"]
