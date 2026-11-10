package com.stonewu.agenteam.service.collaboration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.github.yulichang.wrapper.MPJLambdaWrapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationRoomEntryMapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationRunLinkMapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationTaskMapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationWorkItemMapper;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationParticipantRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationRoomEntryRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationRoomRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationRunLinkRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationTaskRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationWorkItemRow;
import com.stonewu.agenteam.model.execution.entity.RunRecord;
import com.stonewu.agenteam.service.http.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;

/** 同一企业、协作对话和成员共用一个队列。修改前由调用方锁定企业。 */
@Service
@RequiredArgsConstructor
public class WebRoomQueue {
    public static final String YIELDED = "web_queue";
    private final CollaborationRoomEntryMapper entries;
    private final CollaborationTaskMapper tasks;
    private final CollaborationWorkItemMapper works;
    private final CollaborationRunLinkMapper links;
    private final Clock clock;

    public static boolean supports(RunRecord run) {
        return "web".equals(run.executionConfig().at("/collaboration/originKind").asText())
            && "collaboration".equals(run.executionConfig().path("sourceType").asText());
    }

    private LambdaQueryWrapper<CollaborationRoomEntryRow> lane(String enterprise, String room, String agent) {
        return new LambdaQueryWrapper<CollaborationRoomEntryRow>().eq(CollaborationRoomEntryRow::getEnterpriseId, enterprise)
            .eq(CollaborationRoomEntryRow::getRoomId, room).eq(CollaborationRoomEntryRow::getRecipientAgentId, agent);
    }

    public boolean occupied(String enterprise, String room, String agent) {
        return works.selectJoinCount(new MPJLambdaWrapper<>(CollaborationWorkItemRow.class)
            .innerJoin(CollaborationParticipantRow.class, on -> on.eq(CollaborationParticipantRow::getEnterpriseId, CollaborationWorkItemRow::getEnterpriseId)
                .eq(CollaborationParticipantRow::getId, CollaborationWorkItemRow::getParticipantId))
            .eq(CollaborationWorkItemRow::getEnterpriseId, enterprise).eq(CollaborationParticipantRow::getWebRoomId, room)
            .eq(CollaborationParticipantRow::getAgentId, agent).isNotNull(CollaborationWorkItemRow::getActiveRunId)) > 0;
    }

    public boolean mustWait(CollaborationRoomRow room, String agent, CollaborationTaskRow target, boolean mentioned) {
        String enterprise = room.getEnterpriseId();
        if (occupied(enterprise, room.getId(), agent) || first(enterprise, room.getId(), agent) != null
            || starting(enterprise, room.getId(), agent) != null) {
            return true;
        }
        if (target == null) {
            return false;
        }
        var owner = works.selectById(target.getOwnerWorkId());
        return owner != null && "ready".equals(owner.getStatus()) && (mentioned || owner.getLeaseOwner() != null);
    }

    private CollaborationRoomEntryRow first(String enterprise, String room, String agent) {
        return entries.selectPage(new Page<>(1, 1, false), lane(enterprise, room, agent)
            .in(CollaborationRoomEntryRow::getDeliveryState, "queued", "selected")
            .orderByDesc(CollaborationRoomEntryRow::getDeliveryState).orderByAsc(CollaborationRoomEntryRow::getQueuePosition))
            .getRecords().stream().findFirst().orElse(null);
    }

    private CollaborationRoomEntryRow starting(String enterprise, String room, String agent) {
        return entries.selectJoinPage(new Page<CollaborationRoomEntryRow>(1, 1, false), CollaborationRoomEntryRow.class,
            new MPJLambdaWrapper<>(CollaborationRoomEntryRow.class).selectAll(CollaborationRoomEntryRow.class)
                .innerJoin(CollaborationTaskRow.class, on -> on.eq(CollaborationTaskRow::getEnterpriseId, CollaborationRoomEntryRow::getEnterpriseId)
                    .eq(CollaborationTaskRow::getId, CollaborationRoomEntryRow::getTaskId))
                .innerJoin(CollaborationWorkItemRow.class, on -> on.eq(CollaborationWorkItemRow::getEnterpriseId, CollaborationTaskRow::getEnterpriseId)
                    .eq(CollaborationWorkItemRow::getId, CollaborationTaskRow::getOwnerWorkId))
                .eq(CollaborationRoomEntryRow::getEnterpriseId, enterprise).eq(CollaborationRoomEntryRow::getRoomId, room)
                .eq(CollaborationRoomEntryRow::getRecipientAgentId, agent).eq(CollaborationRoomEntryRow::getDeliveryState, "starting")
                .eq(CollaborationWorkItemRow::getStatus, "ready")).getRecords().stream().findFirst().orElse(null);
    }

