package com.stonewu.agenteam.model.knowledge.response;

/**
 * 引用直接来自本次读到的文件和处理版本，不由模型生成来源编号。
 */
public record CitationView(String chunkId, String documentId, String fileId, int generation, String name, Integer page,
                           String section, String excerpt) {
}
