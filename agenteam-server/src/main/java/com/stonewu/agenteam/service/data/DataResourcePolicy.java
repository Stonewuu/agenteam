package com.stonewu.agenteam.service.data;

import com.stonewu.agenteam.mapper.data.DataCollectionMapper;
import com.stonewu.agenteam.mapper.resource.ResourceMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.data.entity.DataCollectionRecord;
import com.stonewu.agenteam.model.resource.entity.ResourceKind;
import com.stonewu.agenteam.model.resource.entity.ResourceRecord;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.permission.EnterpriseAuthorizationService;
import com.stonewu.agenteam.service.permission.ResourceAuthorizationService;
import com.stonewu.agenteam.service.resource.ResourcePolicy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 自动工具验证使用授权，管理页面另外验证菜单权限；集合必须属于所选资源。
 */
@Service
public class DataResourcePolicy {
    private final ResourcePolicy policy;
    private final ResourceAuthorizationService access;
    private final EnterpriseAuthorizationService enterprise;
    private final ResourceMapper resources;
    private final DataCollectionMapper collections;

    public DataResourcePolicy(ResourcePolicy policy, ResourceAuthorizationService access,
                              EnterpriseAuthorizationService enterprise, ResourceMapper resources,
                              DataCollectionMapper collections) {
        this.policy = policy;
        this.access = access;
        this.enterprise = enterprise;
        this.resources = resources;
        this.collections = collections;
    }

    public ResourceRecord edit(AuthContext actor, String resource, boolean mutation) {
        var record = policy.authorize(actor, resource, "edit", mutation, false);
        data(record);
        policy.editable(record);
        if (!record.status().equals("active")) {
            throw new ApiException(HttpStatus.CONFLICT, "RESOURCE_DISABLED", "数据源已停用，请先恢复使用。");
        }
        return record;
    }

    public ResourceRecord view(AuthContext actor, String resource) {
        var record = policy.authorize(actor, resource, "view", false, false);
        data(record);
        return record;
    }

    public ResourceRecord use(AuthContext actor, String resource) {
        access.requireUse(actor, resource, "data");
        return resources.find(actor.enterpriseId(), resource, false, false)
            .orElseThrow(ResourceAuthorizationService::unavailable);
    }

    public ResourceRecord manualUse(AuthContext actor, String resource) {
        enterprise.require(actor, "data.view");
        return use(actor, resource);
    }

    public DataCollectionRecord collection(String enterprise, String resource, String id, boolean lock) {
        var record = collections.find(enterprise, id, lock).orElseThrow(ResourceAuthorizationService::unavailable);
        if (!record.resourceId().equals(resource)) {
            throw ResourceAuthorizationService.unavailable();
        }
        return record;
    }

    public void revision(DataCollectionRecord collection, long expected) {
        if (collection.revision() != expected) {
            throw ApiException.versionConflict(collection.revision());
        }
    }

    public void fileSource(ResourceRecord resource) {
        if (!resource.config().path("sourceType").asText().equals("file")) {
            throw ApiException.invalidField("resourceId", "请为文件类型的数据源导入 CSV。");
        }
    }

    private void data(ResourceRecord resource) {
        if (resource.kind() != ResourceKind.DATA) {
            throw ResourceAuthorizationService.unavailable();
        }
    }
}
