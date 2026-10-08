package com.stonewu.agenteam.service.resource;

import com.stonewu.agenteam.mapper.enterprise.EnterpriseMapper;
import com.stonewu.agenteam.mapper.resource.ResourceMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.permission.entity.EnterprisePermissionChanged;
import com.stonewu.agenteam.model.resource.entity.ResourceRecord;
import com.stonewu.agenteam.service.audit.AuditEventService;
import com.stonewu.agenteam.service.permission.ResourceAuthorizationService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Map;
import java.util.Set;

/**
 * 所有权只交给本企业仍可维护该类资源的成员，原配置和发布版本保持不变。
 */
@Service
public class ResourceOwnershipService {
    private final ResourcePolicy policy;
    private final ResourceAuthorizationService access;
    private final ResourceMapper resources;
    private final EnterpriseMapper enterprises;
    private final ResourceOwnershipPolicy recipients;
    private final AuditEventService audit;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    public ResourceOwnershipService(ResourcePolicy policy, ResourceAuthorizationService access,
                                    ResourceMapper resources,
                                    EnterpriseMapper enterprises, ResourceOwnershipPolicy recipients,
                                    AuditEventService audit, Clock clock, ApplicationEventPublisher events) {
        this.policy = policy;
        this.access = access;
        this.resources = resources;
        this.enterprises = enterprises;
        this.recipients = recipients;
        this.audit = audit;
        this.clock = clock;
        this.events = events;
    }

    public void authorize(AuthContext actor, String id) {
        policy.authorize(actor, id, "edit", true, false);
        access.requireGrantManager(actor, id, true);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ResourceRecord transfer(AuthContext actor, String id, String recipient, long revision) {
        authorize(actor, id);
        var resource = resources.find(actor.enterpriseId(), id, false, true)
            .orElseThrow(ResourceAuthorizationService::unavailable);
        policy.revision(resource, revision);
        policy.editable(resource);
        String ownerId = recipients.requireRecipient(actor.enterpriseId(), recipient, Set.of(resource.kind()),
            "ownerUserId");
        resources.owner(resource, ownerId, clock.instant());
        enterprises.advancePermissionVersion(actor.enterpriseId());
        events.publishEvent(new EnterprisePermissionChanged(actor.enterpriseId()));
        audit.record(actor.enterpriseId(), actor.user(), "resource.owner.transfer", "resource", id,
            "转交" + resource.kind().label() + "所有权",
            Map.of("kind", resource.kind().code(), "previousOwnerId", resource.ownerUserId(), "ownerUserId", ownerId));
        return resources.find(actor.enterpriseId(), id, false, false).orElseThrow();
    }
}
