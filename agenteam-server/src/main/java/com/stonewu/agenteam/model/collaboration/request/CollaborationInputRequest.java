package com.stonewu.agenteam.model.collaboration.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/** 只有登录的原发起人可以补充要求或选择成果公开范围。 */
public record CollaborationInputRequest(
    @NotBlank(message = "请填写请求编号。") @Pattern(regexp = "[A-Fa-f0-9]{8}(-[A-Fa-f0-9]{4}){3}-[A-Fa-f0-9]{12}", message = "请求编号格式不正确。") String requestId,
    @NotNull(message = "请提供当前版本。") @Positive(message = "版本必须为正整数。") Long expectedRevision,
    @NotBlank(message = "请输入补充要求。") @Size(max = 32000, message = "补充要求过长，请拆分后发送。") String text,
    @Size(max = 20, message = "最多引用二十条消息。") List<@NotBlank(message = "消息编号不能为空。") @Size(max = 100, message = "消息编号过长。") String> messageReferences,
    @Pattern(regexp = "summary_link|chat_full", message = "请选择有效的成果公开范围。") String outputVisibility,
    @Size(max = 20, message = "最多选择二十个岗位。") List<@NotBlank(message = "岗位编号不能为空。") @Size(max = 100, message = "岗位编号过长。") String> waivedRoleIds) {
}
