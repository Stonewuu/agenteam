package com.stonewu.agenteam.model.knowledge.response;

/**
 * 输入框只展示固定知识能力中当前可读取的文档，不返回完整配置。
 */
public record InputDocumentOptionView(String kind, String documentId, int generation, String name,
                                      String knowledgeName) {
}
