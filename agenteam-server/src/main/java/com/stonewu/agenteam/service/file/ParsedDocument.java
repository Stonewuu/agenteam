package com.stonewu.agenteam.service.file;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.model.file.entity.CsvProfile;
import com.stonewu.agenteam.model.file.entity.CsvRow;
import com.stonewu.agenteam.model.file.entity.DocumentChunk;
import com.stonewu.agenteam.service.file.parser.DocumentParserMain;
import com.stonewu.agenteam.service.workspace.WorkspaceStore;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * 逐块消费解析输出，避免把整份知识资料加载到应用内存。
 */
public class ParsedDocument implements AutoCloseable {
    private final Path directory;
    private final Runnable cleanupAction;
    private final ObjectMapper json;
    private final DocumentParserMain.Result result;

    ParsedDocument(Path directory, ObjectMapper json, DocumentParserMain.Result result) {
        this(directory, json, result, () -> cleanup(directory));
    }

    ParsedDocument(Path directory, ObjectMapper json, DocumentParserMain.Result result, Runnable cleanupAction) {
        this.directory = directory;
        this.json = json;
        this.result = result;
        this.cleanupAction = cleanupAction;
    }

    public int chunkCount() {
        return result.chunkCount();
    }

    public Integer pageCount() {
        return result.pageCount();
    }

    public CsvProfile csv() {
        return result.csv();
    }

    public void forEachCsv(Consumer<CsvRow> consumer) {
        if (result.csv() == null) {
            throw new IllegalStateException("文件没有 CSV 解析结果");
        }
        try (var reader = Files.newBufferedReader(directory.resolve("rows.jsonl"))) {
            String line;
            long count = 0;
            while ((line = reader.readLine()) != null) {
                if (line.length() > 20 * 1024 * 1024) {
                    throw new IOException("数据行超过解析限制");
                }
                var row = json.readValue(line, CsvRow.class);
                if (row.row() != ++count || row.values().size() != result.csv().columns().size()) {
                    throw new IOException("解析后的数据行不完整");
                }
                consumer.accept(row);
            }
            if (count != result.csv().rowCount()) {
                throw new IOException("解析后的数据行数不一致");
            }
        } catch (IOException invalid) {
            throw DocumentParserProcess.failure("CSV_FORMAT_INVALID");
        }
    }

    public void forEach(Consumer<DocumentChunk> consumer) {
        try (BufferedReader reader = Files.newBufferedReader(directory.resolve("chunks.jsonl"))) {
            int count = 0;
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.length() > 16000) {
                    throw new IOException("解析后的文本块超过限制");
                }
                DocumentChunk chunk = json.readValue(line, DocumentChunk.class);
                if (chunk.ordinal() != ++count || chunk.text().isBlank() || chunk.text()
                    .codePointCount(0, chunk.text().length()) > 1200
                    || chunk.overlapCharacters() < 0 || chunk.overlapCharacters() > 100
                    || chunk.overlapCharacters() >= chunk.text().codePointCount(0, chunk.text().length())) {
                    throw new IOException("解析后的文本块不完整");
                }
                consumer.accept(chunk);
            }
            if (count != result.chunkCount()) {
                throw new IOException("解析后的文本块数量不一致");
            }
        } catch (IOException invalid) {
            throw DocumentParserProcess.failure("FILE_TYPE_INVALID");
        }
    }

    @Override
    public void close() {
        cleanupAction.run();
    }

    static void cleanup(Path directory) {
        try {
            Path absolute = directory.toAbsolutePath().normalize();
            WorkspaceStore.deleteTree(absolute.getParent(), absolute);
        } catch (IOException unavailable) {
            throw FileStorageKeys.storageUnavailable(unavailable);
        }
    }
}
