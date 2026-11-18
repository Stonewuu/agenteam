package com.stonewu.agenteam.model.system.entity;

import java.util.Arrays;
import java.util.Optional;

/** 可由系统管理员调整的运行设置，字段名称与接口保持一致。 */
public enum RuntimeSetting {
    WORKER_CONCURRENT("workerConcurrent", "execution", "execution.worker.maximum-concurrent", 10, 1),
    QUEUE_TIMEOUT_SECONDS("queueTimeoutSeconds", "execution", "execution.queue-timeout-seconds", 300, 1),
    ENTERPRISE_ACTIVE_RUNS("enterpriseActiveRuns", "execution", "execution.enterprise-active-runs", 20, 1),
    USER_ACTIVE_RUNS("userActiveRuns", "execution", "execution.user-active-runs", 3, 1),
    CONVERSATION_QUEUED_MESSAGES("conversationQueuedMessages", "execution", "execution.conversation-queued-messages", 20, 1),
    MODEL_IDLE_SECONDS("modelIdleSeconds", "model", "execution.model.idle-timeout", 900, 1),
    MODEL_INPUT_MB("modelInputMb", "model", "execution.model.maximum-input-bytes", 0, 0),
    TOOL_INLINE_KB("toolInlineKb", "model", "tools.results.inline-kb", 32, 1),
    TOOL_LARGE_INLINE_KB("toolLargeInlineKb", "model", "tools.results.large-context-inline-kb", 64, 1),
    TOOL_PREVIEW_KB("toolPreviewKb", "model", "tools.results.preview-kb", 2, 1),
    TOOL_READ_KB("toolReadKb", "model", "tools.results.read-kb", 8, 1),
    TOOL_READ_LINES("toolReadLines", "model", "tools.results.read-lines", 200, 1),
    TOOL_BATCH_MULTIPLIER("toolBatchMultiplier", "model", "tools.results.batch-multiplier", 2, 1),
    TOOL_FILE_MB("toolFileMb", "model", "tools.results.max-file-mb", 20, 1),
    COLLABORATION_WORKS("collaborationWorks", "collaboration", "collaboration.max-works", 20, 1),
    COLLABORATION_HANDOFFS("collaborationHandoffs", "collaboration", "collaboration.max-handoffs", 8, 0),
    COLLABORATION_RUNS("collaborationRuns", "collaboration", "collaboration.max-runs", 40, 1),
    COLLABORATION_PARALLEL("collaborationParallel", "collaboration", "collaboration.max-parallel", 2, 1),
    COLLABORATION_DEADLINE_HOURS("collaborationDeadlineHours", "collaboration", "collaboration.deadline-hours", 24, 1),
    COLLABORATION_CONTEXT_CHARACTERS("collaborationContextCharacters", "collaboration", "collaboration.max-context-characters", 24000, 1),
    COLLABORATION_CONTEXT_MESSAGES("collaborationContextMessages", "collaboration", "collaboration.max-context-messages", 30, 1),
    SANDBOX_MEMORY_MB("sandboxMemoryMb", "environment", "execution.sandbox.memory-mb", 512, 128),
    SANDBOX_CPUS("sandboxCpus", "environment", "execution.sandbox.cpus", 1, 1),
    SANDBOX_PROCESSES("sandboxProcesses", "environment", "execution.sandbox.process-limit", 128, 16),
    SANDBOX_TEMPORARY_MB("sandboxTemporaryMb", "environment", "execution.sandbox.temporary-mb", 256, 16),
    SANDBOX_TIMEOUT_SECONDS("sandboxTimeoutSeconds", "environment", "execution.sandbox.timeout-seconds", 1800, 1),
    SANDBOX_CONCURRENT("sandboxConcurrent", "environment", "execution.sandbox.maximum-concurrent", 2, 1),
    SANDBOX_RUNNING("sandboxRunning", "environment", "execution.sandbox.maximum-running-containers", 4, 1),
    SANDBOX_IDLE_SECONDS("sandboxIdleSeconds", "environment", "execution.sandbox.idle-seconds", 1800, 1),
    COMMAND_RETENTION_DAYS("commandRetentionDays", "retention", "execution.command-results.retention-days", 7, 1),
    COMMAND_MAXIMUM_ENTRIES("commandMaximumEntries", "retention", "execution.command-results.maximum-entries", 1000, 1),
    WORKSPACE_RETENTION_DAYS("workspaceRetentionDays", "retention", "execution.workspace.retention-days", 30, 1),
    AUTH_RETENTION_DAYS("authRetentionDays", "retention", "agenteam.integration.retention.oauth-days", 7, 1),
    PAYLOAD_RETENTION_DAYS("payloadRetentionDays", "retention", "agenteam.integration.retention.payload-days", 30, 1),
    ATTEMPT_RETENTION_DAYS("attemptRetentionDays", "retention", "agenteam.integration.retention.attempt-days", 90, 1),
    RESULT_RETENTION_DAYS("resultRetentionDays", "retention", "agenteam.integration.retention.result-days", 180, 180);

    private final String key;
    private final String section;
    private final String property;
    private final long fallback;
    private final long minimum;

    RuntimeSetting(String key, String section, String property, long fallback, long minimum) {
        this.key = key;
        this.section = section;
        this.property = property;
        this.fallback = fallback;
        this.minimum = minimum;
    }

    public String key() {
        return key;
    }

    public String section() {
        return section;
    }

    public String property() {
        return property;
    }

    public long fallback() {
        return fallback;
    }

    public long minimum() {
        return minimum;
    }

    public boolean accepts(Long value) {
        return value != null && LimitValue.validInteger(value, minimum);
    }

    public static Optional<RuntimeSetting> find(String key) {
        return Arrays.stream(values()).filter(value -> value.key.equals(key)).findFirst();
    }
}
