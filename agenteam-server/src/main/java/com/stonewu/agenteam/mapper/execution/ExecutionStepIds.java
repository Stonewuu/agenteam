package com.stonewu.agenteam.mapper.execution;

import com.stonewu.agenteam.model.execution.entity.RunRecord;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * 步骤编号在同一次尝试中稳定；新的自动尝试不能覆盖此前输出或确认记录。
 */
public final class ExecutionStepIds {
    private ExecutionStepIds() {
    }

    public static String id(RunRecord run, String key) {
        return id(run.id(), run.currentAttemptNo(), key);
    }

    public static String id(String run, int attempt, String key) {
        return UUID.nameUUIDFromBytes((run + ":" + attempt + ":" + key).getBytes(StandardCharsets.UTF_8)).toString();
    }
}
