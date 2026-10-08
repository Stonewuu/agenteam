package com.stonewu.agenteam.model.enterprise.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record MemberRemovalRequest(
    @NotNull(message = "请填写必填项。") @Size(min = 1, max = 8192, message = "文字长度不符合要求。") String impactToken,
    @JsonProperty(value = "resourceOwnerId", required = true) @Size(min = 1, max = 100, message = "文字长度不符合要求。") String resourceOwnerId,
    @JsonProperty(value = "todoOwnerId", required = true) @Size(min = 1, max = 100, message = "文字长度不符合要求。") String todoOwnerId,
    @JsonProperty(value = "cancelOpenTodos", required = true) boolean cancelOpenTodos) {
}
