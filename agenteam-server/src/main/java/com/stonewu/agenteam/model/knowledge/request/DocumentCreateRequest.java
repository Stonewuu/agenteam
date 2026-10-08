package com.stonewu.agenteam.model.knowledge.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.hibernate.validator.constraints.UniqueElements;

import java.util.List;

/**
 * 一次加入一至十份已经上传并通过检查的资料。
 */
public record DocumentCreateRequest(
    @NotNull(message = "请填写必填项。") @Size(min = 1, max = 10, message = "条目数量超出允许范围。") @UniqueElements(message = "不能包含重复条目。") List<@NotNull(message = "请填写必填项。") @Size(min = 1, max = 100, message = "文字长度不符合要求。") String> fileIds) {
}
