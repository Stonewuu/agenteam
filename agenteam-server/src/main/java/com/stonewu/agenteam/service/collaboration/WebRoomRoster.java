package com.stonewu.agenteam.service.collaboration;

import com.stonewu.agenteam.service.i18n.Messages;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.github.yulichang.wrapper.MPJLambdaWrapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationParticipantMapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationRoomMemberMapper;
import com.stonewu.agenteam.mapper.resource.ResourceVersionMapper;
import com.stonewu.agenteam.mapper.resource.ResourceMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationParticipantRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationRoomMemberRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationRoomRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationTaskRow;
import com.stonewu.agenteam.model.resource.entity.ResourceVersionRecord;
import com.stonewu.agenteam.model.http.response.PageResponse;
import com.stonewu.agenteam.service.agent.EmployeeQueryService;
import com.stonewu.agenteam.service.chat.ChatRecords;
import com.stonewu.agenteam.service.execution.ExecutionConfigurationService;
import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;

/** 名单只接受本人已雇佣的岗位；发送任务时重新核对并保存当次使用的版本。 */
@Service
public class WebRoomRoster {
    private final CollaborationRoomMemberMapper members;
    private final EmployeeQueryService employees;
    private final CollaborationParticipantMapper participants;
    private final ExecutionConfigurationService configuration;
    private final ResourceVersionMapper versions;
    private final ResourceMapper resources;
    private final Clock clock;

    public WebRoomRoster(CollaborationRoomMemberMapper members, EmployeeQueryService employees, ExecutionConfigurationService configuration,
            ResourceVersionMapper versions, ResourceMapper resources, CollaborationParticipantMapper participants, Clock clock) {
        this.participants = participants;
        this.resources = resources;
        this.members = members;
        this.employees = employees;
        this.configuration = configuration;
        this.versions = versions;
        this.clock = clock;
    }

    public PageResponse<Candidate> candidates(AuthContext actor, String search, String cursor) {
        var page = employees.list(actor, "mine", search, List.of(), cursor, 15);
        var items = new ArrayList<Candidate>();
        for (var employee : page.items()) {
            if (employee.canRun() && "active".equals(employee.hireStatus())) {
                items.add(new Candidate(employee.agentId(), employee.name(), employee.description(), employee.icon(), employee.color()));
            }
        }
        return new PageResponse<>(items, page.nextCursor(), page.hasMore());
    }

    public List<CollaborationRoomMemberRow> prepareMembers(AuthContext actor, CollaborationRoomRow room, List<String> ids) {
        if (ids == null || ids.size() < 2 || ids.size() > 8 || ids.stream().anyMatch(id -> id == null || id.isBlank())
            || new HashSet<>(ids).size() != ids.size()) {
            throw ApiException.invalidField("agentIds", Messages.text("error.collaboration.webRoomRoster.prepareMembers.1"));
        }
        var selectedMembers = new ArrayList<CollaborationRoomMemberRow>();
        for (int index = 0; index < ids.size(); index++) {
            String id = ids.get(index);
            var selected = configuration.normal(actor, id, null);
            var member = ChatRecords.initialize(new CollaborationRoomMemberRow(), actor.enterpriseId(), clock.instant());
            member.setRoomId(room.getId());
            member.setAgentId(id);
            member.setDisplayName(selected.name());
            member.setSortOrder(index);
            selectedMembers.add(member);
        }
        return selectedMembers;
    }

    public void saveMembers(List<CollaborationRoomMemberRow> selected) {
        members.insert(selected);
    }

    public List<CollaborationRoomMemberRow> members(CollaborationRoomRow room) {
        return members.selectList(new LambdaQueryWrapper<CollaborationRoomMemberRow>().eq(CollaborationRoomMemberRow::getEnterpriseId, room.getEnterpriseId())
            .eq(CollaborationRoomMemberRow::getRoomId, room.getId()).orderByAsc(CollaborationRoomMemberRow::getSortOrder));
    }

