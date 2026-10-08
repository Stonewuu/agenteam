package com.stonewu.agenteam.mapper.resource;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.github.yulichang.toolkit.JoinWrappers;
import com.github.yulichang.wrapper.MPJLambdaWrapper;
import com.stonewu.agenteam.mapper.query.LikePattern;
import com.stonewu.agenteam.model.http.entity.PagePosition;
import com.stonewu.agenteam.model.resource.entity.ResourceTagRow;
import com.stonewu.agenteam.model.resource.entity.TagQueryRow;
import com.stonewu.agenteam.model.resource.entity.TagRow;
import com.stonewu.agenteam.model.resource.response.TagView;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/**
 * 标签按企业读取，删除标签只解除标签关联，不删除任何资源。
 */
@Repository
public class TagMapper {
    private final TagSqlMapper statements;
    private final ResourceTagTableMapper resourceTagTableMapper;

    public TagMapper(TagSqlMapper statements, ResourceTagTableMapper resourceTagTableMapper) {
        this.resourceTagTableMapper = resourceTagTableMapper;
        this.statements = statements;
    }

    public Optional<TagView> find(String enterprise, String id) {
        return Optional.ofNullable(statements.selectOne(Wrappers.<TagRow>lambdaQuery()
                .eq(TagRow::getEnterpriseId, enterprise).eq(TagRow::getId, id).isNull(TagRow::getDeletedAt)))
            .map(row -> new TagView(row.getId(), Long.toString(row.getRevision()), row.getCreatedAt().toString(),
                row.getUpdatedAt().toString(), row.getName()));
    }

    public List<TagView> forResource(String enterprise, String id) {
        return statements.selectJoinList(TagQueryRow.class, tagQuery(enterprise).eq(ResourceTagRow::getResourceId, id))
            .stream().map(this::map).toList();
    }

    public Map<String, List<TagView>> forResources(String enterprise, List<String> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<String, List<TagView>> result = new LinkedHashMap<>();
        for (var rows : statements.selectJoinList(TagQueryRow.class,
            tagQuery(enterprise).in(ResourceTagRow::getResourceId, ids))) {
            result.computeIfAbsent(rows.getResourceId(), ignored -> new ArrayList<>()).add(map(rows));
        }
        return result;
    }

    public List<TagView> list(String enterprise, String query, PagePosition cursor, int limit) {
        var criteria = Wrappers.<TagRow>lambdaQuery().eq(TagRow::getEnterpriseId, enterprise)
            .isNull(TagRow::getDeletedAt).like(TagRow::getName, LikePattern.escapeWildcards(query));
        if (cursor != null) {
            criteria.and(part -> part.lt(TagRow::getCreatedAt, cursor.time())
                .or(equal -> equal.eq(TagRow::getCreatedAt, cursor.time()).lt(TagRow::getId, cursor.id())));
        }
        criteria.orderByDesc(TagRow::getCreatedAt, TagRow::getId);
        return statements.selectPage(new Page<TagRow>(1, (long) limit + 1, false), criteria).getRecords().stream()
            .map(row -> new TagView(row.getId(), Long.toString(row.getRevision()), row.getCreatedAt().toString(),
                row.getUpdatedAt().toString(), row.getName())).toList();
    }

    public void create(String enterprise, String id, String name, String key, Instant now) {
        TagRow row = new TagRow();
        row.setId(id);
        row.setEnterpriseId(enterprise);
        row.setName(name);
        row.setNameKey(key);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        statements.insert(row);
    }

    public void rename(String enterprise, String id, String name, String key, Instant now) {
        statements.renameTag(name, key, Timestamp.from(now), enterprise, id);
    }

    public void delete(String enterprise, String id, Instant now) {
        statements.deleteResource(Timestamp.from(now), enterprise, id);
        resourceTagTableMapper.deleteResourceTag(enterprise, id);
        statements.deleteTag(Timestamp.from(now), enterprise, id);
    }

    public void replace(String enterprise, String resource, List<String> tags) {
        resourceTagTableMapper.clearResourceTags(enterprise, resource);
        if (!tags.isEmpty()) {
            var rows = tags.stream().map(tag -> {
                var row = new ResourceTagRow();
                row.setEnterpriseId(enterprise);
                row.setResourceId(resource);
                row.setTagId(tag);
                return row;
            }).toList();
            resourceTagTableMapper.insert(rows, 100);
        }
    }

    private TagView map(TagQueryRow rows) {
        return new TagView(rows.getId(), Long.toString(rows.getRevision()), rows.getCreatedAt().toString(),
            rows.getUpdatedAt().toString(), rows.getName());
    }

    private MPJLambdaWrapper<TagRow> tagQuery(String enterprise) {
        return JoinWrappers.lambda(TagRow.class).selectAll(TagRow.class).select(ResourceTagRow::getResourceId)
            .innerJoin(ResourceTagRow.class, on -> on.eq(ResourceTagRow::getEnterpriseId, TagRow::getEnterpriseId)
                .eq(ResourceTagRow::getTagId, TagRow::getId))
            .eq(ResourceTagRow::getEnterpriseId, enterprise).isNull(TagRow::getDeletedAt)
            .orderByAsc(TagRow::getName, TagRow::getId);
    }
}
