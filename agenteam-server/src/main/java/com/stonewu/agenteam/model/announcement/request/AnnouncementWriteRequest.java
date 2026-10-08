package com.stonewu.agenteam.model.announcement.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 保存公告标题、正文和提醒等级，范围由请求入口确定。
 */
public record AnnouncementWriteRequest(
    @NotBlank(message = "请填写公告标题。") @Size(max = 160, message = "标题最多160个字符。") String title,
    @NotBlank(message = "请填写公告正文。") @Size(max = 200000, message = "正文排版过多，请精简后重试。") String content,
    @NotBlank(message = "请选择提醒等级。") @Size(max = 32, message = "请选择有效的提醒等级。") String level,
    @Size(max = 20, message = "请选择有效的正文格式。") String contentFormat) {
}
