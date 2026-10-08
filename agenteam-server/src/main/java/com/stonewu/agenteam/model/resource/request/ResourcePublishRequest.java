package com.stonewu.agenteam.model.resource.request;

import com.stonewu.agenteam.model.permission.entity.ResourceGrantSpec;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 省略授权或上架信息时保持原值；提交时与发布一起验证和保存。
 */
public record ResourcePublishRequest(
    @NotNull(message = "请填写必填项。") @Size(min = 1, max = 500, message = "文字长度不符合要求。") String releaseNote,
    @Size(min = 0, max = 500, message = "条目数量超出允许范围。") List<@NotNull(message = "请填写必填项。") @Valid ResourceGrantSpec> grants,
    @Valid AgentListingRequest listing) {
}
