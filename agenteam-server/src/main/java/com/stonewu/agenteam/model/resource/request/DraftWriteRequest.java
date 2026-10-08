package com.stonewu.agenteam.model.resource.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.hibernate.validator.constraints.UniqueElements;

import java.util.List;
import java.util.Map;

/**
 * 完整替换草稿，修改版本由请求头单独提供。
 */
public record DraftWriteRequest(
    @NotNull(message = "请填写必填项。") @Size(min = 1, max = 80, message = "文字长度不符合要求。") String name,
    @NotNull(message = "请填写必填项。") @Size(min = 0, max = 500, message = "文字长度不符合要求。") String description,
    @NotNull(message = "请填写必填项。") @Size(min = 0, max = 10, message = "条目数量超出允许范围。") @UniqueElements(message = "不能包含重复条目。") List<@NotNull(message = "请填写必填项。") @Size(min = 1, max = 100, message = "文字长度不符合要求。") String> tagIds,
    @NotNull(message = "请填写必填项。") Map<String, Object> config) {
}
