package com.stonewu.agenteam.service.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.mapper.integration.ChannelResponseMapper;
import com.stonewu.agenteam.model.integration.entity.ChannelHttpResponse;
import com.stonewu.agenteam.service.file.FileContentStorage;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.MultipartBody;
import okhttp3.MediaType;
import okio.BufferedSink;
import com.stonewu.agenteam.service.chat.ChatPlatformAdapter.UploadFile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Locale;
import java.nio.charset.StandardCharsets;

/** 平台文件不自动跳转，边下载边写入既有私有存储，错误沿用渠道脱敏日志。 */
@Component
public class IntegrationResourceHttpClient {
    private static final long MAX_FILE_BYTES = 20L * 1024 * 1024;
    private final ObjectMapper json;
    private final FileContentStorage storage;
    private final ChannelResponseMapper responses;
    private final OkHttpClient client;

    public IntegrationResourceHttpClient(ObjectMapper json, FileContentStorage storage, ChannelResponseMapper responses) {
        this.json = json;
        this.storage = storage;
        this.responses = responses;
        this.client = new OkHttpClient.Builder().connectTimeout(Duration.ofSeconds(3)).readTimeout(Duration.ofSeconds(10))
            .callTimeout(Duration.ofSeconds(20)).retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build();
    }

    public FileContentStorage.Stored download(String enterprise, String connection, URI address, String bearer) {
        var url = HttpUrl.get(address);
        var parameters = new HashMap<String, String>();
        for (String name : url.queryParameterNames()) {
            parameters.put(name, url.queryParameter(name));
        }
        var diagnostics = new ChannelRequestDiagnostics(parameters, null, bearer, json);
        var builder = new Request.Builder().url(url).get();
        if (bearer != null) {
            builder.header("Authorization", "Bearer " + bearer);
        }
        var request = builder.build();
        try (var response = client.newCall(request).execute()) {
            String trace = response.header("X-Tt-Logid");
            if (trace != null && !trace.matches("[A-Za-z0-9_-]{1,191}")) {
                trace = null;
            }
            boolean jsonResponse = response.header("Content-Type", "").toLowerCase(Locale.ROOT).contains("application/json") && response.header("Content-Disposition") == null;
            if (!response.isSuccessful() || jsonResponse) {
                JsonNode body = null;
                try {
                    var bytes = response.body().byteStream().readNBytes(1_048_577);
                    if (bytes.length > 1_048_576) {
                        throw new IOException("平台错误响应超过允许大小");
                    }
                    body = json.readTree(bytes);
                } catch (IOException malformed) {
                    diagnostics.failure(connection, request, response.code(), trace, malformed, true);
                }
                diagnostics.response(connection, request, response.code(), body, trace);
                responses.requireSuccess(new ChannelHttpResponse(response.code(), body, null, trace), true);
                throw new ChannelProviderException("CHAT_RESOURCE_RESPONSE_INVALID", "平台没有返回预期的文件内容。");
            }
            if (response.body().contentLength() > MAX_FILE_BYTES) {
                throw new ChannelProviderException("CHAT_RESOURCE_TOO_LARGE", "平台文件超过二十 MiB，请拆分后发送。");
            }
            return storage.write(enterprise, response.body().byteStream(), MAX_FILE_BYTES);
        } catch (IOException failure) {
            diagnostics.failure(connection, request, null, null, failure, false);
            throw new ChannelProviderException("CHAT_RESOURCE_DOWNLOAD_FAILED", "平台文件暂时无法下载。", failure);
        }
    }

    public String upload(String connection, URI address, String bearer, UploadFile file, Runnable beforeUpload) {
        if (file.size() < 1 || file.size() > MAX_FILE_BYTES) {
            throw new ChannelProviderException("CHAT_RESOURCE_TOO_LARGE", "文件为空或超过二十 MiB。");
        }
        var stream = new RequestBody() {
            @Override
            public MediaType contentType() {
                return MediaType.get(file.mediaType());
            }
            @Override
            public long contentLength() {
                return file.size();
            }
            @Override
            public void writeTo(BufferedSink sink) throws IOException {
                try (var source = file.open().get()) {
                    byte[] buffer = new byte[8192];
                    long total = 0;
                    int count;
                    while ((count = source.read(buffer)) != -1) {
                        total += count;
                        if (total > file.size()) {
                            throw new IOException("成果文件内容已发生变化");
                        }
                        sink.write(buffer, 0, count);
                    }
                    if (total != file.size()) {
                        throw new IOException("成果文件大小已发生变化");
                    }
                }
            }
        };
        var body = new MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("file_type", "stream")
            .addFormDataPart("file_name", file.name()).addFormDataPart("file", file.name(), stream).build();
        var request = new Request.Builder().url(address.toString()).header("Authorization", "Bearer " + bearer).post(body).build();
        var diagnostics = new ChannelRequestDiagnostics(Map.of("file_name", file.name()), null, bearer, json);
        beforeUpload.run();
        try (var response = client.newCall(request).execute()) {
            JsonNode parsed;
            try {
                byte[] bytes = response.body().byteStream().readNBytes(1_048_577);
                if (bytes.length > 1_048_576) {
                    throw new IOException("平台上传响应超过允许大小");
                }
                parsed = json.readTree(bytes);
            } catch (IOException failure) {
                diagnostics.failure(connection, request, response.code(), response.header("X-Tt-Logid"), failure, true);
                throw new ChannelProviderException("CHAT_FILE_UPLOAD_INVALID", "平台没有返回可用的文件上传结果。", failure);
            }
            if (!response.isSuccessful() || parsed == null || parsed.path("code").asLong(-1) != 0 || parsed.hasNonNull("error")) {
                try (var original = file.open().get()) {
                    diagnostics.fileContent(new String(original.readNBytes((int) MAX_FILE_BYTES + 1), StandardCharsets.UTF_8));
                } catch (IOException failure) {
                    diagnostics.failure(connection, request, response.code(), response.header("X-Tt-Logid"), failure, true);
                    throw new ChannelProviderException("CHAT_FILE_UPLOAD_INVALID", "平台拒绝文件且无法安全读取原文件进行错误脱敏。", failure);
                }
            }
            diagnostics.response(connection, request, response.code(), parsed, response.header("X-Tt-Logid"));
            var result = responses.requireSuccess(new ChannelHttpResponse(response.code(), parsed, null, response.header("X-Tt-Logid")), true);
            return responses.required(result.path("data"), "file_key");
        } catch (IOException failure) {
            diagnostics.failure(connection, request, null, null, failure, false);
            throw new ChannelProviderException("CHAT_FILE_UPLOAD_FAILED", "成果文件暂时无法上传到平台。", failure);
        }
    }
}
