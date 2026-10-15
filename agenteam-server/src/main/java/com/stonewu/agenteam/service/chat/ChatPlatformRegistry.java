package com.stonewu.agenteam.service.chat;

import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 只使用服务端登记的实现，不允许外部输入任意网络适配器。 */
@Component
public class ChatPlatformRegistry {
    private final Map<String, ChatPlatformAdapter> adapters;
    private final Map<String, ChatEventNormalizer> normalizers;

    public ChatPlatformRegistry(List<ChatPlatformAdapter> adapters, List<ChatEventNormalizer> normalizers) {
        this.adapters = adapters.stream().collect(Collectors.toUnmodifiableMap(ChatPlatformAdapter::providerCode, Function.identity()));
        this.normalizers = normalizers.stream().collect(Collectors.toUnmodifiableMap(ChatEventNormalizer::providerCode, Function.identity()));
    }

    public ChatPlatformAdapter adapter(String code) {
        var result = adapters.get(code);
        if (result == null) {
            throw unavailable();
        }
        return result;
    }

    public ChatEventNormalizer normalizer(String code) {
        var result = normalizers.get(code);
        if (result == null) {
            throw unavailable();
        }
        return result;
    }

    public static ApiException unavailable() {
        return new ApiException(HttpStatus.CONFLICT, "CHAT_CAPABILITY_UNAVAILABLE", "当前接入尚不具备此聊天能力。");
    }
}
