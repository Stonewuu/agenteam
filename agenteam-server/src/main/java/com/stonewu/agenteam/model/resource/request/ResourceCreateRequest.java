package com.stonewu.agenteam.model.resource.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.hibernate.validator.constraints.UniqueElements;

import java.util.List;
import java.util.Map;

/**
 * 创建仅接收草稿内容，不接受所有者、来源与发布状态。
 */
public record ResourceCreateRequest(
    @NotNull(message = "请填写必填项。") @Size(min = 1, max = 20, message = "文字长度不符合要求。") @Pattern(regexp = "(?:agent|skill|plugin|workflow|knowledge|data)", message = "请选择支持的选项或格式。") String kind,
    @NotNull(message = "请填写必填项。") @Size(min = 1, max = 80, message = "文字长度不符合要求。") String name,
    @NotNull(message = "请填写必填项。") @Size(min = 0, max = 500, message = "文字长度不符合要求。") String description,
    @NotNull(message = "请填写必填项。") @Size(min = 0, max = 10, message = "条目数量超出允许范围。") @UniqueElements(message = "不能包含重复条目。") List<@NotNull(message = "请填写必填项。") @Size(min = 1, max = 100, message = "文字长度不符合要求。") String> tagIds,
    @NotNull(message = "请填写必填项。") Map<String, Object> config) {
}
