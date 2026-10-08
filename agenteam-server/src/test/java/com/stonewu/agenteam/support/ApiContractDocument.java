package com.stonewu.agenteam.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

/** 测试组合公共契约和当前发行组件的补充定义，禁止覆盖已有操作和结构。 */
public final class ApiContractDocument {
    private ApiContractDocument() {
    }

    public static ObjectNode load(ObjectMapper json) {
        try (var input = new ClassPathResource("contracts/openapi.json").getInputStream()) {
            var document = (ObjectNode) json.readTree(input);
            var resource = new ClassPathResource("contracts/edition-openapi.json");
            if (resource.exists()) {
                try (var extension = resource.getInputStream()) {
                    merge(document, json.readTree(extension));
                }
            }
            return document;
        } catch (IOException failure) {
            throw new IllegalStateException("测试无法读取接口定义", failure);
        }
    }

    public static void merge(ObjectNode document, JsonNode extension) {
        if (extension.path("formatVersion").asInt() != 1
            || !document.required("info").required("version").asText().equals(extension.path("version").asText())) {
            throw new IllegalArgumentException("接口扩展格式或版本不一致");
        }
        var paths = (ObjectNode) document.required("paths");
        extension.required("paths").properties().forEach(entry -> {
            var path = paths.has(entry.getKey()) ? (ObjectNode) paths.get(entry.getKey()) : paths.putObject(entry.getKey());
            entry.getValue().properties().forEach(operation -> add(path, operation.getKey(), operation.getValue()));
        });
        var schemas = (ObjectNode) document.required("components").required("schemas");
        extension.required("schemas").properties().forEach(entry -> add(schemas, entry.getKey(), entry.getValue()));
        var permissions = (ArrayNode) document.required("x-permissions");
        Set<String> codes = new HashSet<>();
        permissions.forEach(permission -> codes.add(permission.required("code").asText()));
        extension.required("permissions").forEach(permission -> {
            if (!codes.add(permission.required("code").asText())) {
                throw new IllegalArgumentException("接口扩展重复定义权限");
            }
            permissions.add(permission.deepCopy());
        });
        extension.required("enumAdditions").properties().forEach(entry -> {
            var target = document.at(entry.getKey());
            if (!entry.getKey().endsWith("/enum") || !(target instanceof ArrayNode values) || !entry.getValue().isArray()) {
                throw new IllegalArgumentException("接口扩展只能补充已经存在的枚举选项");
            }
            entry.getValue().forEach(value -> {
                if (!value.isTextual() || contains(values, value)) {
                    throw new IllegalArgumentException("接口扩展枚举值无效或重复");
                }
                values.add(value.deepCopy());
            });
        });
    }

    private static boolean contains(ArrayNode values, JsonNode expected) {
        for (var value : values) {
            if (value.equals(expected)) {
                return true;
            }
        }
        return false;
    }

    private static void add(ObjectNode target, String key, JsonNode value) {
        if (target.has(key)) {
            throw new IllegalArgumentException("接口扩展不能覆盖已有定义：" + key);
        }
        target.set(key, value.deepCopy());
    }
}
