package com.stonewu.agenteam.service.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.model.integration.entity.IntegrationApplication;
import com.stonewu.agenteam.service.chat.ChatFilePolicy;
import com.stonewu.agenteam.service.chat.ChatPlatformAdapter.Downloaded;
import com.stonewu.agenteam.service.file.FileContentStorage;
import com.stonewu.agenteam.service.http.ApiException;
import okhttp3.Dns;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.springframework.http.ContentDisposition;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;

/** 只下载认证事件中的公网 HTTPS（加密网络）资源，禁止跳转和内网解析。 */
@Component
public class WecomResourceDownloader {
    private static final int LIMIT = 20 * 1024 * 1024;
    private final FileContentStorage storage;
    private final ObjectMapper json;
    private final OkHttpClient client;

    public WecomResourceDownloader(FileContentStorage storage, ObjectMapper json) {
        this.storage = storage;
        this.json = json;
        client = new OkHttpClient.Builder().connectTimeout(Duration.ofSeconds(3)).readTimeout(Duration.ofSeconds(10))
            .callTimeout(Duration.ofSeconds(20)).followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .dns(host -> {
                var addresses = Dns.SYSTEM.lookup(host);
                for (var address : addresses) {
                    if (!publicAddress(address)) {
                        throw new UnknownHostException("平台资源地址不是允许的公网地址");
                    }
                }
                return addresses;
            }).build();
    }

    public Downloaded fetch(IntegrationApplication app, JsonNode material) {
        HttpUrl address;
        try {
            address = HttpUrl.get(material.path("url").asText());
            if (!address.isHttps() || address.port() != 443 || !address.username().isEmpty() || !address.password().isEmpty() || address.fragment() != null) {
                throw new IllegalArgumentException("资源地址必须使用公网加密连接");
            }
        } catch (RuntimeException failure) {
            throw new ChannelProviderException("CHAT_RESOURCE_URL_INVALID", "平台附件下载地址无效。", failure);
        }
        var request = new Request.Builder().url(address).get().build();
        var diagnostics = new ChannelRequestDiagnostics(Map.of("url", address.toString()), material, null, json);
        try (var response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                diagnostics.response(app.id(), request, response.code(), null, null);
                throw new ChannelProviderException("CHAT_RESOURCE_DOWNLOAD_REJECTED", "平台拒绝附件下载，请重新发送附件。");
            }
            String disposition = response.header("Content-Disposition", "");
            if (disposition.length() > 2048) {
                throw new IllegalArgumentException("平台文件名字段过长");
            }
            String name = ContentDisposition.parse(disposition).getFilename();
            ChatFilePolicy.mediaType(name, "file");
            byte[] content = response.body().byteStream().readNBytes(LIMIT + 33);
            if (content.length > LIMIT + 32) {
                throw new ChannelProviderException("CHAT_RESOURCE_TOO_LARGE", "平台附件超过二十 MiB。");
            }
            String key = material.path("aeskey").asText();
            byte[] plain = key.isEmpty() ? content : decrypt(content, key);
            return new Downloaded(storage.write(app.enterpriseId(), new ByteArrayInputStream(plain), LIMIT), name);
        } catch (Exception failure) {
            diagnostics.failure(app.id(), request, null, null, failure, true);
            if (failure instanceof ChannelProviderException platform) {
                throw platform;
            }
            if (failure instanceof ApiException api) {
                throw api;
            }
            throw new ChannelProviderException("CHAT_RESOURCE_DOWNLOAD_FAILED", "企微附件暂时无法下载或解密。", failure);
        }
    }

    public static byte[] decrypt(byte[] encrypted, String encodedKey) throws Exception {
        byte[] key = Base64.getDecoder().decode(encodedKey);
        if (key.length != 32 || encrypted.length == 0 || encrypted.length % 16 != 0) {
            throw new IllegalArgumentException("企微附件密钥或加密内容无效");
        }
        var cipher = Cipher.getInstance("AES/CBC/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(Arrays.copyOf(key, 16)));
        byte[] plain = cipher.doFinal(encrypted);
        int padding = plain[plain.length - 1] & 255;
        if (padding < 1 || padding > 32 || padding > plain.length) {
            throw new IllegalArgumentException("企微附件填充无效");
        }
        for (int index = plain.length - padding; index < plain.length; index++) {
            if ((plain[index] & 255) != padding) {
                throw new IllegalArgumentException("企微附件填充不一致");
            }
        }
        return Arrays.copyOf(plain, plain.length - padding);
    }

    public static boolean publicAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
            || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return false;
        }
        byte[] bytes = address.getAddress();
        int first = bytes[0] & 255;
        int second = bytes[1] & 255;
        if (bytes.length == 16) {
            return (first & 0xe0) == 0x20 && !(first == 0x20 && second == 0x01 && (bytes[2] & 255) == 0x0d && (bytes[3] & 255) == 0xb8);
        }
        return first != 0 && first != 127 && first < 224 && first != 10 && !(first == 100 && second >= 64 && second <= 127)
            && !(first == 169 && second == 254) && !(first == 172 && second >= 16 && second <= 31)
            && !(first == 192 && (second == 168 || second == 0 || second == 2)) && !(first == 198 && (second == 18 || second == 19 || second == 51))
            && !(first == 203 && second == 0 && (bytes[2] & 255) == 113);
    }
}
