package com.stonewu.agenteam.service.agent;

import com.stonewu.agenteam.mapper.agent.AgentListingMapper;
import com.stonewu.agenteam.mapper.permission.ResourceAuthorizationMapper;
import com.stonewu.agenteam.mapper.resource.ResourceMapper;
import com.stonewu.agenteam.mapper.resource.ResourceVersionMapper;
import com.stonewu.agenteam.model.agent.entity.EmployeeSource;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.permission.entity.ResourceCapability;
import com.stonewu.agenteam.model.resource.entity.ResourceKind;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.permission.ResourceAuthorizationService;
import com.stonewu.agenteam.service.resource.ResourceConfigurationService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 员工发现和建立使用关系分别按对应操作范围核对，恢复已有关系不要求重新上架。
 */
@Service
public class EmployeeAccessService {
    private final ResourceMapper resources;
    private final ResourceVersionMapper versions;
    private final ResourceAuthorizationService access;
    private final ResourceAuthorizationMapper grants;
    private final AgentListingMapper listings;
    private final ResourceConfigurationService configurations;

    public EmployeeAccessService(ResourceMapper resources, ResourceVersionMapper versions,
                                 ResourceAuthorizationService access,
                                 ResourceAuthorizationMapper grants, AgentListingMapper listings,
                                 ResourceConfigurationService configurations) {
        this.resources = resources;
        this.versions = versions;
        this.access = access;
        this.grants = grants;
        this.listings = listings;
        this.configurations = configurations;
    }

    public EmployeeSource requireAvailable(AuthContext actor, String id, String operation, boolean listed) {
        var resource = resources.find(actor.enterpriseId(), id, false, false)
            .filter(value -> value.kind() == ResourceKind.AGENT)
            .orElseThrow(ResourceAuthorizationService::unavailable);
        var scope = access.scope(actor, "agent", operation, ResourceCapability.USE);
        if (grants.visible(resource.id(), scope).isEmpty()) {
            throw ResourceAuthorizationService.unavailable();
        }
        var listing = listings.find(actor.enterpriseId(), resource.id());
        if (listed) {
            var market = access.scope(actor, "agent", "agent.market_view", ResourceCapability.USE);
            if (!listing.listed() || grants.visible(resource.id(), market).isEmpty()) {
                throw ResourceAuthorizationService.unavailable();
            }
        }
        var version = resource.publishedVersionId() == null ? null : versions.find(actor.enterpriseId(),
                resource.publishedVersionId())
            .filter(value -> value.resourceId().equals(resource.id()) && value.status().equals("available"))
            .orElse(null);
        if (version == null) {
            throw new ApiException(HttpStatus.CONFLICT, "AGENT_UNAVAILABLE", "该员工暂时没有可用的发布版本。");
        }
        configurations.use(actor, ResourceKind.AGENT, version.config());
        return new EmployeeSource(resource, version, listing);
    }
}
