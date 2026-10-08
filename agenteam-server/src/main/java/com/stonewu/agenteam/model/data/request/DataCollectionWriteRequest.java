package com.stonewu.agenteam.model.data.request;

import com.stonewu.agenteam.model.data.entity.DataField;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 只配置明确的集合与字段，不接受查询语句或临时接口地址。
 */
public record DataCollectionWriteRequest(
    @NotNull(message = "请填写必填项。") @Size(min = 1, max = 80, message = "文字长度不符合要求。") String name,
    @NotNull(message = "请填写必填项。") @Size(min = 1, max = 128, message = "文字长度不符合要求。") String sourceName,
    @NotNull(message = "请填写必填项。") @Size(min = 1, max = 100, message = "条目数量超出允许范围。") List<@NotNull(message = "请填写必填项。") @Valid DataField> fields) {
}
