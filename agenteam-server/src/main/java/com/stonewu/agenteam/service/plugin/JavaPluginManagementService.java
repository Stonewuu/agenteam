package com.stonewu.agenteam.service.plugin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.mapper.plugin.JavaPluginInstallationMapper;
import com.stonewu.agenteam.mapper.auth.AuthMapper;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.mapper.tool.ToolProviderMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.plugin.entity.JavaPluginInstallationRow;
import com.stonewu.agenteam.model.plugin.response.JavaPluginView;
import com.stonewu.agenteam.model.tool.entity.ToolDefinition;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.permission.EnterpriseAuthorizationService;
import com.stonewu.agenteam.service.plugin.javaworker.JavaPluginProcesses;
import com.stonewu.agenteam.service.tool.ToolCallControl;
import com.stonewu.agenteam.service.tool.ToolSchemaValidation;
import com.stonewu.agenteam.service.tool.provider.ToolProviderContext;
import com.stonewu.tool.api.ToolSpec;
import org.springframework.http.HttpStatus;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 平台管理员先检查程序包，再明确安装；检查失败不改变当前活动版本。
 */
@Service
public class JavaPluginManagementService {
    private static final Logger LOG = LoggerFactory.getLogger(JavaPluginManagementService.class);
    private final JavaPluginPackageReader packages;
    private final JavaPluginProcesses processes;
    private final JavaPluginInstallationMapper installations;
    private final JavaPluginRegistry registry;
    private final EnterpriseAuthorizationService authorization;
    private final ToolSchemaValidation schemas;
    private final ToolProviderMapper definitions;
    private final ResourceJson json;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final AuthMapper users;
    private final TransactionTemplate transactions;

    public JavaPluginManagementService(JavaPluginPackageReader packages, JavaPluginProcesses processes,
                                       JavaPluginInstallationMapper installations, JavaPluginRegistry registry,
                                       EnterpriseAuthorizationService authorization, ToolSchemaValidation schemas,
                                       ToolProviderMapper definitions, ResourceJson json, ObjectMapper mapper, Clock clock,
                                       AuthMapper users, PlatformTransactionManager transactionManager) {
        this.packages = packages;
        this.processes = processes;
        this.installations = installations;
        this.registry = registry;
        this.authorization = authorization;
        this.schemas = schemas;
        this.definitions = definitions;
        this.json = json;
        this.mapper = mapper;
        this.clock = clock;
        this.users = users;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public void authorize(AuthContext actor, boolean mutation) {
        authorization.require(actor, "plugin.view");
        if (mutation && !users.findById(actor.userId()).map(user -> user.superAdmin()).orElse(false)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "JAVA_PLUGIN_ADMIN_REQUIRED", "只有平台管理员可以安装、升级或卸载扩展插件。");
        }
    }

    public JavaPluginView prepare(AuthContext actor, String key, InputStream content) throws IOException {
        authorize(actor, true);
        byte[] bytes = content.readNBytes(JavaPluginPackageReader.MAX_PACKAGE_BYTES + 1);
        var descriptor = packages.read(bytes);
        String id = UUID.nameUUIDFromBytes((actor.enterpriseId() + ":" + actor.userId() + ":" + key)
            .getBytes(StandardCharsets.UTF_8)).toString();
        String hash;
        try {
            hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception failure) {
            throw new IllegalStateException("无法计算插件包摘要", failure);
        }
        var existing = registry.find(actor.enterpriseId(), id);
        if (existing != null) {
            if (!existing.getArtifactHash().equals(hash)) {
                throw ApiException.invalidField("package", "同一次上传不能更换程序包，请重新选择文件。");
            }
            return registry.view(existing);
        }
        requireCapacity(actor.enterpriseId());
        List<ToolDefinition> tools;
        try (var cancellation = new ToolCallControl(() -> {
        })) {
            var context = new ToolProviderContext(actor.enterpriseId(), Duration.ofSeconds(30), cancellation);
            var request = mapper.createObjectNode().put("mode", "discover");
            tools = validateTools(processes.call(id, bytes, request, context, () -> authorize(actor, true)));
        } catch (ApiException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw ApiException.invalidField("package", "插件工具声明无法解析，请按平台接口规范重新构建。", failure);
        }
        var row = new JavaPluginInstallationRow();
        row.setId(id);
        row.setEnterpriseId(actor.enterpriseId());
        row.setPluginId(descriptor.pluginId());
        row.setPluginVersion(descriptor.version());
        row.setName(descriptor.name());
        row.setDescription(descriptor.description());
        row.setAuthor(descriptor.author());
        row.setConfigurationSchema(json.write(descriptor.configurationSchema()));
        row.setToolsJson(json.write(json.tree(tools)));
        row.setArtifact(bytes);
        row.setArtifactHash(hash);
        row.setStatus("prepared");
        row.setInstalledBy(actor.userId());
        row.setCreatedAt(clock.instant());
        row.setUpdatedAt(clock.instant());
        return transactions.execute(status -> savePrepared(actor, row));
    }

