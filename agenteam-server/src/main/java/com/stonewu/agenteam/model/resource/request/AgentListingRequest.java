package com.stonewu.agenteam.model.resource.request;

import com.stonewu.agenteam.model.permission.entity.ResourceGrantSpec;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 上架与使用范围一同保存，不改变发布配置；空范围明确表示仅所有者可使用。
 */
public record AgentListingRequest(
    @NotNull(message = "请填写必填项。") Boolean listed,
    @NotNull(message = "请填写必填项。") @Size(min = 1, max = 20, message = "文字长度不符合要求。") @Pattern(regexp = "(?:automatic|approval)", message = "请选择支持的选项或格式。") String hirePolicy,
    @Size(max = 500, message = "条目数量超出允许范围。") List<@NotNull(message = "请填写必填项。") @Valid ResourceGrantSpec> useGrants) {
    public AgentListingRequest(Boolean listed, String hirePolicy) {
        this(listed, hirePolicy, null);
    }
}
