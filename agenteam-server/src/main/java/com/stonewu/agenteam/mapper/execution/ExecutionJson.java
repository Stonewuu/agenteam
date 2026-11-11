package com.stonewu.agenteam.mapper.execution;

import com.stonewu.agenteam.service.i18n.Messages;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.model.execution.response.ContentBlock;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * 执行存储使用的 JSON 与时间转换，读取失败不能伪装为空消息。
 */
@Component
public class ExecutionJson {
    private final ObjectMapper json;

    public ExecutionJson(ObjectMapper json) {
        this.json = json;
    }

    public String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException(Messages.text("error.execution.executionJson.write.1"), error);
        }
    }

    public JsonNode tree(String value) {
        try {
            return json.readTree(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException(Messages.text("error.execution.executionJson.tree.1"), error);
        }
    }

    public <T> T read(String value, Class<T> type) {
        if (value == null) {
            return null;
        }
        try {
            return json.readValue(value, type);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException(Messages.text("error.execution.executionJson.tree.1"), error);
        }
    }

    public List<ContentBlock> blocks(String value) {
        try {
            return json.readValue(value, new TypeReference<>() {
            });
        } catch (JsonProcessingException error) {
            throw new IllegalStateException(Messages.text("error.execution.executionJson.blocks.1"), error);
        }
    }

    public static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    public static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    public static Instant instant(ResultSet row, String column) throws SQLException {
        Timestamp value = row.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
