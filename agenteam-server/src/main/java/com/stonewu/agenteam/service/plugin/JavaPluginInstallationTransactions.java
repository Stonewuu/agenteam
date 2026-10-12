package com.stonewu.agenteam.service.plugin;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.stonewu.agenteam.mapper.plugin.JavaPluginInstallationMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.plugin.entity.JavaPluginInstallationRow;
import com.stonewu.agenteam.model.plugin.response.JavaPluginView;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.audit.AuditEventService;
import com.stonewu.agenteam.service.permission.EnterpriseAuthorizationService;
import com.stonewu.agenteam.service.plugin.javaworker.JavaPluginProcesses;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Clock;
import java.util.Objects;
import java.util.Map;

/**
 * 版本切换与旧包删除在同一事务提交，提交成功后结束本机旧进程。
 */
@Service
public class JavaPluginInstallationTransactions {
    private final JavaPluginInstallationMapper installations;
    private final JavaPluginRegistry registry;
    private final ApplicationEventPublisher events;
    private final JavaPluginProcesses processes;
    private final Clock clock;
    private final JavaPluginManagementService management;
    private final EnterpriseAuthorizationService authorization;
    private final AuditEventService audit;

    public JavaPluginInstallationTransactions(JavaPluginInstallationMapper installations, JavaPluginRegistry registry,
                                              ApplicationEventPublisher events, JavaPluginProcesses processes, Clock clock,
                                              JavaPluginManagementService management, EnterpriseAuthorizationService authorization,
                                              AuditEventService audit) {
        this.installations = installations;
        this.registry = registry;
        this.events = events;
        this.processes = processes;
        this.clock = clock;
        this.management = management;
        this.authorization = authorization;
        this.audit = audit;
    }

    public record Invalidated(String installationId) {
    }

    @Transactional
    public JavaPluginView activate(AuthContext actor, String id, String previousId) {
        authorization.lockAndRequire(actor, "plugin.view");
        management.authorize(actor, true);
        var candidate = installations.lock(actor.enterpriseId(), id);
        if (candidate == null) {
            throw conflict();
        }
        if (candidate.getStatus().equals("active")) {
            return registry.view(candidate);
        }
        if (!candidate.getStatus().equals("prepared")) {
            throw conflict();
        }
        var current = registry.current(actor.enterpriseId(), candidate.getPluginId());
        if (!Objects.equals(current == null ? null : current.getId(), previousId)) {
            throw conflict();
        }
        if (current != null) {
            if (current.getPluginVersion().equals(candidate.getPluginVersion())) {
                throw new ApiException(HttpStatus.CONFLICT, "JAVA_PLUGIN_VERSION_EXISTS", "此插件版本已安装，请使用新的版本号。");
            }
            retire(actor.enterpriseId(), current.getId(), "upgraded");
        }
        int changed = installations.update(null, new LambdaUpdateWrapper<JavaPluginInstallationRow>()
            .eq(JavaPluginInstallationRow::getEnterpriseId, actor.enterpriseId()).eq(JavaPluginInstallationRow::getId, id)
            .eq(JavaPluginInstallationRow::getStatus, "prepared")
            .set(JavaPluginInstallationRow::getStatus, "active").set(JavaPluginInstallationRow::getActiveKey, candidate.getPluginId())
            .set(JavaPluginInstallationRow::getUpdatedAt, clock.instant()));
        if (changed != 1) {
            throw conflict();
        }
        audit.record(actor.enterpriseId(), actor.user(), "java_plugin.install", "java_plugin", id,
            "安装扩展插件", Map.of("pluginId", candidate.getPluginId(), "version", candidate.getPluginVersion()));
        return registry.view(registry.require(actor.enterpriseId(), id));
    }

    @Transactional
    public void uninstall(AuthContext actor, String id) {
        authorization.lockAndRequire(actor, "plugin.view");
        management.authorize(actor, true);
        var row = installations.lock(actor.enterpriseId(), id);
        if (row == null) {
            throw conflict();
        }
        if (row.getStatus().equals("uninstalled") || row.getStatus().equals("upgraded")) {
            return;
        }
        retire(actor.enterpriseId(), id, "uninstalled");
        audit.record(actor.enterpriseId(), actor.user(), "java_plugin.uninstall", "java_plugin", id,
            "卸载扩展插件", Map.of("pluginId", row.getPluginId(), "version", row.getPluginVersion()));
    }

    private void retire(String enterprise, String id, String status) {
        int changed = installations.update(null, new LambdaUpdateWrapper<JavaPluginInstallationRow>()
            .eq(JavaPluginInstallationRow::getEnterpriseId, enterprise).eq(JavaPluginInstallationRow::getId, id)
            .in(JavaPluginInstallationRow::getStatus, "active", "prepared")
            .set(JavaPluginInstallationRow::getStatus, status).set(JavaPluginInstallationRow::getActiveKey, null)
            .set(JavaPluginInstallationRow::getArtifact, null).set(JavaPluginInstallationRow::getUpdatedAt, clock.instant()));
        if (changed != 1) {
            throw conflict();
        }
        events.publishEvent(new Invalidated(id));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void stop(Invalidated event) {
        processes.stop(event.installationId());
    }

    private static ApiException conflict() {
        return new ApiException(HttpStatus.CONFLICT, "JAVA_PLUGIN_INSTALLATION_CHANGED", "插件安装状态已变化，请重新读取后操作。");
    }
}
