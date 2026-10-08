package com.stonewu.agenteam.model.file.entity;

/**
 * 用于模型上下文的附件文字；未提取到文字时为空，超出部分不进入模型上下文。
 */
public record AttachmentText(String text, boolean truncated) {
}
