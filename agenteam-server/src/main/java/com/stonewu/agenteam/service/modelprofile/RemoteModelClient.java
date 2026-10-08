package com.stonewu.agenteam.service.modelprofile;

import com.stonewu.agenteam.mapper.modelprofile.RemoteModelMapper;
import com.stonewu.agenteam.model.modelprofile.entity.ModelProviderRecord;
import com.stonewu.agenteam.model.modelprofile.response.RemoteModelView;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.network.OutboundAddressPolicy;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * 只访问已保存提供方的模型列表，密钥不随跳转发送，也不返回远程错误正文。
 */
@Component
public class RemoteModelClient {
    private static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;
    private final OutboundAddressPolicy addresses;
    private final RemoteModelMapper mapper;
    private final OkHttpClient client;

    @Autowired
    public RemoteModelClient(OutboundAddressPolicy addresses, RemoteModelMapper mapper) {
        this(addresses, mapper, new OkHttpClient.Builder().connectTimeout(Duration.ofSeconds(5))
            .readTimeout(Duration.ofSeconds(15)).callTimeout(Duration.ofSeconds(20)).build());
    }

    RemoteModelClient(OutboundAddressPolicy addresses, RemoteModelMapper mapper, OkHttpClient client) {
        this.addresses = addresses;
        this.mapper = mapper;
        this.client = client;
    }

    public List<RemoteModelView> list(ModelProviderRecord provider) {
        String base = provider.baseUrl().replaceAll("/+$", "");
        HttpUrl url = HttpUrl.get(base + "/models");
        boolean local = Set.of("127.0.0.1", "localhost", "::1").contains(url.host());
        // 本机模型沿用配置表单允许的地址，其他地址复用现有外部网络限制。
        var connection = client.newBuilder().proxy(Proxy.NO_PROXY).followRedirects(false).followSslRedirects(false)
            .retryOnConnectionFailure(false).dns(host -> local
                ? addresses.resolveHost(host, Duration.ofSeconds(5), true)
                : addresses.resolve(url, Duration.ofSeconds(5))).build();
        var request = new Request.Builder().url(url).header("Accept", "application/json").get();
        if (provider.apiKey() != null && !provider.apiKey().isBlank()) {
            request.header("Authorization", "Bearer " + provider.apiKey());
        }
        try (var response = connection.newCall(request.build()).execute()) {
            if (!response.isSuccessful()) {
                throw statusError(response.code());
            }
            if (response.body() == null) {
                throw statusError(502);
            }
            if (response.body().contentLength() > MAX_RESPONSE_BYTES) {
                throw tooLarge();
            }
            byte[] body = response.body().byteStream().readNBytes(MAX_RESPONSE_BYTES + 1);
            if (body.length > MAX_RESPONSE_BYTES) {
                throw tooLarge();
            }
            return mapper.read(body);
        } catch (InterruptedIOException timeout) {
            throw new ApiException(HttpStatus.GATEWAY_TIMEOUT, "MODEL_LIST_TIMEOUT",
                "获取模型列表超时，请重试或手动填写模型标识。", timeout);
        } catch (IOException failure) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "MODEL_LIST_UNAVAILABLE",
                "无法连接模型提供方，请检查服务地址后重试。", failure);
        }
    }

    private ApiException statusError(int status) {
        String message = switch (status) {
            case 401, 403 -> "提供方拒绝访问，请检查连接中保存的访问密钥及其模型读取权限。";
            case 404, 405 -> "此服务未提供模型列表，请检查服务地址或手动填写模型标识。";
            case 429 -> "获取模型列表过于频繁，请稍后重试。";
            case 301, 302, 303, 307, 308 -> "模型服务地址发生跳转，请在连接配置中填写最终服务地址。";
            default -> "提供方暂时无法返回模型列表，请稍后重试。";
        };
        return new ApiException(HttpStatus.BAD_GATEWAY, "MODEL_LIST_UNAVAILABLE", message);
    }

    private ApiException tooLarge() {
        return new ApiException(HttpStatus.BAD_GATEWAY, "MODEL_LIST_TOO_LARGE",
            "提供方返回的模型列表过大，请手动填写模型标识。");
    }
}