    public boolean waitToDispatch(CollaborationTaskRow task, CollaborationParticipantRow role, CollaborationWorkItemRow work) {
        if (!task.fromWebRoom()) {
            return false;
        }
        String enterprise = task.getEnterpriseId(), room = task.getWebRoomId(), agent = role.getAgentId();
        if (occupied(enterprise, room, agent)) {
            return true;
        }
        var next = starting(enterprise, room, agent);
        if (next != null) {
            return !next.getTaskId().equals(task.getId()) || !task.getOwnerWorkId().equals(work.getId());
        }
        var head = first(enterprise, room, agent);
        if (head == null) {
            return false;
        }
        // 首条消息已经创建任务但尚未领取时，先让它执行一次。其余就绪工作不能抢在队首消息之前。
        boolean originalTurn = "queued".equals(head.getDeliveryState()) && task.getId().equals(head.getTaskId())
            && task.getOwnerWorkId().equals(work.getId()) && work.getRunRound() == 0;
        return !originalTurn;
    }

    public void started(CollaborationTaskRow task, CollaborationParticipantRow role, CollaborationWorkItemRow work) {
        if (task.fromWebRoom() && task.getOwnerWorkId().equals(work.getId())) {
            entries.update(new LambdaUpdateWrapper<CollaborationRoomEntryRow>()
                .eq(CollaborationRoomEntryRow::getEnterpriseId, task.getEnterpriseId()).eq(CollaborationRoomEntryRow::getRoomId, task.getWebRoomId())
                .eq(CollaborationRoomEntryRow::getRecipientAgentId, role.getAgentId()).eq(CollaborationRoomEntryRow::getTaskId, task.getId())
                .eq(CollaborationRoomEntryRow::getDeliveryState, "starting").set(CollaborationRoomEntryRow::getDeliveryState, "visible"));
        }
    }

    private CollaborationRoomEntryRow forRun(RunRecord run) {
        String taskId = run.executionConfig().at("/collaboration/taskId").asText();
        var task = tasks.selectOne(new LambdaQueryWrapper<CollaborationTaskRow>().eq(CollaborationTaskRow::getEnterpriseId, run.enterpriseId())
            .eq(CollaborationTaskRow::getId, taskId).eq(CollaborationTaskRow::getInitiatorUserId, run.userId())
            .eq(CollaborationTaskRow::getOriginKind, "web").in(CollaborationTaskRow::getStatus, "active", "waiting_input", "waiting_approval"));
        return task == null ? null : first(run.enterpriseId(), task.getWebRoomId(), run.executionConfig().path("agentId").asText());
    }

    public boolean waiting(RunRecord run) {
        return forRun(run) != null;
    }

    public boolean selectAfterTools(RunRecord run) {
        var next = forRun(run);
        if (next == null || !"queued".equals(next.getDeliveryState())) {
            return false;
        }
        var work = works.selectOne(new LambdaQueryWrapper<CollaborationWorkItemRow>()
            .eq(CollaborationWorkItemRow::getEnterpriseId, run.enterpriseId()).eq(CollaborationWorkItemRow::getActiveRunId, run.id())
            .eq(CollaborationWorkItemRow::getId, run.executionConfig().at("/collaboration/workItemId").asText())
            .in(CollaborationWorkItemRow::getStatus, "running", "waiting_approval"));
        if (work == null || links.update(new LambdaUpdateWrapper<CollaborationRunLinkRow>()
            .eq(CollaborationRunLinkRow::getEnterpriseId, run.enterpriseId()).eq(CollaborationRunLinkRow::getRunId, run.id())
            .eq(CollaborationRunLinkRow::getWorkId, work.getId()).isNull(CollaborationRunLinkRow::getRequestedStopReason)
            .isNull(CollaborationRunLinkRow::getTerminalObservedAt).set(CollaborationRunLinkRow::getRequestedStopReason, YIELDED)) != 1) {
            return false;
        }
        updateState(next, "selected");
        return true;
    }

