package com.stonewu.agenteam.service.plugin;

import com.stonewu.agenteam.mapper.plugin.PluginConfigurationMapper;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.mapper.resource.ResourceMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.plugin.entity.PluginCheckResult;
import com.stonewu.agenteam.model.resource.entity.ResourceKind;
import com.stonewu.agenteam.model.resource.entity.ResourceRecord;
import com.stonewu.agenteam.model.resource.response.ConnectionCheckView;
import com.stonewu.agenteam.model.tool.entity.ToolDefinition;
import com.stonewu.agenteam.service.audit.AuditEventService;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.permission.ResourceAuthorizationService;
import com.stonewu.agenteam.service.resource.ResourceConfigurationService;
import com.stonewu.agenteam.service.resource.ResourcePolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 网络检查与数据库保存分开；返回后再次核对草稿和操作者，防止旧结果覆盖新配置。
 */
@Service
public class PluginCheckService {
    private static final Logger LOG = LoggerFactory.getLogger(PluginCheckService.class);
    private final ResourcePolicy policy;
    private final ResourceConfigurationService configurations;
    private final PluginDiscoveryService discovery;
    private final PluginCheckEvidence evidence;
    private final ResourceMapper resources;
    private final ResourceJson json;
    private final AuditEventService audit;
    private final Clock clock;

    public PluginCheckService(ResourcePolicy policy, ResourceConfigurationService configurations,
                              PluginDiscoveryService discovery,
                              PluginCheckEvidence evidence, ResourceMapper resources, ResourceJson json,
                              AuditEventService audit, Clock clock) {
        this.policy = policy;
        this.configurations = configurations;
        this.discovery = discovery;
        this.evidence = evidence;
        this.resources = resources;
        this.json = json;
        this.audit = audit;
        this.clock = clock;
    }

    public ResourceRecord authorize(AuthContext actor, String id, boolean mutation) {
        var resource = policy.authorize(actor, id, "test", mutation, false);
        if (resource.kind() != ResourceKind.PLUGIN) {
            throw ResourceAuthorizationService.unavailable();
        }
        return resource;
    }

    public ResourceRecord prepare(AuthContext actor, String id, long revision) {
        var resource = authorize(actor, id, false);
        policy.revision(resource, revision);
        configurations.draft(ResourceKind.PLUGIN, json.object(resource.config()));
        configurations.use(actor, ResourceKind.PLUGIN, resource.config());
        return resource;
    }

    public PluginCheckResult inspect(ResourceRecord resource) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("远程插件检查不能占用数据库事务");
        }
        long started = System.nanoTime();
        String startedAt = clock.instant().toString();
        var previous = evidence.tools(resource);
        String toolField = PluginConfigurationMapper.modern(
            resource.config()) ? "config.tools" : "config.enabledToolNames";
        boolean reviewPending = resource.validation() != null && (resource.validation().path("fieldErrors")
            .has(toolField) || resource.validation().path("fieldErrors").has("config.enabledToolNames"));
        Map<String, List<String>> reviewError = Map.of(toolField,
            List.of("所选工具已移除或发生变化，请核对当前工具并保存草稿后重新检查。"));
        try {
            List<ToolDefinition> tools = discovery.discover(resource.enterpriseId(), resource.config());
            if (PluginConfigurationMapper.mode(resource.config()).equals("collection")) {
                return new PluginCheckResult(startedAt,
                    new ConnectionCheckView(clock.instant().toString(), true, "已读取 " + tools.size() + " 个已选工具。",
                        elapsed(started), List.of()), tools, Map.of());
            }
            var changes = changes(previous, tools);
            var selected = PluginConfigurationMapper.selectedNames(resource.config());
            var invalid = changes.stream()
                .filter(change -> change.change().equals("removed") || change.change().equals("changed"))
                .filter(change -> selected.contains(change.name())).toList();
            Map<String, List<String>> errors = invalid.isEmpty() && !reviewPending ? Map.of() : reviewError;
            var view = new ConnectionCheckView(clock.instant().toString(), true,
                "连接检查通过，已读取 " + tools.size() + " 个工具。", elapsed(started), changes);
            return new PluginCheckResult(startedAt, view, tools, errors);
        } catch (ApiException failed) {
            LOG.warn("插件连接检查失败，资源编号 {}", resource.id(), failed);
            return new PluginCheckResult(startedAt,
                new ConnectionCheckView(clock.instant().toString(), false, failed.getReason(), elapsed(started),
                    List.of()), previous, reviewPending ? reviewError : Map.of());
        }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ConnectionCheckView save(AuthContext actor, ResourceRecord inspected, PluginCheckResult result) {
        var current = authorize(actor, inspected.id(), true);
        policy.revision(current, inspected.revision());
        if (!current.configHash().equals(inspected.configHash())) {
            throw ApiException.versionConflict(current.revision());
        }
        if (current.validation() != null && current.validation().path("connection").hasNonNull("startedAt")
            && Instant.parse(current.validation().path("connection").path("startedAt").asText())
            .isAfter(Instant.parse(result.startedAt()))) {
            throw new ApiException(HttpStatus.CONFLICT, "RESOURCE_CHECK_SUPERSEDED",
                "已有更新的连接检查结果，请重新加载。");
        }
        configurations.use(actor, ResourceKind.PLUGIN, current.config());
        var connection = new LinkedHashMap<String, Object>();
        connection.put("startedAt", result.startedAt());
        connection.put("status", result.view().success() ? "succeeded" : "failed");
        connection.put("checkedAt", result.view().checkedAt());
        connection.put("summary", result.view().summary());
        connection.put("durationMs", result.view().durationMs());
        connection.put("toolChanges", result.view().toolChanges());
        connection.put("connectionHash", evidence.connectionHash(current.config()));
        connection.put("toolNames", result.tools().stream().map(ToolDefinition::name).toList());
        connection.put("tools", result.tools());
        resources.validation(actor.enterpriseId(), current.id(), json.tree(
            Map.of("configHash", current.configHash(), "connection", connection, "fieldErrors", result.fieldErrors())));
        audit.record(actor.enterpriseId(), actor.user(), "plugin.check", "resource", current.id(),
            "检查插件连接与工具清单",
            Map.of("success", result.view().success(), "toolCount", result.tools().size(), "configHash",
                current.configHash()));
        return result.view();
    }

    private List<ConnectionCheckView.ToolChange> changes(List<ToolDefinition> previous, List<ToolDefinition> current) {
        var old = previous.stream().collect(Collectors.toMap(ToolDefinition::name, Function.identity()));
        var changes = new ArrayList<ConnectionCheckView.ToolChange>();
        for (var tool : current) {
            var prior = old.remove(tool.name());
            changes.add(new ConnectionCheckView.ToolChange(tool.name(),
                prior == null ? "added" : prior.schemaHash().equals(tool.schemaHash()) ? "unchanged" : "changed"));
        }
        old.keySet().stream().sorted()
            .forEach(name -> changes.add(new ConnectionCheckView.ToolChange(name, "removed")));
        return changes;
    }

    private static long elapsed(long started) {
        return Math.min(120000, Math.max(0, (System.nanoTime() - started) / 1_000_000));
    }
}
