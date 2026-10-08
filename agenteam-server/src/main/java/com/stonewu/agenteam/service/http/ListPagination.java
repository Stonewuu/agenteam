package com.stonewu.agenteam.service.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.configuration.security.ApplicationSecretKeys;
import com.stonewu.agenteam.model.http.entity.PagePosition;
import com.stonewu.agenteam.model.http.response.PageResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.function.Function;

/**
 * 签名分页位置绑定用户、企业、接口与筛选，每次读取仍执行最新权限条件。
 */
@Service
public class ListPagination {
    private final ApplicationSecretKeys keys;
    private final ObjectMapper json;
    private final Clock clock;

    public ListPagination(ApplicationSecretKeys keys, ObjectMapper json, Clock clock) {
        this.keys = keys;
        this.json = json;
        this.clock = clock;
    }

    public record Binding(String userId, String enterpriseId, String collection, String query, String sort) {
    }

    public record Cursor(Binding binding, String time, String id, String sortValue, long expiresAt) {
    }

    public int limit(Integer value) {
        int limit = value == null ? 30 : value;
        if (limit < 1 || limit > 100) {
            throw ApiException.invalidField("limit", "每页条数需要在 1～100 之间。");
        }
        return limit;
    }

    public String query(String value) {
        String query = value == null ? "" : value.trim();
        if (query.codePointCount(0, query.length()) > 100 || query.codePoints().anyMatch(Character::isISOControl)) {
            throw ApiException.invalidField("query", "搜索内容不能超过 100 个字符或包含控制字符。");
        }
        return query;
    }

    public PagePosition read(String encoded, Binding expected) {
        if (encoded == null) {
            return null;
        }
        if (encoded.isBlank() || encoded.length() > 2048) {
            throw invalid();
        }
        var key = keys.requestKey();
        try {
            String[] parts = encoded.split("\\.", -1);
            if (parts.length != 2) {
                throw invalid();
            }
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            byte[] signature = mac.doFinal(("pagination:" + parts[0]).getBytes(StandardCharsets.UTF_8));
            if (!MessageDigest.isEqual(signature, Base64.getUrlDecoder().decode(parts[1]))) {
                throw invalid();
            }
            Cursor cursor = json.readValue(Base64.getUrlDecoder().decode(parts[0]), Cursor.class);
            if (!expected.equals(
                cursor.binding()) || cursor.expiresAt() <= clock.millis() || cursor.id() == null || cursor.id()
                .isBlank()
                || cursor.id().length() > 100) {
                throw invalid();
            }
            return new PagePosition(Instant.parse(cursor.time()), cursor.id(), cursor.sortValue());
        } catch (Exception exception) {
            throw invalid();
        }
    }

    public <T> PageResponse<T> page(List<T> rows, int limit, Binding binding, Function<T, PagePosition> position) {
        boolean more = rows.size() > limit;
        List<T> items = List.copyOf(rows.subList(0, Math.min(limit, rows.size())));
        return new PageResponse<>(items, more ? encode(binding, position.apply(items.getLast())) : null, more);
    }

    public String encode(Binding binding, PagePosition position) {
        var key = keys.requestKey();
        try {
            var cursor = new Cursor(binding, position.time().toString(), position.id(), position.sortValue(),
                clock.millis() + 3_600_000);
            String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(json.writeValueAsBytes(cursor));
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            String signature = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(mac.doFinal(("pagination:" + payload).getBytes(StandardCharsets.UTF_8)));
            return payload + "." + signature;
        } catch (Exception exception) {
            throw new IllegalStateException("分页位置无法生成");
        }
    }

    private ApiException invalid() {
        return new ApiException(HttpStatus.BAD_REQUEST, "CURSOR_INVALID", "分页位置已失效，请重新加载列表。");
    }
}
