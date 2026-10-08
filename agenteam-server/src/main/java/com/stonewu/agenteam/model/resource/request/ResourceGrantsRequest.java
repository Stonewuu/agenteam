package com.stonewu.agenteam.model.resource.request;

import com.stonewu.agenteam.model.permission.entity.ResourceGrantSpec;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record ResourceGrantsRequest(
    @NotNull(message = "请填写必填项。") @Size(min = 0, max = 500, message = "条目数量超出允许范围。") List<@NotNull(message = "请填写必填项。") @Valid ResourceGrantSpec> grants) {
}
