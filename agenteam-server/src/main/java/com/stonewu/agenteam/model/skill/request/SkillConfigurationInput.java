package com.stonewu.agenteam.model.skill.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.hibernate.validator.constraints.UniqueElements;

import java.util.List;

/**
 * 输入字段及基础约束；类型之间的组合要求由对应业务代码检查。
 */
public record SkillConfigurationInput(
    @NotNull(message = "请填写必填项。") @Size(min = 1, max = 40, message = "文字长度不符合要求。") @Pattern(regexp = "(?:Sparkles|Feather|Telescope|ShoppingBag|NotebookPen|Lightbulb|WandSparkles|BookOpen|Box|FileText|GitBranch|Library|Folders|Database|ScanText)", message = "请选择支持的选项或格式。") String icon,
    @NotNull(message = "请填写必填项。") @Size(min = 1, max = 20, message = "文字长度不符合要求。") @Pattern(regexp = "(?:purple|pink|blue|amber|mint|teal|coral|indigo|plum|slate)", message = "请选择支持的选项或格式。") String color,
    @NotNull(message = "请填写必填项。") @Size(min = 0, max = 1000, message = "文字长度不符合要求。") String scenario,
    @NotNull(message = "请填写必填项。") @Size(min = 0, max = 2000, message = "文字长度不符合要求。") String inputDescription,
    @NotNull(message = "请填写必填项。") @Size(min = 1, max = 20000, message = "文字长度不符合要求。") String instructions,
    @NotNull(message = "请填写必填项。") @Size(min = 0, max = 2000, message = "文字长度不符合要求。") String outputDescription,
    @NotNull(message = "请填写必填项。") @Size(min = 0, max = 5000, message = "文字长度不符合要求。") String example,
    @NotNull(message = "请填写必填项。") @Size(min = 0, max = 10, message = "条目数量超出允许范围。") @UniqueElements(message = "不能包含重复条目。") List<@NotNull(message = "请填写必填项。") @Size(min = 1, max = 100, message = "文字长度不符合要求。") String> pluginVersionIds,
    @NotNull(message = "请填写必填项。") @Size(min = 0, max = 10, message = "条目数量超出允许范围。") @UniqueElements(message = "不能包含重复条目。") List<@NotNull(message = "请填写必填项。") @Size(min = 1, max = 100, message = "文字长度不符合要求。") String> knowledgeVersionIds,
    @NotNull(message = "请填写必填项。") Boolean showInWorkspace) {
}
