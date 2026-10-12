package com.stonewu.agenteam.service.modelpricing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.mapper.modelpricing.ModelsDevCatalogParser;
import com.stonewu.agenteam.model.modelpricing.entity.ModelPriceCatalogEntry;
import com.stonewu.agenteam.service.modelprofile.protocol.ModelRequestDiagnostics;
import com.stonewu.agenteam.service.network.OutboundAddressPolicy;
import jakarta.annotation.PreDestroy;
import okhttp3.ConnectionPool;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** 固定读取公开目录，不向第三方发送模型配置、密钥或用户搜索内容。 */
@Component
public class ModelsDevCatalogClient {
    public static final String SOURCE = "https://models.dev/api.json";
    private static final int MAX_BYTES = 16 * 1024 * 1024;
    private final ModelsDevCatalogParser parser;
    private final ObjectMapper json;
    private final OkHttpClient client;
    private final HttpUrl url;

    @Autowired
    public ModelsDevCatalogClient(ModelsDevCatalogParser parser, ObjectMapper json, OutboundAddressPolicy addresses) {
        this(parser, json, new OkHttpClient.Builder().proxy(Proxy.NO_PROXY)
            .dns(host -> addresses.resolve(HttpUrl.get(SOURCE), Duration.ofSeconds(4)))
            .connectionPool(new ConnectionPool(1, 30, TimeUnit.SECONDS))
            .connectTimeout(Duration.ofSeconds(4)).readTimeout(Duration.ofSeconds(8)).callTimeout(Duration.ofSeconds(15))
            .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build(), HttpUrl.get(SOURCE));
    }

    ModelsDevCatalogClient(ModelsDevCatalogParser parser, ObjectMapper json, OkHttpClient client, HttpUrl url) {
        this.parser = parser;
        this.json = json;
        this.client = client;
        this.url = url;
    }

    public List<ModelPriceCatalogEntry> fetch(String modelId) {
        var diagnostic = new ModelRequestDiagnostics(json, modelId, null, List.of());
        var request = new Request.Builder().url(url).header("Accept", "application/json").build();
        diagnostic.request(request);
        try (var original = client.newCall(request).execute(); var response = diagnostic.response(original)) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("价格目录请求失败，HTTP 状态=" + response.code());
            }
            if (response.body().contentLength() > MAX_BYTES) {
                throw new IOException("价格目录超过允许大小。");
            }
            try (var input = new LimitedInputStream(response.body().byteStream())) {
                return parser.read(input);
            }
        } catch (IOException | RuntimeException failure) {
            if (failure instanceof ModelsDevCatalogParser.SourceError source) {
                diagnostic.captureError(source.error());
            }
            throw diagnostic.failure(failure);
        }
    }

    @PreDestroy
    public void close() {
        client.connectionPool().evictAll();
        client.dispatcher().executorService().shutdown();
    }

    private static final class LimitedInputStream extends FilterInputStream {
        private int remaining = MAX_BYTES;

        private LimitedInputStream(InputStream input) {
            super(input);
        }

        @Override
        public int read() throws IOException {
            int result = in.read();
            if (result >= 0 && --remaining < 0) {
                throw new IOException("价格目录超过允许大小。");
            }
            return result;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int result = in.read(buffer, offset, Math.min(length, remaining + 1));
            if (result > 0 && (remaining -= result) < 0) {
                throw new IOException("价格目录超过允许大小。");
            }
            return result;
        }
    }
}
