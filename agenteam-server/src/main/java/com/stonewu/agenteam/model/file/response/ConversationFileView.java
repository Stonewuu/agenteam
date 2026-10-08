package com.stonewu.agenteam.model.file.response;

/**
 * 对话侧栏的文件元数据，正文按需读取，不暴露私有存储路径。
 */
public record ConversationFileView(String id, String name, String path, boolean directory, String source,
                                   String mediaType, long sizeBytes, String modifiedAt, String revision,
                                   String previewKind) {
}
