package com.stonewu.agenteam.model.modelusage.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;
import java.math.BigDecimal;

/**
 * 一次模型请求的原始用量与固定计价依据。
 */
@Getter
@Setter
@TableName("model_usage_record")
public class ModelUsageRecordRow {
    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    @TableField("enterprise_id")
    private String enterpriseId;

    @TableField("user_id")
    private String userId;

    @TableField("conversation_id")
    private String conversationId;

    @TableField("run_id")
    private String runId;

    @TableField("run_attempt_no")
    private Integer runAttemptNo;

    @TableField("run_lease_version")
    private Long runLeaseVersion;

    @TableField("agent_id")
    private String agentId;

    @TableField("agent_session_id")
    private String agentSessionId;

    @TableField("workflow_node_id")
    private String workflowNodeId;

    @TableField("source_kind")
    private String sourceKind;

    @TableField("source_id")
    private String sourceId;

    @TableField("purpose")
    private String purpose;

    @TableField("model_profile_id")
    private String modelProfileId;

    @TableField("provider_id")
    private String providerId;

    @TableField("model_profile_revision")
    private Long modelProfileRevision;

    @TableField("provider_revision")
    private Long providerRevision;

    @TableField("model_name_snapshot")
    private String modelNameSnapshot;

    @TableField("provider_name_snapshot")
    private String providerNameSnapshot;

    @TableField("protocol")
    private String protocol;

    @TableField("usage_format")
    private String usageFormat;

    @TableField("provider_response_id")
    private String providerResponseId;

    @TableField("provider_request_id")
    private String providerRequestId;

    @TableField("response_model")
    private String responseModel;

    @TableField("requested_service_tier")
    private String requestedServiceTier;

    @TableField("actual_service_tier")
    private String actualServiceTier;

    @TableField("call_status")
    private String callStatus;

    @TableField("usage_status")
    private String usageStatus;

    @TableField("price_status")
    private String priceStatus;

    @TableField("reason_code")
    private String reasonCode;

    @TableField("registered_at")
    private Instant registeredAt;

    @TableField("dispatched_at")
    private Instant dispatchedAt;

    @TableField("finished_at")
    private Instant finishedAt;

    @TableField("last_usage_at")
    private Instant lastUsageAt;

    @TableField("input_tokens")
    private Long inputTokens;

    @TableField("uncached_input_tokens")
    private Long uncachedInputTokens;

    @TableField("output_tokens")
    private Long outputTokens;

    @TableField("cache_read_tokens")
    private Long cacheReadTokens;

    @TableField("cache_write_tokens")
    private Long cacheWriteTokens;

    @TableField("reasoning_tokens")
    private Long reasoningTokens;

    @TableField("tool_use_prompt_tokens")
    private Long toolUsePromptTokens;

    @TableField("total_tokens")
    private Long totalTokens;

    @TableField("cache_write_details_json")
    private String cacheWriteDetailsJson;

    @TableField("field_states_json")
    private String fieldStatesJson;

    @TableField("usage_evidence_json")
    private String usageEvidenceJson;

    @TableField("price_version_id")
    private String priceVersionId;

    @TableField("price_rule_id")
    private String priceRuleId;

    @TableField("currency")
    private String currency;

    @TableField("pricing_algorithm_version")
    private Integer pricingAlgorithmVersion;

    @TableField("total_amount")
    private BigDecimal totalAmount;

    @TableField("snapshot_seq")
    private Long snapshotSeq;

    @TableField("revision")
    private Long revision;

    @TableField("request_owner_id")
    private String requestOwnerId;

    @TableField("request_lease_until")
    private Instant requestLeaseUntil;

    @TableField("calculation_attempts")
    private Integer calculationAttempts;

    @TableField("next_calculation_at")
    private Instant nextCalculationAt;

    @TableField("calculation_lease_until")
    private Instant calculationLeaseUntil;

    @TableField("http_status")
    private Integer httpStatus;

    @TableField("provider_error_code")
    private String providerErrorCode;

    @TableField("error_category")
    private String errorCategory;

    @TableField("created_at")
    private Instant createdAt;

    @TableField("updated_at")
    private Instant updatedAt;
}
