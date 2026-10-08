package com.stonewu.agenteam.service.permission;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.mapper.permission.PermissionMapper;
import com.stonewu.agenteam.model.permission.entity.DataScope;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.*;

/**
 * 为新企业创建内置角色；扩展只增加明确列出的权限，不修改已有企业的角色。
 */
@Component
public class BuiltinRoleCatalog {
    private record Template(String code, String name, Set<String> permissions) {
    }

    private final List<Template> templates;
    private final Set<String> protectedPermissions;
    private final Set<String> availablePermissions;

    public BuiltinRoleCatalog(List<BuiltinRoleExtension> extensions) {
        var base = read("catalog/permission-templates.json");
        var roles = new LinkedHashMap<String, Template>();
        for (var role : base.required("roles")) {
            String code = role.required("code").asText();
            var template = new Template(code, role.required("name").asText(), codes(role.required("permissionCodes")));
            if (code.isBlank() || roles.putIfAbsent(code, template) != null) {
                throw new IllegalStateException("内置角色模板包含空白或重复的角色编号");
            }
        }
        var protectedCodes = new HashSet<>(codes(base.required("protectedPermissionCodes")));
        var paths = new HashSet<>(Set.of("catalog/permission-templates.json"));
        for (var extension : extensions) {
            String path = extension.resourcePath();
            if (path == null || path.isBlank() || !paths.add(path)) {
                throw new IllegalStateException("内置角色扩展资源名称为空或重复");
            }
            var additional = read(path);
            for (var role : additional.required("roles")) {
                String code = role.required("code").asText();
                var existing = roles.get(code);
                if (existing == null) {
                    throw new IllegalStateException("内置角色扩展引用了不存在的角色编号");
                }
                var merged = new HashSet<>(existing.permissions());
                merged.addAll(codes(role.required("permissionCodes")));
                roles.put(code, new Template(code, existing.name(), Set.copyOf(merged)));
            }
            protectedCodes.addAll(codes(additional.required("protectedPermissionCodes")));
        }
        templates = List.copyOf(roles.values());
        var available = new HashSet<String>();
        templates.forEach(template -> available.addAll(template.permissions()));
        if (!available.containsAll(protectedCodes)) {
            throw new IllegalStateException("受保护权限未包含在当前内置角色模板中");
        }
        protectedPermissions = Set.copyOf(protectedCodes);
        availablePermissions = Set.copyOf(available);
    }

    public Map<String, String> createRoles(PermissionMapper mapper, String enterpriseId, Instant now) {
        Map<String, String> ids = new LinkedHashMap<>();
        for (Template template : templates) {
            String id = UUID.randomUUID().toString();
            mapper.insertRole(id, enterpriseId, template.code(), template.name(), "", DataScope.ENTERPRISE, true, now);
            mapper.replaceRolePermissions(enterpriseId, id, template.permissions());
            ids.put(template.code(), id);
        }
        return Map.copyOf(ids);
    }

    public boolean protectedPermission(String permission) {
        return protectedPermissions.contains(permission);
    }

    public boolean availablePermission(String permission) {
        return availablePermissions.contains(permission);
    }

    private JsonNode read(String resource) {
        try (InputStream input = new ClassPathResource(resource).getInputStream()) {
            return new ObjectMapper().readTree(input);
        } catch (IOException exception) {
            throw new UncheckedIOException("无法读取内置角色模板", exception);
        }
    }

    private Set<String> codes(JsonNode values) {
        if (!values.isArray()) {
            throw new IllegalStateException("内置角色权限必须使用数组");
        }
        var result = new HashSet<String>();
        for (var value : values) {
            if (!value.isTextual() || value.asText().isBlank() || !result.add(value.asText())) {
                throw new IllegalStateException("内置角色模板包含无效或重复的权限编号");
            }
        }
        return Set.copyOf(result);
    }
}