    public void stage(CollaborationRoomEntryRow entry, String agent) {
        if (entries.selectCount(lane(entry.getEnterpriseId(), entry.getRoomId(), agent)
            .in(CollaborationRoomEntryRow::getDeliveryState, "queued", "selected")) >= 20) {
            throw new ApiException(HttpStatus.CONFLICT, "ROOM_QUEUE_FULL", "这位成员已有 20 条消息排队，请稍后再发送。");
        }
        var update = guard(entry).set(CollaborationRoomEntryRow::getDeliveryState, "queued")
            .set(CollaborationRoomEntryRow::getRecipientAgentId, agent).set(CollaborationRoomEntryRow::getQueuePosition, entry.getSequenceNo());
        changed(entries.update(update));
    }

    public List<CollaborationRoomEntryRow> list(CollaborationRoomRow room) {
        return entries.selectList(new LambdaQueryWrapper<CollaborationRoomEntryRow>()
            .eq(CollaborationRoomEntryRow::getEnterpriseId, room.getEnterpriseId()).eq(CollaborationRoomEntryRow::getRoomId, room.getId())
            .in(CollaborationRoomEntryRow::getDeliveryState, "queued", "selected").orderByAsc(CollaborationRoomEntryRow::getRecipientAgentId)
            .orderByDesc(CollaborationRoomEntryRow::getDeliveryState).orderByAsc(CollaborationRoomEntryRow::getQueuePosition));
    }

    public List<CollaborationRoomEntryRow> ready(int limit) {
        return entries.readyHeads(limit);
    }

    public boolean canAdmit(CollaborationRoomEntryRow row, CollaborationTaskRow anchor, boolean targetsAnchor) {
        var first = first(row.getEnterpriseId(), row.getRoomId(), row.getRecipientAgentId());
        if (first == null || !row.getId().equals(first.getId())
            || occupied(row.getEnterpriseId(), row.getRoomId(), row.getRecipientAgentId())
            || starting(row.getEnterpriseId(), row.getRoomId(), row.getRecipientAgentId()) != null) {
            return false;
        }
        var owner = works.selectById(anchor.getOwnerWorkId());
        return !targetsAnchor || !"queued".equals(row.getDeliveryState()) || owner.getRunRound() > 0 || !"ready".equals(owner.getStatus());
    }

    public CollaborationRoomEntryRow find(CollaborationRoomRow room, String id) {
        return entries.selectOne(new LambdaQueryWrapper<CollaborationRoomEntryRow>()
            .eq(CollaborationRoomEntryRow::getEnterpriseId, room.getEnterpriseId()).eq(CollaborationRoomEntryRow::getRoomId, room.getId())
            .eq(CollaborationRoomEntryRow::getId, id));
    }

    public void change(CollaborationRoomRow room, CollaborationRoomEntryRow row, boolean prioritize) {
        if (row == null || !"queued".equals(row.getDeliveryState())) {
            throw new ApiException(HttpStatus.CONFLICT, "ROOM_QUEUE_CHANGED", "这条消息已离开等待队列。");
        }
        if (!prioritize) {
            updateState(row, "withdrawn");
        } else {
            // 每次调整取本成员最小位置的前一位；不改变其他成员的顺序。
            var first = entries.selectPage(new Page<>(1, 1, false), lane(room.getEnterpriseId(), room.getId(), row.getRecipientAgentId())
                .eq(CollaborationRoomEntryRow::getDeliveryState, "queued").orderByAsc(CollaborationRoomEntryRow::getQueuePosition))
                .getRecords().getFirst();
            if (!first.getId().equals(row.getId())) {
                changed(entries.update(guard(row).set(CollaborationRoomEntryRow::getQueuePosition, first.getQueuePosition() - 1)));
            }
        }
    }

    public void updateState(CollaborationRoomEntryRow entry, String state) {
        changed(entries.update(guard(entry).set(CollaborationRoomEntryRow::getDeliveryState, state)));
    }

    private LambdaUpdateWrapper<CollaborationRoomEntryRow> guard(CollaborationRoomEntryRow row) {
        return new LambdaUpdateWrapper<CollaborationRoomEntryRow>().eq(CollaborationRoomEntryRow::getEnterpriseId, row.getEnterpriseId())
            .eq(CollaborationRoomEntryRow::getRoomId, row.getRoomId()).eq(CollaborationRoomEntryRow::getId, row.getId())
            .eq(CollaborationRoomEntryRow::getDeliveryState, row.getDeliveryState())
            .setIncrBy(CollaborationRoomEntryRow::getRevision, 1).set(CollaborationRoomEntryRow::getUpdatedAt, clock.instant());
    }

    private static void changed(int count) {
        if (count != 1) {
            throw new IllegalStateException("协作排队消息已被其他操作处理");
        }
    }
}
