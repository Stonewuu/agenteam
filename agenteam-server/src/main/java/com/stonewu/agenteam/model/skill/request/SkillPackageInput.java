package com.stonewu.agenteam.model.skill.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.util.List;

/**
 * 输入字段及基础约束；类型之间的组合要求由对应业务代码检查。
 */
public record SkillPackageInput(
    @NotNull(message = "请填写必填项。") @Min(value = 1L, message = "数值超出允许范围。") @Max(value = 1L, message = "数值超出允许范围。") Integer formatVersion,
    @NotNull(message = "请填写必填项。") @Size(min = 1, max = 80, message = "文字长度不符合要求。") String name,
    @NotNull(message = "请填写必填项。") @Size(min = 0, max = 500, message = "文字长度不符合要求。") String description,
    @NotNull(message = "请填写必填项。") @Valid Config config,
    @NotNull(message = "请填写必填项。") @Size(min = 0, max = 20, message = "条目数量超出允许范围。") List<@NotNull(message = "请填写必填项。") @Valid DependenciesItem> dependencies) {
    public record Config(
        @NotNull(message = "请填写必填项。") @Size(min = 1, max = 40, message = "文字长度不符合要求。") @Pattern(regexp = "(?:Sparkles|Feather|Telescope|ShoppingBag|NotebookPen|Lightbulb|WandSparkles|BookOpen|Box|FileText|GitBranch|Library|Folders|Database|ScanText)", message = "请选择支持的选项或格式。") String icon,
        @NotNull(message = "请填写必填项。") @Size(min = 1, max = 20, message = "文字长度不符合要求。") @Pattern(regexp = "(?:purple|pink|blue|amber|mint|teal|coral|indigo|plum|slate)", message = "请选择支持的选项或格式。") String color,
        @NotNull(message = "请填写必填项。") @Size(min = 0, max = 1000, message = "文字长度不符合要求。") String scenario,
        @NotNull(message = "请填写必填项。") @Size(min = 0, max = 2000, message = "文字长度不符合要求。") String inputDescription,
        @NotNull(message = "请填写必填项。") @Size(min = 1, max = 20000, message = "文字长度不符合要求。") String instructions,
        @NotNull(message = "请填写必填项。") @Size(min = 0, max = 2000, message = "文字长度不符合要求。") String outputDescription,
        @NotNull(message = "请填写必填项。") @Size(min = 0, max = 5000, message = "文字长度不符合要求。") String example,
        @NotNull(message = "请填写必填项。") Boolean showInWorkspace) {
    }

    public record DependenciesItem(
        @NotNull(message = "请填写必填项。") @Size(min = 1, max = 20, message = "文字长度不符合要求。") @Pattern(regexp = "(?:plugin|knowledge)", message = "请选择支持的选项或格式。") String kind,
        @NotNull(message = "请填写必填项。") @Size(min = 1, max = 80, message = "文字长度不符合要求。") String name) {
    }
}