    private JavaPluginView savePrepared(AuthContext actor, JavaPluginInstallationRow row) {
        // 程序包检查结束后才加锁，防止并发上传越过数量限制，也不在执行插件时持有数据库锁。
        authorization.lockAndRequire(actor, "plugin.view");
        authorize(actor, true);
        String id = row.getId();
        String hash = row.getArtifactHash();
        var existing = registry.find(actor.enterpriseId(), id);
        if (existing != null) {
            if (!existing.getArtifactHash().equals(hash)) {
                throw ApiException.invalidField("package", "同一次上传不能更换程序包，请重新选择文件。");
            }
            return registry.view(existing);
        }
        requireCapacity(actor.enterpriseId());
        try {
            if (installations.insert(row) != 1) {
                throw new IllegalStateException("插件安装记录未能保存");
            }
        } catch (DuplicateKeyException duplicate) {
            LOG.info("并发上传已保存相同请求，安装编号 {}", id, duplicate);
            var saved = registry.find(actor.enterpriseId(), id);
            if (saved == null || !saved.getArtifactHash().equals(hash)) {
                throw new ApiException(HttpStatus.CONFLICT, "JAVA_PLUGIN_UPLOAD_CHANGED", "同一次上传已提交其他程序包。", duplicate);
            }
            return registry.view(saved);
        }
        return registry.view(row);
    }

    private void requireCapacity(String enterprise) {
        if (installations.selectCount(new LambdaQueryWrapper<JavaPluginInstallationRow>()
            .eq(JavaPluginInstallationRow::getEnterpriseId, enterprise)
            .in(JavaPluginInstallationRow::getStatus, "active", "prepared")) >= 100) {
            throw ApiException.invalidField("package", "已安装及待安装插件最多 100 个，请先移除不需要的程序包。");
        }
    }

    private List<ToolDefinition> validateTools(JsonNode result) {
        if (!result.isArray() || result.isEmpty() || result.size() > 100) {
            throw ApiException.invalidField("package", "插件需要提供 1 至 100 个工具。");
        }
        List<ToolSpec> specs = mapper.convertValue(result, new TypeReference<>() {
        });
        var names = new HashSet<String>();
        var output = new ArrayList<ToolDefinition>();
        for (var tool : specs) {
            if (tool.name() == null || !tool.name().matches("[A-Za-z0-9_.-]{1,128}") || !names.add(tool.name())
                || tool.description() == null || tool.description().isBlank() || tool.description().length() > 4000
                || tool.timeoutSeconds() < 1 || tool.timeoutSeconds() > 120
                || tool.operationClass() == null || !Set.of("read", "write", "destructive", "unknown").contains(tool.operationClass())
                || tool.supportsDeduplication() || tool.supportsResultQuery()) {
                throw ApiException.invalidField("package", "插件工具定义不符合规范，当前接口不支持结果查询和自动重试声明。");
            }
            schemas.compile(json.tree(tool.inputSchema()));
            if (tool.outputSchema() != null) {
                schemas.compile(json.tree(tool.outputSchema()));
            }
            var definition = definitions.definition(tool);
            output.add(new ToolDefinition(definition.name(), definition.description(), json.hash(json.tree(tool)),
                definition.inputSchema(), definition.outputSchema(), definition.annotations(), definition.operationClass(),
                false, false, true, definition.redactPaths(), definition.timeoutSeconds()));
        }
        return List.copyOf(output);
    }
}
