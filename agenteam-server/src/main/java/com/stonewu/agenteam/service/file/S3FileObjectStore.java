package com.stonewu.agenteam.service.file;

import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;

/**
 * 对象存储只返回受控内容流，不向页面提供桶地址、访问密钥或对象路径。
 */
public final class S3FileObjectStore implements FileObjectStore {
    private final S3Client client;
    private final String bucket;
    private final String prefix;
    private final S3PrivateBucket privacy;

    public S3FileObjectStore(S3Client client, String bucket, String prefix, ObjectMapper json) {
        this.client = client;
        this.bucket = bucket;
        this.prefix = prefix;
        this.privacy = new S3PrivateBucket(client, bucket, json);
        if (bucket == null || !bucket.matches("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]") || bucket.contains("..")
            || prefix == null || !prefix.matches("[A-Za-z0-9_-]+(?:/[A-Za-z0-9_-]+)*/") || prefix.length() > 100) {
            throw new IllegalArgumentException("文件存储桶或目录配置不正确");
        }
        privacy.requirePrivate();
    }

    @Override
    public void put(String key, Path source) {
        privacy.requirePrivate();
        try {
            client.putObject(request -> request.bucket(bucket).key(key(key)).contentType("application/octet-stream"),
                RequestBody.fromFile(source));
        } catch (RuntimeException failed) {
            throw FileStorageKeys.storageUnavailable(failed);
        }
    }

    @Override
    public InputStream open(String key) {
        privacy.requirePrivate();
        try {
            var response = client.getObject(request -> request.bucket(bucket).key(key(key)));
            return new FilterInputStream(response) {
                private boolean ended;

                @Override
                public int read() throws IOException {
                    int value = in.read();
                    ended |= value < 0;
                    return value;
                }

                @Override
                public int read(byte[] bytes, int offset, int length) throws IOException {
                    int value = in.read(bytes, offset, length);
                    ended |= value < 0;
                    return value;
                }

                @Override
                public void close() throws IOException {
                    if (ended) {
                        response.close();
                    } else {
                        response.abort();
                    }
                }
            };
        } catch (S3Exception failed) {
            if (failed.statusCode() == 404) {
                throw FileStorageKeys.unavailable(failed);
            }
            throw FileStorageKeys.storageUnavailable(failed);
        } catch (RuntimeException failed) {
            throw FileStorageKeys.storageUnavailable(failed);
        }
    }

    @Override
    public void delete(String key) {
        privacy.requirePrivate();
        try {
            client.deleteObject(request -> request.bucket(bucket).key(key(key)));
        } catch (RuntimeException failed) {
            throw FileStorageKeys.storageUnavailable(failed);
        }
    }

    @Override
    public Page list(String cursor, int limit) {
        privacy.requirePrivate();
        try {
            var response = client.listObjectsV2(
                request -> request.bucket(bucket).prefix(prefix).continuationToken(cursor).maxKeys(limit));
            var items = response.contents().stream()
                .filter(item -> item.key().startsWith(prefix) && item.lastModified() != null)
                .map(item -> new Item(item.key().substring(prefix.length()), item.lastModified())).toList();
            return new Page(items,
                Boolean.TRUE.equals(response.isTruncated()) ? response.nextContinuationToken() : null);
        } catch (RuntimeException failed) {
            throw FileStorageKeys.storageUnavailable(failed);
        }
    }

    private String key(String value) {
        if (value == null) {
            throw FileStorageKeys.unavailable();
        }
        FileStorageKeys.require(value.endsWith(".part") ? value.substring(0, value.length() - 5) : value);
        return prefix + value;
    }

    @Override
    public void close() {
        client.close();
    }
}
