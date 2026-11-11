package com.stonewu.agenteam.service.resource;

import com.stonewu.agenteam.service.i18n.Messages;

import com.stonewu.agenteam.mapper.resource.TagMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.http.entity.PagePosition;
import com.stonewu.agenteam.model.http.response.PageResponse;
import com.stonewu.agenteam.model.resource.response.TagView;
import com.stonewu.agenteam.service.audit.AuditEventService;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.http.ListPagination;
import com.stonewu.agenteam.service.permission.EnterpriseAuthorizationService;
import com.stonewu.agenteam.service.permission.ResourceAuthorizationService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 标签名称对有效成员可见；名称和关系修改在企业事务中完成。
 */
@Service
public class TagService {
    private final TagMapper tags;
    private final EnterpriseAuthorizationService authorization;
    private final AuditEventService audit;
    private final ListPagination pagination;
    private final Clock clock;

    public TagService(TagMapper tags, EnterpriseAuthorizationService authorization, AuditEventService audit,
                      ListPagination pagination, Clock clock) {
        this.tags = tags;
        this.authorization = authorization;
        this.audit = audit;
        this.pagination = pagination;
        this.clock = clock;
    }

    public void authorize(AuthContext actor) {
        authorization.lockAndRequire(actor, "tag.manage");
    }

    public PageResponse<TagView> list(AuthContext actor, String query, String cursor, Integer requested) {
        int limit = pagination.limit(requested);
        String search = pagination.query(query);
        var binding = new ListPagination.Binding(actor.userId(), actor.enterpriseId(), "tags", search, "created_desc");
        return pagination.page(tags.list(actor.enterpriseId(), search, pagination.read(cursor, binding), limit), limit,
            binding,
            item -> new PagePosition(Instant.parse(item.createdAt()), item.id()));
    }

    @Transactional
    public TagView create(AuthContext actor, String name) {
        authorize(actor);
        String normalized = ResourceInput.text(name, "name", 20, true);
        String id = UUID.randomUUID().toString();
        try {
            tags.create(actor.enterpriseId(), id, normalized, key(normalized), clock.instant());
        } catch (DuplicateKeyException duplicate) {
            throw ApiException.invalidField("name", Messages.text("error.resource.tagService.create.1"));
        }
        audit.record(actor.enterpriseId(), actor.user(), "tag.create", "tag", id, Messages.text("text.resource.tagService.create.1"),
            Map.of("name", normalized));
        return tags.find(actor.enterpriseId(), id).orElseThrow();
    }

    @Transactional
    public TagView rename(AuthContext actor, String id, long revision, String name) {
        authorize(actor);
        current(actor, id, revision);
        String normalized = ResourceInput.text(name, "name", 20, true);
        try {
            tags.rename(actor.enterpriseId(), id, normalized, key(normalized), clock.instant());
        } catch (DuplicateKeyException duplicate) {
            throw ApiException.invalidField("name", Messages.text("error.resource.tagService.create.1"));
        }
        audit.record(actor.enterpriseId(), actor.user(), "tag.update", "tag", id, Messages.text("text.resource.tagService.rename.1"),
            Map.of("name", normalized));
        return tags.find(actor.enterpriseId(), id).orElseThrow();
    }

    @Transactional
    public void delete(AuthContext actor, String id, long revision) {
        authorize(actor);
        current(actor, id, revision);
        tags.delete(actor.enterpriseId(), id, clock.instant());
        audit.record(actor.enterpriseId(), actor.user(), "tag.delete", "tag", id, Messages.text("text.resource.tagService.delete.1"),
            Map.of());
    }

    public List<String> validateSelection(String enterprise, List<String> selected) {
        var ids = ResourceInput.tags(selected);
        for (String id : ids) {
            if (tags.find(enterprise, id).isEmpty()) {
                throw ApiException.invalidField("tagIds", Messages.text("error.resource.tagService.validateSelection.1"));
            }
        }
        return ids;
    }

    private void current(AuthContext actor, String id, long revision) {
        var tag = tags.find(actor.enterpriseId(), id).orElseThrow(ResourceAuthorizationService::unavailable);
        if (Long.parseLong(tag.revision()) != revision) {
            throw ApiException.versionConflict(Long.parseLong(tag.revision()));
        }
    }

    private String key(String name) {
        String key = Normalizer.normalize(name, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        if (key.codePointCount(0, key.length()) > 20) {
            throw ApiException.invalidField("name", Messages.text("error.resource.tagService.key.1"));
        }
        return key;
    }
}