    /** 继续同一轮讨论时，各自回到该成员最近负责的任务，已取消或失败的任务不再接收要求。 */
    public Map<String, CollaborationParticipantRow> speakers(CollaborationTaskRow current) {
        Map<String, CollaborationParticipantRow> result = new LinkedHashMap<>();
        if (current != null) {
            var rows = participants.selectJoinList(CollaborationParticipantRow.class, new MPJLambdaWrapper<CollaborationParticipantRow>()
                .selectAll(CollaborationParticipantRow.class)
                .innerJoin(CollaborationTaskRow.class, on -> on.eq(CollaborationTaskRow::getEnterpriseId, CollaborationParticipantRow::getEnterpriseId)
                    .eq(CollaborationTaskRow::getId, CollaborationParticipantRow::getTaskId)
                    .eq(CollaborationTaskRow::getOwnerParticipantId, CollaborationParticipantRow::getId))
                .eq(CollaborationTaskRow::getEnterpriseId, current.getEnterpriseId()).eq(CollaborationTaskRow::getWebRoomId, current.getWebRoomId())
                .eq(CollaborationTaskRow::getWebTopicId, current.getWebTopicId()).eq(CollaborationTaskRow::getInitiatorUserId, current.getInitiatorUserId())
                .notIn(CollaborationTaskRow::getStatus, "failed", "cancelled", "cancelling")
                .orderByDesc(CollaborationTaskRow::getCreatedAt, CollaborationTaskRow::getId));
            rows.forEach(role -> result.putIfAbsent(role.getAgentId(), role));
        }
        return result;
    }

    public List<Member> describe(CollaborationRoomRow room, Map<String, CollaborationParticipantRow> speakers) {
        var rows = members(room);
        var current = resources.findMany(room.getEnterpriseId(), rows.stream().map(CollaborationRoomMemberRow::getAgentId).toList(), false);
        var ids = new HashSet<String>();
        current.values().forEach(resource -> ids.add(resource.publishedVersionId()));
        speakers.values().forEach(role -> ids.add(role.getAgentVersionId()));
        ids.remove(null);
        var snapshots = versions.findMany(room.getEnterpriseId(), List.copyOf(ids));
        return rows.stream().map(row -> {
            var resource = current.get(row.getAgentId());
            var published = resource == null ? null : snapshots.get(resource.publishedVersionId());
            var speaker = speakers.get(row.getAgentId());
            var reply = speaker == null ? published : snapshots.get(speaker.getAgentVersionId());
            return new Member(row.getId(), row.getAgentId(), row.getDisplayName(), published == null ? null : published.config().path("icon").asText(null),
                published == null ? null : published.config().path("color").asText(null), allowsFiles(published), allowsFiles(reply));
        }).toList();
    }

    private static boolean allowsFiles(ResourceVersionRecord version) {
        return version != null && "available".equals(version.status()) && version.config().path("attachmentsEnabled").asBoolean();
    }

    public record Member(String id, String agentId, String name, String icon, String color, boolean attachmentsEnabled, boolean replyAttachmentsEnabled) {
    }

    public List<CollaborationParticipantRow> freeze(AuthContext actor, CollaborationRoomRow room, String recipient) {
        var roles = new ArrayList<CollaborationParticipantRow>();
        for (var member : members(room)) {
            var selected = configuration.normal(actor, member.getAgentId(), null);
            var version = versions.find(actor.enterpriseId(), selected.versionId()).orElseThrow();
            var role = ChatRecords.initialize(new CollaborationParticipantRow(), actor.enterpriseId(), clock.instant());
            role.setWebRoomId(room.getId());
            role.setWebMemberId(member.getId());
            role.setBindingRevision(member.getRevision());
            role.setAgentId(member.getAgentId());
            role.setAgentVersionId(selected.versionId());
            String name = selected.name();
            role.setRoleName(name.substring(0, name.offsetByCodePoints(0, Math.min(80, name.codePointCount(0, name.length())))));
            String description = version.description() == null ? "" : version.description();
            role.setDescriptionSnapshot(description.substring(0, description.offsetByCodePoints(0, Math.min(500, description.codePointCount(0, description.length())))));
            role.setAvailability("available");
            role.setExplicitlyRequested(member.getAgentId().equals(recipient));
            roles.add(role);
        }
        return roles;
    }

    public boolean acceptsFiles(String enterprise, String agent, String fixedVersion) {
        String version = fixedVersion;
        if (version == null) {
            version = resources.find(enterprise, agent, false, false).map(resource -> resource.publishedVersionId()).orElse(null);
        }
        return version != null && versions.find(enterprise, version)
            .filter(value -> agent.equals(value.resourceId()) && "available".equals(value.status()))
            .map(value -> value.config().path("attachmentsEnabled").asBoolean()).orElse(false);
    }

    public record Candidate(String id, String name, String description, String icon, String color) {
    }
}
