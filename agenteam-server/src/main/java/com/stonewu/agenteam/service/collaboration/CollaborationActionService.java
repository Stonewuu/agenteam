package com.stonewu.agenteam.service.collaboration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.mapper.collaboration.CollaborationActionMapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationTaskMapper;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationActionRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationTaskRow;
import com.stonewu.agenteam.service.chat.ChatPayloadCipher;
import com.stonewu.agenteam.service.chat.ChatRecords;
import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Map;
import java.util.Optional;

/** 动作、工作和既有工具结果必须由调用方在同一事务提交。 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class CollaborationActionService {
    private final CollaborationActionMapper actions;
    private final CollaborationTaskMapper tasks;
    private final ResourceJson json;
    private final ChatPayloadCipher cipher;
    private final Clock clock;

    public CollaborationActionService(CollaborationActionMapper actions, CollaborationTaskMapper tasks,
            ResourceJson json, ChatPayloadCipher cipher, Clock clock) {
        this.actions = actions;
        this.tasks = tasks;
        this.json = json;
        this.cipher = cipher;
        this.clock = clock;
    }

    public Optional<JsonNode> replay(String enterprise, String task, String operation, JsonNode arguments) {
        var existing = actions.selectOne(new LambdaQueryWrapper<CollaborationActionRow>()
            .eq(CollaborationActionRow::getEnterpriseId, enterprise).eq(CollaborationActionRow::getOperationKey, operation));
        if (existing == null) {
            return Optional.empty();
        }
        if (!existing.getTaskId().equals(task) || !existing.getArgumentsHash().equals(json.hash(arguments))) {
            throw new ApiException(HttpStatus.CONFLICT, "COLLABORATION_ACTION_CONFLICT", "相同操作编号不能更改任务或参数。");
        }
        return Optional.of(cipher.decrypt(enterprise, "action-result", existing.getId(), existing.getResultEncrypted()));
    }

    public CollaborationActionRow begin(CollaborationTaskRow task, String work, String run, String call,
            String operation, String type, JsonNode arguments) {
        var current = tasks.selectOne(new LambdaQueryWrapper<CollaborationTaskRow>().eq(CollaborationTaskRow::getEnterpriseId, task.getEnterpriseId())
            .eq(CollaborationTaskRow::getId, task.getId()));
        var action = ChatRecords.initialize(new CollaborationActionRow(), task.getEnterpriseId(), clock.instant());
        action.setTaskId(task.getId());
        action.setWorkId(work);
        action.setSourceRunId(run);
        action.setSourceToolCallId(call);
        action.setOperationKey(operation);
        action.setActionType(type);
        action.setInputRevision(task.getInputRevision());
        action.setSequence(current.getNextSequence());
        action.setArgumentsHash(json.hash(arguments));
        action.setPayloadEncrypted(cipher.encrypt(task.getEnterpriseId(), "action", action.getId(), arguments));
        action.setResultEncrypted(cipher.encrypt(task.getEnterpriseId(), "action-result", action.getId(), Map.of()));
        if (tasks.update(new LambdaUpdateWrapper<CollaborationTaskRow>().eq(CollaborationTaskRow::getEnterpriseId, task.getEnterpriseId())
            .eq(CollaborationTaskRow::getId, task.getId()).eq(CollaborationTaskRow::getNextSequence, current.getNextSequence())
            .setIncrBy(CollaborationTaskRow::getNextSequence, 1)) != 1) {
            throw new IllegalStateException("协作任务动作顺序已发生变化");
        }
        actions.insert(action);
        return action;
    }

    public JsonNode finish(CollaborationActionRow action, Object result) {
        var node = json.tree(result);
        if (actions.update(new LambdaUpdateWrapper<CollaborationActionRow>().eq(CollaborationActionRow::getEnterpriseId, action.getEnterpriseId())
            .eq(CollaborationActionRow::getId, action.getId()).set(CollaborationActionRow::getResultEncrypted,
                cipher.encrypt(action.getEnterpriseId(), "action-result", action.getId(), node))) != 1) {
            throw new IllegalStateException("协作动作不存在，当前修改不能提交");
        }
        return node;
    }
}
